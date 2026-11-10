<#
.SYNOPSIS
    Runs a Hushfacebook release one stage at a time: source, preflight, bundle, rebuild, publish, index.

.DESCRIPTION
    One command per stage, in this order. Each stage checks that the one before it finished and
    refuses to run otherwise, so a stage can be run again after a fix without redoing the others.

    source     The CHANGELOG cut (Unreleased becomes the version, -Summary opens it), the version
               in gradle.properties and the README badge, the patch list and the translation check.
               A step that fails puts the tree back. Then review the diff and commit it as
               "chore(release): prepare X.Y.Z for publication".
    preflight  The quick checks a release gate would otherwise reach late, in about five minutes:
               the script contract tests, the release text tests in tools/, the translation check,
               a draft of the GitHub notes, the patch tests that don't read the Facebook fixtures,
               the extension's translation and release-check tests, both Android lints and the
               release facts with the index still on the previous release. Run it on the clean
               source commit, before the push whose pre-push gate runs everything.
    bundle     From the pushed, clean source commit: the bundle with its SBOM, tooling list and Hush
               Unpacker's signed APK, the receipt over every declared build (reading the runs the gate
               kept in patches/build/fixture-apply) and SHA256SUMS.txt, into build/release-assets/<version>.
    rebuild    The source commit built a second time, in a fresh worktree under the temp folder,
               and that bundle compared with the staged one: every class, DEX, extension payload
               and resource by name and SHA-256, whatever the ZIP order, times or compression. The
               identity each bundle carries is held to its own checkout and doesn't decide, so a
               checkout with other line endings passes and a changed class doesn't. A pass leaves a
               note beside the assets naming the commit and the staged bundle's hash, and with that
               note there the stage builds nothing again. The worktree is removed whatever happens.
    publish    A copy of the six assets, held to SHA256SUMS.txt and the receipt (the bundle, SBOM
               and tooling report it names, at the source commit) before anything else runs, and
               the bundle in it to the rebuild stage's note by its hash, and -Walk to that bundle:
               the walk-receipt.json scripts/device-audit.ps1 left after walking an APK patched
               from it, which has to be a full PASS with no crash, on a release phone or an
               emulator, naming the patch build identity this bundle carries, and -Verifier to it
               as well: the verifier-receipt.json scripts/verify-injected-registers.ps1 -Serial
               -ReceiptPath left for an APK patched from it, whose device verifier raised exactly
               the messages Meta's build raises, and a refusal names the ones that differ. Then
               notes from the whole CHANGELOG section with -Checked as What was checked, a
               lightweight tag, and the GitHub release with that checked copy, each file downloaded
               back and compared. -Checked must say "applied ... to Facebook <version>" with
               "Facebook" right after "to": the app's update check reads the target from that phrase.
    index      patches-bundle.json, the README's latest-release sentence and the bug form, from the
               published release and the gate's test results, and the repository description. Then
               commit "chore(release): point Manager to the verified X.Y.Z bundle" and push.

    Gradle goes through HUSHFACEBOOK_BUILD_WRAPPER as the pre-push gate does, or through the
    machine's build queue (BUILD_QUEUE_SCRIPT) when no wrapper is set. Every heavy step runs at
    release priority, and the variables this sets are put back when it ends. The text steps are
    tools/release_text.py, run with `py -3.13`.

