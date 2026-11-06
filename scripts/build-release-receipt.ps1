<#
.SYNOPSIS
    Build the machine-readable release provenance receipt.

.DESCRIPTION
    Local patching puts three things together: the user's APK, this bundle, and a toolchain. A
    checksum can only say that one of them arrived unaltered. This writes down all of it as one
    JSON document: which commit and tag the bundle was built from, what the bundle weighs and
    hashes to, which extension payloads it carries, which APK each proof was run against, that
    every patch in the catalog applied to it, and what patching did to the Android manifest.

    Nothing here is asserted. Each fixture is patched with the real desktop CLI, the verdicts
    come out of the CLI's own result report, and both manifests are read back with aapt2: the
    patched one, and the one the patches started from. A split bundle is merged into one APK first
    with the CLI's own merger (Get-MergedApk), the CLI patches that merge, and its manifest is the
    baseline, since the merge rewrites the manifest before any patch runs. A
    patch that fails on any fixture stops the run with its name and no receipt is written: the
    receipt describes a bundle that fully applies, which is why the validator refuses any
    verdict of applied = false rather than reading it as a recorded failure. Every build the
    catalog declares needs a fixture of its own, patched without -f: the README says the
    patches were checked on each of them, and the receipt is refused without a run of every
    one. A fixture of any other build is patched under -f and recorded as forced. The fixtures'
    base manifests are read before anything is patched, so a declared build with no fixture
    stops the run at once instead of after the others, which for Facebook unpacks gigabytes.

    The patched APKs are working files and are deleted on the way out, including after a failure.
    The merges and patch runs share one slot of the machine's build queue when BUILD_QUEUE_SCRIPT
    names one (Invoke-HeavyJob in common.ps1), and run straight away when it doesn't.

    -AppliedDir names the runs the pre-push gate kept (patches/build/fixture-apply, one folder per
    fixture file, written by verify-all-patches.ps1 -KeepIn). A fixture whose kept stamp names
    this bundle, this APK, this patch list and this CLI by SHA-256, and the same version, version
    code and forced flag, is read from there: its CLI report, patched APK and merge are copied into
    the run's own folder, hashed there and go through the same checks a fresh run's do, and only
    the patching is skipped. Any other fixture is patched here as before, and kept runs are read,
    never changed.

    The bundle has to be a build of HEAD from a clean tree, and a clean tree when the receipt is
    cut doesn't show that. So its stamp has to be HEAD's commit time (the build writes 0 when the
    tree had uncommitted changes as it started), and no source may be newer than it. Both are
    checked before anything is patched.

    Before any of that, the SBOM :patches:buildAndroid wrote beside the bundle is held to it and
    the libraries it lists are put to OSV (release-advisories.ps1). A high or critical advisory
    that scripts/advisory-exceptions.txt doesn't accept stops the run before anything is patched,
    and so does an OSV that can't be asked. -SkipAdvisoryCheck lets an offline run through with a
    warning. The receipt records the SBOM's name, hash and component count either way, and the
    pre-push hook asks OSV again on the index push.

