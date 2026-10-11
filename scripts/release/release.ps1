<#
.SYNOPSIS
    Runs a Hushfacebook release one stage at a time: source, preflight, bundle, publish, index.

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
    bundle     From the pushed, clean source commit: the bundle with its SBOM and tooling list,
               the receipt over every declared build (reading the runs the gate kept in
               patches/build/fixture-apply) and SHA256SUMS.txt, into build/release-assets/<version>.
    publish    Notes from the whole CHANGELOG section with -Checked as What was checked, a
               lightweight tag, the GitHub release with the five assets, each downloaded back and
               compared. -Checked must say "applied ... to Facebook <version>" with "Facebook"
               right after "to": the app's update check reads the target from that phrase.
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
    scripts/release/release.ps1 -Stage publish -Version 0.9.0 -Checked checked.txt
    scripts/release/release.ps1 -Stage index -Version 0.9.0 -ReadmeSummary readme.txt -BundleSummary bundle.txt
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateSet('source', 'preflight', 'bundle', 'publish', 'index')][string]$Stage,
    [Parameter(Mandatory = $true)][ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version,
    # source: a file holding the release's one-paragraph summary, and the CHANGELOG date if not today.
    [string]$Summary,
    [string]$Date,
    # publish: a file holding the What was checked paragraph.
    [string]$Checked,
    # index: files holding the README's line about the release and Manager's text for the bundle.
    [string]$ReadmeSummary,
    [string]$BundleSummary,
    [string]$Repository = 'SysAdminDoc/HushFacebook'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
. (Join-Path $root 'scripts/common.ps1')
. (Join-Path $root 'scripts/patch-target.ps1')

$tag = "v$Version"
$assets = Join-Path $root "build/release-assets/$Version"
$releaseText = Join-Path $root 'tools/release_text.py'
$receiptName = "release-receipt-$Version.json"
# What :patches:buildAndroid leaves in patches/build/release, published beside the receipt.
$builtNames = @("patches-$Version.mpp", "patches-$Version.cdx.json", "patches-$Version.tooling.json")
$assetNames = $builtNames + $receiptName

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
    param([Parameter(Mandatory = $true)][string[]]$Tasks)
    Set-GitHubPackagesCredential
    $wrapper = $env:HUSHFACEBOOK_BUILD_WRAPPER
    if ($wrapper -and -not (Test-Path -LiteralPath $wrapper -PathType Leaf)) {
        throw "HUSHFACEBOOK_BUILD_WRAPPER names $wrapper, which is not there."
    }
    $global:LASTEXITCODE = 0
    if ($wrapper) {
        & $wrapper -ProjectDir $root -Tasks $Tasks
    } else {
        Invoke-HeavyJob -Label "release gradle $($Tasks[0])" -ScriptBlock { & (Join-Path $root 'gradlew.bat') -p $root @Tasks }
    }
    if ($LASTEXITCODE -ne 0) { throw "Gradle $($Tasks -join ' ') did not pass (exit $LASTEXITCODE)." }
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
                if (-not (Test-Path -LiteralPath (Join-Path $built $name) -PathType Leaf)) {
                    throw "No $name in $built. :patches:buildAndroid writes it beside the bundle."
                }
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
            $work = Join-Path ([IO.Path]::GetTempPath()) "hushfacebook-receipt-$Version"
            New-Item -ItemType Directory -Force -Path $work | Out-Null
            try {
                $global:LASTEXITCODE = 0
                & (Join-Path $root 'scripts/build-release-receipt.ps1') -Root $root -WorkDir $work -Fixture $fixtures `
                    -Bundle (Join-Path $built $builtNames[0]) -AppliedDir (Join-Path $root 'patches/build/fixture-apply')
                if ($LASTEXITCODE -ne 0) { throw "The receipt did not pass (exit $LASTEXITCODE)." }
            } finally { Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue }

            New-Item -ItemType Directory -Force -Path $assets | Out-Null
            Copy-Item -LiteralPath ($builtNames | ForEach-Object { Join-Path $built $_ }) -Destination $assets -Force
            Copy-Item -LiteralPath (Join-Path $root $receiptName) -Destination $assets -Force
            $sums = foreach ($name in $assetNames) {
                '{0}  {1}' -f (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $assets $name)).Hash.ToLowerInvariant(), $name
            }
            [IO.File]::WriteAllText((Join-Path $assets 'SHA256SUMS.txt'), (($sums -join "`n") + "`n"), [Text.UTF8Encoding]::new($false))
            $sums | ForEach-Object { Step $_ }
            Step "assets in $assets. Write the What was checked paragraph, then run -Stage publish"
        }
        'publish' {
            $head = Assert-PushedHead
            Assert-File $Checked '-Checked, the file holding the What was checked paragraph,'
            foreach ($name in $assetNames + 'SHA256SUMS.txt') {
                if (-not (Test-Path -LiteralPath (Join-Path $assets $name) -PathType Leaf)) { throw "No $name in $assets. Run -Stage bundle first." }
            }
            $receipt = Get-Content -LiteralPath (Join-Path $assets $receiptName) -Raw | ConvertFrom-Json
            if ($receipt.source.commit -and $receipt.source.commit -ne $head) { throw "The receipt is for $($receipt.source.commit), and HEAD is $head." }

            $notes = Join-Path $assets 'notes.md'
            Invoke-ReleaseText 'The release notes' @('github-notes', '--version', $Version, '--checked', $Checked, '--out', $notes)
            if (-not (& git -C $root tag --list $tag)) { Invoke-Native 'The tag' { & git -C $root tag $tag $head } }
            if (([string](& git -C $root rev-parse "$tag^{commit}")).Trim() -ne $head) { throw "$tag doesn't point at $head." }
            Invoke-Native 'The tag push' { & git -C $root push --quiet origin $tag }

            Step "publishing $tag"
            Invoke-Native 'gh release create' {
                & gh release create $tag -R $Repository --verify-tag --title $tag --notes-file $notes `
                    (($assetNames + 'SHA256SUMS.txt') | ForEach-Object { Join-Path $assets $_ })
            }
            $check = Join-Path ([IO.Path]::GetTempPath()) "hushfacebook-published-$Version"
            Remove-Item -LiteralPath $check -Recurse -Force -ErrorAction SilentlyContinue
            try {
                Invoke-Native 'The download back' { & gh release download $tag -R $Repository -D $check }
                foreach ($name in $assetNames + 'SHA256SUMS.txt') {
                    $local = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $assets $name)).Hash
                    $hostedPath = Join-Path $check $name
                    if (-not (Test-Path -LiteralPath $hostedPath -PathType Leaf)) { throw "The release has no $name." }
                    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $hostedPath).Hash -ne $local) { throw "The hosted $name differs from the local one." }
                    Step "$name matches the hosted copy"
                }
            } finally { Remove-Item -LiteralPath $check -Recurse -Force -ErrorAction SilentlyContinue }
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