.EXAMPLE
    scripts/release/release.ps1 -Stage source -Version 0.9.0 -Summary summary.txt
    scripts/release/release.ps1 -Stage preflight -Version 0.9.0
    scripts/release/release.ps1 -Stage bundle -Version 0.9.0
    scripts/release/release.ps1 -Stage rebuild -Version 0.9.0
    scripts/release/release.ps1 -Stage publish -Version 0.9.0 -Checked checked.txt -Walk <audit folder>/walk-receipt.json -Verifier verifier-receipt.json
    scripts/release/release.ps1 -Stage index -Version 0.9.0 -ReadmeSummary readme.txt -BundleSummary bundle.txt
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateSet('source', 'preflight', 'bundle', 'rebuild', 'publish', 'index')][string]$Stage,
    [Parameter(Mandatory = $true)][ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version,
    # source: a file holding the release's one-paragraph summary, and the CHANGELOG date if not today.
    [string]$Summary,
    [string]$Date,
    # publish: a file holding the What was checked paragraph.
    [string]$Checked,
    # publish: the walk-receipt.json of the whole-app walk on an APK patched from the staged bundle.
    [string]$Walk,
    # publish: the verifier-receipt.json of the device register check on an APK patched from it.
    [string]$Verifier,
    # index: files holding the README's line about the release and Manager's text for the bundle.
    [string]$ReadmeSummary,
    [string]$BundleSummary,
    [string]$Repository = 'SysAdminDoc/HushFacebook'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
. (Join-Path $root 'scripts/common.ps1')
. (Join-Path $root 'scripts/patch-target.ps1')
. (Join-Path $root 'scripts/release-receipt.ps1')
. (Join-Path $root 'scripts/walk-receipt.ps1')

$tag = "v$Version"
$assets = Join-Path $root "build/release-assets/$Version"
$releaseText = Join-Path $root 'tools/release_text.py'
$receiptName = "release-receipt-$Version.json"
# Published beside the receipt: what :patches:buildAndroid leaves in patches/build/release, Hush Unpacker's APK included.
$builtNames = @("patches-$Version.mpp", "patches-$Version.cdx.json", "patches-$Version.tooling.json", "hush-unpacker-$Version.apk")
$assetNames = $builtNames + $receiptName
$rebuildName = "rebuild-$Version.json"  # The rebuild stage's note when the rebuilt bundle matched. Not published.

function Step([string]$Text) { Write-Host "[release] $Text" -ForegroundColor Cyan }

function Assert-Clean {
    $changes = @(& git -C $root status --porcelain --untracked-files=all)
    if ($LASTEXITCODE -ne 0) { throw "git could not read the working tree at $root." }
    if ($changes.Count -gt 0) { throw "The working tree has changes, so what's checked isn't a commit: $($changes[0])" }
}

function Assert-SourceVersion {
    $source = Get-BundleVersion -Root $root
    if ($source -ne $Version) { throw "gradle.properties says $source, not $Version." }
}

function Assert-PushedHead {
    Assert-Clean
    $head = ([string](& git -C $root rev-parse HEAD)).Trim()
    $remote = (([string](& git -C $root ls-remote origin refs/heads/main)) -split '\s+')[0]
    if ($head -ne $remote) { throw "HEAD $head isn't origin/main $remote. Push the source commit first." }
    return $head
}

function Assert-File([string]$Path, [string]$What) {
    if (-not $Path) { throw "$What is missing." }
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "$What names $Path, which is not a file." }
}

function Invoke-Native {
    param([Parameter(Mandatory = $true)][string]$What, [Parameter(Mandatory = $true)][scriptblock]$Command)
    $global:LASTEXITCODE = 0
    & $Command
    if ($LASTEXITCODE -ne 0) { throw "$What did not pass (exit $LASTEXITCODE)." }
}

# py, or the launcher's per-user place when a shell opened before it was installed lacks it on PATH.
function Get-Python {
    if (Get-Command py -ErrorAction SilentlyContinue) { return 'py' }
    $launcher = if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA 'Programs/Python/Launcher/py.exe' }
    if ($launcher -and (Test-Path -LiteralPath $launcher -PathType Leaf)) { return $launcher }
    throw 'The Python launcher (py) is not on PATH, and the release text steps need it.'
}

function Invoke-ReleaseText {
    param([Parameter(Mandatory = $true)][string]$What, [Parameter(Mandatory = $true)][string[]]$Arguments)
    $python = Get-Python
    Invoke-Native $What { & $python -3.13 -I $releaseText @Arguments }
}

function Invoke-Gradle {
    param(
        [Parameter(Mandatory = $true)][string[]]$Tasks,
        # The checkout to build: this one, or the worktree the rebuild stage made.
        [string]$ProjectDir = $root
    )
    Set-GitHubPackagesCredential
    $wrapper = $env:HUSHFACEBOOK_BUILD_WRAPPER
    if ($wrapper -and -not (Test-Path -LiteralPath $wrapper -PathType Leaf)) {
        throw "HUSHFACEBOOK_BUILD_WRAPPER names $wrapper, which is not there."
    }
    $global:LASTEXITCODE = 0
    if ($wrapper) {
        & $wrapper -ProjectDir $ProjectDir -Tasks $Tasks
    } else {
        Invoke-HeavyJob -Label "release gradle $($Tasks[0])" -ScriptBlock { & (Join-Path $ProjectDir 'gradlew.bat') -p $ProjectDir @Tasks }
    }
    if ($LASTEXITCODE -ne 0) { throw "Gradle $($Tasks -join ' ') did not pass (exit $LASTEXITCODE)." }
}

# The rebuild stage's note, or nothing when there is none that reads. Publish asks for one naming
# its commit and the hash of the bundle it's about to upload.
function Read-RebuildNote {
    $path = Join-Path $assets $rebuildName
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $null }
    try { return [IO.File]::ReadAllText($path) | ConvertFrom-Json } catch { return $null }
}