.EXAMPLE
    One -Fixture taking a comma separated list, not the switch repeated: PowerShell binds a
    parameter once and refuses the second.

    scripts/build-release-receipt.ps1 -WorkDir C:\scratch `
        -Fixture C:\fixtures\facebook-a.apk,C:\fixtures\facebook-b.apk
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string[]]$Fixture,
    [Parameter(Mandatory = $true)][string]$WorkDir,
    [string]$Root,
    [string]$Bundle,
    [string]$PatchList,
    [string]$DesktopJar,
    [string]$Java,
    [string]$Aapt2,
    [string]$OutputPath,
    # The SBOM :patches:buildAndroid writes beside the bundle, named for it. Defaults to that.
    [string]$Sbom,
    # For working with no network only: OSV isn't asked about the SBOM's libraries, and the run
    # says so. The index push asks again, so a release can't go out on it.
    [switch]$SkipAdvisoryCheck,
    # The pre-push gate's kept fixture runs, read instead of patching again when their stamps
    # match. See the description.
    [string]$AppliedDir
)

$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }

. (Join-Path $PSScriptRoot 'Resolve-Java.ps1')
. (Join-Path $PSScriptRoot 'patch-report.ps1')
. (Join-Path $PSScriptRoot 'patch-target.ps1')
. (Join-Path $PSScriptRoot 'release-receipt.ps1')
. (Join-Path $PSScriptRoot 'release-advisories.ps1')
. (Join-Path $PSScriptRoot 'common.ps1')

$Java = Resolve-Java -Explicit $Java
$Aapt2 = Resolve-Aapt2 -Explicit $Aapt2 -Root $Root

if (-not $DesktopJar) { $DesktopJar = $env:HUSHFACEBOOK_DESKTOP_JAR }
if (-not $DesktopJar -or -not (Test-Path -LiteralPath $DesktopJar -PathType Leaf)) {
    throw ('No Morphe desktop CLI. Pass -DesktopJar or set HUSHFACEBOOK_DESKTOP_JAR: the receipt ' +
        'records real patch verdicts, so it has to actually patch each fixture.')
}

$releaseVersion = Get-BundleVersion -Root $Root
if (-not $Bundle) { $Bundle = Get-ReleaseBundlePath -Root $Root -Version $releaseVersion }
if (-not (Test-Path -LiteralPath $Bundle -PathType Leaf)) {
    throw "No bundle for version ${releaseVersion}: $Bundle. Run :patches:generatePatchesList then :patches:buildAndroid."
}
# Found in PowerShell's location; the ZipFile reads below would look in the process directory.
$Bundle = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Bundle)
if (-not $PatchList) { $PatchList = Join-Path $Root 'patches-list.json' }
if (-not $OutputPath) { $OutputPath = Join-Path $Root "release-receipt-$releaseVersion.json" }

$catalog = Get-Content -LiteralPath $PatchList -Raw | ConvertFrom-Json
$patchNames = @($catalog.patches | ForEach-Object { $_.name })
if ($patchNames.Count -eq 0) { throw "No patches listed in $PatchList." }
$dependencyNames = @(Get-PatchDependencyNames -PatchList $catalog -RequestedNames $patchNames)
$expectedTarget = Get-PatchTarget -PatchList $catalog

$catalogText = Get-Content -LiteralPath (Join-Path $Root 'gradle/libs.versions.toml') -Raw
$patcherMatch = [regex]::Match($catalogText, '(?m)^\s*morphe-patcher\s*=\s*"([^"]+)"')
$floorMatch = [regex]::Match($catalogText, '(?m)^\s*manager-floor\s*=\s*"([^"]+)"')
if (-not $patcherMatch.Success -or -not $floorMatch.Success) {
    throw 'gradle/libs.versions.toml does not pin both morphe-patcher and manager-floor.'
}

$commit = (& git -C $Root rev-parse HEAD).Trim()
if ($commit -notmatch '^[0-9a-f]{40}$') { throw "git did not answer with a commit: $commit" }
$commitTimestamp = [long](& git -C $Root log -1 --format=%ct).Trim()

# The bundle is read once, here, before any fixture is patched. Measuring it at the end instead
# would describe whatever the release path holds when the run finishes, which is not necessarily
# what the patch runs used: a run takes long enough for another buildAndroid to replace it.
$bundleManifest = Get-BundleManifestFacts -BundlePath $Bundle
$bundleSize = (Get-Item -LiteralPath $Bundle).Length
$bundleHash = Get-Sha256Hex -Path $Bundle

# Refused here rather than reported, because a receipt that records the mismatch would be a
# document saying its own subject cannot be rebuilt from the source it names.
$dirty = @(& git -C $Root status --porcelain)
if ($dirty.Count -gt 0) {
    # -join, not Join-String: the pre-push hook prefers pwsh and falls back to Windows
    # PowerShell 5.1, which has no Join-String, so a dirty tree would have stopped the run with
    # a command-not-found instead of the reason it stopped.
    $shown = @($dirty | Select-Object -First 5 | ForEach-Object { $_.Trim() }) -join '; '
    throw ("The working tree has uncommitted changes, so the commit this receipt would name is " +
        "not what was built: $shown")
}

# A clean tree now says nothing about the tree the bundle was built from. Edits someone else made
# in a shared checkout can be in the bundle and gone again by the time the receipt is cut, and the
# stamp used to be HEAD's commit time whatever the tree held. :patches:buildAndroid stamps a bundle
# 0 now when the tree had uncommitted changes as it started, and anything but this commit's time
# means the bundle isn't a build of it. The receipt check at the end refuses that too, but only
# once every fixture has been patched.
$expectedStamp = $commitTimestamp * 1000
if ($bundleManifest.timestamp -ne $expectedStamp) {
    if ($bundleManifest.timestamp -eq 0) {
        throw ("The bundle is stamped 0. :patches:buildAndroid writes that when the working tree has uncommitted " +
            "changes as the build starts, or git can't read it, so this isn't a build of commit $commit. " +
            'Build it again from a clean tree. Nothing was patched.')
    }
    throw ("The bundle is stamped $($bundleManifest.timestamp), but commit $commit was made at $expectedStamp. " +
        "It was built from another commit, or with SOURCE_DATE_EPOCH set to something else. Build it again " +
        'from this commit. Nothing was patched.')
}
# Once morphe-signing.pub exists, the index publishes it and Manager refuses any bundle that key
# didn't sign. verifyBundle already checked the signature against the bytes; this is the release
# saying the bundle has one, since a build without HUSHFACEBOOK_SIGNING_KEY_FILE leaves it off.
$publishedKeyPath = Join-Path $Root 'morphe-signing.pub'
if (Test-Path -LiteralPath $publishedKeyPath -PathType Leaf) {
    $publishedKey = (Get-Content -LiteralPath $publishedKeyPath -Raw).Trim()
    $bundleBytes = [IO.File]::ReadAllBytes($Bundle)
    $signer = $null
    # The end of central directory record is the last one whose comment reaches the end of the file.
    for ($at = $bundleBytes.Length - 22; $at -ge [Math]::Max(0, $bundleBytes.Length - 22 - 65535); $at--) {
        if ($bundleBytes[$at] -eq 0x50 -and $bundleBytes[$at + 1] -eq 0x4b -and $bundleBytes[$at + 2] -eq 5 -and
            $bundleBytes[$at + 3] -eq 6 -and
            ($bundleBytes[$at + 20] + 256 * $bundleBytes[$at + 21]) -eq ($bundleBytes.Length - $at - 22)) {
            $comment = [Text.Encoding]::ASCII.GetString($bundleBytes, $at + 22, $bundleBytes.Length - $at - 22)
            # Case-sensitive like Manager's startsWith, which reads "morphesig1" as an unsigned bundle.
            if ($comment -cmatch '^MorpheSig1 (\S+) \S+$') { $signer = $Matches[1] }
            break
        }
    }
    if ($signer -cne $publishedKey) {
        # A user variable set after this shell opened never reached the build, so say that rather than "set it".
        $why = if ($signer) {
            'Point HUSHFACEBOOK_SIGNING_KEY_FILE, or hushfacebook.signingKeyFile in ~/.gradle/gradle.properties, at the key morphe-signing.pub was made from'
        } elseif (-not $env:HUSHFACEBOOK_SIGNING_KEY_FILE -and [Environment]::GetEnvironmentVariable('HUSHFACEBOOK_SIGNING_KEY_FILE', 'User')) {
            'HUSHFACEBOOK_SIGNING_KEY_FILE is in your user environment, but this shell opened before it was set, so the build never saw it. Open a new shell, or put hushfacebook.signingKeyFile in ~/.gradle/gradle.properties,'
        } else { 'Set HUSHFACEBOOK_SIGNING_KEY_FILE, or hushfacebook.signingKeyFile in ~/.gradle/gradle.properties, to the key file' }
        throw ("The bundle isn't signed by morphe-signing.pub's key ($(if ($signer) { "it names $signer" } else { 'it has no signature' })), " +
            "and Manager refuses it once the index publishes that key. $why and build again. Nothing was patched.")
    }
}
# And what changed after the build started: an edit made and put back since leaves the tree clean
# and the stamp right, and a file written after the bundle is the trace it leaves.
$newerSources = @(Get-SourcesNewerThanBundle -Root $Root -Bundle $Bundle)
if ($newerSources.Count -gt 0) {
    throw ("$($newerSources.Count) source file(s) changed after the bundle was built, the newest " +
        "$($newerSources[0].FullName). The bundle may not hold what they hold now, so build it again. " +
        'Nothing was patched.')
}
# File times miss an input deleted, renamed or put back behind an older time. A bundle that carries
# the digest of its inputs is held to the checkout's; a legacy one with no record keeps the checks above.
if ((Get-BundleIdentityFacts -BundlePath $Bundle).Present) {
    $staleBundle = Get-BundleInputMismatch -Root $Root -BundlePath $Bundle
    if ($staleBundle) { throw "The bundle doesn't match this checkout: $staleBundle. Build it again. Nothing was patched." }
}

# The SBOM, read with the bundle and for the same reason, and held to it: an SBOM left from another
# build would put another bundle's libraries in front of OSV. Then the advisory gate, before any
# fixture is patched, so a release OSV refuses doesn't cost the patch runs first.
if (-not $Sbom) { $Sbom = [System.IO.Path]::ChangeExtension($Bundle, '.cdx.json') }
if (-not (Test-Path -LiteralPath $Sbom -PathType Leaf)) {
    throw "No SBOM for the bundle: $Sbom. :patches:buildAndroid writes it beside the bundle, so build again."
}
$Sbom = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Sbom)
$sbomDocument = Read-ReleaseSbom -Path $Sbom
$sbomBound = Test-ReleaseSbom -Sbom $sbomDocument -BundlePath $Bundle -BundleName (Split-Path -Leaf $Bundle) `
    -ExpectedCommit $commit
if (-not $sbomBound.Valid) { throw "The SBOM does not describe the bundle: $($sbomBound.Reason)" }
Invoke-ReleaseAdvisoryGate -Sbom $sbomDocument -ExceptionsPath (Join-Path $PSScriptRoot 'advisory-exceptions.txt') `
    -SkipAdvisoryCheck:$SkipAdvisoryCheck

$toolingPath = [IO.Path]::ChangeExtension($Bundle, '.tooling.json')
$toolingDocument = Read-ReleaseTooling -Path $toolingPath
$toolingScopeText = @(Invoke-RepoGit -Root $Root -Arguments @('show', "${commit}:gradle/tooling-scopes.txt")) -join "`n"
if (-not $toolingScopeText) { throw 'The release commit has no tooling scope manifest.' }
$toolingBound = Test-ReleaseTooling -Tooling $toolingDocument -BundlePath $Bundle `
    -ExpectedCommit $commit -ScopeManifestText $toolingScopeText
if (-not $toolingBound.Valid) { throw "The tooling report does not describe the release: $($toolingBound.Reason)" }
$toolingAuditStatus = Invoke-ToolingAdvisoryGate -Tooling $toolingDocument `
    -ExceptionsPath (Join-Path $PSScriptRoot 'tooling-advisory-exceptions.txt') -SkipAdvisoryCheck:$SkipAdvisoryCheck