function Test-RebuildNote($Note, [string]$Commit, [string]$BundleHash) {
    return [bool]($null -ne $Note -and $BundleHash -and [string]$Note.version -ceq $Version -and
        [string]$Note.commit -ceq $Commit -and [string]$Note.bundle.sha256 -ieq $BundleHash)
}

# Takes away the worktree the rebuild stage added, and nothing else: git's own removal, which
# drops its entry from the repository too, then whatever a build process still held open, the way
# the other scratch folders go. What won't go is named, since it's a whole checkout left in temp.
function Remove-RebuildWorktree([string]$Path) {
    $global:LASTEXITCODE = 0
    & git -C $root worktree remove --force $Path
    $removed = $LASTEXITCODE -eq 0
    if (Test-Path -LiteralPath $Path) { Remove-TreeWithoutFollowingLinks -Path $Path }
    # A folder that went only by the fallback leaves git's entry for it, which prune drops.
    if (-not $removed) { & git -C $root worktree prune }
    if (-not $removed -or (Test-Path -LiteralPath $Path)) {
        Write-Warning "The rebuild's worktree didn't go cleanly. See 'git worktree list', and remove $Path if it's still there."
    }
}

function Get-TestCount([string[]]$Directories) {
    $count = 0
    foreach ($directory in $Directories) {
        $files = @(Get-ChildItem -LiteralPath (Join-Path $root $directory) -Filter '*.xml' -File -ErrorAction SilentlyContinue)
        if ($files.Count -eq 0) { throw "No test results in $directory. The release gate writes them; run it in this checkout." }
        foreach ($file in $files) { $count += @(([xml](Get-Content -LiteralPath $file.FullName -Raw)).testsuite.testcase).Count }
    }
    return $count
}

# The hook imports these from the user's registry the same way: a shell opened before they were
# set doesn't have them. What this run sets or imports is put back at the end, so the shell isn't
# left at release priority or holding a token it didn't have.
$touched = @('HUSHFACEBOOK_FIXTURE_DIR', 'HUSHFACEBOOK_BUILD_WRAPPER', 'HUSHFACEBOOK_DESKTOP_JAR', 'HUSHFACEBOOK_WORKDIR',
    'BUILD_QUEUE_SCRIPT', 'BUILD_QUEUE_PRIORITY', 'GITHUB_ACTOR', 'GITHUB_TOKEN')