function Get-PatchVerdicts {
    <#
    .SYNOPSIS
        One entry per catalog patch, in catalog order, from the CLI's own report.
    .DESCRIPTION
        Every requested patch has to appear in exactly one of the report's two lists. A patch in
        neither was not decided, and recording it as applied would be the receipt inventing a
        verdict, which is the one thing it exists not to do.
    #>
    param([object]$Report, [string[]]$Names)

    $applied = [System.Collections.Generic.HashSet[string]]::new(
        [string[]]@(Get-ReportPatchNames -Entries $Report.appliedPatches),
        [System.StringComparer]::Ordinal)
    $failures = Get-ReportFailures -Entries $Report.failedPatches

    $verdicts = New-Object System.Collections.Generic.List[object]
    foreach ($name in $Names) {
        if ($applied.Contains($name)) {
            $verdicts.Add([ordered]@{ name = $name; applied = $true; reason = $null })
        } elseif ($failures.Contains($name)) {
            $verdicts.Add([ordered]@{ name = $name; applied = $false; reason = $failures[$name] })
        } else {
            throw "The result report decided nothing about patch $name."
        }
    }
    return ,$verdicts.ToArray()
}

# Read with the hash and size above, and for the same reason.
$extensionPayloads = Get-ExtensionPayloads -BundlePath $Bundle

New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null
$workRoot = (Resolve-Path -LiteralPath $WorkDir).Path
$targets = New-Object System.Collections.Generic.List[object]

# Every declared build needs its own run without -f, and the receipt check refuses a receipt
# without one. That check comes after the patch runs, and a Facebook patch run unpacks the whole
# bundle, while a base manifest is cheap to read. So the fixtures are held to the catalog here,
# before anything is patched: a run given only the newest build patched it and then refused the
# receipt it had produced.
$stockFacts = @{}
foreach ($apk in $Fixture) {
    if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) { throw "Fixture not found: $apk" }
    $label = Split-Path -Leaf $apk
    $readDir = Resolve-WithinRoot -Path (Join-Path $workRoot ("manifest-" + [guid]::NewGuid().ToString('N'))) -Root $workRoot
    try {
        # Facebook ships as a split bundle, which aapt2 can't read; its manifest is the base APK's.
        $stockApk = Get-BaseApk -Apk $apk -Destination (Join-Path $readDir 'stock-base.apk')
        $stock = Get-ApkManifestFacts -Apk $stockApk -Aapt2 $Aapt2
    } finally {
        Remove-GeneratedPath -Path $readDir -Root $workRoot
    }
    if ($stock.package -ne $expectedTarget.PackageName) {
        throw "$label is $($stock.package), not the catalog's target $($expectedTarget.PackageName)."
    }
    $stockFacts[$apk] = $stock
}
# A fixture is the run of a declared build only when it's that build, version code and all: another
# arm64 build of 580 was taken for the declared one by its name, patched without -f and recorded as
# proof of a build nobody ran.
$fixtureVersions = @($Fixture | Where-Object {
        Test-DeclaredBuild -Target $expectedTarget -VersionName ([string]$stockFacts[$_].versionName) `
            -VersionCode ([string]$stockFacts[$_].versionCode) } |
    ForEach-Object { [string]$stockFacts[$_].versionName })
$unfixed = @($expectedTarget.PackageVersions | Where-Object { $fixtureVersions -notcontains $_ })
if ($unfixed.Count -gt 0) {
    $given = @($Fixture | ForEach-Object { "$($stockFacts[$_].versionName) ($($stockFacts[$_].versionCode))" })
    throw ("No fixture is the declared $($expectedTarget.PackageName) $($unfixed -join ', '), and the " +
        "receipt needs a run of every declared build without -f. The catalog declares " +
        "$(Format-DeclaredBuilds -Target $expectedTarget), and the fixtures given are $($given -join ', '). " +
        'Nothing was patched.')
}

if ($AppliedDir) {
    $AppliedDir = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($AppliedDir)
    $patchListHash = Get-Sha256Hex -Path $PatchList
    $desktopJarHash = Get-Sha256Hex -Path $DesktopJar
}

function Get-KeptRun {
    <#
    .SYNOPSIS
        The run the pre-push gate kept for -Apk (verify-all-patches.ps1 -KeepIn), or $null.
    .DESCRIPTION
        It counts only when its stamp names the bundle, APK, patch list and CLI this receipt is cut
        from by SHA-256, the same version name, version code and forced flag, and a merge exactly
        when the fixture is a split bundle, and when every file the stamp stands for is there with
        the SHA-256 the stamp gives it, with fingerprint validation enabled (stamp schema 3).
        Anything less and the fixture is patched
        here, as it would be with no kept runs at all.

        The answer is a copy of the kept run in -Into, hashed there: a file hashed in the kept
        folder could be changed before the checks below read it, and the copy is this run's own.
        The copy's folder, and then each file in it before it's hashed, is held (Open-HeldFile) and
        added to -Held, which the caller closes once its checks are done: the folder can't be renamed
        or swapped for a link, a file can't be written, replaced or deleted, and each hold is proved
        to be the path's own file rather than one a link there led to. So the checks read what was
        hashed. A copy that can't be held that way, or that holds a link, isn't used.
    #>
    param([string]$Apk, [object]$Stock, [bool]$Forced, [string]$Into,
        [System.Collections.Generic.List[System.IDisposable]]$Held)

    if (-not $AppliedDir) { return $null }
    $label = Split-Path -Leaf $Apk
    $dir = Join-Path $AppliedDir $label
    $stampPath = Join-Path $dir 'stamp.json'
    if (-not (Test-Path -LiteralPath $stampPath -PathType Leaf)) {
        Write-Host "[receipt] the gate kept no run of $label"
        return $null
    }
    try { $stamp = Get-Content -LiteralPath $stampPath -Raw | ConvertFrom-Json }
    catch {
        Write-Host "[receipt] the gate's stamp for $label can't be read, so it's patched here"
        return $null
    }
    $splitBundle = [System.IO.Path]::GetExtension($Apk).TrimStart('.').ToLowerInvariant() -in @('apkm', 'apks', 'xapk')
    $files = @('patched.apk', 'result.json')
    if ($splitBundle) { $files += 'stock-merged.apk' }
    $missing = @($files | Where-Object { -not (Test-Path -LiteralPath (Join-Path $dir $_) -PathType Leaf) })
    $same = $missing.Count -eq 0 -and
        [string]$stamp.bundleSha256 -ceq $bundleHash -and [string]$stamp.patchListSha256 -ceq $patchListHash -and
        [string]$stamp.desktopJarSha256 -ceq $desktopJarHash -and
        [string]$stamp.versionName -ceq [string]$Stock.versionName -and [string]$stamp.versionCode -ceq [string]$Stock.versionCode -and
        $stamp.forced -is [bool] -and $stamp.forced -eq $Forced -and $stamp.merged -is [bool] -and $stamp.merged -eq $splitBundle -and
        [string]$stamp.apkSha256 -ceq (Get-Sha256Hex -Path $Apk)
    if (-not $same) {
        Write-Host "[receipt] the gate's run of $label was made with another bundle, APK, patch list or CLI, so it's patched here"
        return $null
    }
    # The checks below repeat only some of the gate's: its resource table and register checks ran on
    # the outputs it kept, and the stamp names those by hash. A stamp from before it did, or an
    # output changed since, can't vouch for them.
    if (($stamp.schema -isnot [int] -and $stamp.schema -isnot [long]) -or $stamp.schema -ne 3) {
        Write-Host "[receipt] the gate's run of $label has an older stamp that doesn't name its outputs, so it's patched here"
        return $null
    }
    if ($stamp.validated -isnot [bool] -or -not $stamp.validated) {
        Write-Host "[receipt] the gate's run of $label was made without fingerprint validation, so it's patched here"
        return $null
    }
    $hashes = @{ 'patched.apk' = 'patchedSha256'; 'result.json' = 'resultSha256'; 'stock-merged.apk' = 'mergedSha256' }
    New-Item -ItemType Directory -Force -Path $Into | Out-Null
    $mark = $Held.Count
    $refused = $null
    try { $Held.Add((Open-HeldFile -Path $Into -Directory)) }
    catch { $refused = "has a copy whose folder can't be held as itself" }
    if (-not $refused) {
        Get-ChildItem -LiteralPath $dir -File | Copy-Item -Destination $Into -Force
        # The caller takes a merge in the copy as what the CLI patched, so one the stamp doesn't
        # vouch for, beside a plain APK's run, goes.
        if (-not $splitBundle) { Remove-Item -LiteralPath (Join-Path $Into 'stock-merged.apk') -Force -ErrorAction SilentlyContinue }
        foreach ($file in $files) {
            $path = Join-Path $Into $file
            try { $Held.Add((Open-HeldFile -Path $path)) }
            catch { $refused = "has a copy whose $file can't be held as itself"; break }
            $want = [string]$stamp.($hashes[$file])
            if (-not $want -or $want -cne (Get-Sha256Hex -Path $path)) {
                $refused = "no longer holds the $file it checked"
                break
            }
        }
    }
    if (-not $refused) {
        foreach ($item in @(Get-ChildItem -LiteralPath $Into -Force)) {
            if ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) { $refused = "has a link in its copy, $($item.Name)"; break }
        }
    }
    if ($refused) {
        Write-Host "[receipt] the gate's run of $label $refused, so it's patched here"
        while ($Held.Count -gt $mark) { $Held[$Held.Count - 1].Dispose(); $Held.RemoveAt($Held.Count - 1) }
        Remove-TreeWithoutFollowingLinks -Path $Into
        return $null
    }
    return $Into
}

# Every fixture in one slot of the machine's build queue when there is one (Invoke-HeavyJob):
# merges and CLI runs that unpack gigabytes each, one release's worth, not interleaved with
# another build's. A throw here ends the job, gives the slot back and stops the receipt.
Invoke-HeavyJob -Label 'receipt' -ScriptBlock {
    foreach ($apk in $Fixture) {
        $label = Split-Path -Leaf $apk

        $runId = [guid]::NewGuid().ToString('N')
        $runDir = Resolve-WithinRoot -Path (Join-Path $workRoot "receipt-$runId") -Root $workRoot
        New-Item -ItemType Directory -Force -Path $runDir | Out-Null
        $held = [System.Collections.Generic.List[System.IDisposable]]::new()
        try {
            $stock = $stockFacts[$apk]

            $out = Resolve-WithinRoot -Path (Join-Path $runDir 'patched.apk') -Root $workRoot
            $temp = Resolve-WithinRoot -Path (Join-Path $runDir 'tmp') -Root $workRoot
            $resultPath = Resolve-WithinRoot -Path (Join-Path $runDir 'result.json') -Root $workRoot

            # A fixture of a build the bundle does not declare, by version name or by version code, is
            # patched under -f, and the receipt says so rather than letting a forced run read like a
            # declared-compatible one.
            $forced = -not (Test-DeclaredBuild -Target $expectedTarget -VersionName ([string]$stock.versionName) `
                -VersionCode ([string]$stock.versionCode))

            # The gate's run of this very fixture with this very bundle, when it kept one: its
            # report, patched APK and merge go through every check below, and only the patching is
            # skipped. The kept folder is read, never written, and what's read is this run's copy.
            $keptRun = Get-KeptRun -Apk $apk -Stock $stock -Forced $forced `
                -Into (Resolve-WithinRoot -Path (Join-Path $runDir 'kept') -Root $workRoot) -Held $held
            if ($keptRun) {
                Write-Host "[receipt] reading the gate's run of ${label}: same bundle, APK, patch list and CLI"
                $out = Join-Path $keptRun 'patched.apk'
                $resultPath = Join-Path $keptRun 'result.json'
                $patchInput = if (Test-Path -LiteralPath (Join-Path $keptRun 'stock-merged.apk') -PathType Leaf) {
                    Join-Path $keptRun 'stock-merged.apk' } else { $apk }
                $cliExitCode = 0
            } else {
                Write-Host "[receipt] patching $label with $($patchNames.Count) patches"
                # The one APK the CLI patches: the fixture's merge when it's a split bundle, made here
                # because the CLI deletes its own, or the fixture itself. No merge, no receipt.
                $mergedApk = Resolve-WithinRoot -Path (Join-Path $runDir 'stock-merged.apk') -Root $workRoot
                $patchInput = Get-MergedApk -Apk $apk -Destination $mergedApk -Java $Java -DesktopJar $DesktopJar

                $enable = @()
                foreach ($name in $patchNames) { $enable += '-e'; $enable += $name }
                $arguments = @('patch', '--validate', '--exclusive', '--continue-on-error', '--unsigned', '-p', $Bundle,
                    '-o', $out, '-t', $temp, '-r', $resultPath)
                if ($forced) { $arguments += '-f' }
                $arguments = $arguments + $enable + @($patchInput)
                # Continue for the call alone: the CLI logs WARNING and SEVERE on stderr, which Windows
                # PowerShell 5.1 turns into a terminating error under Stop. The report and the exit code
                # are what decide.
                $preference = $ErrorActionPreference
                try {
                    $ErrorActionPreference = 'Continue'
                    $global:LASTEXITCODE = -1
                    & $Java '-jar' $DesktopJar @arguments 2>&1 | Out-Null
                    $cliExitCode = $LASTEXITCODE
                } finally {
                    $ErrorActionPreference = $preference
                }
            }

            if (-not (Test-Path -LiteralPath $resultPath -PathType Leaf)) {
                throw "The desktop CLI wrote no result report for $label (exit $cliExitCode)."
            }
            $report = Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
            $validation = Test-PatchingReport -Report $report -ExpectedNames $patchNames `
                -AllowedDependencyNames $dependencyNames -OutputPath $out `
                -ExpectedPackageName $expectedTarget.PackageName `
                -ExpectedPackageVersion $stock.versionName
            if (-not $validation.Valid) { throw "$label did not patch cleanly: $($validation.Reason)" }
            if ($cliExitCode -ne 0) { throw "The desktop CLI exited with $cliExitCode on $label." }

            # A phone with 16 KB pages maps a stored native library straight out of the APK only
            # from a 16 KB boundary, and Android 17's linker stops there instead of falling back.
            # The patcher aligns them on repack (1.15.1); this checks it did, every stored library
            # and every other stored entry's 4 bytes. A release doesn't go out on a miss.
            $zipalign = Join-Path (Split-Path -Parent $Aapt2) ('zipalign' + [IO.Path]::GetExtension($Aapt2))
            if (-not (Test-Path -LiteralPath $zipalign -PathType Leaf)) {
                throw "No zipalign beside $Aapt2 for the 16 KB alignment check."
            }
            $preference = $ErrorActionPreference
            try {
                $ErrorActionPreference = 'Continue'
                $global:LASTEXITCODE = -1
                $alignment = @(& $zipalign '-c' '-P' '16' '-v' '4' $out 2>&1 | ForEach-Object { "$_" })
                $alignExitCode = $LASTEXITCODE
            } finally {
                $ErrorActionPreference = $preference
            }
            if ($alignExitCode -ne 0) {
                $misses = @($alignment | Where-Object { $_ -match '\(BAD' } | Select-Object -First 5)
                if ($misses.Count -eq 0) {
                    # No entry was named, so zipalign itself refused the run. One older than build-tools 35
                    # doesn't know -P, and its own words are the only clue.
                    throw ("zipalign exited $alignExitCode on $label without naming an entry, so the 16 KB check " +
                        "didn't run. It said: " + (@($alignment | Select-Object -Last 5) -join '; '))
                }
                throw "$label is not 16 KB aligned (zipalign exit $alignExitCode): $($misses -join '; ')"
            }

            # zipalign answered for where a stored library sits in the ZIP, and for nothing else.
            # What each library's own ELF says is read apart from that and written down, so a ZIP
            # that lines up is never read as an app ready for 16 KB pages. A miss here doesn't stop
            # the receipt: these are Facebook's libraries, which patching doesn't rebuild, and
            # refusing them would refuse every release while the app still runs on 4 KB pages.
            $native = Get-NativeLibraryAlignment -Apk $out
            $native.zipOffsets.aligned = $true
            Write-Host ("[receipt] $label`: $($native.zipOffsets.stored) stored and $($native.zipOffsets.compressed) " +
                "compressed native libraries, every stored one at a 16 KB offset")
            if ($null -eq $native.staticChecksPass) {
                Write-Host "[receipt] $label`: no 64-bit library to hold to 16 KB pages"
            } else {
                Write-Host ("[receipt] $label`: $($native.loadSegments.libraries) 64-bit ELF libraries, " +
                    "$($native.loadSegments.misaligned.Count) with a LOAD segment off 16 KB, " +
                    "$($native.relroEnds.misaligned.Count) of $($native.relroEnds.present) RELRO ends off 16 KB, " +
                    "$($native.malformed.Count) unreadable")
                if (-not $native.staticChecksPass) {
                    Write-Host "[receipt] $label`: not ready for 16 KB pages as built, and the receipt names each library"
                }
            }

            $patched = Get-ApkManifestFacts -Apk $out -Aapt2 $Aapt2
            # The manifest the patches started from is the APK the CLI patched, the merge for a split
            # bundle, not the base APK's: the merge rewrites the manifest itself, and a delta against
            # the base would record its changes as the patches' own.
            $baseline = Get-ApkManifestFacts -Apk $patchInput -Aapt2 $Aapt2
            $delta = Get-ManifestDelta -Stock $baseline -Patched $patched
            $verdicts = Get-PatchVerdicts -Report $report -Names $patchNames
            $changes = @(ConvertTo-ManifestDeltaEntries -Delta $delta)
            Write-Host ("[receipt] $label" + ": $(@($verdicts | Where-Object { $_.applied }).Count)/" +
                "$($patchNames.Count) applied, $($changes.Count) manifest changes")
            foreach ($change in $changes) { Write-Host "[receipt]   $change" }

            $targets.Add([ordered]@{
                source        = [ordered]@{
                    file        = $label
                    package     = $stock.package
                    versionName = $stock.versionName
                    versionCode = $stock.versionCode
                    sha256      = Get-Sha256Hex -Path $apk
                    forced      = $forced
                }
                patches       = $verdicts
                nativeAlignment = $native
                manifestDelta = [ordered]@{
                    permissionsAdded          = @($delta.permissionsAdded)
                    permissionsRemoved        = @($delta.permissionsRemoved)
                    exportedComponentsAdded   = @($delta.exportedComponentsAdded)
                    exportedComponentsRemoved = @($delta.exportedComponentsRemoved)
                }
            })
        } finally {
            foreach ($handle in $held) { $handle.Dispose() }
            # Links go as links: Windows PowerShell's recursive removal follows a junction.
            if ($runDir.StartsWith($workRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
                Remove-TreeWithoutFollowingLinks -Path $runDir
            }
        }
    }
}