$before = @{}
foreach ($name in $touched) { $before[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    foreach ($name in $touched | Where-Object { $_ -like 'HUSHFACEBOOK_*' -or $_ -eq 'BUILD_QUEUE_SCRIPT' }) {
        if (-not $before[$name]) {
            $value = [Environment]::GetEnvironmentVariable($name, 'User')
            if ($value) { [Environment]::SetEnvironmentVariable($name, $value, 'Process') }
        }
    }
    $env:BUILD_QUEUE_PRIORITY = 'release'

    switch ($Stage) {
        'source' {
            Assert-Clean
            Assert-File $Summary '-Summary, the file holding the release summary,'
            $branch = ([string](& git -C $root rev-parse --abbrev-ref HEAD)).Trim()
            if ($branch -ne 'main') { throw "Releases are cut on main, and this checkout is on $branch." }
            if (& git -C $root tag --list $tag) { throw "$tag already exists." }
            # The tree was clean a moment ago, so whatever a failed step leaves behind is its own.
            try {
                Step "cutting the CHANGELOG for $Version"
                Invoke-ReleaseText 'The CHANGELOG cut' (@('cut', '--version', $Version, '--summary', $Summary) +
                    $(if ($Date) { @('--date', $Date) } else { @() }))
                Step 'version in gradle.properties and the README badge'
                Invoke-ReleaseText 'The version bump' @('bump', '--version', $Version)
                Step 'patch list'
                Invoke-Gradle @(':patches:generatePatchesList')
                Step 'translation check'
                Invoke-ReleaseText 'The translation check' @('check-translations')
            } catch {
                & git -C $root checkout --quiet -- .
                throw
            }
            Step "review the diff, commit it as 'chore(release): prepare $Version for publication', then run -Stage preflight"
        }
        'preflight' {
            Assert-Clean
            Assert-SourceVersion
            $started = Get-Date

            Step 'script contract tests'
            $global:LASTEXITCODE = 0
            & (Get-Process -Id $PID).Path -NoProfile -File (Join-Path $root 'scripts/test-script-contracts.ps1') -Root $root
            if ($LASTEXITCODE -ne 0) { throw "The script contract tests did not pass (exit $LASTEXITCODE)." }

            Step 'release text tests, translation check and a draft of the GitHub notes'
            $python = Get-Python
            Invoke-Native 'The release text tests' { & $python -3.13 -I -m unittest discover -s (Join-Path $root 'tools') -p 'test_*.py' }
            Invoke-ReleaseText 'The translation check' @('check-translations')
            # build/ is ignored, so the draft leaves the tree clean. It needs the cut heading only,
            # and shows the notes this release will open with before anything is built.
            New-Item -ItemType Directory -Force -Path $assets | Out-Null
            Invoke-ReleaseText 'The notes draft' @('github-notes', '--version', $Version, '--draft', '--out', (Join-Path $assets 'notes-draft.md'))

            # One Gradle run: the patch tests without the fixture partition and its selection check,
            # the extension tests a release's text depends on, and both lints. The pre-push gate's
            # quick pass is the same patch partition, so this is what fails first there too.
            Step 'quick patch tests, translation and release-check tests, both lints'
            Invoke-Gradle @(
                ':patches:test', '-x', ':patches:fixtureTest', '-x', ':patches:verifyPatchTestSelection',
                ':extensions:facebook:testDebugUnitTest', '--tests', '*L10nTest', '--tests', '*L10nCatalogTest',
                '--tests', '*ReleaseCheckTest',
                ':extensions:shared:library:lint', ':extensions:facebook:lint')

            Step 'release facts, with the index still on the previous release'
            $global:LASTEXITCODE = 0
            & (Join-Path $root 'scripts/validate-release-facts.ps1') -Root $root -SkipDescriptionTestCount `
                -AllowPublishedIndexLag -SkipTestResults
            if ($LASTEXITCODE -ne 0) { throw "The release facts check did not pass (exit $LASTEXITCODE)." }

            $minutes = ((Get-Date) - $started).TotalMinutes
            if ($minutes -gt 5) {
                Write-Warning (("Preflight took {0:N1} minutes, past its five-minute budget. A quick check that " +
                    'got slow belongs in the gate, not here.') -f $minutes)
            }
            Step (("preflight passed in {0:N1} minutes. The notes draft is in {1}. Push the source commit; its " +
                'pre-push gate runs the fixture tests, then run -Stage bundle') -f $minutes, $assets)
        }
        'bundle' {
            $head = Assert-PushedHead
            Assert-SourceVersion
            Step "bundle from $head"
            Invoke-Gradle @(':patches:buildAndroid')
            $built = Split-Path -Parent (Get-ReleaseBundlePath -Root $root -Version $Version)
            foreach ($name in $builtNames) {
                if (-not (Test-Path -LiteralPath (Join-Path $built $name) -PathType Leaf)) { throw "No $name in $built. :patches:buildAndroid writes it beside the bundle (the APK once helper/keystore.properties names its key)." }
            }

            # Every declared build's fixtures, picked as the gate picks them, so the receipt can
            # read the runs the gate kept instead of patching each one again.
            $fixtureDir = $env:HUSHFACEBOOK_FIXTURE_DIR
            if (-not $fixtureDir -or -not (Test-Path -LiteralPath $fixtureDir -PathType Container)) {
                throw 'HUSHFACEBOOK_FIXTURE_DIR has to name the folder of Facebook fixtures the receipt patches.'
            }
            $catalog = Get-Content -LiteralPath (Join-Path $root 'patches-list.json') -Raw | ConvertFrom-Json
            $versions = @($catalog.patches | ForEach-Object { $_.compatibility } |
                Where-Object { $_.packageName -eq 'com.facebook.katana' } | ForEach-Object { $_.targets } |
                ForEach-Object { [string]$_.version } | Where-Object { $_ } | Sort-Object -Unique)
            if ($versions.Count -eq 0) { throw 'patches-list.json declares no Facebook build.' }
            $fixtures = @(foreach ($declared in $versions) {
                    $found = @(Select-FixtureBuild -Folder $fixtureDir -Version $declared)
                    if ($found.Count -eq 0) { throw "HUSHFACEBOOK_FIXTURE_DIR holds no fixture of Facebook $declared, a build the bundle declares." }
                    $found | ForEach-Object { $_.FullName }
                })

            Step "receipt over $($fixtures.Count) fixtures of $($versions -join ', ')"
            # A fresh name each run, made here and not found, so nothing can be waiting in it.
            $work = Join-Path ([IO.Path]::GetTempPath()) "hushfacebook-receipt-$Version-$([guid]::NewGuid().ToString('N'))"
            New-Item -ItemType Directory -Path $work | Out-Null
            try {
                $global:LASTEXITCODE = 0
                & (Join-Path $root 'scripts/build-release-receipt.ps1') -Root $root -WorkDir $work -Fixture $fixtures `
                    -Bundle (Join-Path $built $builtNames[0]) -AppliedDir (Join-Path $root 'patches/build/fixture-apply')
                if ($LASTEXITCODE -ne 0) { throw "The receipt did not pass (exit $LASTEXITCODE)." }
            } finally { Remove-TreeWithoutFollowingLinks -Path $work }

            New-Item -ItemType Directory -Force -Path $assets | Out-Null
            Copy-Item -LiteralPath ($builtNames | ForEach-Object { Join-Path $built $_ }) -Destination $assets -Force
            Copy-Item -LiteralPath (Join-Path $root $receiptName) -Destination $assets -Force
            $sums = foreach ($name in $assetNames) {
                '{0}  {1}' -f (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $assets $name)).Hash.ToLowerInvariant(), $name
            }
            [IO.File]::WriteAllText((Join-Path $assets 'SHA256SUMS.txt'), (($sums -join "`n") + "`n"), [Text.UTF8Encoding]::new($false))
            $sums | ForEach-Object { Step $_ }
            Step "assets in $assets. Run -Stage rebuild next"
        }
        'rebuild' {
            $head = Assert-PushedHead
            Assert-SourceVersion
            foreach ($name in $assetNames + 'SHA256SUMS.txt') {
                if (-not (Test-Path -LiteralPath (Join-Path $assets $name) -PathType Leaf)) { throw "No $name in $assets. Run -Stage bundle first." }
            }
            # The staged bundle stays held from before its hash is taken until the compare is done,
            # so the hash the note names is the hash of the bytes that were compared.
            $staged = Join-Path $assets $builtNames[0]
            try { $hold = Open-HeldFile -Path $staged }
            catch { throw "The staged $($builtNames[0]) couldn't be held as itself, so nothing was rebuilt: $($_.Exception.Message)" }
            try {
                $stagedHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $staged).Hash.ToLowerInvariant()
                if (Test-RebuildNote (Read-RebuildNote) $head $stagedHash) {
                    Step "$($builtNames[0]) ($stagedHash) already matched a rebuild of $head, so nothing is built again"
                } else {
                    # A note for another commit or another bundle says nothing about this one.
                    Remove-Item -LiteralPath (Join-Path $assets $rebuildName) -Force -ErrorAction SilentlyContinue
                    # A fresh name each run, and only a folder git made under it here is ever removed.
                    $worktree = Join-Path ([IO.Path]::GetTempPath()) "hushfacebook-rebuild-$([guid]::NewGuid().ToString('N'))"
                    if (Test-Path -LiteralPath $worktree) { throw "$worktree is already there, and the rebuild only uses a folder it made." }
                    $added = $false
                    try {
                        Step "a fresh worktree of $head in $worktree"
                        Invoke-Native 'The rebuild worktree' { & git -C $root worktree add --quiet --detach $worktree $head }
                        $added = $true
                        # Where the Android SDK is. git keeps it out of every checkout, and it isn't
                        # one of the inputs a bundle is built from, so the worktree stays clean.
                        $sdk = Join-Path $root 'local.properties'
                        if (Test-Path -LiteralPath $sdk -PathType Leaf) { Copy-Item -LiteralPath $sdk -Destination $worktree }

                        Step 'bundle from the worktree'
                        # Without the build cache: its keys don't depend on the folder, so the staged
                        # build's own outputs would come back from it, and a build that doesn't come
                        # out the same twice would pass.
                        Invoke-Gradle @(':patches:buildAndroid', '--no-build-cache') -ProjectDir $worktree
                        $rebuilt = Get-ReleaseBundlePath -Root $worktree -Version $Version
                        if (-not (Test-Path -LiteralPath $rebuilt -PathType Leaf)) {
                            throw "The rebuild left no $($builtNames[0]) in $(Split-Path -Parent $rebuilt)."
                        }

                        Step 'holding the staged bundle to the rebuilt one'
                        $verdict = Test-RebuiltBundle -Root $root -BundlePath $staged -RebuiltRoot $worktree -RebuiltPath $rebuilt -Commit $head
                        if (-not $verdict.Valid) { throw "The rebuild doesn't back the staged bundle, so it can't be published: $($verdict.Reason)" }
                        $moved = @($verdict.Comparison.Identity | Where-Object { $_ -and -not $_.Same } | ForEach-Object { [string]$_.Name })
                        $note = [ordered]@{
                            version = $Version
                            commit = $head
                            bundle = [ordered]@{ file = $builtNames[0]; sha256 = $stagedHash }
                            rebuilt = [ordered]@{ sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $rebuilt).Hash.ToLowerInvariant() }
                            compared = [int]$verdict.Comparison.Compared
                            identityDiffers = $moved
                        }
                        [IO.File]::WriteAllText((Join-Path $assets $rebuildName), (($note | ConvertTo-Json -Depth 4) + "`n"), [Text.UTF8Encoding]::new($false))
                        Step "$($note.compared) entries match by name and SHA-256"
                        if ($moved.Count -gt 0) {
                            Step "the build identity differs in $($moved -join ' and '), and each bundle was held to its own checkout for it"
                        }
                    } finally {
                        if ($added) { Remove-RebuildWorktree $worktree }
                    }
                }
            } finally { $hold.Dispose() }
            Step 'the staged bundle is what the commit builds. Write the What was checked paragraph, then run -Stage publish'
        }
        'publish' {
            $head = Assert-PushedHead
            Assert-File $Checked '-Checked, the file holding the What was checked paragraph,'
            Assert-File $Walk '-Walk, the walk-receipt.json the whole-app walk of this bundle left,'
            Assert-File $Verifier '-Verifier, the verifier-receipt.json the device register check of this bundle left,'
            foreach ($name in $assetNames + 'SHA256SUMS.txt') {
                if (-not (Test-Path -LiteralPath (Join-Path $assets $name) -PathType Leaf)) { throw "No $name in $assets. Run -Stage bundle first." }
            }
            $rebuildNote = Read-RebuildNote
            if ($null -eq $rebuildNote) { throw "No $rebuildName in $assets that reads. Run -Stage rebuild first." }
            $receipt = Get-Content -LiteralPath (Join-Path $assets $receiptName) -Raw | ConvertFrom-Json
            # build-release-receipt writes the commit it saw as release.commit.
            $receiptCommit = [string]$receipt.release.commit
            if ($receiptCommit -ne $head) {
                throw "The receipt is for $(if ($receiptCommit) { $receiptCommit } else { 'no commit' }), and HEAD is $head."
            }

            # What goes out is a copy taken now and checked, so a staged file changed after the
            # check can't be uploaded in its place, and the download back is held to the same bytes.
            # The copy's folder gets a name nobody knows ahead, and it and each file in it stay held
            # (Open-HeldFile) from before the check until the download back is compared: the folder
            # can't be renamed or swapped for a link, and a file can't be written, replaced or
            # deleted. Each hold is proved to be the path's own file rather than one a link there
            # led to, so what was checked is what gh opens by those paths. The hashes taken as
            # they're opened are what the hosted copies must match.
            $publishing = Join-Path ([IO.Path]::GetTempPath()) "hushfacebook-publishing-$Version-$([guid]::NewGuid().ToString('N'))"
            New-Item -ItemType Directory -Path $publishing | Out-Null
            $held = [Collections.Generic.List[IDisposable]]::new()
            try {
                try { $held.Add((Open-HeldFile -Path $publishing -Directory)) }
                catch { throw "The publishing copy couldn't be held as itself, so nothing was tagged: $($_.Exception.Message)" }
                Copy-Item -LiteralPath (($assetNames + 'SHA256SUMS.txt') | ForEach-Object { Join-Path $assets $_ }) -Destination $publishing
                $checkedHashes = @{}
                foreach ($name in $assetNames + 'SHA256SUMS.txt') {
                    $path = Join-Path $publishing $name
                    try { $held.Add((Open-HeldFile -Path $path)) }
                    catch { throw "$name in the publishing copy couldn't be held as itself, so nothing was tagged: $($_.Exception.Message)" }
                    $checkedHashes[$name] = (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash
                }
                Step 'holding the staged assets to SHA256SUMS.txt, the receipt and the source commit'
                $staged = Test-StagedReleaseAt -Root $root -Folder $publishing -Version $Version -Commit $head
                if (-not $staged.Valid) { throw "The staged release doesn't hold together, so nothing was tagged: $($staged.Reason)" }
                # Anything else in the copy that's a link is refused too: nothing put one there, so
                # one showing up means the copy was tampered with.
                foreach ($item in @(Get-Item -LiteralPath $publishing -Force) + @(Get-ChildItem -LiteralPath $publishing -Force)) {
                    if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
                        throw "$($item.Name) in the publishing copy is a link, so nothing was tagged: what was checked might not be what gh uploads."
                    }
                }
                # The identity that check reads is the build's own word. The rebuild stage's note
                # is what backs it, and it has to name this commit and the hash of the bundle in
                # the copy, so a bundle staged or changed since the rebuild isn't taken for the
                # one that was compared.
                if (-not (Test-RebuildNote $rebuildNote $head $checkedHashes[$builtNames[0]])) {
                    throw ("The staged $($builtNames[0]) isn't the bundle a rebuild of $head was compared with, so nothing " +
                        'was tagged. Run -Stage rebuild again.')
                }
                # Nothing goes out that hasn't been walked: the whole app, on an APK patched from
                # this very bundle, with the app's own word for which bundle that was.
                $bundleBuild = Get-BundlePatchBuild -BundlePath (Join-Path $publishing $builtNames[0])
                $walked = Test-WalkReceipt -Path $Walk -PatchBuild $bundleBuild
                if (-not $walked.Valid) {
                    throw ("The whole-app walk doesn't back the staged $($builtNames[0]), so nothing was tagged: $($walked.Reason). " +
                        'Patch an APK from this bundle, run scripts/device-audit.ps1 on a release phone or an emulator, and pass its walk-receipt.json as -Walk.')
                }
                Step "walked on $($walked.Receipt.device): $($walked.Receipt.familiesRan) of $($walked.Receipt.families) hook families ran, no crash"
                # Nor anything the Android verifier rejects. A method that fails it assembles and
                # applies without a word and breaks whichever screen reaches its class first.
                $verified = Test-VerifierReceipt -Path $Verifier -PatchBuild $bundleBuild
                if (-not $verified.Valid) {
                    throw ("The device register check doesn't back the staged $($builtNames[0]), so nothing was tagged: $($verified.Reason). " +
                        'Run scripts/verify-injected-registers.ps1 -PatchedApk <an APK patched from this bundle> -Serial <phone> ' +
                        '-ReceiptPath verifier-receipt.json under a lease, and pass that receipt as -Verifier.')
                }
                Step "verified on $($verified.Receipt.device): the patched build's verifier raised $($verified.Receipt.patchedMessages) message(s), as Meta's does"

                $notes = Join-Path $assets 'notes.md'
                Invoke-ReleaseText 'The release notes' @('github-notes', '--version', $Version, '--checked', $Checked, '--out', $notes)
                if (-not (& git -C $root tag --list $tag)) { Invoke-Native 'The tag' { & git -C $root tag $tag $head } }
                if (([string](& git -C $root rev-parse "$tag^{commit}")).Trim() -ne $head) { throw "$tag doesn't point at $head." }
                Invoke-Native 'The tag push' { & git -C $root push --quiet origin $tag }

                # Create may have succeeded before an interrupted download-back. Reuse that
                # release without uploading again, and hold its hosted bytes to the same checks.
                $preference = $ErrorActionPreference
                try {
                    $ErrorActionPreference = 'Continue'
                    $global:LASTEXITCODE = -1
                    $releaseOutput = @(& gh release view $tag -R $Repository --json 'tagName,isDraft,publishedAt' 2>&1 |
                        ForEach-Object { [string]$_ })
                    $releaseExit = $LASTEXITCODE
                } finally { $ErrorActionPreference = $preference }
                $releaseMetadataText = $releaseOutput -join "`n"
                if ($releaseExit -eq 0) {
                    try { $publishedRelease = $releaseMetadataText | ConvertFrom-Json }
                    catch { throw "gh returned no readable release metadata for ${tag}: $($_.Exception.Message)" }
                    if ($publishedRelease.isDraft -is [bool] -and $publishedRelease.isDraft) {
                        throw "$tag is a draft. Publish it explicitly before retrying verification."
                    }
                    $publishedAt = [DateTimeOffset]::MinValue
                    $hasPublishedTime = $publishedRelease.publishedAt -is [DateTime] -or $publishedRelease.publishedAt -is [DateTimeOffset]
                    if ($publishedRelease.publishedAt -is [string]) {
                        $hasPublishedTime = [DateTimeOffset]::TryParse($publishedRelease.publishedAt,
                            [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::AssumeUniversal, [ref]$publishedAt)
                    }
                    if ($publishedRelease.tagName -isnot [string] -or $publishedRelease.tagName -cne $tag -or
                            $publishedRelease.isDraft -isnot [bool] -or -not $hasPublishedTime) {
                        throw "gh returned no matching published release metadata for $tag."
                    }
                    Step "$tag is already published; verifying its existing assets"
                } elseif ($releaseMetadataText -match '\brelease not found\b|\bHTTP 404\b') {
                    Step "publishing $tag"
                    Invoke-Native 'gh release create' {
                        & gh release create $tag -R $Repository --verify-tag --title $tag --notes-file $notes `
                            (($assetNames + 'SHA256SUMS.txt') | ForEach-Object { Join-Path $publishing $_ })
                    }
                } else {
                    throw "gh could not check whether $tag is already published (exit ${releaseExit}): $releaseMetadataText"
                }
                # A name nobody knows ahead, like the publishing copy's, so nothing can wait there
                # with good copies for the compare.
                $check = Join-Path ([IO.Path]::GetTempPath()) "hushfacebook-published-$Version-$([guid]::NewGuid().ToString('N'))"
                try {
                    Invoke-Native 'The download back' { & gh release download $tag -R $Repository -D $check }
                    foreach ($name in $assetNames + 'SHA256SUMS.txt') {
                        $hostedPath = Join-Path $check $name
                        if (-not (Test-Path -LiteralPath $hostedPath -PathType Leaf)) { throw "The release has no $name." }
                        if ((Get-FileHash -Algorithm SHA256 -LiteralPath $hostedPath).Hash -ne $checkedHashes[$name]) { throw "The hosted $name differs from the local one." }
                        Step "$name matches the hosted copy"
                    }
                } finally { Remove-TreeWithoutFollowingLinks -Path $check }
            } finally {
                foreach ($handle in $held) { $handle.Dispose() }
                # Links go as links, at any depth: Windows PowerShell's recursive removal follows a
                # junction and would empty whatever it points at.
                Remove-TreeWithoutFollowingLinks -Path $publishing
            }
            Step 'published. Write the README and bundle summaries, then run -Stage index'
        }
        'index' {
            Assert-PushedHead | Out-Null
            Assert-File $ReadmeSummary '-ReadmeSummary, the file holding the README line,'
            Assert-File $BundleSummary '-BundleSummary, the file holding the Manager text,'
            $published = [string](& gh release view $tag -R $Repository --json publishedAt --jq .publishedAt)
            if ($LASTEXITCODE -ne 0 -or -not $published.Trim()) { throw "$tag isn't published. Run -Stage publish first." }
            $created = [DateTime]::Parse($published.Trim(), [Globalization.CultureInfo]::InvariantCulture,
                [Globalization.DateTimeStyles]::AdjustToUniversal).ToString('yyyy-MM-ddTHH:mm:ss')
            # The counts the facts check holds the description to, read the same way from the
            # gate's run in this checkout.
            $runtime = Get-TestCount @('extensions/facebook/build/test-results/testDebugUnitTest')
            $patch = Get-TestCount @('patches/build/test-results/test', 'patches/build/test-results/fixtureTest')
            Invoke-ReleaseText 'The index' @('index', '--version', $Version, '--created', $created,
                '--bundle-summary', $BundleSummary, '--readme-summary', $ReadmeSummary,
                '--runtime', [string]$runtime, '--patch', [string]$patch)
            # The facts check on the index push reads the receipt from the root, where .gitignore keeps it out.
            Copy-Item -LiteralPath (Join-Path $assets $receiptName) -Destination (Join-Path $root $receiptName) -Force

            $description = [string](& gh repo view $Repository --json description --jq .description)
            # An unread description would compare equal to its own update and be left as it was.
            if ($LASTEXITCODE -ne 0) { throw "gh couldn't read $Repository's description (exit $LASTEXITCODE)." }
            $catalog = Get-Content -LiteralPath (Join-Path $root 'patches-list.json') -Raw | ConvertFrom-Json
            $count = @($catalog.patches).Count
            # The build moves with the catalog too, or a port leaves the description on the old one.
            $build = (Get-PatchTarget -PatchList $catalog).PackageVersion
            $updated = $description.Trim() -replace 'Hushfacebook v\d+\.\d+\.\d+', "Hushfacebook v$Version" -replace '\b\d+ patches\b', "$count patches" `
                -replace '\bFacebook \d+(?:\.\d+)+', "Facebook $build"
            if ($updated -ne $description.Trim()) { Invoke-Native 'The repository description' { & gh repo edit $Repository --description $updated } }
            Step ("index points at $tag ($runtime runtime tests, $patch patch tests). Commit it as " +
                "'chore(release): point Manager to the verified $Version bundle' and push")
        }
    }
} finally {
    foreach ($name in $touched) { [Environment]::SetEnvironmentVariable($name, $before[$name], 'Process') }
}