$receipt = [ordered]@{
    schemaVersion = Get-ReleaseReceiptSchemaVersion
    generatedAt   = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    release       = [ordered]@{
        version         = $releaseVersion
        tag             = "v$releaseVersion"
        commit          = $commit
        commitTimestamp = $commitTimestamp
        patchCount      = $patchNames.Count
    }
    bundle        = [ordered]@{
        file      = Split-Path -Leaf $Bundle
        sizeBytes = $bundleSize
        sha256    = $bundleHash
        timestamp = $bundleManifest.timestamp
    }
    sbom          = [ordered]@{
        file       = Split-Path -Leaf $Sbom
        sha256     = $sbomDocument.Sha256
        components = @($sbomDocument.Components).Count
    }
    tooling       = [ordered]@{
        file       = Split-Path -Leaf $toolingPath
        sha256     = $toolingDocument.Sha256
        components = @($toolingDocument.Components).Count
        scopes     = @($toolingDocument.Scopes).Count
        audit      = [ordered]@{ status = $toolingAuditStatus }
    }
    toolchain     = [ordered]@{
        patcherVersion = $patcherMatch.Groups[1].Value
        managerFloor   = $floorMatch.Groups[1].Value
    }
    extension     = [ordered]@{ dexPayloads = $extensionPayloads }
    targets       = $targets.ToArray()
}

# The receipt is checked before it is written. A file that fails the gate it exists to pass is
# worse than no file, because the next reader has to work out which half to believe.
$approved = Read-ManifestDeltaAllowlist -Path (Join-Path $PSScriptRoot 'manifest-delta-allowlist.txt')
$check = Test-ReleaseReceipt -Receipt ($receipt | ConvertTo-Json -Depth 12 | ConvertFrom-Json) `
    -ExpectedVersion $releaseVersion -ExpectedPatchNames $patchNames `
    -ExpectedPatcherVersion $patcherMatch.Groups[1].Value `
    -ExpectedManagerFloor $floorMatch.Groups[1].Value `
    -ExpectedPackageName $expectedTarget.PackageName `
    -ExpectedPackageVersions $expectedTarget.PackageVersions -ExpectedPackageVersionCodes $expectedTarget.PackageVersionCodes `
    -BundlePath $Bundle `
    -ApprovedManifestDelta $approved -SbomPath $Sbom -ToolingPath $toolingPath -ToolingScopeManifestText $toolingScopeText
if (-not $check.Valid) { throw "The receipt this run produced does not pass validation: $($check.Reason)" }

$receipt | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $OutputPath -Encoding UTF8
Write-Host "[receipt] wrote $OutputPath"
exit 0
