<#
.SYNOPSIS
    Exercise the release and gate scripts: patch target and report, receipts, release facts,
    pre-push routing, the changelog, d8 and Java resolution, split bundles and the shared helpers.
.DESCRIPTION
    Ported from Hushfeed's suite on 2026-09-25 and held to Facebook's facts: one declared build,
    both of Meta's signers, and .apkm split bundles. The pre-push hook runs it for changes under
    scripts/ or assets/, and for README.md, patches-list.json or patches/build.gradle.kts. A
    missing suite stops the push.
#>
[CmdletBinding()]
param([string]$Root)

$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
. (Join-Path $PSScriptRoot 'patch-target.ps1')
. (Join-Path $PSScriptRoot 'patch-report.ps1')
. (Join-Path $PSScriptRoot 'device-install.ps1')
. (Join-Path $PSScriptRoot 'Resolve-Java.ps1')
. (Join-Path $PSScriptRoot 'common.ps1')
. (Join-Path $PSScriptRoot 'script-wiring.ps1')

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Assert-Throws {
    param([scriptblock]$Action, [string]$Pattern, [string]$Message)
    try {
        & $Action
    } catch {
        if ($_.Exception.Message -like $Pattern) { return }
        throw "$Message Unexpected error: $($_.Exception.Message)"
    }
    throw "$Message No error was raised."
}

function New-DesktopJarStandIn {
    # A desktop CLI jar holding only what Get-DesktopPatcherVersion reads: the patcher's
    # version.properties, left out when -Patcher is empty, or -Properties as that file's text.
    # Its manifest names no Patcher-Version, so it also stands in for a bundle that names none.
    param([string]$Path, [string]$Patcher, [string]$Properties)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    if (Test-Path -LiteralPath $Path) { Remove-Item -LiteralPath $Path -Force }
    $archive = [System.IO.Compression.ZipFile]::Open($Path, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        $entries = [ordered]@{ 'META-INF/MANIFEST.MF' = "Manifest-Version: 1.0`nImplementation-Title: morphe-desktop`n" }
        if ($Properties) { $entries['app/morphe/patcher/version.properties'] = $Properties }
        elseif ($Patcher) { $entries['app/morphe/patcher/version.properties'] = "version=$Patcher`n" }
        foreach ($name in $entries.Keys) {
            $writer = New-Object System.IO.StreamWriter($archive.CreateEntry($name).Open())
            try { $writer.Write($entries[$name]) } finally { $writer.Dispose() }
        }
    } finally { $archive.Dispose() }
}

function New-NotFoundAnswer {
    # What Invoke-WebRequest raises for a 404 in both shells, as far as Assert-UrlReachable reads
    # it: an error whose Response carries the status. A function named Invoke-WebRequest that
    # throws this stands in for the request, so no case here needs the network.
    $answer = New-Object System.Exception 'Response status code does not indicate success: 404 (Not Found).'
    Add-Member -InputObject $answer -NotePropertyName Response -NotePropertyValue ([pscustomobject]@{ StatusCode = 404 })
    return $answer
}

# The machine's build queue, which the heavy jobs wait for (Invoke-HeavyJob in common.ps1), is a
# stand-in for the whole suite: it runs each job at once and notes its label, with a mark when
# the job came from inside a slot already, so no case waits in the real queue or holds one of its
# slots, and the cases can read which jobs went through it. The variable set, the pre-push runs
# below keep it; unset, the hook would import the real one from the registry. The stand-in takes
# the real script's parameters too, which a dot-source binds in the caller's scope.
$queueBefore = @{ Script = $env:BUILD_QUEUE_SCRIPT; Ticket = $env:BUILD_QUEUE_TICKET }
$queueStandIn = [System.IO.Path]::GetFullPath((Join-Path ([System.IO.Path]::GetTempPath()) `
    ('hushfacebook-queue-' + [guid]::NewGuid().ToString('N'))))
New-Item -ItemType Directory -Path $queueStandIn | Out-Null
$queueLog = Join-Path $queueStandIn 'queue.log'
$env:BUILD_QUEUE_SCRIPT = Join-Path $queueStandIn 'build-queue.ps1'
Remove-Item -LiteralPath Env:\BUILD_QUEUE_TICKET -ErrorAction SilentlyContinue
[System.IO.File]::WriteAllText($env:BUILD_QUEUE_SCRIPT, @'
[CmdletBinding()]
param([switch]$Status, [string]$Label, [ValidateSet('release', 'normal')][string]$Priority, [string]$Run)
function Invoke-InBuildQueue {
    param([string]$Label = 'build', [string]$Priority, [Parameter(Mandatory)][scriptblock]$ScriptBlock)
    $nested = [bool]$env:BUILD_QUEUE_TICKET
    $note = if ($nested) { "$Label (inside a slot)" } else { $Label }
    [System.IO.File]::AppendAllText('QUEUE_LOG', $note + "`n")
    if (-not $nested) { $env:BUILD_QUEUE_TICKET = 'stand-in' }
    $global:LASTEXITCODE = 0
    try {
        & $ScriptBlock | Out-Host
        return $LASTEXITCODE
    } finally {
        if (-not $nested) { Remove-Item -LiteralPath Env:\BUILD_QUEUE_TICKET -ErrorAction SilentlyContinue }
    }
}
'@.Replace('QUEUE_LOG', $queueLog.Replace("'", "''")))
function Read-QueueLog {
    # The labels the stand-in queue saw since the last read, which empties it. Call it in @().
    if (-not (Test-Path -LiteralPath $queueLog -PathType Leaf)) { return }
    [System.IO.File]::ReadAllLines($queueLog) | Where-Object { $_ }
    Remove-Item -LiteralPath $queueLog -Force
}

# A combined patch log can fail in another source even when Hushfacebook supports the APK.
# Reports must retain both the source versions and the selection belonging to each source.
$bugReportForm = Get-Content -LiteralPath (Join-Path $Root '.github/ISSUE_TEMPLATE/bug_report.yml') -Raw
foreach ($field in @('patch_sources', 'selected_patches')) {
    $fieldBlock = [regex]::Match($bugReportForm,
        ('(?ms)^  - type: textarea\r?\n    id: ' + $field + '\r?\n.*?(?=^  - type:|\z)')).Value
    Assert-True ($fieldBlock -match '(?m)^      required: true\s*$') `
        "The bug report must require $field so a mixed-source failure can be traced."
    if ($field -eq 'patch_sources') {
        Assert-True ($fieldBlock -match '(?s)Source:.*?version') `
            'The source field must ask for the patch log Source lines and their versions.'
    } else {
        Assert-True ($fieldBlock -match 'each source') `
            'The patch selection must be reported separately for each source.'
    }
}

# --- patch-target.ps1 ------------------------------------------------------------------------
#
# Facebook ships a build a week, and the catalog declares only the newest stable one, the build
# the bundle was last proved on: a newer stable build replaces it in the same release. Several
# declared builds still come back newest first, by number and not by text (the cases below), and
# every patch has to declare the same builds: a build only some patches declare is one the bundle
# can't fully patch, and the release scripts would take it for a declared target.
$catalog = Get-Content -LiteralPath (Join-Path $Root 'patches-list.json') -Raw | ConvertFrom-Json
$target = Get-PatchTarget -PatchList $catalog
Assert-True ($target.PackageName -eq 'com.facebook.katana') 'The catalog package was not resolved.'
Assert-True (@($target.PackageVersions).Count -eq 1 -and $target.PackageVersion -eq $target.PackageVersions[0]) `
    "The catalog's declared Facebook builds were not read: $($target.PackageVersions -join ', ')"
foreach ($patch in @($catalog.patches)) {
    Assert-True (((@($patch.compatiblePackages.'com.facebook.katana') | Sort-Object) -join ',') -eq
        ((@($target.PackageVersions) | Sort-Object) -join ',')) `
        "$($patch.name) declares other Facebook builds than the rest of the catalog."
}
# And the version code each build is pinned to. APKMirror lists several arm64 builds of one Facebook
# version, each with its own dex, so a name alone doesn't say which of them the patches were proved
# on. A receipt once counted another arm64 build of a declared version (vc 475019283 beside the
# declared 475019344, both Facebook 580) as an unforced run of the declared one.
foreach ($version in @($target.PackageVersions)) {
    $pinned = @($catalog.patches[0].compatibility | Where-Object { $_.packageName -eq 'com.facebook.katana' } |
        ForEach-Object { @($_.targets) } | Where-Object { $_.version -eq $version } |
        ForEach-Object { $_.versionCodes.PSObject.Properties } | ForEach-Object { [string]$_.Value })
    Assert-True ($pinned.Count -gt 0 -and (@($target.PackageVersionCodes[$version]) -join ',') -eq (($pinned | Sort-Object -Unique) -join ',')) `
        "The version codes the catalog pins to Facebook $version were not read: $(@($target.PackageVersionCodes[$version]) -join ', ')"
    Assert-True (Test-DeclaredBuild -Target $target -VersionName $version -VersionCode $pinned[0]) `
        "Facebook $version at its pinned code $($pinned[0]) was not taken for a declared build."
    Assert-True (-not (Test-DeclaredBuild -Target $target -VersionName $version -VersionCode "$([long]$pinned[0] - 61)")) `
        "Another build of Facebook $version, version code $([long]$pinned[0] - 61), was taken for the declared one."
}
Assert-True (-not (Test-DeclaredBuild -Target $target -VersionName '1.0.0' -VersionCode '1')) 'An undeclared version was taken for a declared build.'

$threeBuilds = [pscustomobject]@{
    patches = @(
        [pscustomobject]@{ name = 'one'
            compatiblePackages = [pscustomobject]@{ 'com.example.app' = @('577.0.0.50.72', '99.1.0.0.1', '580.0.0.51.74') } },
        [pscustomobject]@{ name = 'two'
            compatiblePackages = [pscustomobject]@{ 'com.example.app' = @('580.0.0.51.74', '99.1.0.0.1', '577.0.0.50.72') } })
}
$three = Get-PatchTarget -PatchList $threeBuilds
Assert-True (($three.PackageVersions -join ',') -eq '580.0.0.51.74,577.0.0.50.72,99.1.0.0.1' -and
    $three.PackageVersion -eq '580.0.0.51.74') `
    "Declared builds were not ordered newest first by number: $($three.PackageVersions -join ', ')"

# Every part of the version, as a number. Facebook's have five and [version] takes four, so the fifth
# was dropped and builds apart only there sorted as equals, the older one first in both shells.
$fifthPart = [pscustomobject]@{
    patches = @([pscustomobject]@{ name = 'hotfixes'
        compatiblePackages = [pscustomobject]@{ 'com.example.app' = @('580.0.0.51.8', '580.0.0.51.10', '580.0.0.51', '580.0.0.51.9') } })
}
$fifth = Get-PatchTarget -PatchList $fifthPart
Assert-True (($fifth.PackageVersions -join ',') -eq '580.0.0.51.10,580.0.0.51.9,580.0.0.51.8,580.0.0.51' -and
    $fifth.PackageVersion -eq '580.0.0.51.10') `
    "Builds apart only in their fifth part were not ordered newest first: $($fifth.PackageVersions -join ', ')"
$notNumbers = [pscustomobject]@{
    patches = @([pscustomobject]@{ name = 'lettered'
        compatiblePackages = [pscustomobject]@{ 'com.example.app' = @('580.0.0.51.74', '580.0.0.51.x') } })
}
Assert-Throws { Get-PatchTarget -PatchList $notNumbers } "*580.0.0.51.x, which isn't a version of dotted numbers*" `
    'A declared version that is not dotted numbers was sorted instead of refused.'

$uneven = [pscustomobject]@{
    patches = @(
        [pscustomobject]@{ name = 'both builds'
            compatiblePackages = [pscustomobject]@{ 'com.example.app' = @('580.0.0.51.74', '577.0.0.50.72') } },
        [pscustomobject]@{ name = 'newest only'
            compatiblePackages = [pscustomobject]@{ 'com.example.app' = @('580.0.0.51.74') } })
}
Assert-Throws { Get-PatchTarget -PatchList $uneven } '*newest only*' `
    'A build only some patches declare was accepted as a target of the whole bundle.'
# The same for the codes: two patches pinning one version to different builds don't declare one.
function New-PinnedPatch([string]$Name, [int]$Code) {
    [pscustomobject]@{ name = $Name
        compatiblePackages = [pscustomobject]@{ 'com.example.app' = @('580.0.0.51.74') }
        compatibility = @([pscustomobject]@{ packageName = 'com.example.app'
            targets = @([pscustomobject]@{ version = '580.0.0.51.74'; versionCodes = [pscustomobject]@{ ARM64_V8A = $Code } }) }) }
}
Assert-Throws { Get-PatchTarget -PatchList ([pscustomobject]@{ patches = @((New-PinnedPatch 'pinned' 475019344),
            (New-PinnedPatch 'another build' 475019283)) }) } '*another build declares 580.0.0.51.74 (475019283)*' `
    'Two patches pinning one version to different builds were read as one declared build.'
# A catalog that pins no code is read by the version name, as before.
Assert-True ((Test-DeclaredBuild -Target $three -VersionName '99.1.0.0.1' -VersionCode '12345') -and
    @($three.PackageVersionCodes['99.1.0.0.1']).Count -eq 0) 'A build declared without a version code was not matched by its name.'

# Both of Meta's signers, on every patch. Facebook rotated its key with a v3.1 lineage, so a
# phone on Android 13 or newer reports the new signer and an older one the old signer, and Morphe
# Manager refuses an APK whose signer the patch doesn't list.
$metaSigners = @('911d604446084ca7f4760b775bfc160fa8702441240a7258645d7a72c4312d27',
    'e3f9e1e0cf99d0e56a055ba65e241b3399f7cea524326b0cdd6ec1327ed0fdc1')
foreach ($patch in @($catalog.patches)) {
    $declaredSigners = @($patch.compatibility | Where-Object { $_.packageName -eq 'com.facebook.katana' } |
        ForEach-Object { @($_.signatures) } | Sort-Object -Unique)
    Assert-True (($declaredSigners -join ',') -eq ($metaSigners -join ',')) `
        "$($patch.name) declares Facebook signers $($declaredSigners -join ', '), not both of Meta's."
}

$allNames = @($catalog.patches | ForEach-Object { $_.name })
$allDependencies = @(Get-PatchDependencyNames -PatchList $catalog -RequestedNames $allNames | Sort-Object)
Assert-True (($allDependencies -join ',') -eq 'BytecodePatch,ResourcePatch') `
    "The real catalog dependency closure is not the two internal patch types: $($allDependencies -join ', ')"
Assert-True (Test-ReportedPatchNames -Expected $allNames `
    -Actual @($allNames + $allDependencies) -AllowedDependencies $allDependencies) `
    'A result that included the real internal dependency was rejected.'

$dependencyCatalog = [pscustomobject]@{
    patches = @(
        [pscustomobject]@{ name = 'Root'; dependencies = @('Helper') },
        [pscustomobject]@{ name = 'Helper'; dependencies = @('Internal') },
        [pscustomobject]@{ name = 'Unrelated'; dependencies = @() }
    )
}
$dependencies = @(Get-PatchDependencyNames -PatchList $dependencyCatalog -RequestedNames @('Root'))
Assert-True ($dependencies.Count -eq 2 -and $dependencies -contains 'Helper' -and
    $dependencies -contains 'Internal') 'The transitive dependency closure was incomplete.'
Assert-True (Test-ReportedPatchNames -Expected @('Root') -Actual @('Root') `
    -AllowedDependencies $dependencies) 'A report that omitted optional dependency rows was rejected.'
Assert-True (Test-ReportedPatchNames -Expected @('Root') -Actual @('Internal', 'Root', 'Helper') `
    -AllowedDependencies $dependencies) 'Declared dependency rows were rejected or order mattered.'
Assert-True (-not (Test-ReportedPatchNames -Expected @('Root') -Actual @('Helper', 'Internal') `
    -AllowedDependencies $dependencies)) 'A report missing its requested root was accepted.'
Assert-True (-not (Test-ReportedPatchNames -Expected @('Root') -Actual @('Root', 'Unrelated') `
    -AllowedDependencies $dependencies)) 'An unrelated extra patch was accepted as a dependency.'
Assert-True (-not (Test-ReportedPatchNames -Expected @('Root') -Actual @('Root', 'Root') `
    -AllowedDependencies $dependencies)) 'A duplicated requested patch was accepted.'
Assert-True (-not (Test-ReportedPatchNames -Expected @('Root') -Actual @('Root', 'Helper', 'Helper') `
    -AllowedDependencies $dependencies)) 'A duplicated dependency patch was accepted.'

$futureCatalog = [pscustomobject]@{
    patches = @([pscustomobject]@{
        name = 'future target'
        compatiblePackages = [pscustomobject]@{ 'com.example.future' = @('99.4.2') }
    })
}
$futureTarget = Get-PatchTarget -PatchList $futureCatalog
Assert-True ($futureTarget.PackageName -eq 'com.example.future' -and
    $futureTarget.PackageVersion -eq '99.4.2') 'A synthetic future target was replaced by a pinned value.'

$twoPackages = [pscustomobject]@{
    patches = @(
        [pscustomobject]@{ name = 'one app'; compatiblePackages = [pscustomobject]@{ 'com.example.app' = @('1.0.0') } },
        [pscustomobject]@{ name = 'another app'; compatiblePackages = [pscustomobject]@{ 'com.example.other' = @('1.0.0') } })
}
Assert-Throws { Get-PatchTarget -PatchList $twoPackages } '*Expected one compatible package*' `
    'A catalog naming two apps was accepted.'
$missingTarget = [pscustomobject]@{ patches = @([pscustomobject]@{ name = 'missing target' }) }
Assert-Throws { Get-PatchTarget -PatchList $missingTarget } '*has no compatible package*' `
    'A patch without compatibility metadata was accepted.'

$tempBase = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$caseRoot = [System.IO.Path]::GetFullPath((Join-Path $tempBase ("hushfacebook-script-test-" + [guid]::NewGuid().ToString('N'))))
$requiredPrefix = $tempBase.TrimEnd('\') + '\'
if (-not $caseRoot.StartsWith($requiredPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to create test files outside the temporary directory: $caseRoot"
}
try {
    New-Item -ItemType Directory -Path $caseRoot | Out-Null
    $reportApk = Join-Path $caseRoot 'report.apk'
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::Open(
        $reportApk, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($entryName in @('AndroidManifest.xml', 'classes.dex')) {
            $stream = $archive.CreateEntry($entryName).Open()
            try { $stream.WriteByte(0) } finally { $stream.Dispose() }
        }
    } finally {
        $archive.Dispose()
    }
    $dependencyReport = [pscustomobject]@{
        success = $true
        stepResults = @(
            [pscustomobject]@{ step = 'PATCHING'; success = $true },
            [pscustomobject]@{ step = 'REBUILDING'; success = $true })
        appliedPatches = @($allNames + $allDependencies | ForEach-Object {
            [pscustomobject]@{ name = $_ }
        })
        failedPatches = @()
        packageName = $target.PackageName
        packageVersion = $target.PackageVersion
    }
    $reportValidation = Test-PatchingReport -Report $dependencyReport -ExpectedNames $allNames `
        -AllowedDependencyNames $allDependencies -OutputPath $reportApk `
        -ExpectedPackageName $target.PackageName -ExpectedPackageVersion $target.PackageVersion
    Assert-True $reportValidation.Valid `
        "A complete result with a declared dependency was rejected: $($reportValidation.Reason)"
    # The 1.18 CLI's step list name still reads, and a result with no step list is invalid, not an error.
    $olderReport = $dependencyReport | Select-Object * -ExcludeProperty success, stepResults
    $olderReport | Add-Member -NotePropertyName patchingSteps -NotePropertyValue @([pscustomobject]@{ success = $true })
    $olderValidation = Test-PatchingReport -Report $olderReport -ExpectedNames $allNames `
        -AllowedDependencyNames $allDependencies -OutputPath $reportApk `
        -ExpectedPackageName $target.PackageName -ExpectedPackageVersion $target.PackageVersion
    Assert-True $olderValidation.Valid "A 1.18 CLI result was rejected: $($olderValidation.Reason)"
    $stepless = $dependencyReport | Select-Object * -ExcludeProperty stepResults
    $steplessValidation = Test-PatchingReport -Report $stepless -ExpectedNames $allNames `
        -AllowedDependencyNames $allDependencies -OutputPath $reportApk `
        -ExpectedPackageName $target.PackageName -ExpectedPackageVersion $target.PackageVersion
    Assert-True (-not $steplessValidation.Valid -and $steplessValidation.Reason -match 'step') `
        "A result with no step list passed: $($steplessValidation.Reason)"
    $failedStep = $dependencyReport | Select-Object *
    $failedStep.stepResults = @([pscustomobject]@{ step = 'PATCHING'; success = $true },
        [pscustomobject]@{ step = 'REBUILDING'; success = $false })
    $failedStepValidation = Test-PatchingReport -Report $failedStep -ExpectedNames $allNames `
        -AllowedDependencyNames $allDependencies -OutputPath $reportApk `
        -ExpectedPackageName $target.PackageName -ExpectedPackageVersion $target.PackageVersion
    Assert-True (-not $failedStepValidation.Valid) 'A result whose rebuild step failed passed.'

    $fakeAdb = Join-Path $caseRoot 'adb.cmd'
    $log = Join-Path $caseRoot 'adb.log'
    $mode = Join-Path $caseRoot 'mode.txt'
    $fakeBody = @'
@echo off
set /p FAKE_ADB_MODE=<"%~dp0mode.txt"
echo mode=%FAKE_ADB_MODE% args=%*>>"%~dp0adb.log"
if "%3|%4|%5"=="shell|pm|path" goto package_path
if "%3"=="uninstall" goto uninstall
exit /b 0
:package_path
if "%FAKE_ADB_MODE%"=="check-fail" goto check_fail
if "%FAKE_ADB_MODE%"=="offline" goto offline
rem pm path answers a package that isn't installed with exit 1 and nothing printed (Android 16).
if "%FAKE_ADB_MODE%"=="absent" exit /b 1
if "%FAKE_ADB_MODE%"=="present" echo package:/data/app/example/base.apk
if "%FAKE_ADB_MODE%"=="uninstall-fail" echo package:/data/app/example/base.apk
exit /b 0
:uninstall
if "%FAKE_ADB_MODE%"=="uninstall-fail" goto uninstall_fail
echo Success
exit /b 0
:check_fail
exit /b 17
:offline
echo adb.exe: device 'BROKEN' not found 1>&2
exit /b 1
:uninstall_fail
exit /b 19
'@
    [System.IO.File]::WriteAllText($fakeAdb, $fakeBody, [System.Text.Encoding]::ASCII)

    [System.IO.File]::WriteAllText($mode, 'absent', [System.Text.Encoding]::ASCII)
    $removed = Remove-AndroidPackageIfInstalled -Adb $fakeAdb -Serial 'CLEAN' -PackageName 'com.example.app'
    $calls = @(Get-Content -LiteralPath $log)
    Assert-True (-not $removed) 'An absent package was reported as removed.'
    Assert-True ($calls.Count -eq 1 -and $calls[0] -like '*shell pm path com.example.app') `
        'The absent-package path attempted an uninstall.'

    Remove-Item -LiteralPath $log -Force
    [System.IO.File]::WriteAllText($mode, 'present', [System.Text.Encoding]::ASCII)
    $removed = Remove-AndroidPackageIfInstalled -Adb $fakeAdb -Serial 'READY' -PackageName 'com.example.app'
    $calls = @(Get-Content -LiteralPath $log)
    Assert-True $removed 'An installed package was not removed.'
    Assert-True ($calls.Count -eq 2 -and $calls[0] -like '*shell pm path com.example.app' -and
        $calls[1] -like '*uninstall com.example.app') 'The installed-package path did not check then uninstall.'

    Remove-Item -LiteralPath $log -Force
    [System.IO.File]::WriteAllText($mode, 'check-fail', [System.Text.Encoding]::ASCII)
    Assert-Throws {
        Remove-AndroidPackageIfInstalled -Adb $fakeAdb -Serial 'BROKEN' -PackageName 'com.example.app'
    } '*could not check*' 'An ADB transport failure was treated as an absent package.'
    $calls = @(Get-Content -LiteralPath $log)
    Assert-True ($calls.Count -eq 1) 'The check-failure path continued after ADB failed.'

    # adb's own failure also exits 1, but says why, so it isn't read as pm's "not installed".
    Remove-Item -LiteralPath $log -Force
    [System.IO.File]::WriteAllText($mode, 'offline', [System.Text.Encoding]::ASCII)
    Assert-Throws {
        Remove-AndroidPackageIfInstalled -Adb $fakeAdb -Serial 'BROKEN' -PackageName 'com.example.app'
    } "*could not check*device 'BROKEN' not found*" 'adb failing with exit 1 was treated as an absent package.'
    [System.IO.File]::WriteAllText($mode, 'absent', [System.Text.Encoding]::ASCII)
    Assert-True (@(Get-AndroidPackagePaths -Adb $fakeAdb -Serial 'CLEAN' -PackageName 'com.example.app').Count -eq 0) `
        'pm path exiting 1 with nothing printed was not read as not installed.'
    [System.IO.File]::WriteAllText($mode, 'present', [System.Text.Encoding]::ASCII)
    Assert-True ((@(Get-AndroidPackagePaths -Adb $fakeAdb -Serial 'READY' -PackageName 'com.example.app') -join ',') -eq
        'package:/data/app/example/base.apk') 'An installed package gave no path.'

    Remove-Item -LiteralPath $log -Force
    [System.IO.File]::WriteAllText($mode, 'uninstall-fail', [System.Text.Encoding]::ASCII)
    Assert-Throws {
        Remove-AndroidPackageIfInstalled -Adb $fakeAdb -Serial 'LOCKED' -PackageName 'com.example.app'
    } '*uninstall failed*' 'An uninstall failure was accepted.'
    $calls = @(Get-Content -LiteralPath $log)
    Assert-True ($calls.Count -eq 2) 'The uninstall-failure path did not perform exactly a check and uninstall.'

    $emptyJdk = Join-Path $caseRoot 'empty-jdk'
    New-Item -ItemType Directory -Path $emptyJdk | Out-Null
    Assert-Throws {
        Resolve-Java -Explicit $emptyJdk
    } "*$emptyJdk*" 'An explicit directory without bin/java fell through to the PATH Java.'

    $pathCandidate = Resolve-Java -Explicit 'java' -Minimum 1
    Assert-True ([System.IO.Path]::IsPathRooted($pathCandidate)) `
        'A PATH Java selection returned a bare command instead of its executable path.'
    Assert-True ($pathCandidate -eq (Get-Command java -CommandType Application | Select-Object -First 1).Source) `
        'A PATH Java selection did not identify the executable that passed its version check.'

    $pathJava = Resolve-Java
    $jdkRoot = Split-Path -Parent (Split-Path -Parent $pathJava)
    $resolvedJava = Resolve-Java -Explicit $jdkRoot
    Assert-True ([System.IO.Path]::GetFullPath($resolvedJava).Equals(
        [System.IO.Path]::GetFullPath($pathJava), [System.StringComparison]::OrdinalIgnoreCase)) `
        'A valid explicit JDK directory did not resolve its bin/java executable.'

    # A release bundle older than the sources it is built from. On 2026-09-23 a test run after a
    # patch change left it behind the new hook and the phone got the old one; patch-for-device
    # now stops on it. Build output beside a module's sources must not count.
    $staleRoot = Join-Path $caseRoot 'stale-bundle'
    $patchSource = Join-Path $staleRoot 'patches/src/main/kotlin/Hook.kt'
    $extensionSource = Join-Path $staleRoot 'extensions/app/library/src/main/java/Hook.java'
    $buildOutput = Join-Path $staleRoot 'extensions/app/build/intermediates/Hook.class'
    $gradleFile = Join-Path $staleRoot 'extensions/app/build.gradle.kts'
    $staleBundle = Join-Path $staleRoot 'patches/build/release/patches-1.0.0.mpp'
    $staleFiles = @($patchSource, $extensionSource, $buildOutput, $gradleFile, $staleBundle)
    foreach ($file in $staleFiles) {
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $file) | Out-Null
        [System.IO.File]::WriteAllText($file, 'x', [System.Text.Encoding]::ASCII)
    }
    $then = [DateTime]::UtcNow.AddHours(-1)
    foreach ($file in $staleFiles) { [System.IO.File]::SetLastWriteTimeUtc($file, $then) }
    [System.IO.File]::SetLastWriteTimeUtc($staleBundle, $then.AddMinutes(5))
    Assert-True (@(Get-SourcesNewerThanBundle -Root $staleRoot -Bundle $staleBundle).Count -eq 0) `
        'A bundle built after every source was called stale.'
    [System.IO.File]::SetLastWriteTimeUtc($buildOutput, $then.AddMinutes(9))
    Assert-True (@(Get-SourcesNewerThanBundle -Root $staleRoot -Bundle $staleBundle).Count -eq 0) `
        'Build output written after the bundle was counted as a source.'
    [System.IO.File]::SetLastWriteTimeUtc($extensionSource, $then.AddMinutes(8))
    $newer = @(Get-SourcesNewerThanBundle -Root $staleRoot -Bundle $staleBundle)
    Assert-True ($newer.Count -eq 1 -and $newer[0].FullName -eq (Get-Item -LiteralPath $extensionSource).FullName) `
        "A submodule's source written after the bundle was missed: $(@($newer | ForEach-Object FullName) -join ', ')"
    [System.IO.File]::SetLastWriteTimeUtc($patchSource, $then.AddMinutes(6))
    [System.IO.File]::SetLastWriteTimeUtc($gradleFile, $then.AddMinutes(7))
    $newer = @(Get-SourcesNewerThanBundle -Root $staleRoot -Bundle $staleBundle)
    Assert-True ($newer.Count -eq 3 -and
        $newer[0].FullName -eq (Get-Item -LiteralPath $extensionSource).FullName -and
        $newer[1].FullName -eq (Get-Item -LiteralPath $gradleFile).FullName -and
        $newer[2].FullName -eq (Get-Item -LiteralPath $patchSource).FullName) `
        "Newer sources are not all listed, newest first: $(@($newer | ForEach-Object FullName) -join ', ')"
    # The R8 rules every extension's build reads, and a compile-only stub in a patches
    # submodule (its constants can be inlined into patch code), count too.
    $rules = Join-Path $staleRoot 'extensions/proguard-rules.pro'
    $stub = Join-Path $staleRoot 'patches/stub/src/main/java/android/os/Build.java'
    foreach ($file in @($rules, $stub)) {
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $file) | Out-Null
        [System.IO.File]::WriteAllText($file, 'x', [System.Text.Encoding]::ASCII)
        [System.IO.File]::SetLastWriteTimeUtc($file, $then)
    }
    Assert-True (@(Get-SourcesNewerThanBundle -Root $staleRoot -Bundle $staleBundle).Count -eq 3) `
        'An R8 rules file or a stub written before the bundle was counted.'
    [System.IO.File]::SetLastWriteTimeUtc($rules, $then.AddMinutes(10))
    [System.IO.File]::SetLastWriteTimeUtc($stub, $then.AddMinutes(11))
    $newer = @(Get-SourcesNewerThanBundle -Root $staleRoot -Bundle $staleBundle)
    Assert-True ($newer.Count -eq 5 -and
        $newer[0].FullName -eq (Get-Item -LiteralPath $stub).FullName -and
        $newer[1].FullName -eq (Get-Item -LiteralPath $rules).FullName) `
        "The R8 rules or a patches submodule's stub was missed: $(@($newer | ForEach-Object FullName) -join ', ')"
    # NOTICE as well: :extensions:facebook compiles it into the payload for the Licenses row, so a
    # bundle built before it moved carries the old text.
    $notice = Join-Path $staleRoot 'NOTICE'
    [System.IO.File]::WriteAllText($notice, 'x', [System.Text.Encoding]::ASCII)
    [System.IO.File]::SetLastWriteTimeUtc($notice, $then)
    Assert-True (@(Get-SourcesNewerThanBundle -Root $staleRoot -Bundle $staleBundle).Count -eq 5) `
        'A NOTICE written before the bundle was counted.'
    [System.IO.File]::SetLastWriteTimeUtc($notice, $then.AddMinutes(12))
    $newer = @(Get-SourcesNewerThanBundle -Root $staleRoot -Bundle $staleBundle)
    Assert-True ($newer.Count -eq 6 -and $newer[0].FullName -eq (Get-Item -LiteralPath $notice).FullName) `
        "A NOTICE written after the bundle, which the payload carries, was missed: $(@($newer | ForEach-Object FullName) -join ', ')"
    $scopeManifest = Join-Path $staleRoot 'gradle/tooling-scopes.txt'
    New-Item -ItemType Directory -Path (Split-Path -Parent $scopeManifest) -Force | Out-Null
    [IO.File]::WriteAllText($scopeManifest, 'required settings/buildscript/classpath', [Text.Encoding]::ASCII)
    [IO.File]::SetLastWriteTimeUtc($scopeManifest, $then.AddMinutes(13))
    $newer = @(Get-SourcesNewerThanBundle -Root $staleRoot -Bundle $staleBundle)
    Assert-True ($newer.Count -eq 7 -and $newer[0].FullName -eq (Get-Item -LiteralPath $scopeManifest).FullName) `
        'A tooling coverage manifest written after the bundle was missed.'

    # File times can't see an input deleted, renamed or edited behind an older time, so device
    # patching holds the bundle to the digest patches/build.gradle.kts stamps instead. The same
    # inputs, in the same order and the same layout, worked out here independently.
    $inputPaths = @('NOTICE', 'extensions/app/build.gradle.kts', 'extensions/app/library/src/main/java/Hook.java',
        'extensions/proguard-rules.pro', 'gradle/tooling-scopes.txt', 'patches/src/main/kotlin/Hook.kt',
        'patches/stub/src/main/java/android/os/Build.java')
    $layout = New-Object System.IO.MemoryStream
    $header = [Text.Encoding]::ASCII.GetBytes("hushfacebook-source-inputs-1`n")
    $layout.Write($header, 0, $header.Length)
    foreach ($path in $inputPaths) {
        $name = [Text.Encoding]::UTF8.GetBytes($path)
        $bytes = [IO.File]::ReadAllBytes((Join-Path $staleRoot $path))
        $fields = @([byte[]](0, 0, 0, $name.Length), $name, [byte[]](0, 0, 0, 0, 0, 0, 0, $bytes.Length), $bytes)
        foreach ($field in $fields) { $layout.Write($field, 0, $field.Length) }
    }
    $expected = ([BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash($layout.ToArray())) -replace '-', '').ToLowerInvariant()
    $inputs = Get-ProducerInputSha256 -Root $staleRoot
    Assert-True ($inputs -ceq $expected) "The input digest isn't the one the build stamps: $inputs, expected $expected"
    # Build output, Git's own files and anything outside the inputs leave it alone.
    $outside = @('extensions/app/library/src/main/.gitignore', 'extensions/app/build/tmp/build.gradle.kts',
        'extensions/app/notes.txt', 'patches/build/libs/build.gradle.kts', 'scripts/common.ps1', 'README.md',
        'extensions/app/library/src/main/java/Hook.java~')
    foreach ($path in $outside) {
        $file = Join-Path $staleRoot $path
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $file) | Out-Null
        [IO.File]::WriteAllText($file, 'z', [Text.Encoding]::ASCII)
    }
    Assert-True ((Get-ProducerInputSha256 -Root $staleRoot) -ceq $inputs) 'A file outside the producer inputs moved their digest.'
    # An edit put back with its old time: the old check saw nothing, the digest does.
    [IO.File]::WriteAllText($extensionSource, 'y', [Text.Encoding]::ASCII)
    [IO.File]::SetLastWriteTimeUtc($extensionSource, $then)
    Assert-True (-not (@(Get-SourcesNewerThanBundle -Root $staleRoot -Bundle $staleBundle) |
        Where-Object { $_.FullName -eq (Get-Item -LiteralPath $extensionSource).FullName }))  `
        'A backdated edit was expected to slip past the file-time check.'
    Assert-True ((Get-ProducerInputSha256 -Root $staleRoot) -cne $inputs) 'A source edited behind an older file time kept its digest.'
    [IO.File]::WriteAllText($extensionSource, 'x', [Text.Encoding]::ASCII)
    Assert-True ((Get-ProducerInputSha256 -Root $staleRoot) -ceq $inputs) 'The same bytes back did not give the same digest.'
    # Renamed, same bytes and time.
    $renamed = Join-Path (Split-Path -Parent $extensionSource) 'Hook2.java'
    Move-Item -LiteralPath $extensionSource -Destination $renamed
    Assert-True ((Get-ProducerInputSha256 -Root $staleRoot) -cne $inputs) 'A renamed input kept its digest.'
    Move-Item -LiteralPath $renamed -Destination $extensionSource
    # Deleted, then a new one not yet built.
    Remove-Item -LiteralPath $patchSource
    Assert-True ((Get-ProducerInputSha256 -Root $staleRoot) -cne $inputs) 'A deleted input kept its digest.'
    [IO.File]::WriteAllText($patchSource, 'x', [Text.Encoding]::ASCII)
    Assert-True ((Get-ProducerInputSha256 -Root $staleRoot) -ceq $inputs) 'A restored input did not give the same digest.'
    $added = Join-Path $staleRoot 'extensions/app/consumer-rules.pro'
    [IO.File]::WriteAllText($added, 'x', [Text.Encoding]::ASCII)
    Assert-True ((Get-ProducerInputSha256 -Root $staleRoot) -cne $inputs) 'A new R8 rules file kept the digest.'
} finally {
    if ($caseRoot.StartsWith($requiredPrefix, [System.StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $caseRoot)) {
        Remove-Item -LiteralPath $caseRoot -Recurse -Force
    }
}

$consumerScripts = @('patch-for-device.ps1', 'verify-all-patches.ps1', 'build-release-receipt.ps1')
foreach ($name in $consumerScripts) {
    $text = Get-Content -LiteralPath (Join-Path $PSScriptRoot $name) -Raw
    Assert-True ($text -notmatch '(?m)expectedPackageVersion\s*=\s*[''\"]') `
        "$name pins an expected package version instead of reading the catalog."
    Assert-True ($text -match 'Get-PatchTarget') "$name does not read its target through Get-PatchTarget."
    Assert-True ($text -match 'Get-PatchDependencyNames') `
        "$name does not derive the selected patches' dependency closure."
    Assert-True ($text -match 'AllowedDependencyNames') `
        "$name does not pass declared dependency names into result validation."
}

# --- release receipt -------------------------------------------------------------------------

. (Join-Path $PSScriptRoot 'release-receipt.ps1')
. (Join-Path $PSScriptRoot 'walk-receipt.ps1')
# New-VerifierReceipt, which writes the receipt release.ps1's publish stage reads.
. (Join-Path $PSScriptRoot 'injected-register-device.ps1')

$manifestLines = @(
    'N: android=http://schemas.android.com/apk/res/android (line=1)',
    '  E: manifest (line=1)',
    '    A: http://schemas.android.com/apk/res/android:versionCode(0x0101021b)=2024607030',
    '    A: http://schemas.android.com/apk/res/android:versionName(0x0101021c)="46.7.3" (Raw: "46.7.3")',
    '    A: package="com.example.host" (Raw: "com.example.host")',
    '    A: platformBuildVersionCode=36',
    '      E: uses-permission (line=10)',
    '        A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.INTERNET" (Raw: "android.permission.INTERNET")',
    '      E: uses-permission (line=11)',
    '        A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.CAMERA" (Raw: "android.permission.CAMERA")',
    '      E: application (line=20)',
    '        E: activity (line=21)',
    '          A: http://schemas.android.com/apk/res/android:name(0x01010003)="com.example.host.Main" (Raw: "com.example.host.Main")',
    '          A: http://schemas.android.com/apk/res/android:exported(0x01010010)=true',
    '            E: intent-filter (line=22)',
    '              E: action (line=23)',
    '                A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.intent.action.MAIN" (Raw: "android.intent.action.MAIN")',
    '        E: activity (line=30)',
    '          A: http://schemas.android.com/apk/res/android:name(0x01010003)="com.example.host.Private" (Raw: "com.example.host.Private")',
    '          A: http://schemas.android.com/apk/res/android:exported(0x01010010)=false',
    '        E: service (line=40)',
    '          A: http://schemas.android.com/apk/res/android:name(0x01010003)=".Sync" (Raw: ".Sync")',
    '          A: http://schemas.android.com/apk/res/android:exported(0x01010010)=true'
)

$facts = ConvertFrom-ManifestXmlTree -Lines $manifestLines -Source 'fixture'
Assert-True ($facts.package -eq 'com.example.host') 'The manifest package was not read.'
Assert-True ($facts.versionName -eq '46.7.3') 'The manifest version name was not read.'
Assert-True ($facts.versionCode -eq '2024607030') 'The manifest version code was not read.'
Assert-True (($facts.permissions -join ',') -eq 'android.permission.CAMERA,android.permission.INTERNET') `
    'The requested permissions were not read and sorted.'
Assert-True (($facts.exported -join ',') -eq 'activity:com.example.host.Main,service:com.example.host.Sync') `
    "Exported components were misread: $($facts.exported -join ',')"
Assert-True ($facts.exported -notcontains 'activity:com.example.host.Private') `
    'A component marked exported=false was reported as exported.'
Assert-True ($facts.exported -notcontains 'action:android.intent.action.MAIN') `
    'An intent-filter action was counted as an exported component.'

Assert-Throws {
    ConvertFrom-ManifestXmlTree -Lines @('  E: manifest (line=1)') -Source 'nameless'
} '*no package name*' 'A manifest with no package was accepted.'

$patchedFacts = ConvertFrom-ManifestXmlTree -Source 'patched' -Lines (
    @($manifestLines | Where-Object { $_ -notlike '*android.permission.CAMERA*' }) + @(
        '      E: uses-permission (line=12)',
        '        A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.VIBRATE" (Raw: "android.permission.VIBRATE")',
        '      E: application (line=20)',
        '        E: receiver (line=50)',
        '          A: http://schemas.android.com/apk/res/android:name(0x01010003)="com.example.host.Probe" (Raw: "com.example.host.Probe")',
        '          A: http://schemas.android.com/apk/res/android:exported(0x01010010)=true'))
$delta = Get-ManifestDelta -Stock $facts -Patched $patchedFacts
Assert-True (($delta.permissionsAdded -join ',') -eq 'android.permission.VIBRATE') `
    'An added permission was not reported.'
Assert-True (($delta.permissionsRemoved -join ',') -eq 'android.permission.CAMERA') `
    'A removed permission was not reported.'
Assert-True (($delta.exportedComponentsAdded -join ',') -eq 'receiver:com.example.host.Probe') `
    'A newly exported component was not reported.'
Assert-True (@($delta.exportedComponentsRemoved).Count -eq 0) `
    'A component that stayed exported was reported as removed.'
$entries = ConvertTo-ManifestDeltaEntries -Delta $delta
Assert-True (($entries -join '; ') -eq (@(
    'exported-added receiver:com.example.host.Probe',
    'permission-added android.permission.VIBRATE',
    'permission-removed android.permission.CAMERA') -join '; ')) `
    "The delta did not flatten to allowlist lines: $($entries -join '; ')"

$unchanged = Get-ManifestDelta -Stock $facts -Patched $facts
Assert-True (@(ConvertTo-ManifestDeltaEntries -Delta $unchanged).Count -eq 0) `
    'An unchanged manifest produced a delta.'

# The checked-in allowlist approves four reviewed changes and nothing else. Install beside Meta's
# apps renames the two permissions Facebook shares with Messenger, Lite, Business Suite and
# Workplace, which the receipt reads as the two new names asked for and the two old ones no longer
# asked for. Open in Facebook exports its share target, GmsCore support swaps Google's push
# RECEIVE permission for microG-RE's, and Clone install (#417) moves Facebook's own five
# permissions to the copy's package.
$checkedInAllowlist = @(Read-ManifestDeltaAllowlist -Path (Join-Path $PSScriptRoot 'manifest-delta-allowlist.txt') |
    Where-Object { $_ })
$clonePermissionNames = @('DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION', 'permission.CREATE_SHORTCUT',
    'permission.CROSS_PROCESS_BROADCAST_MANAGER', 'permission.RECEIVE_ADM_MESSAGE', 'provider.ACCESS')
$clonePermissionsAdded = @($clonePermissionNames | ForEach-Object { "com.facebook.katana.hush.$_" })
$clonePermissionsRemoved = @($clonePermissionNames | ForEach-Object { "com.facebook.katana.$_" })
$reviewedEntries = @(
    'permission-added app.hushfacebook.permission.prod.FB_APP_COMMUNICATION',
    'permission-added app.hushfacebook.receiver.permission.ACCESS',
    'permission-removed com.facebook.permission.prod.FB_APP_COMMUNICATION',
    'permission-removed com.facebook.receiver.permission.ACCESS',
    'exported-added activity:app.morphe.extension.facebook.misc.OpenInFacebook',
    'permission-added app.revanced.android.c2dm.permission.RECEIVE',
    'permission-removed com.google.android.c2dm.permission.RECEIVE') +
    @($clonePermissionsAdded | ForEach-Object { "permission-added $_" }) +
    @($clonePermissionsRemoved | ForEach-Object { "permission-removed $_" })
Assert-True ($reviewedEntries.Count -eq 17) "The reviewed allowlist entries number $($reviewedEntries.Count), not 17."
Assert-True ((@($checkedInAllowlist | Sort-Object -CaseSensitive) -join "`n") -ceq
        (@($reviewedEntries | Sort-Object -CaseSensitive) -join "`n")) `
    ('The checked-in manifest delta allowlist approves something besides the four reviewed changes, or ' +
     "less than all of them: $($checkedInAllowlist -join ', ')")

$allowlistRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("receipt-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $allowlistRoot | Out-Null
try {
    $good = Join-Path $allowlistRoot 'good.txt'
    Set-Content -LiteralPath $good -Encoding UTF8 -Value @(
        '# a comment', '', 'permission-added android.permission.VIBRATE',
        'exported-added receiver:com.example.host.Probe')
    Assert-True (@(Read-ManifestDeltaAllowlist -Path $good).Count -eq 2) `
        'The allowlist reader did not skip comments and blank lines.'

    $bad = Join-Path $allowlistRoot 'bad.txt'
    Set-Content -LiteralPath $bad -Encoding UTF8 -Value @('permission-added')
    Assert-Throws { Read-ManifestDeltaAllowlist -Path $bad } '*<kind> <value>*' `
        'A malformed allowlist line was accepted.'
    Assert-Throws { Read-ManifestDeltaAllowlist -Path (Join-Path $allowlistRoot 'absent.txt') } `
        '*allowlist is missing*' 'A missing allowlist was treated as an empty one.'

    # A stand-in bundle, so the size, hash and manifest checks compare against real bytes.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $commitSeconds = 1700000000L
    function New-TestBundle {
        param([string]$Path, [string]$Version = '9.9.9', [long]$Timestamp = 1700000000000L,
            [string]$Patcher = '1.12.0', [hashtable]$Entries = @{}, [hashtable]$Source = @{})
        if (Test-Path -LiteralPath $Path) { Remove-Item -LiteralPath $Path -Force }
        $archive = [System.IO.Compression.ZipFile]::Open(
            $Path, [System.IO.Compression.ZipArchiveMode]::Create)
        try {
            $entry = $archive.CreateEntry('META-INF/MANIFEST.MF')
            $writer = New-Object System.IO.StreamWriter($entry.Open())
            try {
                $writer.Write("Manifest-Version: 1.0`nVersion: $Version`n" +
                    "Timestamp: $Timestamp`nPatcher-Version: $Patcher`n")
                foreach ($name in @($Source.Keys | Sort-Object)) { $writer.Write("${name}: $($Source[$name])`n") }
                $writer.Write("`n")
            } finally { $writer.Dispose() }
            foreach ($name in @($Entries.Keys | Sort-Object)) {
                $writer = New-Object System.IO.StreamWriter($archive.CreateEntry($name).Open())
                try { $writer.Write($Entries[$name]) } finally { $writer.Dispose() }
            }
        } finally { $archive.Dispose() }
    }

    # An SBOM in the shape :patches:releaseSbom writes, describing the bundle at -Bundle: its name,
    # hash, version and pinned stamp, the libraries given as package URLs, the patch module as the
    # first-party code, and each extension payload the bundle carries with its hash. -Mutate edits
    # the document before it's written.
    function New-TestSbom {
        param([string]$Path, [string]$Bundle,
            [string[]]$Libraries = @('pkg:maven/com.google.code.gson/gson@2.14.0'), [scriptblock]$Mutate)
        $bundleName = Split-Path -Leaf $Bundle
        $facts = Get-BundleManifestFacts -BundlePath $Bundle
        $components = New-Object System.Collections.Generic.List[object]
        foreach ($purl in @($Libraries | Where-Object { $_ })) {
            $parts = [regex]::Match($purl, '^pkg:maven/([^/]+)/([^@]+)@(.+)$')
            $components.Add([ordered]@{ type = 'library'; 'bom-ref' = $purl; group = $parts.Groups[1].Value
                name = $parts.Groups[2].Value; version = $parts.Groups[3].Value; scope = 'required'; purl = $purl
                properties = @([ordered]@{ name = 'hushfacebook:carried-by'; value = $bundleName }) })
        }
        $components.Add([ordered]@{ type = 'library'; 'bom-ref' = 'project:patches'; name = ':patches'
            version = $facts.version; scope = 'required'
            properties = @([ordered]@{ name = 'hushfacebook:first-party'; value = 'built from this repository' }) })
        $archive = [System.IO.Compression.ZipFile]::OpenRead($Bundle)
        try {
            foreach ($entry in @($archive.Entries | Where-Object { $_.FullName -like 'extensions/*.mpe' } | Sort-Object FullName)) {
                $stream = $entry.Open()
                $sha = [System.Security.Cryptography.SHA256]::Create()
                try { $digest = ($sha.ComputeHash($stream) | ForEach-Object { '{0:x2}' -f $_ }) -join '' } finally { $sha.Dispose(); $stream.Dispose() }
                $components.Add([ordered]@{ type = 'file'; 'bom-ref' = $entry.FullName; name = $entry.FullName
                    version = $facts.version; scope = 'required'; hashes = @([ordered]@{ alg = 'SHA-256'; content = $digest }) })
            }
        } finally { $archive.Dispose() }
        $document = [ordered]@{
            bomFormat = 'CycloneDX'; specVersion = '1.6'; serialNumber = "urn:uuid:$([guid]::NewGuid())"; version = 1
            metadata = [ordered]@{
                timestamp = [DateTimeOffset]::FromUnixTimeMilliseconds($facts.timestamp).UtcDateTime.ToString(
                    "yyyy-MM-dd'T'HH:mm:ss'Z'", [Globalization.CultureInfo]::InvariantCulture)
                component = [ordered]@{ type = 'file'; 'bom-ref' = $bundleName; name = $bundleName; version = $facts.version
                    hashes = @([ordered]@{ alg = 'SHA-256'; content = (Get-Sha256Hex -Path $Bundle).ToLowerInvariant() }) }
            }
            components = $components.ToArray()
            dependencies = @()
        }
        if ($Mutate) { & $Mutate $document }
        Set-Content -LiteralPath $Path -Encoding UTF8 -Value ($document | ConvertTo-Json -Depth 12)
    }

    function New-TestTooling {
        param([string]$Path, [string]$Bundle, [string]$Commit = '0123456789abcdef0123456789abcdef01234567',
            [string]$ScopeText = "required settings/buildscript/classpath`nrequired :patches/configuration/testRuntimeClasspath`n",
            [string[]]$Libraries = @('pkg:maven/com.google.code.gson/gson@2.14.0'), [scriptblock]$Mutate, [switch]$SettingsOnly)
        $manifest = Read-ToolingScopeManifest -Text $ScopeText
        $facts = Get-BundleManifestFacts -BundlePath $Bundle
        $scopes = @($manifest.Entries | ForEach-Object {
            if ($_.Requirement -ceq 'conditional') {
                [ordered]@{ id = $_.Id; status = 'notConfigured'; reason = "No $($_.TestTask) test component" }
            } else {
                $coordinates = @($Libraries | ForEach-Object { $_.Substring('pkg:maven/'.Length).Replace('/', ':').Replace('@', ':') })
                if ($SettingsOnly -and $_.Id -cne 'settings/buildscript/classpath') { $coordinates = @() }
                [ordered]@{ id = $_.Id; status = 'resolved'; root = 'root'; nodes = @('root') + $coordinates
                    edges = @([ordered]@{ from = 'root'; to = $coordinates }) + @($coordinates | ForEach-Object {
                        [ordered]@{ from = $_; to = @() } }) }
            }
        })
        $components = @($Libraries | ForEach-Object {
            $parts = [regex]::Match($_, '^pkg:maven/([^/]+)/([^@]+)@(.+)$')
            $coordinate = "$($parts.Groups[1].Value):$($parts.Groups[2].Value):$($parts.Groups[3].Value)"
            [ordered]@{ coordinate = $coordinate
                group = $parts.Groups[1].Value; name = $parts.Groups[2].Value; version = $parts.Groups[3].Value; purl = $_
                scopes = @($scopes | Where-Object { $_.status -ceq 'resolved' -and $_.nodes -ccontains $coordinate } | ForEach-Object { $_.id })
                artifacts = @([ordered]@{ name = "$($parts.Groups[2].Value).jar"; sha256 = ('a' * 64) }) }
        })
        $document = [ordered]@{ schemaVersion = 1; source = [ordered]@{ commit = $Commit; tree = 'clean' }
            bundle = [ordered]@{ file = Split-Path -Leaf $Bundle; version = $facts.version; sha256 = Get-Sha256Hex -Path $Bundle }
            scopeManifest = [ordered]@{ file = 'gradle/tooling-scopes.txt'; sha256 = $manifest.Sha256
                entries = @($manifest.Entries | ForEach-Object { [ordered]@{ id = $_.Id; requirement = $_.Requirement
                    status = if ($_.Requirement -ceq 'conditional') { 'notConfigured' } else { 'resolved' } } }) }
            scopes = $scopes; components = $components }
        if ($Mutate) { & $Mutate $document }
        Set-Content -LiteralPath $Path -Encoding UTF8 -Value ($document | ConvertTo-Json -Depth 16)
    }

    $bundle = Join-Path $allowlistRoot 'patches-9.9.9.mpp'
    New-TestBundle -Path $bundle -Entries @{ 'extensions/facebook.mpe' = "dex`n035 payload" }
    $bundleHash = Get-Sha256Hex -Path $bundle
    $bundleSize = (Get-Item -LiteralPath $bundle).Length
    $sbomFile = Join-Path $allowlistRoot 'patches-9.9.9.cdx.json'
    New-TestSbom -Path $sbomFile -Bundle $bundle
    $toolingFile = Join-Path $allowlistRoot 'patches-9.9.9.tooling.json'
    New-TestTooling -Path $toolingFile -Bundle $bundle

    $manifestFacts = Get-BundleManifestFacts -BundlePath $bundle
    Assert-True ($manifestFacts.version -eq '9.9.9') 'The bundle manifest version was not read.'
    Assert-True ($manifestFacts.timestamp -eq 1700000000000L) 'The bundle timestamp was not read.'
    Assert-True ($manifestFacts.patcherVersion -eq '1.12.0') 'The bundle patcher stamp was not read.'

    # Two declared builds, the way the Facebook catalog declares the newest release and the one
    # before it, each pinned to its version code, and a run of each.
    $declaredBuilds = @('46.7.3', '46.6.1')
    $declaredCodes = @{ '46.7.3' = [string[]]@('2024607030'); '46.6.1' = [string[]]@('2024606010') }
    $template = [ordered]@{
        schemaVersion = Get-ReleaseReceiptSchemaVersion
        release   = [ordered]@{ version = '9.9.9'; tag = 'v9.9.9'
            commit = '0123456789abcdef0123456789abcdef01234567'
            commitTimestamp = $commitSeconds; patchCount = 2 }
        bundle    = [ordered]@{ file = 'patches-9.9.9.mpp'; sizeBytes = $bundleSize
            sha256 = $bundleHash; timestamp = 1700000000000L }
        sbom      = [ordered]@{ file = 'patches-9.9.9.cdx.json'; sha256 = (Get-Sha256Hex -Path $sbomFile); components = 3 }
        tooling   = [ordered]@{ file = 'patches-9.9.9.tooling.json'; sha256 = Get-Sha256Hex -Path $toolingFile
            components = 1; scopes = 2; audit = [ordered]@{ status = 'checked' } }
        toolchain = [ordered]@{ patcherVersion = '1.12.0'; managerFloor = '1.29.0' }
        extension = [ordered]@{ dexPayloads = (Get-ExtensionPayloads -BundlePath $bundle) }
        targets   = @([ordered]@{
            source = [ordered]@{ file = 'stock.apkm'; package = 'com.example.host'
                versionName = '46.7.3'; versionCode = '2024607030'; sha256 = ('B' * 64)
                forced = $false }
            patches = @([ordered]@{ name = 'Alpha'; applied = $true; reason = $null },
                        [ordered]@{ name = 'Beta'; applied = $true; reason = $null })
            manifestDelta = [ordered]@{ permissionsAdded = @(); permissionsRemoved = @()
                exportedComponentsAdded = @(); exportedComponentsRemoved = @() }
        }, [ordered]@{
            source = [ordered]@{ file = 'previous.apkm'; package = 'com.example.host'
                versionName = '46.6.1'; versionCode = '2024606010'; sha256 = ('D' * 64)
                forced = $false }
            patches = @([ordered]@{ name = 'Alpha'; applied = $true; reason = $null },
                        [ordered]@{ name = 'Beta'; applied = $true; reason = $null })
            manifestDelta = [ordered]@{ permissionsAdded = @(); permissionsRemoved = @()
                exportedComponentsAdded = @(); exportedComponentsRemoved = @() }
        })
    }
    $templateJson = $template | ConvertTo-Json -Depth 12

    function New-TestReceipt {
        param([scriptblock]$Mutate)
        $copy = $templateJson | ConvertFrom-Json
        if ($Mutate) { & $Mutate $copy }
        return $copy
    }

    function Test-TestReceipt {
        param($Receipt, [string[]]$Approved = @(), [string]$BundlePath = $bundle)
        return Test-ReleaseReceipt -Receipt $Receipt -ExpectedVersion '9.9.9' `
            -ExpectedPatchNames @('Alpha', 'Beta') -ExpectedPatcherVersion '1.12.0' `
            -ExpectedManagerFloor '1.29.0' -ExpectedPackageName 'com.example.host' -ExpectedPackageVersions $declaredBuilds `
            -ExpectedPackageVersionCodes $declaredCodes -BundlePath $BundlePath -ApprovedManifestDelta $Approved
    }

    $valid = Test-TestReceipt -Receipt (New-TestReceipt)
    Assert-True $valid.Valid "A complete receipt was refused: $($valid.Reason)"

    # New source fields must be covered by the payload digest. Legacy release bytes stay valid.
    Assert-True (-not (Get-BundleIdentityFacts -BundlePath $bundle).Present) 'A legacy bundle fabricated build identity.'
    $identityRoot = Join-Path $allowlistRoot 'identity'
    New-Item -ItemType Directory -Path $identityRoot -Force | Out-Null
    $identityBundle = Join-Path $identityRoot 'patches-9.9.9.mpp'
    function New-IdentityBundle([string]$State = 'clean', [string]$Commit = $template.release.commit,
        [string]$InputSha256 = ('b' * 64), [string]$Path = $identityBundle) {
        $source = @{
            'Hushfacebook-Source-State' = $State; 'Hushfacebook-Source-Commit' = $Commit
            'Hushfacebook-Source-Tree' = ('a' * 40); 'Hushfacebook-Input-SHA256' = $InputSha256
        }
        if ($State -ceq 'unknown') { $source['Hushfacebook-Source-Commit'] = 'unknown'; $source['Hushfacebook-Source-Tree'] = 'unknown' }
        New-TestBundle -Path $Path -Source $source -Entries @{
            'classes.dex' = "dex`n035 payload"; 'META-INF/hushfacebook-build.identity' = 'placeholder'
            # The template receipt's payload, which the receipt check holds to the bundle.
            'extensions/facebook.mpe' = "dex`n035 payload"
        }
        $zip = [IO.Compression.ZipFile]::OpenRead($Path)
        try { $payload = Get-BundlePayloadSha256 -Archive $zip } finally { $zip.Dispose() }
        $zip = [IO.Compression.ZipFile]::Open($Path, [IO.Compression.ZipArchiveMode]::Update)
        try {
            $zip.GetEntry('META-INF/hushfacebook-build.identity').Delete()
            $writer = [IO.StreamWriter]::new($zip.CreateEntry('META-INF/hushfacebook-build.identity').Open())
            try { $writer.Write("hushfacebook-bundle-1`n$payload`n") } finally { $writer.Dispose() }
        } finally { $zip.Dispose() }
    }
    function Test-IdentityReceipt {
        $receipt = New-TestReceipt
        $receipt.bundle.sha256 = Get-Sha256Hex -Path $identityBundle
        $receipt.bundle.sizeBytes = (Get-Item -LiteralPath $identityBundle).Length
        return Test-TestReceipt -Receipt $receipt -BundlePath $identityBundle
    }
    New-IdentityBundle
    $identity = Get-BundleIdentityFacts -BundlePath $identityBundle
    Assert-True ($identity.Present -and $identity.Valid -and $identity.SourceState -ceq 'clean' -and
        $identity.SourceCommit -ceq $template.release.commit) 'Bound clean identity was not read.'
    Assert-True (Test-IdentityReceipt).Valid 'A receipt refused its bound clean bundle identity.'
    # The walk gate words that identity the way BundleIdentity.fromJar does, which is what the
    # patched app reports, and a bundle with none has nothing to hold a walk to.
    Assert-True ((Get-BundlePatchBuild -BundlePath $identityBundle) -ceq ("sha256=$($identity.PayloadSha256); source=clean:" +
            "$($template.release.commit); tree=$('a' * 40); inputs=$('b' * 64)")) `
        "The walk gate words a bundle's identity otherwise than the app reports it: $(Get-BundlePatchBuild -BundlePath $identityBundle)"
    Assert-True ($null -eq (Get-BundlePatchBuild -BundlePath $bundle)) 'The walk gate found an identity in a legacy bundle.'
    # The SBOM check holds that identity to the commit it's given, as the receipt build and the
    # receipt check both call it; given none, it says so rather than refusing every new bundle.
    $identitySbom = Join-Path $identityRoot 'patches-9.9.9.cdx.json'
    New-TestSbom -Path $identitySbom -Bundle $identityBundle
    $identitySbomRead = Read-ReleaseSbom -Path $identitySbom
    $bound = Test-ReleaseSbom -Sbom $identitySbomRead -BundlePath $identityBundle -BundleName 'patches-9.9.9.mpp' `
        -ExpectedCommit $template.release.commit
    Assert-True $bound.Valid "An SBOM refused a bundle whose identity names the commit given: $($bound.Reason)"
    $bound = Test-ReleaseSbom -Sbom $identitySbomRead -BundlePath $identityBundle -BundleName 'patches-9.9.9.mpp' `
        -ExpectedCommit ('c' * 40)
    Assert-True (-not $bound.Valid -and $bound.Reason -like '*build identity does not bind*') `
        "An SBOM accepted a bundle whose identity names another commit: $($bound.Reason)"
    $bound = Test-ReleaseSbom -Sbom $identitySbomRead -BundlePath $identityBundle -BundleName 'patches-9.9.9.mpp'
    Assert-True (-not $bound.Valid -and $bound.Reason -like '*no source commit was given*') `
        "An SBOM check with no commit to hold an identity to answered: $($bound.Reason)"
    foreach ($state in @('dirty', 'unknown')) {
        New-IdentityBundle -State $state
        Assert-True (Get-BundleIdentityFacts -BundlePath $identityBundle).Valid "Honest $state identity was rejected by the reader."
        $answer = Test-IdentityReceipt
        Assert-True (-not $answer.Valid -and $answer.Reason -like '*build identity*') "A release accepted $state source identity."
    }
    New-IdentityBundle -Commit ('c' * 40)
    $answer = Test-IdentityReceipt
    Assert-True (-not $answer.Valid -and $answer.Reason -like '*build identity*') 'A receipt accepted another producer commit.'
    New-IdentityBundle
    $zip = [IO.Compression.ZipFile]::Open($identityBundle, [IO.Compression.ZipArchiveMode]::Update)
    try {
        $zip.GetEntry('classes.dex').Delete()
        $writer = [IO.StreamWriter]::new($zip.CreateEntry('classes.dex').Open())
        try { $writer.Write('changed payload') } finally { $writer.Dispose() }
    } finally { $zip.Dispose() }
    Assert-True (-not (Get-BundleIdentityFacts -BundlePath $identityBundle).Valid) 'Copied metadata verified a changed payload.'
    Assert-True (-not (Test-IdentityReceipt).Valid) 'A rehashed receipt accepted stale build identity.'
    New-IdentityBundle
    $zip = [IO.Compression.ZipFile]::Open($identityBundle, [IO.Compression.ZipArchiveMode]::Update)
    try { $zip.GetEntry('META-INF/hushfacebook-build.identity').Delete() } finally { $zip.Dispose() }
    $identity = Get-BundleIdentityFacts -BundlePath $identityBundle
    Assert-True ($identity.Present -and -not $identity.Valid) 'Removing identity downgraded a new bundle to legacy.'
    # What patch-for-device holds its bundle to before the CLI runs: the input digest it carries,
    # against the checkout's as it is now. A bundle with no intact record is refused, not trusted.
    Assert-True ((Get-BundleInputMismatch -Root $identityRoot -BundlePath $identityBundle) -like '*no intact record*') `
        'A bundle whose identity record is gone was taken for a build of the checkout.'
    Assert-True ((Get-BundleInputMismatch -Root $identityRoot -BundlePath $bundle) -like '*no intact record*') `
        'A legacy bundle with no input digest was taken for a build of the checkout.'
    $inputRoot = Join-Path $identityRoot 'inputs'
    $inputFile = Join-Path $inputRoot 'patches/src/main/kotlin/Hook.kt'
    New-Item -ItemType Directory -Path (Split-Path -Parent $inputFile) -Force | Out-Null
    [IO.File]::WriteAllText($inputFile, 'x', [Text.Encoding]::ASCII)
    $inputBundle = Join-Path $identityRoot 'inputs.mpp'
    New-IdentityBundle -Path $inputBundle -InputSha256 (Get-ProducerInputSha256 -Root $inputRoot)
    Assert-True ($null -eq (Get-BundleInputMismatch -Root $inputRoot -BundlePath $inputBundle)) `
        'A bundle built from the checkout as it is was called stale.'
    $time = [IO.File]::GetLastWriteTimeUtc($inputFile)
    [IO.File]::WriteAllText($inputFile, 'y', [Text.Encoding]::ASCII)
    [IO.File]::SetLastWriteTimeUtc($inputFile, $time.AddHours(-1))
    $mismatch = Get-BundleInputMismatch -Root $inputRoot -BundlePath $inputBundle
    Assert-True ($mismatch -like '*built from other sources*') "A source edited behind an older file time passed: $mismatch"
    $deviceScript = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'patch-for-device.ps1') -Raw
    $guard = $deviceScript.IndexOf('Get-BundleInputMismatch -Root $root -BundlePath $bundle')
    Assert-True ($guard -gt 0 -and $guard -lt $deviceScript.IndexOf('Invoke-HeavyJob') -and
        $deviceScript -match '\$staleBundle -and -not \$AllowStaleBundle\) \{\s+throw' -and
        $deviceScript -match '\[switch\]\$AllowStaleBundle' -and
        $deviceScript -match 'Write-Warning "\[device\] -AllowStaleBundle') `
        'patch-for-device.ps1 patches without holding the bundle to the checkout first, or overrides it unannounced.'

    # Every fact the receipt exists to pin, put in front of the check one at a time. A gate that
    # has never been shown to fail is a gate nobody has tested.
    $mutations = [ordered]@{
        'a receipt from a different schema'     = { param($r) $r.schemaVersion = 99 }
        'no tooling report'                    = { param($r) $r.PSObject.Properties.Remove('tooling') }
        'a tooling filename for another version' = { param($r) $r.tooling.file = 'patches-9.9.8.tooling.json' }
        'an unhashed tooling report'            = { param($r) $r.tooling.sha256 = '' }
        'no tooling components'                = { param($r) $r.tooling.components = 0 }
        'no tooling scopes'                    = { param($r) $r.tooling.scopes = 0 }
        'an unknown tooling audit status'       = { param($r) $r.tooling.audit.status = 'unchecked' }
        'a receipt for a different version'     = { param($r) $r.release.version = '9.9.8' }
        'a tag that does not match the version' = { param($r) $r.release.tag = 'v9.9.8' }
        'a short commit'                        = { param($r) $r.release.commit = '0123456' }
        'a patch count that is not the catalog' = { param($r) $r.release.patchCount = 3 }
        'a different patcher'                   = { param($r) $r.toolchain.patcherVersion = '1.13.0' }
        'a different Manager floor'             = { param($r) $r.toolchain.managerFloor = '1.30.0' }
        'a bundle size that is not the bundle'  = { param($r) $r.bundle.sizeBytes = 4 }
        'a bundle hash that is not the bundle'  = { param($r) $r.bundle.sha256 = ('C' * 64) }
        'an empty extension payload'            = { param($r) $r.extension.dexPayloads[0].sizeBytes = 0 }
        'an unhashed extension payload'         = { param($r) $r.extension.dexPayloads[0].sha256 = 'nope' }
        'no extension payload at all'           = { param($r) $r.extension.dexPayloads = @() }
        'no target at all'                      = { param($r) $r.targets = @() }
        'an unhashed source APK'                = { param($r) $r.targets[0].source.sha256 = '' }
        'fewer verdicts than patches'           = { param($r) $r.targets[0].patches = @($r.targets[0].patches[0]) }
        'a patch the catalog does not list'     = { param($r) $r.targets[0].patches[1].name = 'Gamma' }
        'the same patch reported twice'         = { param($r) $r.targets[0].patches[1].name = 'Alpha' }
        'a patch that did not apply'            = { param($r) $r.targets[0].patches[1].applied = $false }
        'a receipt with no commit time'         = { param($r) $r.release.commitTimestamp = 0 }
        'a stamp that is not the bundle stamp'  = { param($r) $r.bundle.timestamp = 1700000001000L }
        'a run against another package'         = { param($r) $r.targets[0].source.package = 'com.example.other' }
        'a run with no version name'            = { param($r) $r.targets[0].source.versionName = '' }
        'a receipt that omits the forced flag'  = { param($r) $r.targets[0].source.PSObject.Properties.Remove('forced') }
        'a forced flag that is not a boolean'   = { param($r) $r.targets[0].source.forced = 'false' }
        'a declared-version run marked forced'  = { param($r) $r.targets[0].source.forced = $true }
        'only forced runs past the target'      = { param($r) $r.targets[0].source.versionName = '46.8.3'; $r.targets[0].source.forced = $true; $r.targets = @($r.targets[0]) }
        'a newer build patched without -f'      = { param($r) $r.targets[0].source.versionName = '46.8.3' }
        'a run of the newest declared build only' = { param($r) $r.targets = @($r.targets[0]) }
        'the older declared build forced'       = { param($r) $r.targets[1].source.forced = $true }
        'both runs at the newest declared build' = { param($r) $r.targets[1].source.versionName = '46.7.3' }
        'another build of a declared version patched without -f' = { param($r) $r.targets[1].source.versionCode = '2024605949' }
        'a receipt that names no SBOM'          = { param($r) $r.PSObject.Properties.Remove('sbom') }
        'an SBOM named for another version'     = { param($r) $r.sbom.file = 'patches-9.9.8.cdx.json' }
        'an SBOM with no hash'                  = { param($r) $r.sbom.sha256 = 'nope' }
        'an SBOM hash in lower case'            = { param($r) $r.sbom.sha256 = ([string]$r.sbom.sha256).ToLowerInvariant() }
        'an SBOM counting no component'         = { param($r) $r.sbom.components = 0 }
    }
    foreach ($description in $mutations.Keys) {
        $result = Test-TestReceipt -Receipt (New-TestReceipt -Mutate $mutations[$description])
        Assert-True (-not $result.Valid) "Receipt validation accepted $description."
        Assert-True ([bool]$result.Reason) "Receipt validation refused $description without saying why."
    }

    # The payload list is held to the bundle it's checked with, not only to its form. Each of these
    # kept the form and passed before.
    $payloadCases = [ordered]@{
        'a payload hash the bundle does not carry' = @{ Pattern = "*records an extension payload the bundle doesn't carry*"
            Mutate = { param($r) $r.extension.dexPayloads[0].sha256 = ('C' * 64) } }
        'a payload hash in lower case' = @{ Pattern = "*records an extension payload the bundle doesn't carry*"
            Mutate = { param($r) $r.extension.dexPayloads[0].sha256 = ([string]$r.extension.dexPayloads[0].sha256).ToLowerInvariant() } }
        'a payload size the bundle does not carry' = @{ Pattern = "*records an extension payload the bundle doesn't carry*"
            Mutate = { param($r) $r.extension.dexPayloads[0].sizeBytes = 99 } }
        'a payload the bundle does not carry' = @{ Pattern = "*records an extension payload the bundle doesn't carry: extensions/other.mpe*"
            Mutate = { param($r)
                $other = $r.extension.dexPayloads[0] | ConvertTo-Json | ConvertFrom-Json
                $other.name = 'extensions/other.mpe'
                $r.extension.dexPayloads = @($r.extension.dexPayloads[0], $other) } }
        'the same payload twice' = @{ Pattern = '*extension payload more than once*'
            Mutate = { param($r) $r.extension.dexPayloads = @($r.extension.dexPayloads[0], $r.extension.dexPayloads[0]) } }
    }
    foreach ($description in $payloadCases.Keys) {
        $result = Test-TestReceipt -Receipt (New-TestReceipt -Mutate $payloadCases[$description].Mutate)
        Assert-True (-not $result.Valid -and $result.Reason -like $payloadCases[$description].Pattern) `
            "Receipt validation took $($description): $($result.Reason)"
    }
    # A bundle carrying two payloads: a receipt naming one leaves the other out, and one naming both
    # passes, which is the control for the cases above.
    $twoPayloads = Join-Path $allowlistRoot 'two-payloads.mpp'
    New-TestBundle -Path $twoPayloads -Entries @{ 'extensions/facebook.mpe' = "dex`n035 payload"; 'extensions/messenger.mpe' = "dex`n035 other" }
    $twoPayloadReceipt = New-TestReceipt -Mutate {
        param($r)
        $r.bundle.sha256 = Get-Sha256Hex -Path $twoPayloads
        $r.bundle.sizeBytes = (Get-Item -LiteralPath $twoPayloads).Length
    }
    $result = Test-TestReceipt -Receipt $twoPayloadReceipt -BundlePath $twoPayloads
    Assert-True (-not $result.Valid -and $result.Reason -like '*leaves out an extension payload the bundle carries: extensions/messenger.mpe*') `
        "A receipt leaving out a payload the bundle carries passed: $($result.Reason)"
    $twoPayloadReceipt.extension.dexPayloads = (Get-ExtensionPayloads -BundlePath $twoPayloads) | ConvertTo-Json -Depth 4 | ConvertFrom-Json
    $result = Test-TestReceipt -Receipt $twoPayloadReceipt -BundlePath $twoPayloads
    Assert-True $result.Valid "A receipt naming both of the bundle's payloads was refused: $($result.Reason)"

    # A receipt built only from forced runs against newer builds has to be refused for that
    # reason and name the target it is missing, not trip over some other field on the way.
    $onlyForced = Test-TestReceipt -Receipt (New-TestReceipt -Mutate $mutations['only forced runs past the target'])
    Assert-True ($onlyForced.Reason -like '*No target*46.7.3*without -f*46.8.3*') `
        "A forced-only receipt was refused for the wrong reason: $($onlyForced.Reason)"
    # A release that ran only the newest build names the declared build it never patched.
    $newestOnly = Test-TestReceipt -Receipt (New-TestReceipt -Mutate $mutations['a run of the newest declared build only'])
    Assert-True ($newestOnly.Reason -like '*No target*46.6.1*without -f*') `
        "A receipt missing a declared build was refused for the wrong reason: $($newestOnly.Reason)"
    $secondTarget = New-TestReceipt -Mutate {
        param($r)
        $newer = $r.targets[0] | ConvertTo-Json -Depth 8 | ConvertFrom-Json
        $newer.source.versionName = '46.8.3'
        $newer.source.forced = $true
        $r.targets = @($r.targets[0], $r.targets[1], $newer)
    }
    $twoTargets = Test-TestReceipt -Receipt $secondTarget
    Assert-True $twoTargets.Valid "A receipt with the declared target beside a forced run was refused: $($twoTargets.Reason)"
    # Another build of a declared version is a build of its own: patched without -f it is refused
    # for that, naming the code the catalog pins, and forced beside the declared runs it is fine.
    $otherBuild = Test-TestReceipt -Receipt (New-TestReceipt -Mutate $mutations['another build of a declared version patched without -f'])
    Assert-True ($otherBuild.Reason -like '*46.6.1 (version code 2024605949) was patched without -f at a declared build*46.6.1 (2024606010)*') `
        "Another build of a declared version was refused for the wrong reason: $($otherBuild.Reason)"
    $forcedOtherBuild = Test-TestReceipt -Receipt (New-TestReceipt -Mutate {
        param($r)
        $variant = $r.targets[1] | ConvertTo-Json -Depth 8 | ConvertFrom-Json
        $variant.source.versionCode = '2024605949'
        $variant.source.forced = $true
        $r.targets = @($r.targets[0], $r.targets[1], $variant)
    })
    Assert-True $forcedOtherBuild.Valid "Another build of a declared version, forced beside the declared runs, was refused: $($forcedOtherBuild.Reason)"

    # The bundle the receipt is about, gone. Every fact above is checked against a file, and a
    # missing file is the one case where there is nothing to disagree with, so an unguarded
    # check would read it as agreement and pass the release.
    $absent = Test-ReleaseReceipt -Receipt (New-TestReceipt) -ExpectedVersion '9.9.9' `
        -ExpectedPatchNames @('Alpha', 'Beta') -ExpectedPatcherVersion '1.12.0' `
        -ExpectedManagerFloor '1.29.0' -ExpectedPackageName 'com.example.host' -ExpectedPackageVersions $declaredBuilds -BundlePath (Join-Path $allowlistRoot 'not-built.mpp')
    Assert-True (-not $absent.Valid) 'A receipt was accepted against a bundle that is not there.'
    Assert-True ($absent.Reason -like '*not there*') `
        "The missing bundle was refused for the wrong reason: $($absent.Reason)"

    # The bundle itself disagreeing with the receipt, which is the v0.28.0 failure: a bundle
    # built before its release commit existed carries the previous commit's pin, and nobody can
    # reproduce the published hash from the tag.
    $strayBundle = Join-Path $allowlistRoot 'patches-stray.mpp'
    New-TestBundle -Path $strayBundle -Timestamp 1699999999000L
    $strayReceipt = New-TestReceipt -Mutate {
        param($r)
        $r.bundle.sizeBytes = (Get-Item -LiteralPath $strayBundle).Length
        $r.bundle.sha256 = Get-Sha256Hex -Path $strayBundle
        $r.bundle.timestamp = 1699999999000L
    }
    $strayResult = Test-ReleaseReceipt -Receipt $strayReceipt -ExpectedVersion '9.9.9' `
        -ExpectedPatchNames @('Alpha', 'Beta') -ExpectedPatcherVersion '1.12.0' `
        -ExpectedManagerFloor '1.29.0' -ExpectedPackageName 'com.example.host' -ExpectedPackageVersions $declaredBuilds -BundlePath $strayBundle
    Assert-True (-not $strayResult.Valid) 'A bundle built from another commit was accepted.'
    Assert-True ($strayResult.Reason -like '*different*commit*') `
        "The stale bundle pin was refused for the wrong reason: $($strayResult.Reason)"

    foreach ($wrong in @(
        @{ Name = 'a bundle stamped with another version'; Version = '9.9.8'; Patcher = '1.12.0'
            Pattern = '*manifest says version*' },
        @{ Name = 'a bundle stamped by another patcher'; Version = '9.9.9'; Patcher = '1.13.0'
            Pattern = '*stamped by patcher*' })) {
        $odd = Join-Path $allowlistRoot 'patches-odd.mpp'
        New-TestBundle -Path $odd -Version $wrong.Version -Patcher $wrong.Patcher
        $oddReceipt = New-TestReceipt -Mutate {
            param($r)
            $r.bundle.sizeBytes = (Get-Item -LiteralPath $odd).Length
            $r.bundle.sha256 = Get-Sha256Hex -Path $odd
        }
        $oddResult = Test-ReleaseReceipt -Receipt $oddReceipt -ExpectedVersion '9.9.9' `
            -ExpectedPatchNames @('Alpha', 'Beta') -ExpectedPatcherVersion '1.12.0' `
            -ExpectedManagerFloor '1.29.0' -ExpectedPackageName 'com.example.host' -ExpectedPackageVersions $declaredBuilds -BundlePath $odd
        Assert-True (-not $oddResult.Valid) "Receipt validation accepted $($wrong.Name)."
        Assert-True ($oddResult.Reason -like $wrong.Pattern) `
            "$($wrong.Name) was refused for the wrong reason: $($oddResult.Reason)"
    }

    # The commit the receipt names, checked against something outside the receipt. Its own
    # timestamp field and the bundle stamp both come from the same document, so a receipt kept
    # from an earlier release agrees with itself and passes on that pair alone.
    $sameCommit = Test-ReleaseReceipt -Receipt (New-TestReceipt) -ExpectedVersion '9.9.9' `
        -ExpectedPatchNames @('Alpha', 'Beta') -ExpectedPatcherVersion '1.12.0' `
        -ExpectedManagerFloor '1.29.0' -ExpectedPackageName 'com.example.host' -ExpectedPackageVersions $declaredBuilds -BundlePath $bundle `
        -ActualCommitTimestamp $commitSeconds
    Assert-True $sameCommit.Valid "A receipt matching git was refused: $($sameCommit.Reason)"

    $movedCommit = Test-ReleaseReceipt -Receipt (New-TestReceipt) -ExpectedVersion '9.9.9' `
        -ExpectedPatchNames @('Alpha', 'Beta') -ExpectedPatcherVersion '1.12.0' `
        -ExpectedManagerFloor '1.29.0' -ExpectedPackageName 'com.example.host' -ExpectedPackageVersions $declaredBuilds -BundlePath $bundle `
        -ActualCommitTimestamp ($commitSeconds + 60)
    Assert-True (-not $movedCommit.Valid) `
        'A receipt whose commit time git disagrees with was accepted.'
    Assert-True ($movedCommit.Reason -like '*git says*') `
        "The stale receipt was refused for the wrong reason: $($movedCommit.Reason)"

    $otherCommit = Test-ReleaseReceipt -Receipt (New-TestReceipt) -ExpectedVersion '9.9.9' `
        -ExpectedPatchNames @('Alpha', 'Beta') -ExpectedPatcherVersion '1.12.0' `
        -ExpectedManagerFloor '1.29.0' -ExpectedPackageName 'com.example.host' -ExpectedPackageVersions $declaredBuilds -BundlePath $bundle `
        -ExpectedCommit ('f' * 40)
    Assert-True (-not $otherCommit.Valid) 'A receipt for another commit was accepted on a release.'
    Assert-True ($otherCommit.Reason -like '*this release is*') `
        "The wrong-commit receipt was refused for the wrong reason: $($otherCommit.Reason)"

    # A truncated document, which is the shape @($null) turns into one null entry.
    foreach ($missing in @('targets', 'dexPayloads')) {
        $truncated = $templateJson | ConvertFrom-Json
        if ($missing -eq 'targets') {
            $truncated.PSObject.Properties.Remove('targets')
        } else {
            $truncated.extension.PSObject.Properties.Remove('dexPayloads')
        }
        $result = Test-TestReceipt -Receipt $truncated
        Assert-True (-not $result.Valid) "A receipt with no $missing was accepted."
        Assert-True ($result.Reason -like '*no target*' -or $result.Reason -like '*no extension*') `
            "A receipt with no $missing was refused for the wrong reason: $($result.Reason)"
    }

    $withDelta = New-TestReceipt -Mutate {
        param($r) $r.targets[0].manifestDelta.permissionsAdded = @('android.permission.VIBRATE')
    }
    $unreviewed = Test-TestReceipt -Receipt $withDelta
    Assert-True (-not $unreviewed.Valid) 'An unreviewed manifest change was accepted.'
    Assert-True ($unreviewed.Reason -like '*nobody reviewed*') `
        "The unreviewed manifest change was refused for the wrong reason: $($unreviewed.Reason)"

    $reviewed = Test-TestReceipt -Receipt $withDelta -Approved @('permission-added android.permission.VIBRATE')
    Assert-True $reviewed.Valid "A reviewed manifest change was refused: $($reviewed.Reason)"

    # The shape a real run hands over, rather than a literal @(). An allowlist file with no
    # entries reaches the validator as $null, and treating that null as an approved entry failed
    # every clean run with an empty list of changes nobody could read.
    $emptyFromFile = Join-Path $allowlistRoot 'empty.txt'
    Set-Content -LiteralPath $emptyFromFile -Encoding UTF8 -Value @('# nothing approved', '')
    $fromFile = Read-ManifestDeltaAllowlist -Path $emptyFromFile
    $clean = Test-TestReceipt -Receipt (New-TestReceipt) -Approved $fromFile
    Assert-True $clean.Valid `
        "A receipt with no manifest change failed against an empty allowlist: $($clean.Reason)"
    $cleanNull = Test-TestReceipt -Receipt (New-TestReceipt) -Approved $null
    Assert-True $cleanNull.Valid `
        "A receipt with no manifest change failed against a null allowlist: $($cleanNull.Reason)"

    $stale = Test-TestReceipt -Receipt (New-TestReceipt) -Approved @('permission-added android.permission.VIBRATE')
    Assert-True (-not $stale.Valid) 'An allowlist entry no patch produces was accepted.'
    Assert-True ($stale.Reason -like '*any more*') `
        "The stale allowlist entry was refused for the wrong reason: $($stale.Reason)"

    # The checked-in allowlist, against receipts that carry its four reviewed changes on both
    # builds: the permission rename, the share target export, the microG push permission and Clone
    # install's moved permissions. They pass. They plus any other change are refused, naming only
    # the other, and a rename that stopped halfway is refused for the half no patch makes any more.
    $withRename = {
        param($r)
        foreach ($target in $r.targets) {
            $target.manifestDelta.permissionsAdded = @('app.hushfacebook.permission.prod.FB_APP_COMMUNICATION',
                'app.hushfacebook.receiver.permission.ACCESS', 'app.revanced.android.c2dm.permission.RECEIVE') +
                $clonePermissionsAdded
            $target.manifestDelta.permissionsRemoved = @('com.facebook.permission.prod.FB_APP_COMMUNICATION',
                'com.facebook.receiver.permission.ACCESS', 'com.google.android.c2dm.permission.RECEIVE') +
                $clonePermissionsRemoved
            $target.manifestDelta.exportedComponentsAdded = @('activity:app.morphe.extension.facebook.misc.OpenInFacebook')
        }
    }
    $renamed = Test-TestReceipt -Receipt (New-TestReceipt -Mutate $withRename) -Approved $checkedInAllowlist
    Assert-True $renamed.Valid "A receipt carrying the approved manifest changes was refused: $($renamed.Reason)"
    $unrenamed = Test-TestReceipt -Receipt (New-TestReceipt) -Approved $checkedInAllowlist
    Assert-True (-not $unrenamed.Valid -and $unrenamed.Reason -like '*any more*') `
        "A receipt without the rename passed the allowlist that approves it: $($unrenamed.Reason)"
    foreach ($other in @(
            @{ Name = 'another permission asked for'; Entry = 'permission-added android.permission.READ_SMS'
                Change = { param($t) $t.manifestDelta.permissionsAdded = @($t.manifestDelta.permissionsAdded) + 'android.permission.READ_SMS' } },
            @{ Name = 'a third renamed permission'; Entry = 'permission-added app.hushfacebook.permission.prod.OTHER'
                Change = { param($t) $t.manifestDelta.permissionsAdded = @($t.manifestDelta.permissionsAdded) + 'app.hushfacebook.permission.prod.OTHER' } },
            @{ Name = 'another permission dropped'; Entry = 'permission-removed android.permission.CAMERA'
                Change = { param($t) $t.manifestDelta.permissionsRemoved = @($t.manifestDelta.permissionsRemoved) + 'android.permission.CAMERA' } },
            @{ Name = 'a component exported'; Entry = 'exported-added receiver:com.facebook.device_id.UniqueIdSupplier'
                Change = { param($t) $t.manifestDelta.exportedComponentsAdded = @($t.manifestDelta.exportedComponentsAdded) + 'receiver:com.facebook.device_id.UniqueIdSupplier' } },
            @{ Name = 'a component no longer exported'; Entry = 'exported-removed activity:com.facebook.katana.ProxyAuth'
                Change = { param($t) $t.manifestDelta.exportedComponentsRemoved = @('activity:com.facebook.katana.ProxyAuth') } })) {
        $receipt = New-TestReceipt -Mutate { param($r) & $withRename $r; & $other.Change $r.targets[1] }
        $result = Test-TestReceipt -Receipt $receipt -Approved $checkedInAllowlist
        Assert-True (-not $result.Valid) "With the permission rename approved, $($other.Name) was accepted."
        Assert-True ($result.Reason -like "*nobody reviewed: $($other.Entry)") `
            "With the permission rename approved, $($other.Name) was refused for the wrong reason: $($result.Reason)"
    }
    $halfway = New-TestReceipt -Mutate {
        param($r)
        & $withRename $r
        foreach ($target in $r.targets) {
            $target.manifestDelta.permissionsAdded = @('app.hushfacebook.permission.prod.FB_APP_COMMUNICATION',
                'app.revanced.android.c2dm.permission.RECEIVE') + $clonePermissionsAdded
            $target.manifestDelta.permissionsRemoved = @('com.facebook.permission.prod.FB_APP_COMMUNICATION',
                'com.google.android.c2dm.permission.RECEIVE') + $clonePermissionsRemoved
        }
    }
    $half = Test-TestReceipt -Receipt $halfway -Approved $checkedInAllowlist
    Assert-True (-not $half.Valid -and $half.Reason -like '*any more*receiver.permission.ACCESS*' -and
            $half.Reason -notlike '*c2dm*' -and $half.Reason -notlike '*OpenInFacebook*' -and
            $half.Reason -notlike '*katana.hush*') `
        "A rename of one permission of the two passed the allowlist of both: $($half.Reason)"

    # The SBOM a receipt names. Each refusal has to name the SBOM fact that failed rather than trip
    # over the next field, or a check that went missing would pass unseen behind the one after it.
    foreach ($named in @(
            @{ Name = 'a receipt that names no SBOM'; Pattern = '*names no SBOM*' },
            @{ Name = 'an SBOM named for another version'; Pattern = '*names the SBOM patches-9.9.8.cdx.json; the one for 9.9.9 is patches-9.9.9.cdx.json*' },
            @{ Name = 'an SBOM with no hash'; Pattern = '*no SHA-256 for patches-9.9.9.cdx.json*' },
            @{ Name = 'an SBOM hash in lower case'; Pattern = '*no SHA-256 for patches-9.9.9.cdx.json*' },
            @{ Name = 'an SBOM counting no component'; Pattern = '*counts no component in patches-9.9.9.cdx.json*' })) {
        $result = Test-TestReceipt -Receipt (New-TestReceipt -Mutate $mutations[$named.Name])
        Assert-True ($result.Reason -like $named.Pattern) "$($named.Name) was refused for the wrong reason: $($result.Reason)"
    }

    # Read back, the SBOM gives what it was written with, and it describes the bundle beside it.
    $sbomRead = Read-ReleaseSbom -Path $sbomFile
    Assert-True ($sbomRead.BundleName -eq 'patches-9.9.9.mpp' -and $sbomRead.BundleVersion -eq '9.9.9' -and
        $sbomRead.BundleSha256 -ceq $bundleHash.ToLowerInvariant() -and $sbomRead.Timestamp -eq '2023-11-14T22:13:20Z' -and
        @($sbomRead.Components).Count -eq 3 -and @($sbomRead.Libraries).Count -eq 1 -and
        $sbomRead.Libraries[0].Purl -eq 'pkg:maven/com.google.code.gson/gson@2.14.0' -and $sbomRead.Libraries[0].Name -eq 'gson' -and
        @($sbomRead.Payloads).Count -eq 1 -and $sbomRead.Payloads[0].Ref -eq 'extensions/facebook.mpe' -and
        $sbomRead.Sha256 -eq (Get-Sha256Hex -Path $sbomFile)) `
        "The SBOM was misread: $($sbomRead.BundleName) $($sbomRead.BundleVersion) $($sbomRead.Timestamp), $(@($sbomRead.Components).Count) components"
    $bound = Test-ReleaseSbom -Sbom $sbomRead -BundlePath $bundle -BundleName 'patches-9.9.9.mpp'
    Assert-True $bound.Valid "An SBOM of the bundle was refused: $($bound.Reason)"

    # The receipt against the SBOM file itself: its hash and its count, and through it the bundle.
    function Test-ReceiptWithSbom($Receipt, [string]$Sbom = $sbomFile, [int]$Schema = (Get-ReleaseReceiptSchemaVersion)) {
        return Test-ReleaseReceipt -Receipt $Receipt -ExpectedVersion '9.9.9' -ExpectedPatchNames @('Alpha', 'Beta') `
            -ExpectedPatcherVersion '1.12.0' -ExpectedManagerFloor '1.29.0' -ExpectedPackageName 'com.example.host' `
            -ExpectedPackageVersions $declaredBuilds -BundlePath $bundle -SbomPath $Sbom -ExpectedSchemaVersion $Schema
    }
    $withSbom = Test-ReceiptWithSbom (New-TestReceipt)
    Assert-True $withSbom.Valid "A receipt was refused against the SBOM it names: $($withSbom.Reason)"
    foreach ($wrong in @(
            @{ Name = 'an SBOM hash that is not the SBOM'; Mutate = { param($r) $r.sbom.sha256 = ('C' * 64) }; Pattern = '*says patches-9.9.9.cdx.json hashes to CCCC*' },
            @{ Name = 'a count that is not the SBOM''s'; Mutate = { param($r) $r.sbom.components = 4 }; Pattern = '*counts 4 components in patches-9.9.9.cdx.json; it lists 3*' })) {
        $result = Test-ReceiptWithSbom (New-TestReceipt -Mutate $wrong.Mutate)
        Assert-True (-not $result.Valid -and $result.Reason -like $wrong.Pattern) "A receipt with $($wrong.Name) was not refused for it: $($result.Reason)"
    }
    $missingSbom = Test-ReceiptWithSbom (New-TestReceipt) -Sbom (Join-Path $allowlistRoot 'absent.cdx.json')
    Assert-True ($missingSbom.Reason -like '*an SBOM that is not there*') "A receipt was checked against an SBOM that isn't there: $($missingSbom.Reason)"
    # An SBOM that isn't this bundle's, each way it can differ, with the receipt recording its hash
    # so that the difference is what refuses it.
    $variant = Join-Path $allowlistRoot 'variant\patches-9.9.9.cdx.json'
    New-Item -ItemType Directory -Path (Split-Path -Parent $variant) -Force | Out-Null
    foreach ($other in @(
            @{ Name = 'another bundle''s hash'; Mutate = { param($d) $d.metadata.component.hashes[0].content = ('0' * 64) }
                Pattern = '*describes a patches-9.9.9.mpp that hashes to 0000*written for another build*' },
            @{ Name = 'a date from the clock'; Mutate = { param($d) $d.metadata.timestamp = '2026-09-25T10:00:00Z' }
                Pattern = '*is dated 2026-09-25T10:00:00Z, and patches-9.9.9.mpp is stamped 2023-11-14T22:13:20Z*' },
            @{ Name = 'another version'; Mutate = { param($d) $d.metadata.component.version = '9.9.8' }; Pattern = '*says patches-9.9.9.mpp is version 9.9.8*' },
            @{ Name = 'another bundle''s name'; Mutate = { param($d) $d.metadata.component.name = 'patches-9.9.8.mpp' }; Pattern = '*describes patches-9.9.8.mpp, not patches-9.9.9.mpp*' },
            @{ Name = 'no payload'; Mutate = { param($d) $d.components = @($d.components | Where-Object { $_.type -ne 'file' }) }
                Pattern = '*carries extensions/facebook.mpe, which patches-9.9.9.cdx.json doesn''t describe*' },
            @{ Name = 'another payload hash'; Mutate = { param($d) @($d.components | Where-Object { $_.type -eq 'file' })[0].hashes[0].content = ('1' * 64) }
                Pattern = '*describes extensions/facebook.mpe hashing to 1111*' },
            @{ Name = 'a payload the bundle lacks'; Mutate = { param($d) $d.components = @($d.components) + @([ordered]@{ type = 'file'; 'bom-ref' = 'extensions/shared.mpe'
                    name = 'extensions/shared.mpe'; version = '9.9.9'; scope = 'required'; hashes = @([ordered]@{ alg = 'SHA-256'; content = ('2' * 64) }) }) }
                Pattern = '*describes extensions/shared.mpe, which patches-9.9.9.mpp doesn''t carry*' })) {
        New-TestSbom -Path $variant -Bundle $bundle -Mutate $other.Mutate
        $variantCount = @((Get-Content -LiteralPath $variant -Raw | ConvertFrom-Json).components).Count
        $result = Test-ReceiptWithSbom (New-TestReceipt -Mutate {
            param($r) $r.sbom.sha256 = Get-Sha256Hex -Path $variant; $r.sbom.components = $variantCount }) -Sbom $variant
        Assert-True (-not $result.Valid -and $result.Reason -like $other.Pattern) `
            "An SBOM with $($other.Name) was taken for the bundle's: $($result.Reason)"
    }

    # The SBOM's own shape. OSV answers {} for a package URL it can't read, so a library that isn't
    # asked about under its own group, name and version would read as having no advisory.
    foreach ($broken in @(
            @{ Name = 'another format'; Mutate = { param($d) $d.bomFormat = 'SPDX' }; Pattern = '*is not a CycloneDX 1.6 SBOM*' },
            @{ Name = 'another spec version'; Mutate = { param($d) $d.specVersion = '1.5' }; Pattern = '*is not a CycloneDX 1.6 SBOM*' },
            @{ Name = 'no bundle hash'; Mutate = { param($d) $d.metadata.component.Remove('hashes') }; Pattern = '*does not name the bundle it describes*' },
            @{ Name = 'a package URL for another version'; Mutate = { param($d) $d.components[0].purl = 'pkg:maven/com.google.code.gson/gson@2.8.8' }
                Pattern = '*with the package URL pkg:maven/com.google.code.gson/gson@2.8.8, which doesn''t name its own group, name and version*' },
            @{ Name = 'a package URL with no group'; Mutate = { param($d) $d.components[0].purl = 'pkg:maven/gson@2.14.0' }
                Pattern = '*with the package URL pkg:maven/gson@2.14.0, which doesn''t name*' },
            @{ Name = 'a package URL for another group'; Mutate = { param($d) $d.components[0].purl = 'pkg:maven/com.google.gson/gson@2.14.0' }
                Pattern = '*with the package URL pkg:maven/com.google.gson/gson@2.14.0, which doesn''t name*' },
            @{ Name = 'a package URL for another name'; Mutate = { param($d) $d.components[0].purl = 'pkg:maven/com.google.code.gson/gson-extras@2.14.0' }
                Pattern = '*with the package URL pkg:maven/com.google.code.gson/gson-extras@2.14.0, which doesn''t name*' },
            @{ Name = 'something that is not a package URL, beside empty fields'
                Mutate = { param($d) $d.components[0].purl = 'not-a-package-url'; $d.components[0].group = ''; $d.components[0].name = ''; $d.components[0].version = '' }
                Pattern = '*with the package URL not-a-package-url, which doesn''t name*' },
            @{ Name = 'a library with no package URL'; Mutate = { param($d) $d.components[0].Remove('purl') }; Pattern = '*the library pkg:maven/com.google.code.gson/gson@2.14.0 with no package URL*' },
            @{ Name = 'the same component twice'; Mutate = { param($d) $d.components = @($d.components) + @($d.components[0]) }; Pattern = '*lists pkg:maven/com.google.code.gson/gson@2.14.0 twice*' },
            @{ Name = 'a component of another type'; Mutate = { param($d) $d.components[0].type = 'framework' }; Pattern = '*as a framework, which a release SBOM doesn''t hold*' },
            @{ Name = 'a payload with no hash'; Mutate = { param($d) @($d.components | Where-Object { $_.type -eq 'file' })[0].Remove('hashes') }; Pattern = '*the file extensions/facebook.mpe with no SHA-256*' },
            @{ Name = 'no component'; Mutate = { param($d) $d.components = @() }; Pattern = '*lists no component*' })) {
        New-TestSbom -Path $variant -Bundle $bundle -Mutate $broken.Mutate
        Assert-Throws { Read-ReleaseSbom -Path $variant } $broken.Pattern "An SBOM with $($broken.Name) was read without complaint."
    }
    Set-Content -LiteralPath $variant -Encoding ASCII -Value 'not json'
    Assert-Throws { Read-ReleaseSbom -Path $variant } '*patches-9.9.9.cdx.json is not JSON*' 'Text that is not JSON was read as an SBOM.'

    # Tooling graph validation is independent of the payload SBOM and binds to source coverage.
    $scopeText = "required settings/buildscript/classpath`nrequired :patches/configuration/testRuntimeClasspath`n"
    $toolingRead = Read-ReleaseTooling -Path $toolingFile
    $bound = Test-ReleaseTooling -Tooling $toolingRead -BundlePath $bundle -ExpectedCommit $template.release.commit -ScopeManifestText $scopeText
    Assert-True ($bound.Valid -and $toolingRead.Scopes.Count -eq 2 -and $toolingRead.Components.Count -eq 1) `
        "The source-bound tooling graph was refused: $($bound.Reason)"
    $downloadedBundle = Join-Path $allowlistRoot 'temporary-download.mpp'
    Copy-Item -LiteralPath $bundle -Destination $downloadedBundle
    $downloaded = Test-ReleaseTooling -Tooling $toolingRead -BundlePath $downloadedBundle -BundleName 'patches-9.9.9.mpp' `
        -ExpectedCommit $template.release.commit -ScopeManifestText $scopeText
    Assert-True $downloaded.Valid "A byte-identical temporary download lost its declared release name: $($downloaded.Reason)"
    function Test-ReceiptWithTooling($Receipt, [switch]$Publish) {
        return Test-ReleaseReceipt -Receipt $Receipt -ExpectedVersion '9.9.9' -ExpectedPatchNames @('Alpha', 'Beta') `
            -ExpectedPatcherVersion '1.12.0' -ExpectedManagerFloor '1.29.0' -ExpectedPackageName 'com.example.host' `
            -ExpectedPackageVersions $declaredBuilds -BundlePath $bundle -ToolingPath $toolingFile `
            -ToolingScopeManifestText $scopeText -RequireToolingAudit:$Publish
    }
    $boundReceipt = Test-ReceiptWithTooling (New-TestReceipt) -Publish
    Assert-True $boundReceipt.Valid "The receipt was refused against its audited tooling report: $($boundReceipt.Reason)"
    foreach ($case in @(
            @{ Name = 'a changed audit report'; Mutate = { param($r) $r.tooling.sha256 = ('c' * 64) }; Pattern = '*changed after its advisory audit*' },
            @{ Name = 'the wrong component count'; Mutate = { param($r) $r.tooling.components = 2 }; Pattern = '*counts differ*' },
            @{ Name = 'the wrong scope count'; Mutate = { param($r) $r.tooling.scopes = 1 }; Pattern = '*counts differ*' },
            @{ Name = 'a skipped audit at publication'; Mutate = { param($r) $r.tooling.audit.status = 'skipped' }; Pattern = '*skipped tooling advisory audit cannot authorize publication*' })) {
        $result = Test-ReceiptWithTooling (New-TestReceipt -Mutate $case.Mutate) -Publish
        Assert-True (-not $result.Valid -and $result.Reason -like $case.Pattern) "Tooling $($case.Name) was not refused for it: $($result.Reason)"
    }
    $offlineReceipt = Test-ReceiptWithTooling (New-TestReceipt -Mutate { param($r) $r.tooling.audit.status = 'skipped' })
    Assert-True $offlineReceipt.Valid 'An explicitly offline local receipt was refused before publication.'
    $variantTooling = Join-Path $allowlistRoot 'variant/patches-9.9.9.tooling.json'
    New-Item -ItemType Directory -Path (Split-Path -Parent $variantTooling) -Force | Out-Null
    foreach ($broken in @(
            @{ Name = 'a duplicate scope'; Mutate = { param($d) $d.scopes += $d.scopes[0] }; Pattern = '*duplicate tooling scope*' },
            @{ Name = 'a missing declared scope'; Mutate = { param($d) $d.scopes = @($d.scopes[1]); $d.components[0].scopes = @($d.scopes[0].id) }; Pattern = '*missing or unresolved declared scope*' },
            @{ Name = 'an unresolved edge'; Mutate = { param($d) $d.scopes[0].edges[0].to = @('absent:module:1') }; Pattern = '*unresolved or duplicate graph edge*' },
            @{ Name = 'a disconnected graph'; Mutate = { param($d) $d.scopes[0].edges[0].to = @() }; Pattern = '*unreachable from its root*' },
            @{ Name = 'a missing coordinate'; Mutate = { param($d) $d.components = @() }; Pattern = '*omits resolved module coordinates*' },
            @{ Name = 'a mismatched package URL'; Mutate = { param($d) $d.components[0].purl = 'pkg:maven/com.example/other@1' }; Pattern = '*does not match its Maven package URL*' },
            @{ Name = 'a missing carrier'; Mutate = { param($d) $d.components[0].scopes = @($d.scopes[0].id) }; Pattern = '*omits one of its resolved scopes*' },
            @{ Name = 'a local artifact path'; Mutate = { param($d) $d.components[0].artifacts[0].name = 'private/account.jar' }; Pattern = '*invalid artifact name or hash*' })) {
        New-TestTooling -Path $variantTooling -Bundle $bundle -Mutate $broken.Mutate
        Assert-Throws { Read-ReleaseTooling -Path $variantTooling } $broken.Pattern "Tooling $($broken.Name) was accepted."
    }
    foreach ($wrong in @(
            @{ Name = 'dirty source'; Mutate = { param($d) $d.source.tree = 'dirty' }; Pattern = '*clean source commit*' },
            @{ Name = 'another commit'; Mutate = { param($d) $d.source.commit = ('b' * 40) }; Pattern = '*clean source commit*' },
            @{ Name = 'another bundle'; Mutate = { param($d) $d.bundle.sha256 = ('b' * 64) }; Pattern = '*does not describe this bundle*' },
            @{ Name = 'another scope manifest'; Mutate = { param($d) $d.scopeManifest.sha256 = ('b' * 64) }; Pattern = '*scope manifest differs*' },
            @{ Name = 'omitted coverage declarations'; Mutate = { param($d) $d.scopeManifest.entries = @($d.scopeManifest.entries[0]) }; Pattern = '*omits or adds scope declarations*' })) {
        New-TestTooling -Path $variantTooling -Bundle $bundle -Mutate $wrong.Mutate
        $result = Test-ReleaseTooling -Tooling (Read-ReleaseTooling $variantTooling) -BundlePath $bundle `
            -ExpectedCommit $template.release.commit -ScopeManifestText $scopeText
        Assert-True (-not $result.Valid -and $result.Reason -like $wrong.Pattern) "Tooling $($wrong.Name) was not refused for it: $($result.Reason)"
    }
    $conditionalText = $scopeText + "conditional :extensions:facebook/configuration/releaseUnitTestRuntimeClasspath testReleaseUnitTest`n"
    New-TestTooling -Path $variantTooling -Bundle $bundle -ScopeText $conditionalText
    $conditional = Read-ReleaseTooling $variantTooling
    $result = Test-ReleaseTooling -Tooling $conditional -BundlePath $bundle -ExpectedCommit $template.release.commit -ScopeManifestText $conditionalText
    Assert-True $result.Valid "An explicitly absent test component was refused: $($result.Reason)"
    New-TestTooling -Path $variantTooling -Bundle $bundle -ScopeText $conditionalText -Mutate { param($d) $d.scopes[2].reason = 'absent' }
    $result = Test-ReleaseTooling -Tooling (Read-ReleaseTooling $variantTooling) -BundlePath $bundle `
        -ExpectedCommit $template.release.commit -ScopeManifestText $conditionalText
    Assert-True ($result.Reason -like '*does not identify the absent test component*') 'An unexplained absent test scope passed.'
    $schemaTwo = New-TestReceipt -Mutate { param($r) $r.schemaVersion = 2; $r.PSObject.Properties.Remove('tooling') }
    $historical = Test-ReceiptWithSbom $schemaTwo -Schema 2
    Assert-True $historical.Valid "A historical payload-only schema 2 receipt was refused: $($historical.Reason)"

    # A receipt cut before schema 2 names no SBOM, and is read as its own commit wrote it; each
    # schema is refused where the other is expected.
    $schemaOne = New-TestReceipt -Mutate { param($r) $r.schemaVersion = 1; $r.PSObject.Properties.Remove('sbom'); $r.PSObject.Properties.Remove('tooling') }
    $oneAtOne = Test-ReleaseReceipt -Receipt $schemaOne -ExpectedVersion '9.9.9' -ExpectedPatchNames @('Alpha', 'Beta') `
        -ExpectedPatcherVersion '1.12.0' -ExpectedManagerFloor '1.29.0' -ExpectedPackageName 'com.example.host' `
        -ExpectedPackageVersions $declaredBuilds -BundlePath $bundle -ExpectedSchemaVersion 1
    Assert-True $oneAtOne.Valid "A schema 1 receipt was refused at schema 1: $($oneAtOne.Reason)"
    $oneAtTwo = Test-TestReceipt -Receipt $schemaOne
    Assert-True ($oneAtTwo.Reason -like '*schema version 1; its release is read at version 3*') `
        "A schema 1 receipt was not refused where schema 3 is expected: $($oneAtTwo.Reason)"
    $twoAtOne = Test-ReceiptWithSbom (New-TestReceipt) -Schema 1
    Assert-True ($twoAtOne.Reason -like '*schema version 3; its release is read at version 1*') `
        "A schema 3 receipt was not refused where schema 1 is expected: $($twoAtOne.Reason)"
    $oneWithSbom = Test-ReceiptWithSbom $schemaOne -Schema 1
    Assert-True ($oneWithSbom.Reason -like '*schema 1 receipt names no SBOM to hold*') `
        "A schema 1 receipt was held to an SBOM it can't name: $($oneWithSbom.Reason)"

    # What the publish stage holds its copy of the staged release to before it tags anything: the
    # four assets, SHA256SUMS.txt over them, and the receipt over the other three. A staged file
    # changed on its own is refused by SHA256SUMS.txt, and changed with SHA256SUMS.txt written over
    # again, by the receipt, so neither check can go missing behind the other.
    $stagedRoot = Join-Path $allowlistRoot 'staged'
    $stagedNames = @('patches-9.9.9.mpp', 'patches-9.9.9.cdx.json', 'patches-9.9.9.tooling.json', 'release-receipt-9.9.9.json')
    $stagedExpected = @{ ExpectedPatchNames = @('Alpha', 'Beta'); ExpectedPatcherVersion = '1.12.0'; ExpectedManagerFloor = '1.29.0'
        ExpectedPackageName = 'com.example.host'; ExpectedPackageVersions = $declaredBuilds; ExpectedPackageVersionCodes = $declaredCodes
        ToolingScopeManifestText = $scopeText }
    function Get-StagedSums {
        @($stagedNames | ForEach-Object { '{0}  {1}' -f (Get-Sha256Hex -Path (Join-Path $stagedRoot $_)).ToLowerInvariant(), $_ })
    }
    function Write-StagedSums([string[]]$Lines = (Get-StagedSums)) {
        [IO.File]::WriteAllText((Join-Path $stagedRoot 'SHA256SUMS.txt'), (($Lines -join "`n") + "`n"))
    }
    function New-StagedRelease {
        Remove-Item -LiteralPath $stagedRoot -Recurse -Force -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Path $stagedRoot -Force | Out-Null
        Copy-Item -LiteralPath $bundle, $sbomFile, $toolingFile -Destination $stagedRoot
        Set-Content -LiteralPath (Join-Path $stagedRoot 'release-receipt-9.9.9.json') -Encoding UTF8 -Value ((New-TestReceipt) | ConvertTo-Json -Depth 12)
        Write-StagedSums
    }
    function Test-Staged { Test-StagedRelease -Folder $stagedRoot -Version '9.9.9' -Expected $stagedExpected }
    New-StagedRelease
    $stagedResult = Test-Staged
    Assert-True $stagedResult.Valid "A complete staged release was refused: $($stagedResult.Reason)"
    $stagedBound = @{
        'patches-9.9.9.mpp' = '*The receipt says the bundle is*bytes*'
        'patches-9.9.9.cdx.json' = '*The receipt says patches-9.9.9.cdx.json hashes to*'
        'patches-9.9.9.tooling.json' = '*tooling report changed after its advisory audit*'
        'release-receipt-9.9.9.json' = '*The receipt says the bundle hashes to CCCC*' }
    foreach ($name in $stagedNames) {
        foreach ($resummed in @($false, $true)) {
            New-StagedRelease
            $path = Join-Path $stagedRoot $name
            if ($name -like 'release-receipt-*') {
                Set-Content -LiteralPath $path -Encoding UTF8 -Value ((New-TestReceipt -Mutate { param($r) $r.bundle.sha256 = ('C' * 64) }) | ConvertTo-Json -Depth 12)
            } else {
                [IO.File]::WriteAllBytes($path, [byte[]]([IO.File]::ReadAllBytes($path) + [byte]10))
            }
            if ($resummed) { Write-StagedSums }
            $result = Test-Staged
            $pattern = if ($resummed) { $stagedBound[$name] } else { "*SHA256SUMS.txt lists * for $name, but the staged $name hashes to*" }
            Assert-True (-not $result.Valid -and $result.Reason -like $pattern) `
                "A staged $name changed$(if ($resummed) { ' with SHA256SUMS.txt over it' }) was not refused for it: $($result.Reason)"
        }
    }
    foreach ($sums in @(
            @{ Name = 'an asset left out'; Lines = { @(Get-StagedSums)[0..2] }; Pattern = '*has no entry for release-receipt-9.9.9.json*' },
            @{ Name = 'a file that is no asset'; Lines = { @(Get-StagedSums) + "$('0' * 64)  notes.md" }; Pattern = "*lists notes.md, which isn't one of the release's assets*" },
            @{ Name = 'an asset twice'; Lines = { @(Get-StagedSums) + @(Get-StagedSums)[0] }; Pattern = '*lists patches-9.9.9.mpp twice*' },
            @{ Name = 'another hash'; Lines = { @("$('1' * 64)  patches-9.9.9.mpp") + @(Get-StagedSums)[1..3] }; Pattern = "*lists $('1' * 64) for patches-9.9.9.mpp*" },
            @{ Name = 'a line that is no checksum'; Lines = { @(Get-StagedSums) + 'see the release notes' }; Pattern = "*a line that isn't a checksum: see the release notes*" })) {
        New-StagedRelease
        Write-StagedSums (& $sums.Lines)
        $result = Test-Staged
        Assert-True (-not $result.Valid -and $result.Reason -like $sums.Pattern) "SHA256SUMS.txt with $($sums.Name) was not refused for it: $($result.Reason)"
    }
    New-StagedRelease
    Remove-Item -LiteralPath (Join-Path $stagedRoot 'patches-9.9.9.cdx.json') -Force
    $result = Test-Staged
    Assert-True ($result.Reason -like '*staged release has no patches-9.9.9.cdx.json*') "A staged release with no SBOM was not refused for it: $($result.Reason)"
    # A receipt naming another bundle file, with SHA256SUMS.txt over it, though its hash and size
    # are the staged bundle's: Test-ReleaseReceipt checks the bytes it's handed, not the name.
    New-StagedRelease
    Set-Content -LiteralPath (Join-Path $stagedRoot 'release-receipt-9.9.9.json') -Encoding UTF8 `
        -Value ((New-TestReceipt -Mutate { param($r) $r.bundle.file = 'patches-9.9.8.mpp' }) | ConvertTo-Json -Depth 12)
    Write-StagedSums
    $result = Test-Staged
    Assert-True ($result.Reason -like '*staged receipt names the bundle patches-9.9.8.mpp, not patches-9.9.9.mpp*') `
        "A staged receipt naming another bundle file was not refused for it: $($result.Reason)"
    # Hush Unpacker's APK, which the bundle stage stages and lists too, is held by its line alone.
    New-StagedRelease
    $stagedHelper = Join-Path $stagedRoot 'hush-unpacker-9.9.9.apk'
    Set-Content -LiteralPath $stagedHelper -Value 'apk' -Encoding ASCII
    Write-StagedSums (@(Get-StagedSums) + ('{0}  hush-unpacker-9.9.9.apk' -f (Get-Sha256Hex -Path $stagedHelper).ToLowerInvariant()))
    $result = Test-Staged
    Assert-True $result.Valid "A staged release listing Hush Unpacker's APK was refused: $($result.Reason)"
    Add-Content -LiteralPath $stagedHelper -Value 'changed'
    $result = Test-Staged
    Assert-True ($result.Reason -like '*lists * for hush-unpacker-9.9.9.apk, but the staged*') "A changed Hush Unpacker APK was not refused: $($result.Reason)"
    Remove-Item -LiteralPath $stagedHelper -Force
    $result = Test-Staged
    Assert-True ($result.Reason -like '*staged release has no hush-unpacker-9.9.9.apk*') "A listed Hush Unpacker APK that isn't staged was not refused: $($result.Reason)"
} finally {
    Remove-Item -LiteralPath $allowlistRoot -Recurse -Force -ErrorAction SilentlyContinue
}

# Which catalog a receipt is judged against. A receipt describes a release that has shipped, so
# moving the patcher pin afterwards must not turn it into a failure; a release, whose receipt is
# cut against the catalog it was built from, must still be held to that catalog exactly.
#
# Driven against a real two-commit repository, because the whole question is what `git show` says
# at a commit and a fake cannot answer that.
#
# Every git call below goes through Invoke-FixtureGit, and that is not tidiness. On 2026-09-15
# this block ran from inside the pre-push hook, which is a git child process, so GIT_DIR and
# GIT_WORK_TREE were in its environment. `git -C <tempdir>` changes the working directory and
# does not override GIT_DIR, so `init` reused the real repository, `add -A` read the two-file
# temp tree through it and staged every other tracked file as deleted, and three fixture commits
# authored by Contracts landed on the branch and were pushed to main, where the tip deleted all
# 703 files. Clearing the environment is the fix; the assertion after init is what would have
# stopped it in the second it happened.
$toolchainRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("hushfacebook-toolchain-" + [guid]::NewGuid().ToString('N'))
$toolchainRemote = "$toolchainRoot-published.git"

function Invoke-FixtureGit {
    <#
    .SYNOPSIS
        git against a fixture repository, with no inherited git environment.
    .DESCRIPTION
        Removes every GIT_* variable for the length of the call, so the repository git acts on is
        the one -C names and nothing else. Run from a hook, GIT_DIR alone is enough to point all
        of this at the real tree.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    $saved = @{}
    foreach ($variable in @(Get-ChildItem Env: | Where-Object { $_.Name -like 'GIT_*' })) {
        $saved[$variable.Name] = $variable.Value
        Remove-Item -LiteralPath ('Env:\' + $variable.Name) -ErrorAction SilentlyContinue
    }
    try {
        return & git -C $Root @Arguments 2>&1
    } finally {
        foreach ($name in $saved.Keys) { Set-Item -LiteralPath ('Env:\' + $name) -Value $saved[$name] }
    }
}

try {
    New-Item -ItemType Directory -Path (Join-Path $toolchainRoot 'gradle') -Force | Out-Null
    $catalogFile = Join-Path $toolchainRoot 'gradle/libs.versions.toml'
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('init', '--quiet') | Out-Null

    # Where git says it will write, asked before anything is written. A fixture that has taken
    # hold of the real repository fails here instead of committing to it.
    $fixtureGitDir = "$(Invoke-FixtureGit -Root $toolchainRoot -Arguments @('rev-parse', '--absolute-git-dir') |
        Select-Object -First 1)".Trim()
    $expectedGitDir = (Join-Path $toolchainRoot '.git')
    Assert-True ($fixtureGitDir -and
        ([IO.Path]::GetFullPath($fixtureGitDir).TrimEnd('\', '/') -ieq [IO.Path]::GetFullPath($expectedGitDir).TrimEnd('\', '/'))) `
        ("The fixture repository resolved to $fixtureGitDir, not $expectedGitDir. Refusing to " +
            'write: this is the shape that put three fixture commits on the real branch.')

    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('config', 'user.email', 'contracts@example.invalid') | Out-Null
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('config', 'user.name', 'Contracts') | Out-Null

    Set-Content -LiteralPath $catalogFile -Encoding UTF8 -Value @(
        '[versions]', 'morphe-patcher = "1.12.0"', 'manager-floor = "1.29.0"')
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('add', '-A') | Out-Null
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('commit', '-m', 'release', '--quiet') | Out-Null
    $releaseCommitSha = "$(Invoke-FixtureGit -Root $toolchainRoot -Arguments @('rev-parse', 'HEAD') |
        Select-Object -First 1)".Trim()

    # One file in the fixture, so one file in its first commit. A commit that carries hundreds is
    # a commit against somebody else's repository.
    $firstCommitFiles = @(Invoke-FixtureGit -Root $toolchainRoot `
        -Arguments @('show', '--name-only', '--format=', 'HEAD') | Where-Object { "$_".Trim() })
    Assert-True ($firstCommitFiles.Count -eq 1 -and "$($firstCommitFiles[0])".Trim() -eq 'gradle/libs.versions.toml') `
        ("The fixture's first commit touched $($firstCommitFiles.Count) files: " +
            (($firstCommitFiles | Select-Object -First 5) -join ', '))

    Set-Content -LiteralPath $catalogFile -Encoding UTF8 -Value @(
        '[versions]', 'morphe-patcher = "1.13.0"', 'manager-floor = "1.30.0"')
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('add', '-A') | Out-Null
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('commit', '-m', 'move the pin', '--quiet') | Out-Null

    $workingToolchain = Read-CatalogToolchain -Text (Get-Content -LiteralPath $catalogFile -Raw) `
        -Source 'the working catalog'
    Assert-True ($workingToolchain.PatcherVersion -eq '1.13.0' -and $workingToolchain.ManagerFloor -eq '1.30.0') `
        "The working catalog was not read: $($workingToolchain.PatcherVersion), $($workingToolchain.ManagerFloor)"

    # The source push after the pin moved: the receipt's own commit still pinned 1.12.0, so that
    # is what it answers for, and the push this used to stop now goes through.
    $atRelease = Resolve-ReceiptToolchain -Root $toolchainRoot -Commit $releaseCommitSha `
        -WorkingToolchain $workingToolchain
    Assert-True ($atRelease.Toolchain.PatcherVersion -eq '1.12.0') `
        "The receipt was not held to the patcher its own commit pinned: $($atRelease.Toolchain.PatcherVersion)"
    Assert-True ($atRelease.Toolchain.ManagerFloor -eq '1.29.0') `
        "The receipt was not held to the Manager floor its own commit pinned: $($atRelease.Toolchain.ManagerFloor)"
    Assert-True ($atRelease.Note -like '*1.12.0*' -and $atRelease.Note -like '*1.13.0*') `
        "The difference between the two catalogs was not reported: $($atRelease.Note)"

    # The release push: the receipt's commit is the commit being released, so the catalog it is
    # held to is the working one and the strict comparison is unchanged. Without this case the
    # one above would pass just as well if the check had been turned off.
    $head = "$(Invoke-FixtureGit -Root $toolchainRoot -Arguments @('rev-parse', 'HEAD') |
        Select-Object -First 1)".Trim()
    $atHead = Resolve-ReceiptToolchain -Root $toolchainRoot -Commit $head -WorkingToolchain $workingToolchain
    Assert-True ($atHead.Toolchain.PatcherVersion -eq '1.13.0' -and $atHead.Toolchain.ManagerFloor -eq '1.30.0') `
        "A receipt at the released commit was not held to that commit's catalog: $($atHead.Toolchain.PatcherVersion)"
    Assert-True ($null -eq $atHead.Note) "An unchanged catalog still reported a difference: $($atHead.Note)"

    # The floor the index description is held to: the published release's own, through its tag,
    # while the working catalog already pins the next one. An untagged version isn't guessed at.
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('tag', 'v0.0.9', $releaseCommitSha) | Out-Null
    $indexFloor = Resolve-IndexManagerFloor -Root $toolchainRoot -Version '0.0.9'
    Assert-True ($indexFloor.Floor -eq '1.29.0' -and $indexFloor.Source -eq 'tag v0.0.9') `
        "The index floor was not read at the release tag: $($indexFloor.Floor) from $($indexFloor.Source)"
    $untagged = Resolve-IndexManagerFloor -Root $toolchainRoot -Version '0.0.10'
    Assert-True ($null -eq $untagged.Floor -and $untagged.Note -like "*v0.0.10 isn't in this clone*") `
        "An untagged release was given a floor: $($untagged.Floor), $($untagged.Note)"
    # A commit the caller read off the remote wins over the clone's tag, which still names the old
    # commit after a release is re-cut on GitHub.
    $recutFloor = Resolve-IndexManagerFloor -Root $toolchainRoot -Version '0.0.9' -Commit $head
    Assert-True ($recutFloor.Floor -eq '1.30.0') "The clone's tag was read over the published commit: $($recutFloor.Floor)"
    # With no tag in the clone the remote is asked, since `gh release create` makes the tag on
    # GitHub only. A bare repository stands in for it: a lightweight tag, an annotated one read
    # through the commit it peels to, and one on a commit this clone doesn't have. The annotated
    # tag is made on the remote alone, so this clone has its commit and never saw the tag object:
    # only the peeled line leads to a commit here.
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('init', '--bare', '--quiet', $toolchainRemote) | Out-Null
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('push', '--quiet', $toolchainRemote,
        "${releaseCommitSha}:refs/tags/v0.0.12", "${head}:refs/heads/main") | Out-Null
    Invoke-FixtureGit -Root $toolchainRemote -Arguments @('-c', 'user.name=Contracts', '-c', 'user.email=contracts@example.invalid',
        'tag', '-a', '-m', 'release', 'v0.0.13', $head) | Out-Null
    $onlyThere = "$(Invoke-FixtureGit -Root $toolchainRemote -Arguments @('-c', 'user.name=Contracts',
        '-c', 'user.email=contracts@example.invalid', 'commit-tree', "$head^{tree}", '-m', 'only there') |
        Select-Object -First 1)".Trim()
    Invoke-FixtureGit -Root $toolchainRemote -Arguments @('tag', 'v0.0.14', $onlyThere) | Out-Null
    $remoteFloor = Resolve-IndexManagerFloor -Root $toolchainRoot -Version '0.0.12' -RemoteUrl $toolchainRemote
    Assert-True ($remoteFloor.Floor -eq '1.29.0' -and $remoteFloor.Source -eq "tag v0.0.12 on $toolchainRemote") `
        "A tag only the remote has was not read there: $($remoteFloor.Floor) from $($remoteFloor.Source), $($remoteFloor.Note)"
    $peeledFloor = Resolve-IndexManagerFloor -Root $toolchainRoot -Version '0.0.13' -RemoteUrl $toolchainRemote
    Assert-True ($peeledFloor.Floor -eq '1.30.0') `
        "An annotated tag only the remote has was not read at its commit: $($peeledFloor.Floor), $($peeledFloor.Note)"
    foreach ($unread in @(
            @{ Version = '0.0.14'; Remote = $toolchainRemote; Note = "*names commit $($onlyThere.Substring(0, 8)), which this clone doesn't have*" },
            @{ Version = '0.0.15'; Remote = $toolchainRemote; Note = "*v0.0.15 isn't in this clone or on $toolchainRemote*" },
            @{ Version = '0.0.15'; Remote = (Join-Path $toolchainRoot 'missing.git'); Note = "*couldn't be asked for it*" })) {
        $unreadFloor = Resolve-IndexManagerFloor -Root $toolchainRoot -Version $unread.Version -RemoteUrl $unread.Remote
        Assert-True ($null -eq $unreadFloor.Floor -and $unreadFloor.Note -like $unread.Note) `
            "A floor the remote couldn't give was not reported as unchecked: $($unreadFloor.Floor), $($unreadFloor.Note)"
    }
    # The clone's tag can be stale: a release re-cut on GitHub moves the tag there, and git fetch
    # won't move it here. With a remote named, the published tag is read first. The clone's v0.0.9
    # still names the release commit, and the remote's names the next one.
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('push', '--quiet', $toolchainRemote, "${head}:refs/tags/v0.0.9") | Out-Null
    $publishedFirst = Resolve-IndexManagerFloor -Root $toolchainRoot -Version '0.0.9' -RemoteUrl $toolchainRemote
    Assert-True ($publishedFirst.Floor -eq '1.30.0' -and $publishedFirst.Source -eq "tag v0.0.9 on $toolchainRemote") `
        "The clone's own tag was read over the published one: $($publishedFirst.Floor) from $($publishedFirst.Source)"

    # A commit with no catalog in it, and a receipt naming no commit at all. Both fall back to
    # the working catalog rather than throwing, and the first says so.
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('rm', '--quiet', '--', 'gradle/libs.versions.toml') | Out-Null
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('commit', '-m', 'no catalog', '--quiet') | Out-Null
    $bare = "$(Invoke-FixtureGit -Root $toolchainRoot -Arguments @('rev-parse', 'HEAD') |
        Select-Object -First 1)".Trim()
    $atBare = Resolve-ReceiptToolchain -Root $toolchainRoot -Commit $bare -WorkingToolchain $workingToolchain
    Assert-True ($atBare.Toolchain.PatcherVersion -eq '1.13.0') `
        'A commit with no catalog did not fall back to the working one.'
    Assert-True ($atBare.Note -like '*no version catalog*') `
        "The fallback was not reported: $($atBare.Note)"
    # The index floor doesn't fall back: the working catalog is the wrong answer for a release.
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('tag', 'v0.0.11', $bare) | Out-Null
    $bareFloor = Resolve-IndexManagerFloor -Root $toolchainRoot -Version '0.0.11'
    Assert-True ($null -eq $bareFloor.Floor -and $bareFloor.Note -like '*no version catalog*') `
        "A tag with no catalog was given a floor: $($bareFloor.Floor), $($bareFloor.Note)"

    $noCommit = Resolve-ReceiptToolchain -Root $toolchainRoot -Commit '' -WorkingToolchain $workingToolchain
    Assert-True ($noCommit.Toolchain.PatcherVersion -eq '1.13.0' -and $null -eq $noCommit.Note) `
        'A receipt naming no commit did not fall back quietly to the working catalog.'

    # The patch list, the same way: a patch renamed after the release is held to the name its own
    # commit carried, which is what the hold after 0.58.0 needed; the released commit itself is
    # held to the working list; no list at that commit, or no commit, falls back to the working one.
    $listFile = Join-Path $toolchainRoot 'patches-list.json'
    function Save-FixtureList([string[]]$Names) {
        $list = @{ patches = @($Names | ForEach-Object { @{ name = $_; compatiblePackages = @{ 'com.example' = @('1.0.0') } } }) }
        Set-Content -LiteralPath $listFile -Encoding UTF8 -Value ($list | ConvertTo-Json -Depth 5)
        Invoke-FixtureGit -Root $toolchainRoot -Arguments @('add', '-A') | Out-Null
        Invoke-FixtureGit -Root $toolchainRoot -Arguments @('commit', '-m', "list $($Names -join ' ')", '--quiet') | Out-Null
        return "$(Invoke-FixtureGit -Root $toolchainRoot -Arguments @('rev-parse', 'HEAD') | Select-Object -First 1)".Trim()
    }
    $listReleased = Save-FixtureList @('Alpha', 'Beta')
    $listNow = Save-FixtureList @('Alpha', 'Gamma')
    $workingList = Get-Content -LiteralPath $listFile -Raw | ConvertFrom-Json
    $atListRelease = Resolve-ReceiptCatalog -Root $toolchainRoot -Commit $listReleased -WorkingPatchList $workingList
    $namesAtRelease = @($atListRelease.PatchList.patches | ForEach-Object { [string]$_.name }) -join ','
    Assert-True ($namesAtRelease -eq 'Alpha,Beta' -and $atListRelease.Note -like '*2 patches*') `
        "The receipt was not held to the patch list its own commit carried: $namesAtRelease / $($atListRelease.Note)"
    $atListHead = Resolve-ReceiptCatalog -Root $toolchainRoot -Commit $listNow -WorkingPatchList $workingList
    Assert-True ((@($atListHead.PatchList.patches | ForEach-Object { [string]$_.name }) -join ',') -eq 'Alpha,Gamma' -and
        $null -eq $atListHead.Note) "A receipt at the released commit was not held to the working patch list: $($atListHead.Note)"
    $atNoList = Resolve-ReceiptCatalog -Root $toolchainRoot -Commit $releaseCommitSha -WorkingPatchList $workingList
    Assert-True ($atNoList.PatchList -eq $workingList -and $atNoList.Note -like '*no patch list*') `
        "A commit with no patch list did not fall back to the working one: $($atNoList.Note)"
    $atNoCommit = Resolve-ReceiptCatalog -Root $toolchainRoot -Commit '' -WorkingPatchList $workingList
    Assert-True ($atNoCommit.PatchList -eq $workingList -and $null -eq $atNoCommit.Note) `
        'A receipt naming no commit did not fall back quietly to the working patch list.'
    # And the release check hands the receipt that list, names and target both, rather than the
    # working one. A helper nothing calls would pass every case above. Anchored at the end, since
    # the unanchored pattern also matched PackageVersions[0], the newest build alone; the release
    # root section below runs the check on a receipt of its own as well.
    $factsSource = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'validate-release-facts.ps1') -Raw
    Assert-True ($factsSource -match '-ExpectedPatchNames @\(\$resolvedList\.PatchList\.patches' -and
        $factsSource -match '\$receiptTarget = Get-PatchTarget -PatchList \$resolvedList\.PatchList' -and
        $factsSource -match '-ExpectedPackageName \$receiptTarget\.PackageName' -and
        $factsSource -match '-ExpectedPackageVersions \$receiptTarget\.PackageVersions(?![\w.\[])' -and
        $factsSource -match '-ExpectedPackageVersionCodes \$receiptTarget\.PackageVersionCodes(?![\w.\[])') `
        ('validate-release-facts.ps1 no longer holds the receipt to the patch list its own commit ' +
            'carried, or to every build that list declares, version codes and all.')

    # The manifest delta allowlist, the same way: the one the receipt's own commit carried. An entry
    # added in the working tree and never committed approved a change into a release no commit had
    # reviewed, and one pruned after a release refused the release that needed it. A commit with no
    # allowlist, or no commit, gets the working one, the first with a note, and a malformed line at
    # the commit says where it is. Written without a byte order mark, as the repository's is, and
    # then with one, which git hands back as text.
    $allowlistFile = Join-Path $toolchainRoot 'scripts/manifest-delta-allowlist.txt'
    New-Item -ItemType Directory -Path (Split-Path -Parent $allowlistFile) -Force | Out-Null
    function Save-FixtureAllowlist([string[]]$Lines, [switch]$Bom, [switch]$Commit) {
        [System.IO.File]::WriteAllText($allowlistFile, (($Lines -join "`n") + "`n"), (New-Object System.Text.UTF8Encoding($Bom.IsPresent)))
        if (-not $Commit) { return $null }
        Invoke-FixtureGit -Root $toolchainRoot -Arguments @('add', '-A') | Out-Null
        Invoke-FixtureGit -Root $toolchainRoot -Arguments @('commit', '-m', 'allowlist', '--quiet') | Out-Null
        return "$(Invoke-FixtureGit -Root $toolchainRoot -Arguments @('rev-parse', 'HEAD') | Select-Object -First 1)".Trim()
    }
    $reviewed = 'exported-added activity:com.example.Reviewed'
    $unreviewed = 'exported-added activity:com.example.Unreviewed'
    foreach ($bom in $false, $true) {
        $allowlistCommit = Save-FixtureAllowlist @('# reviewed at the release', $reviewed) -Bom:$bom -Commit
        Save-FixtureAllowlist @('# added since, never committed', $unreviewed) | Out-Null
        $atAllowlist = Resolve-ReceiptManifestAllowlist -Root $toolchainRoot -Commit $allowlistCommit -WorkingPath $allowlistFile
        Assert-True ((@($atAllowlist.Entries) -join ',') -ceq $reviewed -and
            $atAllowlist.Note -like "*allowlist at its own commit $($allowlistCommit.Substring(0, 8)) reviews*") `
            "The receipt was not held to the allowlist its own commit carried (byte order mark: $bom): $(@($atAllowlist.Entries) -join ', ') / $($atAllowlist.Note)"
    }
    # The one with a byte order mark again, under a console on code page 437, which a hook's pwsh
    # gets when the push starts in Git Bash. PowerShell decoded git's UTF-8 with it, the mark came
    # back as U+2229 U+2557 U+2510, and the allowlist's first line was refused as malformed. The
    # console's own encoding has to be back once the read is done.
    $consoleEncoding = [Console]::OutputEncoding
    try {
        [Console]::OutputEncoding = [System.Text.Encoding]::GetEncoding(437)
        $allowlistCommit = Save-FixtureAllowlist @('# reviewed at the release', $reviewed) -Bom -Commit
        Save-FixtureAllowlist @('# added since, never committed', $unreviewed) | Out-Null
        $oemAllowlist = $null
        $oemFailure = $null
        try {
            $oemAllowlist = Resolve-ReceiptManifestAllowlist -Root $toolchainRoot -Commit $allowlistCommit -WorkingPath $allowlistFile
        } catch {
            $oemFailure = $_.Exception.Message
        }
        $oemAfter = [Console]::OutputEncoding.CodePage
    } finally {
        [Console]::OutputEncoding = $consoleEncoding
    }
    Assert-True ($null -eq $oemFailure -and (@($oemAllowlist.Entries) -join ',') -ceq $reviewed) `
        "An allowlist with a byte order mark was misread under a code page 437 console: $oemFailure $(@($oemAllowlist.Entries) -join ', ')"
    Assert-True ($oemAfter -eq 437) "Reading git's output left the console on code page $oemAfter instead of putting 437 back."
    # The same helper in a process with no console, a detached start or a service. There Windows
    # PowerShell 5.1's setter throws "The handle is invalid." before git runs, while pwsh 7 keeps
    # the value. Each edition installed here gets a child that detaches from its console and runs
    # the helper. The child starts no program of its own after that: Windows gives a console
    # program started from a process with no console a console window of its own. So it checks the
    # encoding PowerShell decodes a native command's output with, inside the block and after it.
    $noConsoleChild = Join-Path $toolchainRoot 'no-console-child.ps1'
    Set-Content -LiteralPath $noConsoleChild -Encoding UTF8 -Value @'
param([string]$Common, [string]$Result)
$ErrorActionPreference = 'Stop'
. $Common
Add-Type -Namespace ContractNoConsole -Name Kernel -MemberDefinition '[System.Runtime.InteropServices.DllImport("kernel32.dll")] public static extern bool FreeConsole();'
$lines = @('detached ' + [ContractNoConsole.Kernel]::FreeConsole())
$before = [Console]::OutputEncoding.CodePage
try {
    $inside = Use-Utf8ConsoleOutput { [Console]::OutputEncoding.CodePage }
    $lines += 'inside ' + $inside
} catch {
    $lines += 'threw ' + $_.Exception.Message
}
$lines += 'after ' + [Console]::OutputEncoding.CodePage + ' before ' + $before
Set-Content -LiteralPath $Result -Value $lines
'@
    $noConsoleShells = @((Get-Process -Id $PID).Path)
    $otherEdition = if ($PSVersionTable.PSEdition -eq 'Core') {
        Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    } else {
        (Get-Command pwsh -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1).Source
    }
    if ($otherEdition -and (Test-Path -LiteralPath $otherEdition)) { $noConsoleShells += $otherEdition }
    foreach ($noConsoleShell in $noConsoleShells) {
        $noConsoleResult = Join-Path $toolchainRoot ('no-console-' + [guid]::NewGuid().ToString('N') + '.txt')
        $preference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            & $noConsoleShell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $noConsoleChild `
                -Common (Join-Path $PSScriptRoot 'common.ps1') -Result $noConsoleResult 2>&1 | Out-Null
        } finally {
            $ErrorActionPreference = $preference
        }
        $noConsole = if (Test-Path -LiteralPath $noConsoleResult) { @(Get-Content -LiteralPath $noConsoleResult) } else { @() }
        $noConsoleBefore = if ($noConsole.Count -eq 3 -and $noConsole[2] -match ' before (\d+)$') { $Matches[1] } else { '?' }
        Assert-True ($noConsole.Count -eq 3 -and $noConsole[0] -eq 'detached True' -and $noConsole[1] -eq 'inside 65001' -and
            $noConsole[2] -eq "after $noConsoleBefore before $noConsoleBefore") `
            "Use-Utf8ConsoleOutput with no console under $noConsoleShell did not read UTF-8 and put the encoding back: $($noConsole -join ' / ')"
    }
    # The release and receipt copies lean on two helpers: one deletes a tree with every link in it
    # removed as a link, the other holds a file or folder and proves the hold is the path's own.
    # Windows PowerShell 5.1's recursive removal is the one that follows a junction, so both
    # editions run them. Each hold is let go at the end and the same change then goes through, so
    # a refusal is the hold's doing and not the machine's.
    $heldChild = Join-Path $toolchainRoot 'held-child.ps1'
    Set-Content -LiteralPath $heldChild -Encoding UTF8 -Value @'
param([string]$Common, [string]$Result, [string]$Work)
$ErrorActionPreference = 'Stop'
. $Common
function Try-Change([scriptblock]$Change) { try { & $Change; 'went' } catch { 'refused' } }
function Try-Hold([string]$Path, [switch]$Directory) {
    try { (Open-HeldFile -Path $Path -Directory:$Directory).Dispose(); 'held' }
    catch {
        if ($_.Exception.Message -like '*is a link*') { return 'refused' }
        # Windows with redirection guard on won't follow a link an ordinary user made, from any process
        # (ERROR_UNTRUSTED_MOUNT_POINT, 448), so the open fails before the link check can see it.
        for ($e = $_.Exception; $e; $e = $e.InnerException) {
            if (($e -is [ComponentModel.Win32Exception] -and $e.NativeErrorCode -eq 448) -or ($e.HResult -band 0xFFFF) -eq 448) { return 'untrusted' }
        }
        'threw ' + $_.Exception.Message
    }
}
$lines = @()
$outside = Join-Path $Work 'outside'
$tree = Join-Path $Work 'tree'
New-Item -ItemType Directory -Path $outside, (Join-Path $tree 'a\b') -Force | Out-Null
Set-Content -LiteralPath (Join-Path $outside 'keep.txt') -Value keep
Set-Content -LiteralPath (Join-Path $tree 'a\file.txt') -Value x
(Get-Item -LiteralPath (Join-Path $tree 'a\file.txt')).IsReadOnly = $true
New-Item -ItemType Junction -Path (Join-Path $tree 'a\b\linked') -Target $outside | Out-Null
$readOnlyFolder = Get-Item -LiteralPath (Join-Path $tree 'a\b')
$readOnlyFolder.Attributes = $readOnlyFolder.Attributes -bor [IO.FileAttributes]::ReadOnly
Remove-TreeWithoutFollowingLinks -Path $tree
$lines += 'tree ' + (Test-Path -LiteralPath $tree) + ' ' + (Test-Path -LiteralPath (Join-Path $outside 'keep.txt'))
$top = Join-Path $Work 'top'
New-Item -ItemType Junction -Path $top -Target $outside | Out-Null
Remove-TreeWithoutFollowingLinks -Path $top
$lines += 'top ' + (Test-Path -LiteralPath $top) + ' ' + (Test-Path -LiteralPath (Join-Path $outside 'keep.txt'))

$folder = Join-Path $Work 'held'
New-Item -ItemType Directory -Path $folder | Out-Null
$file = Join-Path $folder 'asset.txt'
Set-Content -LiteralPath $file -Value checked
$folderHold = Open-HeldFile -Path $folder -Directory
$renamed = Try-Change { [IO.Directory]::Move($folder, "$folder-moved") }
# What the release flow does inside a held folder: copy in, read, plant and remove entries.
$inside = Try-Change {
    $extra = Join-Path $folder 'extra.txt'
    Copy-Item -LiteralPath $file -Destination $extra
    if ([IO.File]::ReadAllText($extra).Trim() -cne 'checked') { throw 'read back wrong' }
    Remove-Item -LiteralPath $extra
}
$folderHold.Dispose()
$renamedAfter = Try-Change { [IO.Directory]::Move($folder, "$folder-moved"); [IO.Directory]::Move("$folder-moved", $folder) }
$lines += "folder $renamed $inside $renamedAfter"
$fileHold = Open-HeldFile -Path $file
$written = Try-Change { [IO.File]::AppendAllText($file, 'swapped') }
$deleted = Try-Change { [IO.File]::Delete($file) }
$read = [IO.File]::ReadAllText($file).Trim()
$fileHold.Dispose()
$writtenAfter = Try-Change { [IO.File]::AppendAllText($file, 'swapped') }
$lines += "file $written $deleted $read $writtenAfter"

$junction = Join-Path $Work 'junction'
New-Item -ItemType Junction -Path $junction -Target $folder | Out-Null
$lines += 'junction ' + (Try-Hold -Path $junction -Directory)
# Through a junction to a real file the file is itself; the folder above it is held on its own.
$lines += 'through ' + (Try-Hold -Path (Join-Path $junction 'asset.txt'))
# A file symbolic link needs a privilege not every machine grants, so it's tried where it can be made.
$symlink = Join-Path $Work 'symlink.txt'
$made = try { New-Item -ItemType SymbolicLink -Path $symlink -Target $file -ErrorAction Stop | Out-Null; $true } catch { $false }
$lines += 'symlink ' + $(if ($made) { Try-Hold -Path $symlink } else { 'unavailable' })
Set-Content -LiteralPath $Result -Value $lines
'@
    foreach ($heldShell in $noConsoleShells) {
        $heldWork = Join-Path $toolchainRoot ('held-' + [guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $heldWork | Out-Null
        $heldResult = Join-Path $heldWork 'result.txt'
        $preference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $heldOutput = & $heldShell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $heldChild `
                -Common (Join-Path $PSScriptRoot 'common.ps1') -Result $heldResult -Work $heldWork 2>&1
        } finally {
            $ErrorActionPreference = $preference
        }
        $heldLines = if (Test-Path -LiteralPath $heldResult) { @(Get-Content -LiteralPath $heldResult) } else { @() }
        $heldSays = "$($heldLines -join ' / ') $($heldOutput -join ' ')"
        Assert-True ($heldLines.Count -eq 7) "The held-file child under $heldShell did not finish: $heldSays"
        Assert-True ($heldLines[0] -ceq 'tree False True' -and $heldLines[1] -ceq 'top False True') `
            "Remove-TreeWithoutFollowingLinks under $heldShell followed a junction or left the tree: $heldSays"
        Assert-True ($heldLines[2] -ceq 'folder refused went went') `
            "A held folder under $heldShell could be renamed, kept files from being copied in and removed, or the control failed: $heldSays"
        Assert-True ($heldLines[3] -ceq 'file refused refused checked went') `
            "A held file under $heldShell could be written or deleted, or the control failed: $heldSays"
        Assert-True ($heldLines[4] -cin @('junction refused', 'junction untrusted') -and $heldLines[5] -cin @('through held', 'through untrusted')) `
            "Open-HeldFile under $heldShell held a junction as a folder, or refused a real file reached through one: $heldSays"
        Assert-True ($heldLines[6] -cin @('symlink refused', 'symlink unavailable', 'symlink untrusted')) `
            "Open-HeldFile under $heldShell held a file symbolic link as the file: $heldSays"
        if ($heldLines[6] -ceq 'symlink unavailable') { Write-Host "[scripts] no file symbolic links here, so that hold case was skipped under $heldShell" }
        if ($heldLines[5] -ceq 'through untrusted') { Write-Host "[scripts] Windows here won't follow a junction an ordinary user made, so holding a file through one went unchecked under $heldShell" }
        Remove-TreeWithoutFollowingLinks -Path $heldWork
    }
    $atNoAllowlist = Resolve-ReceiptManifestAllowlist -Root $toolchainRoot -Commit $releaseCommitSha -WorkingPath $allowlistFile
    Assert-True ((@($atNoAllowlist.Entries) -join ',') -ceq $unreviewed -and $atNoAllowlist.Note -like '*has no manifest delta allowlist*') `
        "A commit with no allowlist did not fall back to the working one, saying so: $($atNoAllowlist.Note)"
    $noCommitAllowlist = Resolve-ReceiptManifestAllowlist -Root $toolchainRoot -Commit '' -WorkingPath $allowlistFile
    Assert-True ((@($noCommitAllowlist.Entries) -join ',') -ceq $unreviewed -and $null -eq $noCommitAllowlist.Note) `
        'A receipt naming no commit was not held quietly to the working allowlist.'
    $sameAllowlist = Resolve-ReceiptManifestAllowlist -Root $toolchainRoot -Commit (Save-FixtureAllowlist @($unreviewed) -Commit) `
        -WorkingPath $allowlistFile
    Assert-True ((@($sameAllowlist.Entries) -join ',') -ceq $unreviewed -and $null -eq $sameAllowlist.Note) `
        "An allowlist the working tree still holds as committed reported a difference: $($sameAllowlist.Note)"
    $brokenAllowlist = Save-FixtureAllowlist @('exported-added') -Commit
    Assert-Throws { Resolve-ReceiptManifestAllowlist -Root $toolchainRoot -Commit $brokenAllowlist -WorkingPath $allowlistFile } `
        "*allowlist at $($brokenAllowlist.Substring(0, 8)) has a line that is not*" `
        'A malformed allowlist at the receipt''s commit was read without complaint.'
    Assert-True ($factsSource -match '\$resolvedAllowlist = Resolve-ReceiptManifestAllowlist -Root \$rootPath -Commit \$receiptCommit' -and
        $factsSource -match '\$approvedDelta = @\(\$resolvedAllowlist\.Entries\)' -and
        $factsSource -match '-ApprovedManifestDelta \$approvedDelta' -and
        $factsSource -notmatch 'Read-ManifestDeltaAllowlist') `
        'validate-release-facts.ps1 no longer holds the receipt to the allowlist its own commit carried.'

    # The receipt schema, read the same way: out of scripts/release-receipt.ps1 at the receipt's
    # commit. A release cut before the SBOM is held to schema 1 and says so, one cut since to this
    # checkout's schema, one from a newer checkout is refused rather than misread, and a commit
    # with no receipt script, or none at all, gets this checkout's.
    $receiptScript = Join-Path $toolchainRoot 'scripts/release-receipt.ps1'
    New-Item -ItemType Directory -Path (Split-Path -Parent $receiptScript) -Force | Out-Null
    $currentSchema = Get-ReleaseReceiptSchemaVersion
    $schemaCommits = @{}
    foreach ($written in @(1, 2, $currentSchema, ($currentSchema + 1))) {
        Set-Content -LiteralPath $receiptScript -Encoding UTF8 -Value @('function Get-ReleaseReceiptSchemaVersion {', '    <#',
            '    .SYNOPSIS', '        Bumped when the shape changes. A receipt at return 9 would be a surprise.', '    #>',
            "    return $written", '}')
        Invoke-FixtureGit -Root $toolchainRoot -Arguments @('add', '-A') | Out-Null
        Invoke-FixtureGit -Root $toolchainRoot -Arguments @('commit', '-m', "schema $written", '--quiet') | Out-Null
        $schemaCommits[$written] = "$(Invoke-FixtureGit -Root $toolchainRoot -Arguments @('rev-parse', 'HEAD') | Select-Object -First 1)".Trim()
    }
    $atOne = Resolve-ReceiptSchema -Root $toolchainRoot -Commit $schemaCommits[1]
    Assert-True ($atOne.Version -eq 1 -and $atOne.Note -like "*schema 1, which its own commit $($schemaCommits[1].Substring(0, 8)) wrote*") `
        "A receipt cut at schema 1 was not held to it: $($atOne.Version), $($atOne.Note)"
    $atCurrent = Resolve-ReceiptSchema -Root $toolchainRoot -Commit $schemaCommits[$currentSchema]
    $atTwo = Resolve-ReceiptSchema -Root $toolchainRoot -Commit $schemaCommits[2]
    Assert-True ($atTwo.Version -eq 2 -and $atTwo.Note -like '*predates tooling reports*') `
        'The historical schema 2 receipt was not identified as predating tooling reports.'
    Assert-True ($atCurrent.Version -eq $currentSchema -and $null -eq $atCurrent.Note) `
        "A receipt cut at this checkout's schema was not held to it quietly: $($atCurrent.Version), $($atCurrent.Note)"
    Assert-Throws { Resolve-ReceiptSchema -Root $toolchainRoot -Commit $schemaCommits[$currentSchema + 1] } `
        "*writes receipt schema $($currentSchema + 1), newer than the $currentSchema this checkout reads*" `
        'A receipt schema newer than this checkout reads was read as if it were known.'
    foreach ($none in @($releaseCommitSha, '')) {
        $atNone = Resolve-ReceiptSchema -Root $toolchainRoot -Commit $none
        Assert-True ($atNone.Version -eq $currentSchema -and $null -eq $atNone.Note) `
            "A commit with no receipt script was not held to this checkout's schema: $($atNone.Version), $($atNone.Note)"
    }
    Assert-True ([System.IO.File]::ReadAllText((Join-Path $PSScriptRoot 'release-receipt.ps1')) -match
            '(?s)function\s+Get-ReleaseReceiptSchemaVersion\b.*?#>\s*return\s+(\d+)\s*\}' -and [int]$Matches[1] -eq $currentSchema) `
        'Resolve-ReceiptSchema can no longer read the schema out of this checkout''s own release-receipt.ps1.'

    # A catalog that pins nothing usable still stops the run, rather than being read as blank.
    foreach ($broken in @(
        @{ Name = 'no patcher pin'; Lines = @('[versions]', 'manager-floor = "1.29.0"') },
        @{ Name = 'no Manager floor'; Lines = @('[versions]', 'morphe-patcher = "1.12.0"') },
        @{ Name = 'an unusable Manager floor'; Lines = @('[versions]', 'morphe-patcher = "1.12.0"', 'manager-floor = "latest"') })) {
        Assert-Throws { Read-CatalogToolchain -Text ($broken.Lines -join "`n") -Source 'the test catalog' } `
            '*the test catalog*' "A catalog with $($broken.Name) was read without complaint."
    }
} finally {
    Remove-Item -LiteralPath $toolchainRoot, $toolchainRemote -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host '[scripts] release receipt schema, manifest reading and validation contracts passed'

# --- release-advisories.ps1 ------------------------------------------------------------------
#
# The gate that asks OSV about the libraries a release's SBOM lists and refuses a release carrying
# a high or critical advisory. OSV's answers are recorded here as it gave them on 2026-09-25,
# trimmed to the fields the gate reads, and a stand-in for Invoke-RestMethod answers from them by
# package URL, so no case needs the network. A package URL it has no answer for fails the way an
# OSV this machine can't reach does. gson 2.8.8 is the deliberately vulnerable library:
# GHSA-4jrv-ppp4-jm57 (CVE-2022-25647), which OSV and GitHub rate HIGH.

. (Join-Path $PSScriptRoot 'release-advisories.ps1')

$gsonAdvisory = '{"id":"GHSA-4jrv-ppp4-jm57","summary":"Deserialization of Untrusted Data in Gson","aliases":["CVE-2022-25647"],"modified":"2026-09-10T03:49:19.710819205Z","database_specific":{"severity":"HIGH"},"severity":[{"type":"CVSS_V3","score":"CVSS:3.1/AV:N/AC:H/PR:N/UI:N/S:U/C:L/I:H/A:H"}]}'
$log4jCritical = '{"id":"GHSA-jfh8-c2jp-5v3q","summary":"Remote code injection in Log4j","aliases":["CVE-2021-44228"],"modified":"2025-10-22T19:37:02.616807Z","database_specific":{"severity":"CRITICAL"},"severity":[{"type":"CVSS_V3","score":"CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H/E:H"}]}'
$log4jModerate = '{"id":"GHSA-8489-44mv-ggj8","summary":"Improper Input Validation and Injection in Apache Log4j2","aliases":["CVE-2021-44832"],"modified":"2026-06-09T10:45:14.253296471Z","database_specific":{"severity":"MODERATE"},"severity":[{"type":"CVSS_V3","score":"CVSS:3.1/AV:N/AC:H/PR:H/UI:N/S:U/C:H/I:H/A:H"}]}'
# Rated only by a CVSS 4 vector once OSV's label is taken out, which this gate can't score.
$log4jVectorFour = '{"id":"GHSA-3pxv-7cmr-fjr4","summary":"Apache Log4j Core: Silent log event loss in XmlLayout due to unescaped XML 1.0 forbidden characters","aliases":["CVE-2026-34480"],"modified":"2026-09-10T03:50:42.492278990Z","severity":[{"type":"CVSS_V4","score":"CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:N/VI:N/VA:N/SC:N/SI:L/SA:N"}]}'
$guavaModerate = '{"id":"GHSA-7g45-4rm6-3mm3","summary":"Guava vulnerable to insecure use of temporary directory","aliases":["CVE-2023-2976"],"modified":"2026-09-10T03:49:53.859811124Z","database_specific":{"severity":"MODERATE"},"severity":[{"type":"CVSS_V3","score":"CVSS:3.1/AV:L/AC:L/PR:L/UI:N/S:U/C:H/I:N/A:N"}]}'
$guavaLow = '{"id":"GHSA-5mg8-w23w-74h3","summary":"Information Disclosure in Guava","aliases":["CVE-2020-8908"],"modified":"2026-09-10T03:49:26.651391253Z","database_specific":{"severity":"LOW"},"severity":[{"type":"CVSS_V3","score":"CVSS:3.1/AV:L/AC:L/PR:L/UI:N/S:U/C:L/I:N/A:N"}]}'
$gsonPurl = 'pkg:maven/com.google.code.gson/gson@2.8.8'
$cleanPurl = 'pkg:maven/com.google.code.gson/gson@2.14.0'
$osvRecorded = @{
    $gsonPurl = "{`"vulns`":[$gsonAdvisory]}"
    'pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1' = "{`"vulns`":[$log4jCritical,$log4jModerate]}"
    'pkg:maven/com.google.guava/guava@31.1-jre' = "{`"vulns`":[$guavaModerate,$guavaLow]}"
    $cleanPurl = '{}'
}
# What the stand-in serves, which a case replaces to try another shape, and what it was asked.
$osvAnswers = $osvRecorded
$osvAsked = New-Object System.Collections.Generic.List[string]
$osvStandIn = {
    function Invoke-RestMethod {
        param($Uri, $Method, $ContentType, $Body, $TimeoutSec)
        $query = $Body | ConvertFrom-Json
        $key = [string]$query.package.purl
        if ($query.page_token) { $key += " page $($query.page_token)" }
        $osvAsked.Add($key)
        if (-not $osvAnswers.ContainsKey($key)) {
            throw "Unable to connect to the remote server (a stand-in for api.osv.dev with no answer for $key)"
        }
        $answer = $osvAnswers[$key]
        if ($answer -is [string] -and $answer.StartsWith('{')) { return ($answer | ConvertFrom-Json) }
        return $answer
    }
}
$advisoryRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("hushfacebook-advisories-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $advisoryRoot -Force | Out-Null
try {
    $today = [datetime]'2026-09-25'
    # What Read-ReleaseSbom hands the gate, for the libraries named.
    function New-GateSbom([string[]]$Purls) {
        $libraries = @(foreach ($purl in @($Purls | Where-Object { $_ })) {
            $parts = [regex]::Match($purl, '^pkg:maven/([^/]+)/([^@]+)@(.+)$')
            [pscustomobject]@{ Ref = $purl; Type = 'library'; Group = $parts.Groups[1].Value; Name = $parts.Groups[2].Value
                Version = $parts.Groups[3].Value; Purl = $purl }
        })
        return [pscustomobject]@{ Path = (Join-Path $advisoryRoot 'patches-9.9.9.cdx.json'); Libraries = $libraries }
    }
    # The gate on an SBOM of these libraries with these exception lines, and everything it said,
    # warnings included.
    function Invoke-Gate([string[]]$Purls, [string[]]$Exceptions = @(), [switch]$Skip) {
        $list = Join-Path $advisoryRoot 'advisory-exceptions.txt'
        Set-Content -LiteralPath $list -Encoding ASCII -Value (@('# exceptions for this case') + @($Exceptions))
        . $osvStandIn
        $osvAsked.Clear()
        return (@(Invoke-ReleaseAdvisoryGate -Sbom (New-GateSbom $Purls) -ExceptionsPath $list -Today $today `
            -SkipAdvisoryCheck:$Skip 3>&1 6>&1 | ForEach-Object { "$_" }) -join "`n")
    }
    $later = $today.AddDays(30).ToString('yyyy-MM-dd')
    $why = 'Only the build reads JSON with it, never input from outside.'

    # CVSS 3 base scores, against the numbers NVD and the specification's calculator give. The guava
    # vectors are the two that plain rounding gets wrong (5.4 and 3.2), and the one scored 8.6
    # rounds down to 8.5 that way; roundup as 3.1 defines it is the difference.
    foreach ($known in @(
            @{ Vector = 'CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H'; Score = 9.8 },
            @{ Vector = 'CVSS:3.1/AV:N/AC:H/PR:N/UI:N/S:U/C:L/I:H/A:H'; Score = 7.7 },
            @{ Vector = 'CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H/E:H'; Score = 10.0 },
            @{ Vector = 'CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:N/I:N/A:H'; Score = 8.6 },
            @{ Vector = 'CVSS:3.1/AV:N/AC:H/PR:H/UI:N/S:U/C:H/I:H/A:H'; Score = 6.6 },
            @{ Vector = 'CVSS:3.1/AV:L/AC:L/PR:L/UI:N/S:U/C:H/I:N/A:N'; Score = 5.5 },
            @{ Vector = 'CVSS:3.1/AV:L/AC:L/PR:L/UI:N/S:U/C:L/I:N/A:N'; Score = 3.3 },
            @{ Vector = 'CVSS:3.0/AV:N/AC:L/PR:L/UI:R/S:C/C:L/I:L/A:N'; Score = 5.4 },
            @{ Vector = 'CVSS:3.1/AV:P/AC:H/PR:H/UI:R/S:U/C:N/I:N/A:N'; Score = 0.0 })) {
        $scored = Get-Cvss3BaseScore -Vector $known.Vector
        Assert-True ($null -ne $scored -and [Math]::Abs($scored - $known.Score) -lt 0.001) `
            "$($known.Vector) scored $scored, not $($known.Score)."
    }
    foreach ($unscored in @('CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N',
            'CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H', 'CVSS:3.1/AV:X/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H',
            'CVSS:3.1/AV:n/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H', 'CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:u/C:H/I:H/A:H')) {
        Assert-True ($null -eq (Get-Cvss3BaseScore -Vector $unscored)) "$unscored was given a CVSS 3 score."
    }
    foreach ($malformed in @(
            'CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H/C:N/I:N/A:N',
            'CVSS:3.1/C:N/I:N/A:N/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H',
            'CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H/E:H/E:U',
            'CVSS:3.1/av:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H',
            'CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H/unknown:H',
            'CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H/E:invalid')) {
        Assert-True ($null -eq (Get-Cvss3BaseScore -Vector $malformed)) "A malformed vector was scored: $malformed"
        $unrated = Get-AdvisorySeverity -Advisory ([pscustomobject]@{
            database_specific = @{}; severity = @([pscustomobject]@{ type = 'CVSS_V3'; score = $malformed }) })
        Assert-True ($unrated.Serious -and $unrated.Level -eq 'UNRATED') 'A malformed vector bypassed the advisory gate.'
    }

    # How serious each recorded advisory is: OSV's label and the vector's score, whichever is worse,
    # and unrated when neither can be read.
    foreach ($rated in @(
            @{ Name = 'the gson advisory'; Json = $gsonAdvisory; Level = 'HIGH'; Serious = $true; Why = 'OSV rates it HIGH' },
            @{ Name = 'the gson advisory with no vector'; Json = $gsonAdvisory.Replace(',"severity":[{"type":"CVSS_V3","score":"CVSS:3.1/AV:N/AC:H/PR:N/UI:N/S:U/C:L/I:H/A:H"}]', '')
                Level = 'HIGH'; Serious = $true; Why = 'OSV rates it HIGH' },
            @{ Name = 'the gson advisory with no label'; Json = $gsonAdvisory.Replace('"database_specific":{"severity":"HIGH"},', '')
                Level = 'HIGH'; Serious = $true; Why = 'its CVSS 3 vector scores 7.7' },
            @{ Name = 'the gson advisory labelled LOW'; Json = $gsonAdvisory.Replace('"severity":"HIGH"', '"severity":"LOW"')
                Level = 'HIGH'; Serious = $true; Why = 'its CVSS 3 vector scores 7.7' },
            @{ Name = 'the log4j advisory'; Json = $log4jCritical; Level = 'CRITICAL'; Serious = $true; Why = 'OSV rates it CRITICAL' },
            @{ Name = 'a CVSS 4 vector alone'; Json = $log4jVectorFour; Level = 'UNRATED'; Serious = $true; Why = '*no severity*' },
            @{ Name = 'the guava temporary directory advisory'; Json = $guavaModerate; Level = 'MODERATE'; Serious = $false; Why = 'OSV rates it MODERATE' },
            @{ Name = 'a MEDIUM label'; Json = $guavaModerate.Replace('"MODERATE"', '"MEDIUM"'); Level = 'MODERATE'; Serious = $false; Why = 'OSV rates it MODERATE' },
            @{ Name = 'the guava disclosure advisory'; Json = $guavaLow; Level = 'LOW'; Serious = $false; Why = 'OSV rates it LOW' })) {
        $severity = Get-AdvisorySeverity -Advisory ($rated.Json | ConvertFrom-Json)
        Assert-True ($severity.Level -eq $rated.Level -and $severity.Serious -eq $rated.Serious -and $severity.Why -like $rated.Why) `
            "$($rated.Name) was rated $($severity.Level), serious $($severity.Serious), because $($severity.Why)."
    }

    # The exception list. Each broken line stops the read and names itself.
    $exceptionList = Join-Path $advisoryRoot 'read.txt'
    Set-Content -LiteralPath $exceptionList -Encoding ASCII -Value @('# accepted', '',
        "GHSA-4jrv-ppp4-jm57 com.google.code.gson:gson $later $why",
        "CVE-2021-44228 org.apache.logging.log4j:log4j-core 2026-09-24 $why")
    $read = @(Read-AdvisoryExceptions -Path $exceptionList -Today $today)
    Assert-True ($read.Count -eq 2 -and $read[0].Advisory -eq 'GHSA-4jrv-ppp4-jm57' -and $read[0].Package -eq 'com.google.code.gson:gson' -and
        -not $read[0].Expired -and $read[1].Expired -and $read[0].Reason -eq $why -and $read[0].Line -eq 3) `
        "The exception list was misread: $(@($read | ForEach-Object { "$($_.Advisory) $($_.Package) $($_.Until) expired=$($_.Expired)" }) -join '; ')"
    Assert-True (@(Read-AdvisoryExceptions -Path $exceptionList -Today $today.AddDays(-60)).Count -eq 2) `
        'An exception 90 days out on the day it was read was refused.'
    foreach ($broken in @(
            @{ Name = 'no date'; Line = "GHSA-4jrv-ppp4-jm57 com.google.code.gson:gson $why"; Pattern = '*isn''t a yyyy-MM-dd date*' },
            @{ Name = 'no package'; Line = "GHSA-4jrv-ppp4-jm57 $later $why"; Pattern = '*is not "<advisory> <group>:<name>*' },
            @{ Name = 'a date that does not exist'; Line = "GHSA-4jrv-ppp4-jm57 com.google.code.gson:gson 2026-02-30 $why"; Pattern = '*isn''t a yyyy-MM-dd date*' },
            @{ Name = 'a date 91 days out'; Line = "GHSA-4jrv-ppp4-jm57 com.google.code.gson:gson $($today.AddDays(91).ToString('yyyy-MM-dd')) $why"
                Pattern = '*more than 90 days out*' },
            @{ Name = 'a two-word reason'; Line = "GHSA-4jrv-ppp4-jm57 com.google.code.gson:gson $later Not reachable."; Pattern = '*without saying why*' },
            @{ Name = 'the same advisory twice'; Line = "GHSA-4jrv-ppp4-jm57 com.google.code.gson:gson $later $why"; Twice = $true; Pattern = '*a second time*' })) {
        $lines = @($broken.Line)
        if ($broken.Twice) { $lines += $broken.Line }
        Set-Content -LiteralPath $exceptionList -Encoding ASCII -Value $lines
        Assert-Throws { Read-AdvisoryExceptions -Path $exceptionList -Today $today } $broken.Pattern `
            "An exception list with $($broken.Name) was read without complaint."
    }
    Assert-Throws { Read-AdvisoryExceptions -Path (Join-Path $advisoryRoot 'absent.txt') -Today $today } '*list is missing*' `
        'A missing exception list was read as an empty one.'
    # The date in the list is a day on the maintainer's own calendar. Both functions defaulted to
    # the UTC date, so on the east coast an exception ran out at 19:00 on the last day it named
    # and refused a release it still covered. The clock can't be moved here, so the defaults are
    # read as written.
    foreach ($dated in 'Read-AdvisoryExceptions', 'Invoke-ReleaseAdvisoryGate') {
        $parameter = @((Get-Command $dated).ScriptBlock.Ast.Body.ParamBlock.Parameters |
            Where-Object { $_.Name.VariablePath.UserPath -eq 'Today' })
        $default = if ($parameter.Count -eq 1 -and $parameter[0].DefaultValue) { $parameter[0].DefaultValue.Extent.Text } else { '' }
        Assert-True ($default -eq '[datetime]::Today') "$dated takes today as $default, not the local calendar day."
    }
    $checkedIn = Join-Path $PSScriptRoot 'advisory-exceptions.txt'
    try {
        $null = @(Read-AdvisoryExceptions -Path $checkedIn)
    } catch {
        throw "The checked-in scripts/advisory-exceptions.txt does not read: $($_.Exception.Message)"
    }

    # The gate. A clean library is asked about once and passes.
    $said = Invoke-Gate @($cleanPurl)
    Assert-True ($said -like '*OSV has no advisory for the libraries patches-9.9.9.cdx.json lists: gson 2.14.0*' -and
        ($osvAsked -join ', ') -eq $cleanPurl) "The gate did not ask OSV about the clean library, or did not say so: $said"

    # The deliberately vulnerable library is refused, naming the advisory, its alias and the version.
    Assert-Throws { Invoke-Gate @($cleanPurl, $gsonPurl) } `
        '*high or critical*GHSA-4jrv-ppp4-jm57 (HIGH, CVE-2022-25647) in com.google.code.gson:gson 2.8.8*' `
        'The gate let a release carry gson 2.8.8.'
    Assert-Throws { Invoke-Gate @('pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1') } '*GHSA-jfh8-c2jp-5v3q (CRITICAL*' `
        'The gate let a release carry log4j-core 2.14.1.'
    # Moderate and low go through, and say so.
    $said = Invoke-Gate @('pkg:maven/com.google.guava/guava@31.1-jre')
    Assert-True ($said -like '*below high, let through: GHSA-7g45-4rm6-3mm3 (MODERATE*' -and
        $said -like '*below high, let through: GHSA-5mg8-w23w-74h3 (LOW*' -and $said -like '*2 advisories, none refused*') `
        "The gate refused, or said nothing of, moderate and low advisories: $said"
    # An advisory rated only by a vector this gate can't score counts as serious.
    $osvAnswers = @{ 'pkg:maven/org.apache.logging.log4j/log4j-core@2.26.0' = "{`"vulns`":[$log4jVectorFour]}" }
    try {
        Assert-Throws { Invoke-Gate @('pkg:maven/org.apache.logging.log4j/log4j-core@2.26.0') } '*GHSA-3pxv-7cmr-fjr4 (UNRATED*' `
            'The gate let an advisory through that no severity it can read describes.'
    } finally {
        $osvAnswers = $osvRecorded
    }

    # Exceptions: by OSV's id or an alias, for the one package, until the date.
    foreach ($named in @('GHSA-4jrv-ppp4-jm57', 'CVE-2022-25647', 'ghsa-4jrv-ppp4-jm57')) {
        $said = Invoke-Gate @($gsonPurl) @("$named com.google.code.gson:gson $later $why")
        Assert-True ($said -like "*accepted: GHSA-4jrv-ppp4-jm57 (HIGH*accepted until $later`: $why*") `
            "An exception naming $named did not accept the gson advisory: $said"
    }
    Assert-Throws { Invoke-Gate @($gsonPurl) @("GHSA-4jrv-ppp4-jm57 com.google.code.gson:gson-extras $later $why") } `
        '*high or critical*GHSA-4jrv-ppp4-jm57*no longer reports*GHSA-4jrv-ppp4-jm57 for com.google.code.gson:gson-extras*' `
        'An exception for another package accepted the gson advisory, or was not called stale.'
    Assert-Throws { Invoke-Gate @($gsonPurl) @("GHSA-4jrv-ppp4-jm57 com.google.code.gson:gson 2026-09-24 $why") } `
        '*GHSA-4jrv-ppp4-jm57 (HIGH*its exception ran out on 2026-09-24*' 'An exception past its date still accepted the gson advisory.'
    Assert-Throws { Invoke-Gate @($cleanPurl) @("GHSA-4jrv-ppp4-jm57 com.google.code.gson:gson $later $why") } `
        '*no longer reports*GHSA-4jrv-ppp4-jm57 for com.google.code.gson:gson (line 2)*' 'An exception nothing matches any more went unnoticed.'

    # OSV has to answer, and answer with something the gate can read.
    Assert-Throws { Invoke-Gate @('pkg:maven/com.example/unknown@1.0') } '*could not be asked about pkg:maven/com.example/unknown@1.0*fails closed*' `
        'The gate read an OSV it could not reach as no advisories.'
    $unreadable = @(
        @{ Name = 'a page of HTML'; Answer = '<html>Service Unavailable</html>'; Pattern = '*isn''t a query result*' },
        @{ Name = 'an advisory with no id'; Answer = '{"vulns":[{"summary":"no id"}]}'; Pattern = '*has no id*' })
    foreach ($odd in $unreadable) {
        $osvAnswers = @{ $cleanPurl = $odd.Answer }
        try {
            Assert-Throws { Invoke-Gate @($cleanPurl) } $odd.Pattern "The gate took $($odd.Name) from OSV for an answer."
        } finally {
            $osvAnswers = $osvRecorded
        }
    }
    # The second page of an answer is read, and an advisory OSV withdrew is not one.
    $osvAnswers = @{ $gsonPurl = '{"next_page_token":"p2"}'; "$gsonPurl page p2" = "{`"vulns`":[$gsonAdvisory]}" }
    try {
        Assert-Throws { Invoke-Gate @($gsonPurl) } '*GHSA-4jrv-ppp4-jm57 (HIGH*' 'The gate stopped at the first page of an answer.'
        Assert-True (($osvAsked -join ', ') -eq "$gsonPurl, $gsonPurl page p2") "The gate did not ask for the second page: $($osvAsked -join ', ')"
        $osvAnswers = @{ $gsonPurl = "{`"vulns`":[$($gsonAdvisory.Replace('{"id"', '{"withdrawn":"2026-09-01T00:00:00Z","id"'))]}" }
        $said = Invoke-Gate @($gsonPurl)
        Assert-True ($said -like '*OSV has no advisory*') "A withdrawn advisory refused the release: $said"
    } finally {
        $osvAnswers = $osvRecorded
    }

    # Offline work: the check is skipped with a warning and OSV isn't asked, but the exception list
    # is still read. And an SBOM with no library has nothing to ask about.
    $said = Invoke-Gate @('pkg:maven/com.example/unknown@1.0') -Skip
    Assert-True ($said -like '*-SkipAdvisoryCheck: OSV was not asked about the libraries patches-9.9.9.cdx.json lists*' -and
        $osvAsked.Count -eq 0) "The skipped check asked OSV, or did not say it was skipped: $said"
    Assert-Throws { Invoke-Gate @($cleanPurl) @('GHSA-4jrv-ppp4-jm57 soon') -Skip } '*is not "<advisory>*' `
        'A skipped check left a broken exception list unread.'
    $said = Invoke-Gate @()
    Assert-True ($said -like '*lists no library to ask OSV about*' -and $osvAsked.Count -eq 0) `
        "An SBOM with no library was not passed as one: $said"

    function New-GateTooling([string[]]$Purls) {
        $value = New-GateSbom $Purls
        $value.Path = Join-Path $advisoryRoot 'patches-9.9.9.tooling.json'
        $value | Add-Member -NotePropertyName Scopes -NotePropertyValue @('settings/buildscript/classpath', ':patches/configuration/testRuntimeClasspath')
        foreach ($library in $value.Libraries) { $library | Add-Member -NotePropertyName Scopes -NotePropertyValue @('settings/buildscript/classpath') }
        return $value
    }
    function Invoke-ToolingGate([string[]]$Purls, [string[]]$Exceptions = @(), [switch]$Skip) {
        $list = Join-Path $advisoryRoot 'tooling-exceptions.txt'
        Set-Content -LiteralPath $list -Encoding ASCII -Value (@('# reviewed applicability for this case') + $Exceptions)
        . $osvStandIn
        $osvAsked.Clear()
        return (@(Invoke-ToolingAdvisoryGate -Tooling (New-GateTooling $Purls) -ExceptionsPath $list -Today $today `
            -SkipAdvisoryCheck:$Skip 3>&1 6>&1 | ForEach-Object { "$_" }) -join "`n")
    }
    foreach ($case in @(
            @{ Purl = $gsonPurl; Pattern = '*GHSA-4jrv-ppp4-jm57 (HIGH*' },
            @{ Purl = 'pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1'; Pattern = '*GHSA-jfh8-c2jp-5v3q (CRITICAL*' })) {
        Assert-Throws { Invoke-ToolingGate @($case.Purl) } $case.Pattern 'A settings-only serious finding passed the tooling gate.'
    }
    try {
        $osvAnswers = @{ $cleanPurl = "{`"vulns`":[$log4jVectorFour]}" }
        Assert-Throws { Invoke-ToolingGate @($cleanPurl) } '*UNRATED*' 'An unscored settings-only finding passed the tooling gate.'
        $guavaVersions = @('4.0-jre', '33.7.1-jre', '33.7.1-android', '33.7.2-jre', '33.7.2-android', '3.0', 'snapshot')
        $guavaPurls = @($guavaVersions | ForEach-Object { "pkg:maven/com.google.guava/guava@$_" })
        $osvAnswers = @{}
        foreach ($purl in $guavaPurls) { $osvAnswers[$purl] = '{}' }
        . $osvStandIn
        $found = @(Get-ToolingAdvisories -Tooling (New-GateTooling $guavaPurls))
        Assert-True ($found.Count -eq 4 -and @($found | Where-Object { $_.Severity.Level -ceq 'MODERATE' }).Count -eq 3 -and
            @($found | Where-Object { $_.Severity.Level -ceq 'UNRATED' }).Count -eq 1 -and
            @($found | Where-Object { $_.Version -eq '33.7.1-android' -and $_.Scopes -contains 'settings/buildscript/classpath' }).Count -eq 1) `
            'The vendor fallback lost an affected version, edition, carrier or the vendor severity.'
        Assert-Throws { Invoke-ToolingGate @($guavaPurls[1]) } '*GHSA-xxph-c9ww-hj94 (MODERATE*reachability review*' `
            'OSV returning no match bypassed the required vendor Guava review.'
        $review = "GHSA-xxph-c9ww-hj94 com.google.guava:guava $later Only trusted local build inputs reach this test API."
        $said = Invoke-ToolingGate @($guavaPurls[1]) @($review)
        Assert-True ($said -like '*accepted until*checked') 'A dated, explained tooling review was not accepted.'
        Assert-Throws { Invoke-ToolingGate @($guavaPurls[1]) @($review.Replace($later, '2026-09-24')) } '*reachability exception expired on 2026-09-24*' `
            'An expired vendor reachability review passed.'
        Assert-Throws { Invoke-ToolingGate @($guavaPurls[1]) @($review.Replace($later, '2027-01-01')) } '*more than 90 days out*' `
            'A tooling review longer than 90 days passed.'
        Assert-Throws { Invoke-ToolingGate @($guavaPurls[1]) @("GHSA-xxph-c9ww-hj94 com.google.guava:guava $later harmless") } '*without saying why*' `
            'An unexplained tooling review passed.'
        $aliasAdvisory = '{"id":"OSV-test-alias","aliases":["GHSA-xxph-c9ww-hj94"],"summary":"Recorded vendor alias","database_specific":{"severity":"MODERATE"}}'
        $osvAnswers[$guavaPurls[1]] = "{`"vulns`":[$aliasAdvisory]}"
        $merged = @(Get-ToolingAdvisories -Tooling (New-GateTooling @($guavaPurls[1])))
        Assert-True ($merged.Count -eq 1 -and $merged[0].Aliases -contains 'OSV-test-alias') 'Vendor fallback duplicated an OSV alias finding.'
        Assert-Throws { Invoke-ToolingGate @($guavaPurls[1]) @($review.Replace($later, '2026-09-24'),
            "OSV-test-alias com.google.guava:guava $later Only trusted local build inputs reach this test API.") } '*reachability exception expired*' `
            'A current alias review hid an expired reachability review.'
        $said = Invoke-ToolingGate @($guavaPurls[3], $guavaPurls[4])
        Assert-True ($said -like '*no refused advisory*checked') 'Fixed Guava editions were refused by the vendor fallback.'
        $said = Invoke-ToolingGate @('pkg:maven/com.example/offline@1') -Skip
        Assert-True ($said -like '*cannot authorize publication*skipped' -and $osvAsked.Count -eq 0) 'An offline tooling audit appeared checked.'
    } finally { $osvAnswers = $osvRecorded }
} finally {
    Remove-Item -LiteralPath $advisoryRoot -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host '[scripts] advisory gate contracts passed'

# --- published patch selection compatibility --------------------------------------------------
#
# Morphe Manager keeps a patch choice by name and starts an untouched patch at its use default, so
# a rename, a removal or a flipped default changes what people get without a word. Each case below
# starts from a comparison that passes and moves one fact.

& {
    function Copy-Document($Object) { return $Object | ConvertTo-Json -Depth 20 | ConvertFrom-Json }
    function Catalog([string]$Version, [object[]]$Patches) {
        return [pscustomobject]@{ version = "v$Version"; patches = $Patches }
    }
    function Patch([string]$Name, [bool]$Use) {
        return [pscustomobject]@{ name = $Name; use = $Use; compatiblePackages = [pscustomobject]@{ 'com.example' = @('1.0.0') } }
    }
    $baseline = Catalog '0.6.0' @((Patch 'Alpha' $false), (Patch 'Beta' $true))
    $baselineCommit = 'a' * 40
    $candidate = Copy-Document $baseline
    $compatibility = $null
    $notes = ''
    function Compare-Here {
        return Test-PatchCatalogCompatibility -BaselinePatchList $baseline -BaselineVersion '0.6.0' `
            -BaselineCommit $baselineCommit -CandidatePatchList $candidate `
            -CandidateVersion $candidate.version.Substring(1) -Compatibility $compatibility -Changelog $notes
    }
    function Assert-Accept([string]$Message) {
        $result = Compare-Here
        Assert-True $result.Valid "$Message $($result.Reason)"
    }
    function Assert-Refuse([string]$Pattern, [string]$Message) {
        $result = Compare-Here
        Assert-True (-not $result.Valid -and $result.Reason -like $Pattern) "$Message Got: $($result.Valid) / $($result.Reason)"
    }
    function Declaration($Before, $After, [string]$Note) {
        $beforeState = [pscustomobject]@{ name = $Before.name; use = $Before.use }
        $afterState = if ($null -eq $After) { $null } else { [pscustomobject]@{ name = $After.name; use = $After.use } }
        return [pscustomobject]@{ before = $beforeState; after = $afterState; migrationNote = $Note }
    }
    function Allow([object[]]$Changes, [string]$Version = '0.6.0') {
        return [pscustomobject]@{ baselineVersion = '0.6.0'; baselineCommit = $baselineCommit; candidateVersion = $Version; changes = $Changes }
    }

    Assert-Accept 'An unchanged held catalog failed.'
    $candidate.patches += Patch 'New patch' $true
    Assert-Accept 'An addition failed.'
    $candidate = Copy-Document $baseline
    $candidate.patches[0].use = $true
    Assert-Refuse '*without compatibility metadata*' 'A default flip passed without metadata.'
    $flipNote = 'Alpha is now included by default.'
    $compatibility = Allow @((Declaration $baseline.patches[0] $candidate.patches[0] $flipNote))
    $notes = "## Unreleased`n- $flipNote`n## 0.6.0 (2026-09-30)`nOlder release."
    Assert-Accept 'An intentional held default flip failed.'
    $notes = "## 0.6.0 (2026-09-30)`n- $flipNote"
    Assert-Refuse '*section for Unreleased*' 'A held default flip used the shipped changelog section.'
    $notes = "## Unreleased`n- $($flipNote.ToLowerInvariant())"
    Assert-Refuse '*exact migration note*' 'A case-changed migration note passed.'
    $notes = "## Unreleased`n- $flipNote`n## Unreleased`n- $flipNote"
    Assert-Refuse '*exactly one CHANGELOG section*' 'A duplicate changelog section passed.'

    $candidate = Copy-Document $baseline
    $candidate.patches[0].name = 'Gamma'
    $compatibility = $null
    $notes = ''
    Assert-Refuse '*without compatibility metadata*' 'A rename passed without metadata.'
    $renameNote = 'Alpha is now Gamma. This causes a selection reset and requires manual re-selection.'
    $compatibility = Allow @((Declaration $baseline.patches[0] $candidate.patches[0] $renameNote))
    $notes = "## Unreleased`n- $renameNote"
    Assert-Accept 'A documented rename failed.'
    $notes = "## Unreleased`n<!-- - $renameNote -->"
    Assert-Refuse '*exact migration note*' 'A rename warning hidden in an HTML comment passed.'
    $notes = "## Unreleased`n<!-- - $renameNote"
    Assert-Refuse '*exact migration note*' 'A rename warning hidden in an unclosed HTML comment passed.'
    $notes = "## Unreleased`n<!-- unrelated comment -->`n- $renameNote"
    Assert-Accept 'A visible rename note after a comment failed.'
    $candidate.patches[0].use = $true
    Assert-Refuse '*after state*' 'A rename allowance covered a different use flag.'
    $candidate.patches[0].use = $false
    $compatibility.changes[0].migrationNote = 'Alpha is now Gamma.'
    $notes = '## Unreleased' + "`n- Alpha is now Gamma."
    Assert-Refuse '*selection reset*manual re-selection*' 'A rename without a reset warning passed.'
    $candidate = Catalog '0.6.0' @((Patch 'alpha' $false), (Patch 'Beta' $true))
    $compatibility = $null
    Assert-Refuse '*without compatibility metadata*' 'Case-only renaming was treated as unchanged.'
    $caseNote = 'Alpha is now alpha. This causes a selection reset and requires manual re-selection.'
    $compatibility = Allow @((Declaration $baseline.patches[0] $candidate.patches[0] $caseNote))
    $notes = "## Unreleased`n- $caseNote"
    Assert-Accept 'An explicit case-only rename failed.'

    $candidate = Catalog '0.6.0' @((Patch 'Beta' $true))
    $compatibility = $null
    Assert-Refuse '*without compatibility metadata*' 'A removal passed without metadata.'
    $removeNote = 'Alpha was removed. This causes a selection reset and requires manual re-selection.'
    $compatibility = Allow @((Declaration $baseline.patches[0] $null $removeNote))
    $notes = "## Unreleased`n- $removeNote"
    Assert-Accept 'A documented removal failed.'
    $compatibility.changes += Copy-Document $compatibility.changes[0]
    Assert-Refuse '*duplicates Alpha*' 'Duplicate allowances passed.'
    $candidate = Copy-Document $baseline
    $compatibility = Allow @((Declaration $baseline.patches[0] $baseline.patches[0] $flipNote))
    $notes = "## Unreleased`n- $flipNote"
    Assert-Refuse '*no-op*' 'An unused no-op allowance passed.'
    $compatibility = Allow @((Declaration $baseline.patches[0] $null $removeNote))
    $notes = "## Unreleased`n- $removeNote"
    Assert-Refuse '*unused*' 'A removal allowance for a surviving patch passed.'
    $candidate = Catalog '0.6.0' @((Patch 'Beta' $true))
    $compatibility = Allow @((Declaration $baseline.patches[0] $candidate.patches[0] $renameNote))
    $notes = "## Unreleased`n- $renameNote"
    Assert-Refuse '*collides*' 'A rename colliding with an existing baseline name passed.'

    $candidate = Copy-Document $baseline
    $candidate.patches[0].use = $true
    $compatibility = Allow @((Declaration $baseline.patches[0] $candidate.patches[0] $flipNote))
    $notes = "## Unreleased`n- $flipNote"
    foreach ($field in @('baselineVersion', 'baselineCommit', 'candidateVersion')) {
        $saved = $compatibility.$field
        $compatibility.$field = 'wrong'
        Assert-Refuse '*exact baseline and candidate*' "A mismatched $field passed."
        $compatibility.$field = $true
        Assert-Refuse '*exact baseline and candidate*' "A Boolean $field passed."
        $compatibility.$field = $saved
    }
    $compatibility.changes[0].before.use = $true
    Assert-Refuse '*before state*' 'An allowance with the wrong published default passed.'
    $compatibility.changes[0].before.use = 'false'
    Assert-Refuse '*Boolean use*' 'An allowance with a string use passed.'
    $compatibility.changes[0].before.use = $false
    $compatibility.changes[0].after = 'Gamma'
    Assert-Refuse '*exactly name and use*' 'A scalar after state passed.'
    $compatibility.changes[0].after = [pscustomobject]@{ name = 'Alpha'; use = $true; extra = 'field' }
    Assert-Refuse '*exactly name and use*' 'An unexpected after field passed.'
    $compatibility.changes[0].after = [pscustomobject]@{ name = 'Alpha'; use = $true }
    $compatibility.changes[0].PSObject.Properties.Remove('after')
    Assert-Refuse '*malformed fields*' 'An omitted after field passed as a removal.'
    $compatibility = Allow @()
    Assert-Refuse '*nonempty array*' 'Empty compatibility declarations passed.'

    $compatibility = $null
    $candidate = Copy-Document $baseline
    $candidate.patches += Patch 'Alpha' $false
    Assert-Refuse '*duplicates patch Alpha*' 'An exact duplicate patch name passed.'
    $candidate = Copy-Document $baseline
    $candidate.patches += Patch 'alpha' $false
    Assert-Accept 'Ordinal names collapsed distinct Alpha and alpha additions.'
    foreach ($badUse in @('false', 0, $null)) {
        $candidate = Copy-Document $baseline
        $candidate.patches[0].use = $badUse
        Assert-Refuse '*Boolean use*' 'A non-Boolean catalog default passed.'
    }
    $candidate = Copy-Document $baseline
    $candidate.patches[0].name = ' Alpha'
    Assert-Refuse '*invalid patch name*' 'A padded patch name passed.'
    $candidate = Copy-Document $baseline
    $baseline.patches[0].use = 'false'
    Assert-Refuse '*Published catalog*Boolean use*' 'A malformed baseline default passed.'
    $baseline.patches[0].use = $false

    # Publication compares the candidate to its predecessor. The next ordinary source push
    # sees that candidate as the published baseline and must remove the consumed allowance.
    $candidate = Catalog '0.7.0' @((Patch 'Gamma' $false), (Patch 'Beta' $true))
    $compatibility = Allow @((Declaration $baseline.patches[0] $candidate.patches[0] $renameNote)) '0.7.0'
    $notes = "## 0.7.0 (2026-10-02)`n* $renameNote`n## Unreleased`nOther changes."
    Assert-Accept 'Publication did not compare to the predecessor.'
    $stale = Test-PatchCatalogCompatibility -BaselinePatchList $candidate -BaselineVersion '0.7.0' `
        -BaselineCommit ('b' * 40) -CandidatePatchList $candidate -CandidateVersion '0.7.0' -Compatibility $compatibility -Changelog $notes
    Assert-True (-not $stale.Valid -and $stale.Reason -like '*exact baseline and candidate*') 'A consumed predecessor allowance survived the next source push.'
    $cleaned = Test-PatchCatalogCompatibility -BaselinePatchList $candidate -BaselineVersion '0.7.0' `
        -BaselineCommit ('b' * 40) -CandidatePatchList $candidate -CandidateVersion '0.7.0' -Changelog $notes
    Assert-True $cleaned.Valid 'Removing the consumed allowance did not restore an ordinary source push.'
    $notes = "## 0.6.0 (2026-09-30)`n- $renameNote`n## 0.7.0 (2026-10-02)`nNo migration."
    Assert-Refuse '*exact migration note*0.7.0*' 'A migration note from the wrong release passed.'

    $first = Catalog '0.1.0' @((Patch 'First' $true))
    $result = Test-PatchCatalogCompatibility -CandidatePatchList $first -CandidateVersion '0.1.0' -FirstRelease
    Assert-True $result.Valid 'A verified first release catalog failed.'
    $result = Test-PatchCatalogCompatibility -CandidatePatchList $first -CandidateVersion '0.1.0' -FirstRelease -Compatibility @{}
    Assert-True (-not $result.Valid) 'A first release accepted malformed compatibility metadata.'
    $result = Test-PatchCatalogCompatibility -CandidatePatchList $first -CandidateVersion '0.1.0'
    Assert-True (-not $result.Valid) 'A missing baseline became a first release without evidence.'

    # Local fixture history tests the strict resolver and hosted-baseline identity without a
    # network or optional working receipt. Its reported applied flags are deliberately different.
    $fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('hushfacebook-selection-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $fixtureRoot | Out-Null
    try {
    function Fixture-Git([string[]]$Arguments) {
        $answer = @(Invoke-RepoGit -Root $fixtureRoot -Arguments $Arguments)
        if ($LASTEXITCODE -ne 0) { throw "Fixture git failed: $($Arguments -join ' ')" }
        return $answer
    }
    Fixture-Git @('init', '--quiet') | Out-Null
    $selectionGitDir = (Fixture-Git @('rev-parse', '--absolute-git-dir') | Select-Object -First 1).Trim()
    Assert-True ([IO.Path]::GetFullPath($selectionGitDir).TrimEnd('\', '/') -ieq [IO.Path]::GetFullPath((Join-Path $fixtureRoot '.git')).TrimEnd('\', '/')) `
        'The selection fixture resolved outside its temporary repository. Refusing to write.'
    Fixture-Git @('config', 'user.name', 'Contracts') | Out-Null
    Fixture-Git @('config', 'user.email', 'contracts@example.invalid') | Out-Null
    Fixture-Git @('commit', '--allow-empty', '-m', 'initial history', '--quiet') | Out-Null
    $emptyCommit = (Fixture-Git @('rev-parse', 'HEAD') | Select-Object -First 1).Trim()
    $absent = @(Fixture-Git @('ls-tree', '--name-only', $emptyCommit, '--', 'patches-bundle.json'))
    Assert-True ($absent.Count -eq 0) 'The first-release fixture does not prove actual index absence.'
    Set-Content -LiteralPath (Join-Path $fixtureRoot 'patches-list.json') -Value ($baseline | ConvertTo-Json -Depth 6) -Encoding UTF8
    Fixture-Git @('add', '--', 'patches-list.json') | Out-Null
    Fixture-Git @('commit', '-m', 'published catalog', '--quiet') | Out-Null
    $publishedCommit = (Fixture-Git @('rev-parse', 'HEAD') | Select-Object -First 1).Trim()
    $legacy = Resolve-ReceiptCatalog -Root $fixtureRoot -Commit '' -WorkingPatchList $candidate
    Assert-True ($legacy.PatchList -eq $candidate -and $null -eq $legacy.Note) 'Legacy missing-commit behavior changed.'
    $legacy = Resolve-ReceiptCatalog -Root $fixtureRoot -Commit $emptyCommit -WorkingPatchList $candidate
    Assert-True ($legacy.PatchList -eq $candidate -and $legacy.Note -like '*no patch list*') 'Legacy missing-catalog behavior changed.'
    Assert-Throws { Resolve-ReceiptCatalog -Root $fixtureRoot -Commit '' -WorkingPatchList $candidate -RequireHistory } '*exact historical commit*' 'Strict mode fell back for a missing commit.'
    Assert-Throws { Resolve-ReceiptCatalog -Root $fixtureRoot -Commit ('f' * 40) -WorkingPatchList $candidate -RequireHistory } '*unavailable*' 'Strict mode accepted an unavailable commit.'
    Assert-Throws { Resolve-ReceiptCatalog -Root $fixtureRoot -Commit $emptyCommit -WorkingPatchList $candidate -RequireHistory } '*no readable patches-list*' 'Strict mode fell back for a missing historical catalog.'
    $strict = Resolve-ReceiptCatalog -Root $fixtureRoot -Commit $publishedCommit -WorkingPatchList $candidate -RequireHistory
    Assert-True ($strict.PatchList.patches[0].name -ceq 'Alpha' -and $strict.PatchList.patches[0].use -eq $false) 'Strict history read working names/defaults.'

    $receiptDocument = [pscustomobject]@{ release = [pscustomobject]@{ version = '0.6.0'; commit = $publishedCommit }
        bundle = [pscustomobject]@{ file = 'patches-0.6.0.mpp'; sha256 = ('d' * 64) }; patches = @([pscustomobject]@{ name = 'Alpha'; applied = $true }) }
    function Receipt-Bytes { return ,[Text.Encoding]::UTF8.GetBytes(($receiptDocument | ConvertTo-Json -Depth 5 -Compress)) }
    function Receipt-Checksum([byte[]]$Bytes, [string]$Version = '0.6.0') {
        $sha = [Security.Cryptography.SHA256]::Create()
        try { $hash = ([BitConverter]::ToString($sha.ComputeHash($Bytes))).Replace('-', '').ToLowerInvariant() }
        finally { $sha.Dispose() }
        return "$hash  release-receipt-$Version.json`n$('d' * 64)  patches-$Version.mpp"
    }
    $receiptBytes = Receipt-Bytes
    $checksum = Receipt-Checksum $receiptBytes
    $index = [pscustomobject]@{ version = '0.6.0'; download_url = 'https://github.com/fixture/catalog/releases/download/v0.6.0/patches-0.6.0.mpp' }
    $tagObject = 'c' * 40
    $tagsText = $tagObject + "`trefs/tags/v0.6.0"
    $tagsText += "`n$publishedCommit`trefs/tags/v0.6.0^{}"
    function Baseline-Here {
        Resolve-PublishedCatalogBaseline -Root $fixtureRoot -WorkingPatchList $candidate -CandidateVersion '0.7.0' `
            -Index $index -ReceiptBytes $receiptBytes -ChecksumText $checksum -RemoteTagsText $tagsText -RemoteTagsExitCode 0
    }
    $published = Baseline-Here
    Assert-True ($published.Commit -ceq $publishedCommit -and $published.PatchList.patches[0].use -eq $false) 'The baseline used an unpeeled tag or receipt applied verdict as default.'
    $savedDownloadUrl = $index.download_url
    foreach ($invalidUrl in @(
        'https://someone@github.com/fixture/catalog/releases/download/v0.6.0/patches-0.6.0.mpp',
        'https://github.com:444/fixture/catalog/releases/download/v0.6.0/patches-0.6.0.mpp',
        'https://github.com/fixture/catalog/releases/download/v0.6.0/patches-0.6.0.mpp?cache=1',
        'https://github.com/fixture/catalog/releases/download/v0.6.0/patches-0.6.0.mpp#part')) {
        $index.download_url = $invalidUrl
        Assert-Throws { Baseline-Here } '*exact hosted bundle*' 'A predecessor URL with userinfo, port, query or fragment passed.'
    }
    $index.download_url = $savedDownloadUrl
    $savedBytes = $receiptBytes
    $receiptBytes = $null
    Assert-Throws { Baseline-Here } '*hosted predecessor receipt or checksum is unavailable*' 'An unavailable predecessor asset passed.'
    $receiptBytes = $savedBytes
    $savedChecksum = $checksum
    $checksum = ('0' * 64) + '  release-receipt-0.6.0.json'
    Assert-Throws { Baseline-Here } '*checksum does not match*' 'Tampered hosted receipt bytes passed.'
    $checksum = "$savedChecksum`n$savedChecksum"
    Assert-Throws { Baseline-Here } '*exactly one*' 'Duplicate hosted checksum records passed.'
    $checksum = $savedChecksum
    foreach ($field in @('file', 'sha256')) {
        $saved = $receiptDocument.bundle.$field
        $receiptDocument.bundle.$field = if ($field -ceq 'file') { 'different.mpp' } else { 'e' * 64 }
        $receiptBytes = Receipt-Bytes; $checksum = Receipt-Checksum $receiptBytes
        Assert-Throws { Baseline-Here } '*indexed bundle checksum*' "A predecessor receipt with the wrong bundle $field passed."
        $receiptDocument.bundle.$field = $true
        $receiptBytes = Receipt-Bytes; $checksum = Receipt-Checksum $receiptBytes
        Assert-Throws { Baseline-Here } '*indexed bundle checksum*' "A Boolean bundle $field passed."
        $receiptDocument.bundle.$field = $saved
    }
    $receiptBytes = Receipt-Bytes; $checksum = Receipt-Checksum $receiptBytes
    $savedChecksum = $checksum
    $checksum = $savedChecksum -replace '(?m)^.*patches-0\.6\.0\.mpp$', ''
    Assert-Throws { Baseline-Here } '*exactly one patches-0.6.0.mpp*' 'A checksum missing the indexed bundle passed.'
    $checksum = "$savedChecksum`n$('d' * 64)  patches-0.6.0.mpp"
    Assert-Throws { Baseline-Here } '*exactly one patches-0.6.0.mpp*' 'Duplicate indexed bundle checksums passed.'
    $checksum = "$savedChecksum`nmalformed  patches-0.6.0.mpp"
    Assert-Throws { Baseline-Here } '*exactly one patches-0.6.0.mpp*' 'A malformed duplicate indexed bundle checksum was hidden.'
    $checksum = $savedChecksum -replace '(?m)^[d]{64}  patches-0\.6\.0\.mpp$', 'malformed  patches-0.6.0.mpp'
    Assert-Throws { Baseline-Here } '*malformed*' 'A malformed indexed bundle checksum passed.'
    $checksum = $savedChecksum
    foreach ($field in @('version', 'commit')) {
        $saved = $receiptDocument.release.$field
        $receiptDocument.release.$field = 'wrong'
        $receiptBytes = Receipt-Bytes; $checksum = Receipt-Checksum $receiptBytes
        Assert-Throws { Baseline-Here } '*version/commit*peeled published tag*' "A wrong receipt $field passed."
        $receiptDocument.release.$field = $true
        $receiptBytes = Receipt-Bytes; $checksum = Receipt-Checksum $receiptBytes
        Assert-Throws { Baseline-Here } '*version/commit*peeled published tag*' "A Boolean receipt $field passed."
        $receiptDocument.release.$field = $saved
    }
    $receiptBytes = Receipt-Bytes; $checksum = Receipt-Checksum $receiptBytes
    $tagsText = "$publishedCommit`trefs/tags/v0.6.0"
    Assert-True ((Baseline-Here).Commit -ceq $publishedCommit) 'A lightweight published tag failed.'
    $tagsText = ''
    Assert-Throws { Baseline-Here } '*tag v0.6.0 is missing*' 'An unavailable published tag passed.'
    Assert-Throws { Resolve-PublishedCatalogBaseline -Root $fixtureRoot -WorkingPatchList $first -CandidateVersion '0.1.0' -RemoteTagsText '' -RemoteTagsExitCode 0 } '*not confirmed absent*' 'A missing index became a first release.'
    Assert-Throws { Resolve-PublishedCatalogBaseline -Root $fixtureRoot -WorkingPatchList $first -CandidateVersion '0.1.0' -PredecessorIndexAbsent -RemoteTagsText '' -RemoteTagsExitCode 1 } '*history is unavailable*' 'Failed remote history became a first release.'
    $firstBaseline = Resolve-PublishedCatalogBaseline -Root $fixtureRoot -WorkingPatchList $first -CandidateVersion '0.1.0' -PredecessorIndexAbsent -RemoteTagsText '' -RemoteTagsExitCode 0
    Assert-True $firstBaseline.FirstRelease 'Confirmed absence and no earlier version history failed first release.'
    $earlierText = "$publishedCommit`trefs/tags/v0.0.9"
    Assert-Throws { Resolve-PublishedCatalogBaseline -Root $fixtureRoot -WorkingPatchList $first -CandidateVersion '0.1.0' -PredecessorIndexAbsent -RemoteTagsText $earlierText -RemoteTagsExitCode 0 } '*earlier version history exists*' 'A 404-style absent index hid prior release history.'
    $earlierText = ('d' * 64) + "`trefs/tags/v0.0.9"
    Assert-Throws { Resolve-PublishedCatalogBaseline -Root $fixtureRoot -WorkingPatchList $first -CandidateVersion '0.1.0' -PredecessorIndexAbsent -RemoteTagsText $earlierText -RemoteTagsExitCode 0 } '*earlier version history exists*' 'An earlier 64-character tag hash was ignored.'

    # Route publication through the predecessor index at the candidate's verified source commit.
    # The working index is already 0.7.0, as it is on the later publication/index push.
    $candidate = Catalog '0.7.0' @((Patch 'Gamma' $false), (Patch 'Beta' $true))
    $compatibility = Allow @((Declaration $baseline.patches[0] $candidate.patches[0] $renameNote)) '0.7.0'
    $notes = "## 0.7.0 (2026-10-02)`n* $renameNote"
    Set-Content -LiteralPath (Join-Path $fixtureRoot 'patches-list.json') -Value ($candidate | ConvertTo-Json -Depth 6) -Encoding UTF8
    Set-Content -LiteralPath (Join-Path $fixtureRoot 'patches-bundle.json') -Value ($index | ConvertTo-Json -Depth 5) -Encoding UTF8
    Fixture-Git @('add', '--', 'patches-list.json', 'patches-bundle.json') | Out-Null
    Fixture-Git @('commit', '-m', 'candidate over predecessor index', '--quiet') | Out-Null
    $candidateCommit = (Fixture-Git @('rev-parse', 'HEAD') | Select-Object -First 1).Trim()
    $workingIndex = [pscustomobject]@{ version = '0.7.0'; download_url = 'https://github.com/fixture/catalog/releases/download/v0.7.0/patches-0.7.0.mpp' }
    Set-Content -LiteralPath (Join-Path $fixtureRoot 'patches-bundle.json') -Value ($workingIndex | ConvertTo-Json -Depth 5) -Encoding UTF8
    Fixture-Git @('add', '--', 'patches-bundle.json') | Out-Null
    Fixture-Git @('commit', '-m', 'published candidate index', '--quiet') | Out-Null
    $selected = Resolve-CatalogBaselineIndex -Root $fixtureRoot -WorkingIndex $workingIndex -CandidateCommit $candidateCommit -VerifyPublishedAsset
    Assert-True ($selected.Index.version -ceq '0.6.0' -and -not $selected.PredecessorIndexAbsent) 'Publication self-compared using the working index instead of the source-commit predecessor.'
    $tagsText = "$publishedCommit`trefs/tags/v0.6.0`n$candidateCommit`trefs/tags/v0.7.0"
    $receiptBytes = Receipt-Bytes; $checksum = Receipt-Checksum $receiptBytes
    $pubBaseline = Resolve-PublishedCatalogBaseline -Root $fixtureRoot -WorkingPatchList $candidate -CandidateVersion '0.7.0' `
        -Index $selected.Index -ReceiptBytes $receiptBytes -ChecksumText $checksum -RemoteTagsText $tagsText -RemoteTagsExitCode 0
    $compatibility.baselineCommit = $pubBaseline.Commit
    $comparison = Test-PatchCatalogCompatibility -BaselinePatchList $pubBaseline.PatchList -BaselineVersion $pubBaseline.Version `
        -BaselineCommit $pubBaseline.Commit -CandidatePatchList $candidate -CandidateVersion '0.7.0' -Compatibility $compatibility -Changelog $notes
    Assert-True $comparison.Valid "The publication predecessor comparison failed: $($comparison.Reason)"
    $ordinary = Resolve-CatalogBaselineIndex -Root $fixtureRoot -WorkingIndex $workingIndex -CandidateCommit $candidateCommit
    Assert-True ($ordinary.Index.version -ceq '0.7.0') 'An ordinary source push used the older source-commit index.'
    $candidateReceiptBytes = [Text.Encoding]::UTF8.GetBytes((@{ release = @{ version = '0.7.0'; commit = $candidateCommit }
        bundle = @{ file = 'patches-0.7.0.mpp'; sha256 = ('d' * 64) } } | ConvertTo-Json -Compress -Depth 4))
    $candidateReceiptHash = Receipt-Checksum $candidateReceiptBytes '0.7.0'
    $nextBaseline = Resolve-PublishedCatalogBaseline -Root $fixtureRoot -WorkingPatchList $candidate -CandidateVersion '0.7.0' `
        -Index $ordinary.Index -ReceiptBytes $candidateReceiptBytes -ChecksumText $candidateReceiptHash -RemoteTagsText $tagsText -RemoteTagsExitCode 0
    $consumed = Test-PatchCatalogCompatibility -BaselinePatchList $nextBaseline.PatchList -BaselineVersion $nextBaseline.Version `
        -BaselineCommit $nextBaseline.Commit -CandidatePatchList $candidate -CandidateVersion '0.7.0' -Compatibility $compatibility -Changelog $notes
    Assert-True (-not $consumed.Valid -and $consumed.Reason -like '*exact baseline and candidate*') 'Index routing allowed a consumed predecessor declaration on an ordinary source push.'
    $removed = Test-PatchCatalogCompatibility -BaselinePatchList $nextBaseline.PatchList -BaselineVersion $nextBaseline.Version `
        -BaselineCommit $nextBaseline.Commit -CandidatePatchList $candidate -CandidateVersion '0.7.0' -Changelog $notes
    Assert-True $removed.Valid 'The next ordinary push still failed after removing consumed declarations.'
    $absentIndex = Resolve-CatalogBaselineIndex -Root $fixtureRoot -WorkingIndex $workingIndex -CandidateCommit $emptyCommit -VerifyPublishedAsset
    Assert-True ($absentIndex.PredecessorIndexAbsent -and $null -eq $absentIndex.Index) 'A real missing source-commit predecessor was not identified.'
    Assert-Throws { Resolve-CatalogBaselineIndex -Root $fixtureRoot -WorkingIndex $workingIndex -CandidateCommit ('f' * 40) -VerifyPublishedAsset } '*source commit is unavailable*' 'An unavailable source commit became an absent predecessor.'
    Set-Content -LiteralPath (Join-Path $fixtureRoot 'patches-bundle.json') -Value '{bad json' -Encoding UTF8
    Set-Content -LiteralPath (Join-Path $fixtureRoot 'patches-list.json') -Value '{bad json' -Encoding UTF8
    Fixture-Git @('add', '--', 'patches-bundle.json', 'patches-list.json') | Out-Null
    Fixture-Git @('commit', '-m', 'malformed historical metadata', '--quiet') | Out-Null
    $badCommit = (Fixture-Git @('rev-parse', 'HEAD') | Select-Object -First 1).Trim()
    Assert-Throws { Resolve-CatalogBaselineIndex -Root $fixtureRoot -WorkingIndex $workingIndex -CandidateCommit $badCommit -VerifyPublishedAsset } '*not readable JSON*' 'Malformed predecessor metadata passed.'
    Assert-Throws { Resolve-ReceiptCatalog -Root $fixtureRoot -Commit $badCommit -WorkingPatchList $candidate -RequireHistory } '*not readable*' 'Malformed historical catalog passed strict mode.'

    } finally {
        if (Test-Path -LiteralPath $fixtureRoot) {
            $resolved = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $fixtureRoot).ProviderPath)
            $expected = [IO.Path]::GetFullPath($fixtureRoot)
            $temporary = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
            if (-not [StringComparer]::OrdinalIgnoreCase.Equals($resolved, $expected) -or
                -not $resolved.StartsWith($temporary, [StringComparison]::OrdinalIgnoreCase)) {
                throw 'The selection fixture resolved outside its temporary directory.'
            }
            Remove-Item -LiteralPath $resolved -Recurse -Force
        }
    }
}

Write-Host '[scripts] published patch selection contracts passed'

# --- validate-release-facts.ps1 -------------------------------------------------------------
#
# The gate the pre-push hook runs on every push that touches a published file, and the one a
# release cannot go out without. Its receipt half has been exercised above since the receipt
# existed; the facts half, which is what holds README.md and patches-bundle.json to the
# generated catalog, had never been shown to fail at all.
#
# Driven against a copy of this checkout rather than a hand-built tree: a fixture assembled by
# hand is a second opinion about what the release files look like, and the thing worth catching
# is a real file drifting from the real catalog. One fact is moved per case.

$factsScript = Join-Path $PSScriptRoot 'validate-release-facts.ps1'
$factsRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("hushfacebook-facts-" + [guid]::NewGuid().ToString('N'))
$factsPreviousWebRequest = Get-Item Function:\Invoke-WebRequest -ErrorAction SilentlyContinue
try {
    New-Item -ItemType Directory -Path $factsRoot -Force | Out-Null
    foreach ($relative in @('patches-list.json', 'patches-bundle.json', 'gradle.properties', 'README.md', 'CHANGELOG.md',
            'CONTRIBUTING.md')) {
        Copy-Item -LiteralPath (Join-Path $Root $relative) -Destination (Join-Path $factsRoot $relative)
    }
    New-Item -ItemType Directory -Path (Join-Path $factsRoot 'gradle') -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $Root 'gradle/libs.versions.toml') `
        -Destination (Join-Path $factsRoot 'gradle/libs.versions.toml')
    $bugFormRelative = '.github/ISSUE_TEMPLATE/bug_report.yml'
    New-Item -ItemType Directory -Path (Join-Path $factsRoot '.github/ISSUE_TEMPLATE') -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $Root $bugFormRelative) -Destination (Join-Path $factsRoot $bugFormRelative)

    # Every run of the check compares the patch names and defaults with the published release's
    # catalog, read through the hosted receipt, SHA256SUMS.txt and the tags on origin. Here each of
    # those is local: a bare repository answers for origin through url.insteadOf, and a stand-in for
    # Invoke-WebRequest serves the receipt and checksum list. The published catalog is this
    # checkout's own, frozen at the start, so a case can move the working catalog away from it.
    $factsPublishedCatalog = Get-Content -LiteralPath (Join-Path $Root 'patches-list.json') -Raw | ConvertFrom-Json
    $factsPublishedCatalogs = @{}
    $factsCatalogRequests = New-Object System.Collections.Generic.List[string]
    $factsCatalogUnavailable = $null
    Invoke-FixtureGit -Root $factsRoot -Arguments @('init', '--quiet') | Out-Null
    $factsGitDir = "$(Invoke-FixtureGit -Root $factsRoot -Arguments @('rev-parse', '--absolute-git-dir') | Select-Object -First 1)".Trim()
    Assert-True ([IO.Path]::GetFullPath($factsGitDir).TrimEnd('\', '/') -ieq [IO.Path]::GetFullPath((Join-Path $factsRoot '.git')).TrimEnd('\', '/')) `
        'The facts catalog fixture resolved outside its temporary repository. Refusing to write.'
    Invoke-FixtureGit -Root $factsRoot -Arguments @('config', 'user.name', 'Contracts') | Out-Null
    Invoke-FixtureGit -Root $factsRoot -Arguments @('config', 'user.email', 'contracts@example.invalid') | Out-Null
    Invoke-FixtureGit -Root $factsRoot -Arguments @('add', '--', 'patches-list.json', 'patches-bundle.json', 'gradle.properties',
        'README.md', 'CHANGELOG.md', 'CONTRIBUTING.md', 'gradle/libs.versions.toml', $bugFormRelative) | Out-Null
    Invoke-FixtureGit -Root $factsRoot -Arguments @('commit', '-m', 'facts fixture', '--quiet') | Out-Null
    $factsPublishedRemote = (Join-Path $factsGitDir 'catalog-published.git').Replace('\', '/')
    Invoke-FixtureGit -Root $factsRoot -Arguments @('init', '--bare', '--quiet', $factsPublishedRemote) | Out-Null
    $factsSourceIndex = Get-Content -LiteralPath (Join-Path $Root 'patches-bundle.json') -Raw | ConvertFrom-Json
    $factsSourceUri = [Uri]$factsSourceIndex.download_url
    $factsCatalogSlug = (@($factsSourceUri.AbsolutePath.Trim('/') -split '/') | Select-Object -First 2) -join '/'
    $factsCatalogRemoteUrl = "https://github.com/$factsCatalogSlug.git"
    Invoke-FixtureGit -Root $factsRoot -Arguments @('config', "url.$factsPublishedRemote.insteadOf", $factsCatalogRemoteUrl) | Out-Null
    Invoke-FixtureGit -Root $factsRoot -Arguments @('remote', 'add', 'origin', $factsCatalogRemoteUrl) | Out-Null

    # Publishes the frozen catalog as release $Version: a commit beside the branch, its tag on the
    # bare origin and the receipt the stand-in serves for it. The receipt's applied verdict is the
    # opposite of the default on purpose, so a check reading defaults off the receipt shows.
    function Register-FactsCatalogVersion([string]$Version) {
        if ($Version -notmatch '^\d+\.\d+\.\d+$' -or $factsPublishedCatalogs.ContainsKey($Version)) { return }
        $catalogPath = Join-Path $factsRoot 'patches-list.json'
        $savedCatalog = [IO.File]::ReadAllBytes($catalogPath)
        $frozen = $factsPublishedCatalog | ConvertTo-Json -Depth 15 | ConvertFrom-Json
        $frozen.version = "v$Version"
        try {
            [IO.File]::WriteAllText($catalogPath, ($frozen | ConvertTo-Json -Depth 15), (New-Object Text.UTF8Encoding($false)))
            Invoke-FixtureGit -Root $factsRoot -Arguments @('add', '--', 'patches-list.json') | Out-Null
            $tree = "$(Invoke-FixtureGit -Root $factsRoot -Arguments @('write-tree') | Select-Object -First 1)".Trim()
            $commit = "$(Invoke-FixtureGit -Root $factsRoot -Arguments @('commit-tree', $tree, '-m', "published selections $Version") | Select-Object -First 1)".Trim()
            Invoke-FixtureGit -Root $factsRoot -Arguments @('push', '--quiet', $factsPublishedRemote, "${commit}:refs/tags/v$Version") | Out-Null
            $receiptPath = Join-Path $factsGitDir "catalog-receipt-$Version.json"
            [IO.File]::WriteAllText($receiptPath, (@{ release = @{ version = $Version; commit = $commit }
                bundle = @{ file = "patches-$Version.mpp"; sha256 = ('d' * 64) }
                patches = @(@{ name = $frozen.patches[0].name; applied = -not $frozen.patches[0].use }) } | ConvertTo-Json -Depth 5),
                (New-Object Text.UTF8Encoding($false)))
            $factsPublishedCatalogs[$Version] = [pscustomobject]@{ Commit = $commit; Receipt = $receiptPath }
        } finally {
            [IO.File]::WriteAllBytes($catalogPath, $savedCatalog)
            Invoke-FixtureGit -Root $factsRoot -Arguments @('reset', '--quiet') | Out-Null
        }
    }

    function Invoke-WebRequest {
        param([Uri]$Uri, $Method, $OutFile, $MaximumRedirection, $TimeoutSec, [switch]$PassThru, [switch]$UseBasicParsing)
        if (-not $UseBasicParsing) { throw "Invoke-WebRequest $Uri without -UseBasicParsing" }
        $factsCatalogRequests.Add($Uri.AbsoluteUri)
        $match = [regex]::Match($Uri.AbsolutePath, '/releases/download/v(\d+\.\d+\.\d+)/(release-receipt-\1\.json|SHA256SUMS\.txt)$')
        if (-not $match.Success -or -not $factsPublishedCatalogs.ContainsKey($match.Groups[1].Value)) {
            throw "No local facts catalog response for $Uri"
        }
        $version = $match.Groups[1].Value
        if ($factsCatalogUnavailable -ceq $match.Groups[2].Value) { throw (New-NotFoundAnswer) }
        $record = $factsPublishedCatalogs[$version]
        $bytes = if ($match.Groups[2].Value -ceq 'SHA256SUMS.txt') {
            [Text.Encoding]::UTF8.GetBytes("$(Get-Sha256Hex $record.Receipt)  release-receipt-$version.json`n$('d' * 64)  patches-$version.mpp`n")
        } else { [IO.File]::ReadAllBytes($record.Receipt) }
        if ($OutFile) { [IO.File]::WriteAllBytes($OutFile, $bytes) }
        return [pscustomobject]@{ StatusCode = 200; Content = $bytes }
    }

    # The release this checkout's index publishes, which the lag windows below copy back in.
    Register-FactsCatalogVersion ([string]$factsSourceIndex.version)

    function Invoke-Facts {
        param([switch]$WithUrls)
        $arguments = @{ Root = $factsRoot; SkipDescriptionTestCount = $true }
        if (-not $WithUrls) { $arguments['SkipUrlCheck'] = $true }
        # 6>, not *>. The check says what it found with Write-Host, which is the information
        # stream, and that is all this wants to hide. Redirecting every stream also swallows the
        # terminating error, so each case below was accepted in silence and proved nothing.
        & $factsScript @arguments 6> $null
    }

    function Set-FactsFile {
        param([string]$Name, [scriptblock]$Edit)
        $path = Join-Path $factsRoot $Name
        $text = Get-Content -LiteralPath $path -Raw
        Set-Content -LiteralPath $path -Value (& $Edit $text) -Encoding UTF8 -NoNewline
    }

    # A prepare window: the source version has moved past the index, which still describes the
    # last release, and the gate allows that lag for an index that did not change. The copied
    # tree is not a release tree then, and holding it to the lagging index failed the control
    # for a reason that had nothing to do with the check (2026-09-17, 0.40.0 being prepared over
    # the 0.39.0 index). The copied index is written up to the catalog it sits beside, its
    # version strings, its patch count and the Facebook build it names, so every case below
    # still moves exactly one fact and is judged on the strict path.
    # A release hold is the same lag with the version standing still: patches join the catalog
    # while the index keeps the published count (2026-09-23, 93 against 0.58.0's 91), so the count
    # is synced even when the version already matches.
    # The build moves the same way. Facebook ships weekly, the catalog takes the new build before
    # the release that publishes it, and the index keeps naming the build it was released on
    # until then. Syncing only the version and the count left the description on the old build,
    # so the control failed on every tree the gate accepted in that window (2026-09-25 review).
    function Sync-FixtureIndex {
        $fixtureVersion = ((Get-Content -LiteralPath (Join-Path $factsRoot 'gradle.properties')) `
            -match '^version\s*=' | Select-Object -First 1) -replace '^version\s*=\s*', ''
        $indexPath = Join-Path $factsRoot 'patches-bundle.json'
        $indexText = Get-Content -LiteralPath $indexPath -Raw
        $index = $indexText | ConvertFrom-Json
        $indexVersion = "$($index.version)"
        $fixtureCatalog = Get-Content -LiteralPath (Join-Path $factsRoot 'patches-list.json') -Raw | ConvertFrom-Json
        $count = @($fixtureCatalog.patches).Count
        $synced = $indexText
        # Bounded by digits, and by a dot on the left, so a version never rewrites part of a build
        # number: 0.10.1 is inside 583.0.0.10.10.
        if ($indexVersion -ne $fixtureVersion) {
            $synced = $synced -replace ('(?<![\d.])' + [regex]::Escape($indexVersion) + '(?!\d)'), $fixtureVersion
        }
        # (?<!\d) and not \b, here and in the count cases below: the raw JSON writes a line break
        # as \n, so a count that opens a paragraph follows the letter n, where \b finds no edge,
        # and an edit that missed it left the description saying the old count.
        $synced = $synced -replace '(?<!\d)\d+ patches\b', "$count patches"
        # The build the description names first is the one the check reads as the published
        # target, so that is the one moved to the catalog's newest, everywhere it appears.
        $fixtureBuild = (Get-PatchTarget -PatchList $fixtureCatalog).PackageVersion
        $indexBuild = [regex]::Match([string]$index.description, 'Facebook\s+(\d+(?:\.\d+)+)')
        if ($indexBuild.Success -and $indexBuild.Groups[1].Value -ne $fixtureBuild) {
            $synced = $synced -replace ('(?<![\d.])' + [regex]::Escape($indexBuild.Groups[1].Value) + '(?!\d)'), $fixtureBuild
        }
        # The index now names the fixture's version, so that version needs a published catalog.
        Register-FactsCatalogVersion $fixtureVersion
        if ($synced -ceq $indexText) { return }
        Set-FactsFile 'patches-bundle.json' { param($text) $synced }
    }

    # The bug form names the published version, which the synced index above now names too.
    function Sync-FixtureBugForm {
        $fixtureVersion = ((Get-Content -LiteralPath (Join-Path $factsRoot 'gradle.properties')) `
            -match '^version\s*=' | Select-Object -First 1) -replace '^version\s*=\s*', ''
        $publishedHere = "$((Get-Content -LiteralPath (Join-Path $Root 'patches-bundle.json') -Raw | ConvertFrom-Json).version)"
        if ($publishedHere -eq $fixtureVersion) { return }
        Set-FactsFile $bugFormRelative {
            param($text) $text -replace ('Version ' + [regex]::Escape($publishedHere) + ' for Facebook'), "Version $fixtureVersion for Facebook"
        }
    }

    # And the README's version badge and the sentence naming the latest release, which a release
    # moves with the index: the fixture's version, and its catalog's count.
    # Every other sentence giving the published count moves with them. Between releases the catalog
    # grows past it (2026-10-18, 0.14.0's 91), and when only the latest-release sentence carried the
    # catalog's count, the case changing that count tripped the README count check first and never
    # reached the check it covers.
    function Sync-FixtureReadme {
        $fixtureVersion = ((Get-Content -LiteralPath (Join-Path $factsRoot 'gradle.properties')) `
            -match '^version\s*=' | Select-Object -First 1) -replace '^version\s*=\s*', ''
        $count = @((Get-Content -LiteralPath (Join-Path $factsRoot 'patches-list.json') -Raw | ConvertFrom-Json).patches).Count
        $readmePath = Join-Path $factsRoot 'README.md'
        $text = Get-Content -LiteralPath $readmePath -Raw
        $publishedCount = [regex]::Match($text, '(?i)latest (?:published )?release is (?:still )?\[v[^\]]*\]\([^)\s]*\), with (\d+) patches')
        if (-not $publishedCount.Success) {
            throw 'Sync-FixtureReadme found no "The latest release is [vX](link), with N patches" sentence to read the published count from.'
        }
        $synced = $text
        if ([int]$publishedCount.Groups[1].Value -ne $count) {
            $synced = $synced -replace ('(?<!\d)' + $publishedCount.Groups[1].Value + ' patches\b'), "$count patches"
        }
        $synced = $synced -replace 'badge/version-\d+(?:\.\d+)+-', "badge/version-$fixtureVersion-" `
            -replace 'alt="Version \d+(?:\.\d+)+"', "alt=`"Version $fixtureVersion`"" `
            -replace '(latest (?:published )?release is (?:still )?\[?v)\d+(?:\.\d+)+', "`${1}$fixtureVersion" `
            -replace '(latest release is \[v[^\]]*\]\([^)\s]*/tag/v)\d+(?:\.\d+)+', "`${1}$fixtureVersion" `
            -replace '(latest release is \[v[^\]]*\]\([^)\s]*\), with )\d+( patches)', "`${1}$count`${2}"
        if ($synced -ceq $text) { return }
        Set-FactsFile 'README.md' { param($unused) $synced }
    }

    function Reset-FactsFile {
        param([string]$Name)
        Copy-Item -LiteralPath (Join-Path $Root $Name) -Destination (Join-Path $factsRoot $Name) -Force
        if ($Name -eq 'patches-bundle.json') { Sync-FixtureIndex }
        if ($Name -eq $bugFormRelative) { Sync-FixtureBugForm }
        if ($Name -eq 'README.md') { Sync-FixtureReadme }
    }

    Sync-FixtureIndex
    Sync-FixtureBugForm
    Sync-FixtureReadme

    # The published patch selections, through the real check and both of its success exits, with
    # no receipt and then a built bundle. Neither may skip the comparison, and the lenient path the
    # hook runs may not either. Each change to the catalog copy (a flipped default, a rename, a
    # removal) comes after a run that passes, is refused naming the patch, and passes again once
    # scripts/patch-catalog-compatibility.json records it and the CHANGELOG carries its note.
    $catalogFixturePath = Join-Path $factsRoot 'patches-list.json'
    $catalogFixtureBytes = [IO.File]::ReadAllBytes($catalogFixturePath)
    $catalogFixtureIndexPath = Join-Path $factsRoot 'patches-bundle.json'
    $catalogFixtureIndexBytes = [IO.File]::ReadAllBytes($catalogFixtureIndexPath)
    $catalogFixtureReadmePath = Join-Path $factsRoot 'README.md'
    $catalogFixtureReadmeBytes = [IO.File]::ReadAllBytes($catalogFixtureReadmePath)
    # A release's source commit has no Unreleased section, since the cut renames it to the version.
    # The cases below record a change against the fixture's own version, and the note for that is
    # read from Unreleased, so a copy without the section gets an empty one for these cases.
    if ((Get-Content -LiteralPath (Join-Path $factsRoot 'CHANGELOG.md') -Raw) -notmatch '(?m)^## Unreleased\s*$') {
        Set-FactsFile 'CHANGELOG.md' { param($text) ([regex]'(?m)^## ').Replace($text, "## Unreleased`n`n## ", 1) }
    }
    $catalogFixtureChangelog = [IO.File]::ReadAllBytes((Join-Path $factsRoot 'CHANGELOG.md'))
    $catalogFixtureList = Get-Content -LiteralPath $catalogFixturePath -Raw | ConvertFrom-Json
    $catalogFixtureVersion = $catalogFixtureList.version.Substring(1)
    $catalogFixtureBaseline = $factsPublishedCatalogs[$catalogFixtureVersion]
    Assert-True ($null -ne $catalogFixtureBaseline) "The facts fixture has no published catalog for $catalogFixtureVersion."
    $catalogFixtureBefore = $catalogFixtureList.patches[0]
    $catalogFixtureBundle = Get-ReleaseBundlePath -Root $factsRoot -Version $catalogFixtureVersion
    $catalogFixtureMetadata = Join-Path $factsRoot 'scripts/patch-catalog-compatibility.json'
    $catalogFixtureWrongPath = Join-Path $factsRoot 'patch-catalog-compatibility.json'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $factsRoot "release-receipt-$catalogFixtureVersion.json")) -and
        -not (Test-Path -LiteralPath $catalogFixtureBundle)) 'The no-receipt/no-bundle catalog fixture is not empty.'
    function Restore-CatalogFixture {
        [IO.File]::WriteAllBytes($catalogFixturePath, $catalogFixtureBytes)
        [IO.File]::WriteAllBytes($catalogFixtureIndexPath, $catalogFixtureIndexBytes)
        [IO.File]::WriteAllBytes($catalogFixtureReadmePath, $catalogFixtureReadmeBytes)
        [IO.File]::WriteAllBytes((Join-Path $factsRoot 'CHANGELOG.md'), $catalogFixtureChangelog)
        foreach ($path in @($catalogFixtureMetadata, $catalogFixtureWrongPath)) {
            if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force }
        }
    }
    try {
        New-Item -ItemType Directory -Path (Split-Path -Parent $catalogFixtureMetadata) -Force | Out-Null
        Set-Content -LiteralPath $catalogFixtureMetadata -Value 'null' -Encoding UTF8
        Assert-Throws { Invoke-Facts } '*compatibility*null*' 'A literal null compatibility file passed the facts gate.'
        Remove-Item -LiteralPath $catalogFixtureMetadata -Force
        $originalUrl = (Get-Content -LiteralPath $catalogFixtureIndexPath -Raw | ConvertFrom-Json).download_url
        foreach ($invalidUrl in @(
            $originalUrl.Replace('https://github.com/', 'https://someone@github.com/'),
            $originalUrl.Replace('https://github.com/', 'https://github.com:444/'),
            ($originalUrl + '?cache=1'), ($originalUrl + '#part'))) {
            Set-FactsFile 'patches-bundle.json' { param($text) $text.Replace($originalUrl, $invalidUrl) }
            $factsCatalogRequests.Clear()
            $invalidUri = [Uri]$invalidUrl
            $expectedUrlError = if ($invalidUri.Query -or $invalidUri.Fragment) {
                '*download URL does not match the generated release facts*'
            } else { '*exact hosted bundle*' }
            Assert-Throws { Invoke-Facts } $expectedUrlError 'A malformed predecessor URL passed the facts gate.'
            Assert-True ($factsCatalogRequests.Count -eq 0) 'The facts gate requested a predecessor URL before validating it.'
            [IO.File]::WriteAllBytes($catalogFixtureIndexPath, $catalogFixtureIndexBytes)
        }
        foreach ($case in @(
                @{ Kind = 'default'; Built = $false }, @{ Kind = 'default'; Built = $true },
                @{ Kind = 'rename'; Built = $false }, @{ Kind = 'removal'; Built = $false })) {
            $label = "$($case.Kind) (built=$($case.Built))"
            if ($case.Built) {
                New-Item -ItemType Directory -Path (Split-Path -Parent $catalogFixtureBundle) -Force | Out-Null
                Add-Type -AssemblyName System.IO.Compression
                Add-Type -AssemblyName System.IO.Compression.FileSystem
                $archive = [IO.Compression.ZipFile]::Open($catalogFixtureBundle, [IO.Compression.ZipArchiveMode]::Create)
                try {
                    $writer = New-Object IO.StreamWriter(($archive.CreateEntry('META-INF/MANIFEST.MF')).Open())
                    try {
                        $pin = (Read-CatalogToolchain -Text (Get-Content -LiteralPath (Join-Path $factsRoot 'gradle/libs.versions.toml') -Raw) -Source 'catalog fixture').PatcherVersion
                        $writer.Write("Manifest-Version: 1.0`nPatcher-Version: $pin`n`n")
                    } finally { $writer.Dispose() }
                } finally { $archive.Dispose() }
            }
            try {
                # The positive control: the unchanged copy passes, and only after asking for the
                # published receipt and its checksum list.
                $factsCatalogRequests.Clear()
                Invoke-Facts
                Assert-True (@($factsCatalogRequests | Where-Object { $_ -like "*/release-receipt-$catalogFixtureVersion.json" }).Count -gt 0 -and
                    @($factsCatalogRequests | Where-Object { $_ -like '*/SHA256SUMS.txt' }).Count -gt 0) `
                    "The facts script's $label control skipped published selection history without a local receipt."
                $name = [string]$catalogFixtureBefore.name
                $edited = Get-Content -LiteralPath $catalogFixturePath -Raw | ConvertFrom-Json
                switch ($case.Kind) {
                    'default' {
                        $edited.patches[0].use = -not $catalogFixtureBefore.use
                        $after = @{ name = $name; use = $edited.patches[0].use }
                        $note = "$name changes its default selection."
                    }
                    'rename' {
                        $edited.patches[0].name = "$name renamed"
                        $after = @{ name = $edited.patches[0].name; use = $catalogFixtureBefore.use }
                        $note = "$name is now $name renamed. This causes a selection reset and requires manual re-selection."
                    }
                    'removal' {
                        $edited.patches = @($edited.patches | Select-Object -Skip 1)
                        $after = $null
                        $note = "$name was removed. This causes a selection reset and requires manual re-selection."
                    }
                }
                Set-Content -LiteralPath $catalogFixturePath -Value ($edited | ConvertTo-Json -Depth 15) -Encoding UTF8
                # A removal moves the count, which the index and the README are held to first.
                if ($case.Kind -eq 'removal') { Sync-FixtureIndex; Sync-FixtureReadme }
                $refusal = "*Patch $([Management.Automation.WildcardPattern]::Escape($name)) was removed, renamed or changed its default without compatibility metadata*"
                Assert-Throws { Invoke-Facts } $refusal "The facts check accepted an undocumented $label."
                Assert-Throws { & $factsScript -Root $factsRoot -SkipDescriptionTestCount -SkipUrlCheck -AllowPublishedIndexLag 6> $null } `
                    $refusal "AllowPublishedIndexLag bypassed the selection check for a $label."
                $metadata = @{ baselineVersion = $catalogFixtureVersion; baselineCommit = $catalogFixtureBaseline.Commit
                    candidateVersion = $catalogFixtureVersion; changes = @(@{ before = @{ name = $name; use = $catalogFixtureBefore.use }
                        after = $after; migrationNote = $note }) }
                Set-Content -LiteralPath $catalogFixtureWrongPath -Value ($metadata | ConvertTo-Json -Depth 6) -Encoding UTF8
                Assert-Throws { Invoke-Facts } $refusal "A root-level compatibility file bypassed the scripts path for a $label."
                Remove-Item -LiteralPath $catalogFixtureWrongPath -Force
                Set-Content -LiteralPath $catalogFixtureMetadata -Value ($metadata | ConvertTo-Json -Depth 6) -Encoding UTF8
                Assert-Throws { Invoke-Facts } "*no exact migration note in Unreleased*" `
                    "A recorded $label passed with no CHANGELOG note."
                Set-FactsFile 'CHANGELOG.md' { param($text) $text -replace '(?m)^## Unreleased\s*$', ("## Unreleased`n`n- " + $note) }
                $global:LASTEXITCODE = 0
                try { Invoke-Facts } catch { throw "The facts check refused a recorded and documented $label`: $($_.Exception.Message)" }
                Assert-True ($LASTEXITCODE -eq 0 -or $null -eq $LASTEXITCODE) "The facts check refused a recorded and documented $label."
            } finally {
                Restore-CatalogFixture
                if (Test-Path -LiteralPath $catalogFixtureBundle) { Remove-Item -LiteralPath $catalogFixtureBundle -Force }
            }
        }
        $factsCatalogUnavailable = "release-receipt-$catalogFixtureVersion.json"
        Assert-Throws { Invoke-Facts } "*release-receipt-$catalogFixtureVersion.json*" 'Unavailable published history passed the actual facts gate.'
        $factsCatalogUnavailable = $null
    } finally {
        $factsCatalogUnavailable = $null
        Restore-CatalogFixture
        Reset-FactsFile 'CHANGELOG.md'
        if (Test-Path -LiteralPath $catalogFixtureBundle) { Remove-Item -LiteralPath $catalogFixtureBundle -Force }
    }

    # The control. Everything below is this same tree with one fact moved, so a failure there is
    # the moved fact talking and not the fixture being wrong.
    Invoke-Facts
    Assert-True ($LASTEXITCODE -eq 0 -or $null -eq $LASTEXITCODE) `
        'The release facts check refused an unmodified copy of this checkout.'

    $catalogVersion = ((Get-Content -LiteralPath (Join-Path $factsRoot 'gradle.properties')) `
        -match '^version\s*=' | Select-Object -First 1) -replace '^version\s*=\s*', ''

    # The windows the sync above exists for, built the way CONTRIBUTING's "When Facebook updates"
    # builds them: the catalog and the README move to a newer Facebook build while the index
    # still describes the last release. Prepared, the version is bumped and the CHANGELOG has
    # its entry; held, the version stays. The gate's own lenient run accepts both, and the
    # control built from the same tree has to as well, or every script push in that window stops.
    $newestBuild = (Get-PatchTarget -PatchList (Get-Content -LiteralPath (Join-Path $factsRoot 'patches-list.json') -Raw |
        ConvertFrom-Json)).PackageVersion
    $movedBuild = "$([int]($newestBuild -split '\.')[0] + 3).0.0.10.10"
    $current = [version]$catalogVersion
    $nextVersion = "$($current.Major).$($current.Minor).$($current.Build + 1)"
    foreach ($window in @('prepared', 'held')) {
        if ($window -eq 'prepared') {
            Set-FactsFile 'gradle.properties' {
                param($text) $text -replace '(?m)^(\s*version\s*=\s*)\S+', "`${1}$nextVersion"
            }
            Set-FactsFile 'patches-list.json' {
                param($text) $text.Replace('"v' + $catalogVersion + '"', '"v' + $nextVersion + '"')
            }
            Set-FactsFile 'CHANGELOG.md' {
                param($text) ([regex]'(?m)^## ').Replace($text,
                    "## $nextVersion (2026-09-26)`n`n* Moves the patches to Facebook $movedBuild.`n`n## ", 1)
            }
            # The badge moves with the source; the sentence naming the latest release waits for it.
            Set-FactsFile 'README.md' {
                param($text) $text -replace 'badge/version-\d+(?:\.\d+)+-', "badge/version-$nextVersion-" `
                    -replace 'alt="Version \d+(?:\.\d+)+"', "alt=`"Version $nextVersion`""
            }
        }
        Set-FactsFile 'patches-list.json' { param($text) $text.Replace($newestBuild, $movedBuild) }
        Set-FactsFile 'README.md' { param($text) $text.Replace($newestBuild, $movedBuild) }
        # What the maintainer's tree holds: the published index and form as they were, the form's
        # build moved with the catalog.
        Copy-Item -LiteralPath (Join-Path $Root 'patches-bundle.json') -Destination (Join-Path $factsRoot 'patches-bundle.json') -Force
        Copy-Item -LiteralPath (Join-Path $Root $bugFormRelative) -Destination (Join-Path $factsRoot $bugFormRelative) -Force
        # And its README sentence naming that published release, which the sync above moved to the
        # source's version. The two only match when this checkout's source is the published one.
        $publishedHere = "$((Get-Content -LiteralPath (Join-Path $Root 'patches-bundle.json') -Raw | ConvertFrom-Json).version)"
        Set-FactsFile 'README.md' {
            param($text) $text -replace '(latest (?:published )?release is (?:still )?\[?v)\d+(?:\.\d+)+', "`${1}$publishedHere" `
                -replace '(latest release is \[v[^\]]*\]\([^)\s]*/tag/v)\d+(?:\.\d+)+', "`${1}$publishedHere"
        }
        Set-FactsFile $bugFormRelative {
            param($text) $text -replace ('(placeholder:\s*Version \S+ for Facebook )' + [regex]::Escape($newestBuild)), "`${1}$movedBuild"
        }
        $global:LASTEXITCODE = 0
        & $factsScript -Root $factsRoot -SkipDescriptionTestCount -AllowPublishedIndexLag -SkipUrlCheck 6> $null
        Assert-True ($LASTEXITCODE -eq 0) "The gate refused the $window tree this case is built on, so it proves nothing."
        # Lagging, the README may name the source's version or the published one, and no other.
        $windowReadme = Get-Content -LiteralPath (Join-Path $factsRoot 'README.md') -Raw
        foreach ($stale in @(
                @{ Pattern = '*version badge names 0.1.0*'; Edit = { param($text) $text -replace 'badge/version-\d+(?:\.\d+)+-', 'badge/version-0.1.0-' } },
                @{ Pattern = '*latest release is v0.1.0*'; Edit = { param($text) $text -replace '(?i)(latest (?:published )?release is (?:still )?\[?v)\d+(?:\.\d+)+', '${1}0.1.0' } })) {
            Set-FactsFile 'README.md' $stale.Edit
            Assert-Throws { & $factsScript -Root $factsRoot -SkipDescriptionTestCount -AllowPublishedIndexLag -SkipUrlCheck 6> $null } `
                $stale.Pattern "The gate accepted a $window README naming a version that is neither the source's nor the published one."
            Set-FactsFile 'README.md' { param($unused) $windowReadme }
        }
        Sync-FixtureIndex
        Sync-FixtureBugForm
        Sync-FixtureReadme
        try {
            Invoke-Facts
        } catch {
            throw "The release facts check refused the $window tree the gate accepts: $($_.Exception.Message)"
        }
        Assert-True ($LASTEXITCODE -eq 0 -or $null -eq $LASTEXITCODE) `
            "The release facts check refused the $window tree the gate accepts."
        $syncedDescription = [string](Get-Content -LiteralPath (Join-Path $factsRoot 'patches-bundle.json') -Raw | ConvertFrom-Json).description
        Assert-True ($syncedDescription -match "Facebook $([regex]::Escape($movedBuild))(?!\d)" -and
            $syncedDescription -notmatch [regex]::Escape($newestBuild)) `
            "The $window index was not synced to the catalog's build: $syncedDescription"
        foreach ($name in @('gradle.properties', 'patches-list.json', 'README.md', 'CHANGELOG.md',
                'patches-bundle.json', $bugFormRelative)) {
            Reset-FactsFile $name
        }
    }

    # The sync's version edit is bounded on both sides, because a version can sit in the build
    # number the index describes. Inside one, as 0.10.1 is in 586.0.0.10.10, either bound stops the
    # edit; at the end of one, as in 586.0.0.10.1, only the dot before it does; at the start, as in
    # 0.10.15, only the digit after it. Unbounded, syncing that index to 0.10.2 rewrote the build, the
    # build edit then found nothing to move, and the control would have refused the tree over a
    # build nobody released. The described builds sit three majors past the catalog's, so none is
    # the catalog's own, and the edit starts from what the index names: between a retarget and its
    # release the index still names the last release's version and build, not the catalog's.
    $indexBefore = Get-Content -LiteralPath (Join-Path $factsRoot 'patches-bundle.json') -Raw | ConvertFrom-Json
    $indexedVersion = [string]$indexBefore.version
    $indexedBuild = [regex]::Match([string]$indexBefore.description, 'Facebook (\d+(?:\.\d+){4})').Groups[1].Value
    Assert-True ($indexedVersion -and $indexedBuild) "The index names no version or Facebook build: $($indexBefore.description)"
    $unreleasedMajor = [int]($newestBuild -split '\.')[0] + 3
    foreach ($describedBuild in @("$unreleasedMajor.0.0.10.10", "$unreleasedMajor.0.0.10.1", '0.10.15')) {
        Set-FactsFile 'patches-bundle.json' {
            param($text) ($text -replace ('(?<![\d.])' + [regex]::Escape($indexedVersion) + '(?!\d)'), '0.10.1').Replace(
                $indexedBuild, $describedBuild)
        }
        Set-FactsFile 'gradle.properties' { param($text) $text -replace '(?m)^(\s*version\s*=\s*)\S+', '${1}0.10.2' }
        try {
            Sync-FixtureIndex
            $syncedDescription = [string](Get-Content -LiteralPath (Join-Path $factsRoot 'patches-bundle.json') -Raw | ConvertFrom-Json).description
            Assert-True ($syncedDescription -match "\bv0\.10\.2\b" -and
                $syncedDescription -match "Facebook $([regex]::Escape($newestBuild))(?!\d)" -and
                $syncedDescription -notmatch "Facebook (?:$unreleasedMajor|0\.10)\.") `
                "Syncing an index at 0.10.1 that describes Facebook $describedBuild to 0.10.2 did not move it to the catalog's build: $syncedDescription"
        } finally {
            Reset-FactsFile 'gradle.properties'
            Reset-FactsFile 'patches-bundle.json'
        }
    }

    # Manager decodes created_at as kotlinx.datetime.LocalDateTime, not Instant.
    # A trailing Z produces its generic "remote metadata file is unavailable" error,
    # even when both the JSON and bundle download answer HTTP 200.
    foreach ($invalidTimestamp in @(
            '"2026-09-20T21:23:31Z"',
            '"2026-09-20T21:23:31+00:00"',
            '"2026-02-30T21:23:31"',
            '"2026-09-20"',
            '""',
            'null',
            '1790000000')) {
        Set-FactsFile 'patches-bundle.json' {
            param($text)
            $text -replace '"created_at"\s*:\s*("[^"\r\n]*"|null|\d+)', ('"created_at": ' + $invalidTimestamp)
        }
        Assert-Throws { Invoke-Facts } '*created_at*' `
            "An index with a Manager-incompatible created_at was accepted: $invalidTimestamp"
        Reset-FactsFile 'patches-bundle.json'
    }
    Set-FactsFile 'patches-bundle.json' {
        param($text) $text -replace '"created_at"\s*:\s*"[^"\r\n]*"\s*,', ''
    }
    Assert-Throws { Invoke-Facts } '*created_at*' 'An index without created_at was accepted.'
    Reset-FactsFile 'patches-bundle.json'

    # A published index naming a version the catalog does not build. This is the shape v0.28.0
    # shipped in: the index said one thing and the bundle behind it was another.
    Set-FactsFile 'patches-bundle.json' {
        param($text) $text -replace [regex]::Escape('"' + $catalogVersion + '"'), '"0.0.1"'
    }
    Assert-Throws { Invoke-Facts } '*' 'A published index naming another version was accepted.'
    Reset-FactsFile 'patches-bundle.json'

    # The count the published description quotes, which is what somebody reads before they
    # install. A number nobody would notice by eye, which is why the catalog is the source of it.
    Set-FactsFile 'patches-bundle.json' {
        param($text) $text -replace '(?<!\d)\d+ patches\b', '3 patches'
    }
    Assert-Throws { Invoke-Facts } '*' 'An index counting patches the catalog does not have was accepted.'
    Reset-FactsFile 'patches-bundle.json'

    # Read one way, all at once: every "N patches" the description says has to be one count and
    # every "Facebook <build>" one build. The lag check read the first of each and the equality
    # check any, so a description quoting the catalog's count once and a stale one elsewhere, or
    # naming another build beside the target, passed the equality check. On both paths now.
    $indexCount = [regex]::Match([string](Get-Content -LiteralPath (Join-Path $factsRoot 'patches-bundle.json') -Raw |
        ConvertFrom-Json).description, '(?<![\d.])(\d+) patches\b').Groups[1].Value
    $indexCounts = [regex]::Matches((Get-Content -LiteralPath (Join-Path $factsRoot 'patches-bundle.json') -Raw), '(?<!\d)\d+ patches\b').Count
    Assert-True ($indexCounts -ge 2) "The copied description names its patch count once, so the stale-count case would prove nothing."
    $factsTarget = (Get-PatchTarget -PatchList (Get-Content -LiteralPath (Join-Path $factsRoot 'patches-list.json') -Raw |
        ConvertFrom-Json)).PackageVersion
    # The facts end at the description's first ### group, whose CHANGELOG bullets may name any
    # build (0.14.0's retarget bullet names its own), so the other-build edit is made before it.
    # Read here from the raw JSON, where the group's line breaks are written \n.
    $factsPart = {
        param($text) $end = $text.IndexOf('\n\n###')
        if ($end -lt 0) { $text } else { $text.Substring(0, $end) }
    }
    Assert-True ([regex]::Matches((& $factsPart (Get-Content -LiteralPath (Join-Path $factsRoot 'patches-bundle.json') -Raw)),
            "Facebook $([regex]::Escape($factsTarget))(?!\d)").Count -ge 2) `
        "The copied description's facts name Facebook $factsTarget once, so the other-build case would prove nothing."
    foreach ($case in @(
            @{ Name = 'a stale count beside the catalog''s'; Pattern = "*names 3 and $indexCount patches, and it has to name one count*"
                Edit = { param($text) ([regex]'(?<!\d)\d+ patches\b').Replace($text, '3 patches', 1) } },
            @{ Name = 'another build beside the target'; Pattern = "*names Facebook $factsTarget and Facebook 9.9.9.9.9, and it has to name one build*"
                Edit = { param($text) $at = (& $factsPart $text).LastIndexOf("Facebook $factsTarget")
                    $text.Substring(0, $at) + 'Facebook 9.9.9.9.9' + $text.Substring($at + "Facebook $factsTarget".Length) } })) {
        Set-FactsFile 'patches-bundle.json' $case.Edit
        try {
            Assert-Throws { Invoke-Facts } $case.Pattern "An index description naming $($case.Name) was accepted."
            Assert-Throws { & $factsScript -Root $factsRoot -SkipDescriptionTestCount -AllowPublishedIndexLag -SkipUrlCheck 6> $null } `
                $case.Pattern "The lenient check read an index description naming $($case.Name) as one fact."
        } finally {
            Reset-FactsFile 'patches-bundle.json'
        }
    }

    # The release's CHANGELOG groups follow the facts in the description (#143), and a bullet there
    # may name an older build or another count, so the facts are read up to the first ### line.
    Set-FactsFile 'patches-bundle.json' {
        param($text) $text -replace '("description":\s*"(?:[^"\\]|\\.)*)"', '$1\n\n### Fixed\n\n* Builds before Facebook 9.9.9.9.9 kept 3 patches."'
    }
    try {
        Assert-True ((Get-Content -LiteralPath (Join-Path $factsRoot 'patches-bundle.json') -Raw | ConvertFrom-Json).description -match
            '(?m)^### Fixed$') 'The description edit added no ### group, so the groups case would prove nothing.'
        try {
            Invoke-Facts
        } catch {
            throw "The release facts check read a CHANGELOG bullet under the description's facts as one: $($_.Exception.Message)"
        }
    } finally {
        Reset-FactsFile 'patches-bundle.json'
    }

    # The same number in the README, which is the other half of the same promise.
    Set-FactsFile 'README.md' {
        param($text) $text -replace '(?<!\d)\d+ patches\b', '3 patches'
    }
    Assert-Throws { Invoke-Facts } '*' 'A README counting patches the catalog does not have was accepted.'
    Reset-FactsFile 'README.md'

    # The version badge and the sentence naming the latest release, which open the README and which
    # nothing read: a copy with both at 0.1.0 passed. Each is held to the release, on both paths.
    foreach ($case in @(
            @{ Name = 'a badge picture naming another version'; Pattern = '*version badge names*0.1.0*'
                Edit = { param($text) $text -replace 'badge/version-\d+(?:\.\d+)+-', 'badge/version-0.1.0-' } },
            @{ Name = 'a badge alt text naming another version'; Pattern = '*version badge names*0.1.0*'
                Edit = { param($text) $text -replace 'alt="Version \d+(?:\.\d+)+"', 'alt="Version 0.1.0"' } },
            @{ Name = 'no version badge'; Pattern = '*no version badge*'
                Edit = { param($text) $text -replace '<img src="https://img\.shields\.io/badge/version-[^>]*>', '' } },
            @{ Name = 'a latest release of another version'; Pattern = '*latest release is v0.1.0*'
                Edit = { param($text) $text -replace '(?i)(latest (?:published )?release is (?:still )?\[v)\d+(?:\.\d+)+(\]\([^)\s]*/tag/v)\d+(?:\.\d+)+', '${1}0.1.0${2}0.1.0' -replace '(?i)(latest (?:published )?release is (?:still )?v)\d+(?:\.\d+)+', '${1}0.1.0' } },
            @{ Name = 'a latest release linked to another tag'; Pattern = '*links it to*/tag/v0.1.0*'
                Edit = { param($text) $text -replace '(?i)(latest (?:published )?release is (?:still )?\[v\d+(?:\.\d+)+\]\([^)\s]*/tag/v)\d+(?:\.\d+)+', '${1}0.1.0' -replace '(?i)(latest (?:published )?release is (?:still )?)v(\d+(?:\.\d+)+)', '${1}[v${2}](https://github.com/SysAdminDoc/Hushfacebook/releases/tag/v0.1.0)' } },
            @{ Name = 'a latest release counting other patches'; Pattern = '*latest release has 13 patches*'
                Edit = { param($text) $text -replace '(?i)(latest (?:published )?release is (?:still )?(?:\[v[^\]]+\]\([^)\s]*\)|v\d+(?:\.\d+)+), with )\d+( patches)', '${1}13${2}' } },
            @{ Name = 'no sentence naming the latest release'; Pattern = '*does not say which release is the latest*'
                Edit = { param($text) $text -replace '(?i)The latest (?:published )?release is (?:still )?(?:\[v[^\]]+\]\([^)\s]*\)|v\d+(?:\.\d+)+), with \d+ patches\.', 'Releases are on GitHub.' } })) {
        $unedited = Get-Content -LiteralPath (Join-Path $factsRoot 'README.md') -Raw
        Set-FactsFile 'README.md' $case.Edit
        try {
            Assert-True ((Get-Content -LiteralPath (Join-Path $factsRoot 'README.md') -Raw) -cne $unedited) `
                "The README case '$($case.Name)' changed nothing, so it proves nothing."
            Assert-Throws { Invoke-Facts } $case.Pattern "A README with $($case.Name) was accepted."
            Assert-Throws { & $factsScript -Root $factsRoot -SkipDescriptionTestCount -AllowPublishedIndexLag -SkipUrlCheck 6> $null } `
                $case.Pattern "The lenient check accepted a README with $($case.Name)."
        } finally {
            Reset-FactsFile 'README.md'
        }
    }

    # The Manager floor in the README is what stops somebody being told to use a Manager that
    # refuses the bundle, so it is held to the patcher the catalog pins.
    #
    # The floor is read out of the fixture rather than written here. It used to name 1.29.0, and
    # when the catalog moved to Manager 1.30.0 the replacement below stopped matching: the README
    # went in unchanged, the check passed as it should have, and this case failed with "No error
    # was raised" while the gate it covers was working perfectly. A version literal in a test
    # goes stale on the next bump, and does it silently until something reads the message.
    $fixtureFloor = ([regex]::Match(
        (Get-Content -LiteralPath (Join-Path $factsRoot 'gradle/libs.versions.toml') -Raw),
        '(?m)^\s*manager-floor\s*=\s*"([^"]+)"')).Groups[1].Value
    Assert-True ($fixtureFloor -match '^\d+\.\d+\.\d+$') `
        "The fixture catalog does not pin a Manager floor, so this case would prove nothing: $fixtureFloor"
    Set-FactsFile 'README.md' {
        param($text) $text -replace ('Morphe Manager ' + [regex]::Escape($fixtureFloor) + ' or newer'),
            'Morphe Manager 1.20.0 or newer'
    }
    Assert-Throws { Invoke-Facts } '*' 'A README naming a Manager older than the patcher needs was accepted.'
    Reset-FactsFile 'README.md'

    # The same floor in the install step, where a link sits between the name and the version, so
    # the pattern above never reached it and a stale step passed while the badge was right. And in
    # the badge's picture, which carries it URL-encoded.
    Set-FactsFile 'README.md' {
        param($text) $text -replace ('(\[Morphe Manager\]\([^)\s]+\)\s+)' + [regex]::Escape($fixtureFloor) + ' or newer'),
            '${1}1.20.0 or newer'
    }
    Assert-Throws { Invoke-Facts } '*Morphe Manager 1.20.0 or newer*' `
        'A README whose install step names an older Manager was accepted.'
    Reset-FactsFile 'README.md'
    Set-FactsFile 'README.md' {
        param($text) $text.Replace("Morphe%20Manager%20$fixtureFloor%2B", 'Morphe%20Manager%201.20.0%2B')
    }
    Assert-Throws { Invoke-Facts } '*README badge Manager floor*' 'A README badge showing an older Manager was accepted.'
    Reset-FactsFile 'README.md'

    # CONTRIBUTING names the patcher and the floor too, and it described "the v0.8.0 bundle" two
    # releases on with nothing reading it. A release named there, an older floor or another
    # patcher is refused.
    foreach ($case in @(
            @{ Name = 'the bundle described by its release'; Pattern = '*describes the bundle by a release*'
                Edit = { param($text) $text -replace '(?m)^The bundle uses Morphe patcher', 'The v0.8.0 bundle uses Morphe patcher' } },
            @{ Name = 'an older Manager floor'; Pattern = '*Morphe Manager 1.20.0 or newer*'
                Edit = { param($text) $text -replace ('Morphe Manager ' + [regex]::Escape($fixtureFloor) + ' or newer'), 'Morphe Manager 1.20.0 or newer' } },
            @{ Name = 'another patcher'; Pattern = '*CONTRIBUTING patcher version*'
                Edit = { param($text) $text -replace 'Morphe patcher \d+(?:\.\d+)+', 'Morphe patcher 1.0.0' } })) {
        $unedited = Get-Content -LiteralPath (Join-Path $factsRoot 'CONTRIBUTING.md') -Raw
        Set-FactsFile 'CONTRIBUTING.md' $case.Edit
        try {
            Assert-True ((Get-Content -LiteralPath (Join-Path $factsRoot 'CONTRIBUTING.md') -Raw) -cne $unedited) `
                "The CONTRIBUTING case '$($case.Name)' changed nothing, so it proves nothing."
            Assert-Throws { Invoke-Facts } $case.Pattern "A CONTRIBUTING.md with $($case.Name) was accepted."
        } finally {
            Reset-FactsFile 'CONTRIBUTING.md'
        }
    }

    # And each copy has to be there. The alt text and install step cases above name a stale floor,
    # which the stale-floor check refuses before the presence checks run, so neither presence check
    # was ever reached. An alt text with no version, or a step reworded past the stale-floor
    # pattern ("or later" for "or newer"), leaves no stale floor to find, and each is refused by
    # its own check's name.
    Set-FactsFile 'README.md' {
        param($text) $text.Replace("alt=`"For Morphe Manager $fixtureFloor or newer`"", 'alt="For Morphe Manager"')
    }
    Assert-Throws { Invoke-Facts } '*README badge alt Manager floor*' 'A README badge whose alt text names no Manager was accepted.'
    Reset-FactsFile 'README.md'
    Set-FactsFile 'README.md' {
        param($text) $text -replace ('(?m)^(\d+\.\s+Install \[Morphe Manager\]\([^)\s]+\))\s+' + [regex]::Escape($fixtureFloor) +
            '\s+or newer\.'), '${1}, version 1.31.0 or later.'
    }
    Assert-Throws { Invoke-Facts } '*README install step Manager floor*' `
        'A README install step reworded past the stale-floor check was accepted.'
    Reset-FactsFile 'README.md'

    # The heading that says this version shipped. Renaming it is what happened on 2026-09-14,
    # and the file is read by this check and by nothing else.
    Set-FactsFile 'CHANGELOG.md' {
        param($text) $text -replace ('(?m)^##\s+' + [regex]::Escape($catalogVersion) + '\b.*$'), '## Unreleased'
    }
    Assert-Throws { Invoke-Facts } '*' 'A CHANGELOG with no heading for the built version was accepted.'
    Reset-FactsFile 'CHANGELOG.md'

    # The bug form's placeholders, which sat three releases behind the target on Hushfeed before
    # anything read them. One for the version line, one for the manager line.
    Set-FactsFile $bugFormRelative {
        param($text) $text -replace '(placeholder:\s*Version \S+ for Facebook )\S+', '${1}46.2.3'
    }
    Assert-Throws { Invoke-Facts } '*bug report form version placeholder*' `
        'A bug report form naming an old Facebook build was accepted.'
    Reset-FactsFile $bugFormRelative
    Set-FactsFile $bugFormRelative {
        param($text) $text -replace '(placeholder:\s*Morphe Manager )\S+', '${1}1.20.0'
    }
    Assert-Throws { Invoke-Facts } '*bug report form Manager placeholder*' `
        'A bug report form naming a Manager below the floor was accepted.'
    Reset-FactsFile $bugFormRelative

    # The download address, one check at a time in the order the script makes them: the release
    # asset's path, HTTPS, then GitHub. Each is refused by name, because a pattern that accepts
    # any error let this case pass on the shape check while it said it tested a dead link.
    foreach ($address in @(
            @{ From = 'https://github\.com/SysAdminDoc/Hushfacebook/releases/download/[^"]+'; To = 'http://127.0.0.1:1/patches.mpp'
                Pattern = '*patches-bundle.json download URL does not match*'; Name = 'an index whose address is not the release asset' },
            @{ From = '"https://github\.com/'; To = '"http://github.com/'
                Pattern = '*must use HTTPS*'; Name = 'an index serving the bundle over plain HTTP' },
            @{ From = '"https://github\.com/'; To = '"https://example.com/'
                Pattern = '*must be on github.com*'; Name = 'an index serving the bundle from another host' })) {
        Set-FactsFile 'patches-bundle.json' { param($text) $text -replace $address.From, $address.To }
        Assert-Throws { Invoke-Facts } $address.Pattern "The release facts check accepted $($address.Name)."
        Reset-FactsFile 'patches-bundle.json'
    }

    # A dead link: the address has every right shape and names a release nobody published, which
    # is how the index once pointed at a tag that did not exist yet. GitHub answers 404 for it;
    # here a stand-in for the request does, so the case needs no network. The fetch itself is
    # held to a refused connection with the shared helpers below.
    & {
        function Invoke-WebRequest { throw (New-NotFoundAnswer) }
        Assert-Throws { Invoke-Facts -WithUrls } '*indexed bundle URL https://github.com/*answered HTTP 404*' `
            'An index naming a release nobody published was accepted.'
    }

    # The test counts the description quotes, which only the strict path reads: a release, or the
    # push that rewrites the index. The copied tree holds no test results, so each folder gets a
    # suite of exactly as many tests as the copied description names, and one fact moves per case.
    $factsDescription = [string](Get-Content -LiteralPath (Join-Path $factsRoot 'patches-bundle.json') -Raw |
        ConvertFrom-Json).description
    $runtimeQuoted = [int]([regex]::Match($factsDescription, '\b(\d+) runtime tests passed\b').Groups[1].Value)
    $patchQuoted = [int]([regex]::Match($factsDescription, '\b(\d+) patch tests passed\b').Groups[1].Value)
    Assert-True ($runtimeQuoted -gt 0 -and $patchQuoted -gt 0) `
        "The copied description quotes no test counts, so these cases would prove nothing: $factsDescription"
    function Write-FactsResults {
        param([string]$Folder, [string]$Suite, [int]$Tests, [int]$Skipped = 0)
        $directory = Join-Path $factsRoot $Folder
        Remove-Item -LiteralPath $directory -Recurse -Force -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
        $cases = (1..$Tests | ForEach-Object {
            if ($_ -le $Skipped) { "<testcase name=`"t$_`" classname=`"fixture.$Suite`"><skipped/></testcase>" }
            else { "<testcase name=`"t$_`" classname=`"fixture.$Suite`"/>" }
        }) -join ''
        Set-Content -LiteralPath (Join-Path $directory "TEST-fixture.$Suite.xml") -Encoding UTF8 -Value (
            "<?xml version=`"1.0`" encoding=`"UTF-8`"?><testsuite name=`"fixture.$Suite`" tests=`"$Tests`" " +
            "skipped=`"$Skipped`" failures=`"0`" errors=`"0`">$cases</testsuite>")
    }
    function Invoke-StrictFacts { & $factsScript -Root $factsRoot -SkipUrlCheck 6> $null }
    $runtimeResults = 'extensions/facebook/build/test-results/testDebugUnitTest'
    $patchResults = 'patches/build/test-results/test'
    try {
        Write-FactsResults $runtimeResults 'RuntimeTest' $runtimeQuoted
        Write-FactsResults $patchResults 'PatchTest' $patchQuoted
        Invoke-StrictFacts
        Assert-True ($LASTEXITCODE -eq 0 -or $null -eq $LASTEXITCODE) `
            'The strict release check refused test results that match the description.'

        # The patch tests run in two partitions, test and fixtureTest, each with its own results
        # folder: the count is both together, and a fixture test that skipped in its own folder is
        # refused as one in test would be.
        $fixtureResults = 'patches/build/test-results/fixtureTest'
        try {
            Write-FactsResults $patchResults 'PatchTest' ($patchQuoted - 2)
            Write-FactsResults $fixtureResults 'PatchFixtureTest' 2
            Invoke-StrictFacts
            Assert-True ($LASTEXITCODE -eq 0 -or $null -eq $LASTEXITCODE) `
                'The strict release check refused patch results split between test and fixtureTest.'
            Write-FactsResults $fixtureResults 'PatchFixtureTest' 2 -Skipped 1
            Assert-Throws { Invoke-StrictFacts } '*PatchFixtureTest*skipped 1 test*' `
                'A release was checked against fixtureTest results with a skipped fixture test.'
            Write-FactsResults $patchResults 'PatchTest' $patchQuoted
            Write-FactsResults $fixtureResults 'PatchFixtureTest' 1
            Assert-Throws { Invoke-StrictFacts } '*patch test count*' `
                'The strict release check left the fixtureTest results out of the patch test count.'
        } finally {
            Remove-Item -LiteralPath (Join-Path $factsRoot $fixtureResults) -Recurse -Force -ErrorAction SilentlyContinue
            Write-FactsResults $patchResults 'PatchTest' $patchQuoted
        }

        # A fixture test that skipped, which Gradle reports as a pass.
        Write-FactsResults $patchResults 'PatchTest' $patchQuoted -Skipped 1
        Assert-Throws { Invoke-StrictFacts } '*skipped 1 test*' `
            'A release was checked against patch test results with a skipped fixture test.'

        # A count the run doesn't have, which is how "All 269 patch tests passed" was written.
        Write-FactsResults $patchResults 'PatchTest' ($patchQuoted + 1)
        Assert-Throws { Invoke-StrictFacts } '*patch test count*' `
            'A description quoting a patch test count the run does not have was accepted.'

        # A test class deleted since the last run. Its results stay until the tests run again,
        # no source left is newer than them, and every class left has results, so its tests
        # were counted as passing. The sources go in first so the results are the newer files.
        $runtimeSource = Join-Path $factsRoot 'extensions/facebook/src/test/java/fixture/RuntimeTest.java'
        $patchSource = Join-Path $factsRoot 'patches/src/test/kotlin/fixture/PatchTest.kt'
        foreach ($source in @($runtimeSource, $patchSource)) {
            New-Item -ItemType Directory -Path (Split-Path -Parent $source) -Force | Out-Null
            Set-Content -LiteralPath $source -Value '' -Encoding ASCII
        }
        function Add-OrphanResult([string]$Folder, [string]$Suite) {
            Set-Content -LiteralPath (Join-Path (Join-Path $factsRoot $Folder) "TEST-fixture.$Suite.xml") -Encoding UTF8 -Value (
                "<?xml version=`"1.0`" encoding=`"UTF-8`"?><testsuite name=`"fixture.$Suite`" tests=`"1`" " +
                "skipped=`"0`" failures=`"0`" errors=`"0`"><testcase name=`"t1`" classname=`"fixture.$Suite`"/></testsuite>")
        }
        Write-FactsResults $patchResults 'PatchTest' $patchQuoted
        Write-FactsResults $runtimeResults 'RuntimeTest' ($runtimeQuoted - 1)
        Add-OrphanResult $runtimeResults 'GoneTest'
        Assert-Throws { Invoke-StrictFacts } '*runtime test results include 1 test class*GoneTest*' `
            'The runtime results of a deleted test class were counted.'
        Write-FactsResults $runtimeResults 'RuntimeTest' $runtimeQuoted
        Write-FactsResults $patchResults 'PatchTest' ($patchQuoted - 1)
        Add-OrphanResult $patchResults 'GonePatchTest'
        Assert-Throws { Invoke-StrictFacts } '*patch test results include 1 test class*GonePatchTest*' `
            'The patch results of a deleted test class were counted.'
        # The control: the same sources with no orphan pass.
        Write-FactsResults $runtimeResults 'RuntimeTest' $runtimeQuoted
        Write-FactsResults $patchResults 'PatchTest' $patchQuoted
        Invoke-StrictFacts
        Assert-True ($LASTEXITCODE -eq 0 -or $null -eq $LASTEXITCODE) `
            'The strict release check refused results that match their sources and the description.'
        foreach ($folder in @('extensions/facebook/src', 'patches/src')) {
            Remove-Item -LiteralPath (Join-Path $factsRoot $folder) -Recurse -Force
        }

        Remove-Item -LiteralPath (Join-Path $factsRoot 'patches') -Recurse -Force
        Assert-Throws { Invoke-StrictFacts } '*No patch test results*' `
            'A release was checked with no patch test results at all.'

        # Results the check is told to leave unread, as the pre-push hook does in its worktree,
        # where they can belong to another commit: read, a skipped runtime test fails the lenient
        # check; unread, it doesn't. A check that quotes counts can't be told to skip them.
        Write-FactsResults $runtimeResults 'RuntimeTest' $runtimeQuoted -Skipped 1
        Assert-Throws { Invoke-Facts } '*skipped=1*' 'A lenient check read past a skipped runtime test.'
        & $factsScript -Root $factsRoot -SkipDescriptionTestCount -SkipUrlCheck -SkipTestResults 6> $null
        Assert-True ($LASTEXITCODE -eq 0 -or $null -eq $LASTEXITCODE) `
            'A check told to leave the test results unread read them anyway.'
        Assert-Throws { & $factsScript -Root $factsRoot -SkipUrlCheck -SkipTestResults 6> $null } `
            '*SkipDescriptionTestCount*' 'A check holding the description to its counts left the results unread.'
    } finally {
        foreach ($folder in @('patches', 'extensions')) {
            Remove-Item -LiteralPath (Join-Path $factsRoot $folder) -Recurse -Force -ErrorAction SilentlyContinue
        }
    }

    # And the same tree, once every fact is put back, is accepted again. Without this the cases
    # above would also pass against a fixture that had become permanently broken.
    Invoke-Facts
    Assert-True ($LASTEXITCODE -eq 0 -or $null -eq $LASTEXITCODE) `
        'The release facts check refused the fixture after every change was put back.'
} finally {
    if ($factsPreviousWebRequest) { Set-Item Function:\Invoke-WebRequest -Value $factsPreviousWebRequest.ScriptBlock }
    else { Remove-Item Function:\Invoke-WebRequest -ErrorAction SilentlyContinue }
    Remove-Item -LiteralPath $factsRoot -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host '[scripts] release facts contracts passed'

# --- pre-push.ps1 ----------------------------------------------------------------------------
#
# Which files make the hook run the release check. The receipt is what the check holds a release
# to and the allowlist is what it accepts manifest changes from, and a push that moved only one
# of them ran no release check at all. Driven against a stub root whose validate script records
# that it was called, so the case proves the routing and not the check.

$prePushScript = Join-Path $PSScriptRoot 'pre-push.ps1'
$hookRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("hushfacebook-hook-" + [guid]::NewGuid().ToString('N'))
$savedSkip = $env:HUSHFACEBOOK_SKIP_PRE_PUSH
$savedRoutingWrapper = $env:HUSHFACEBOOK_BUILD_WRAPPER
$savedRoutingActor = $env:GITHUB_ACTOR
$savedRoutingToken = $env:GITHUB_TOKEN
$savedHookGit = @{}
# These cases invoke another hook against a foreign repository. Git's own hook environment
# must not leak into that repository or its local bare transport, including GIT_EXEC_PATH.
foreach ($variable in @(Get-ChildItem Env: | Where-Object { $_.Name -like 'GIT_*' })) {
    $savedHookGit[$variable.Name] = $variable.Value
    Remove-Item -LiteralPath ('Env:\' + $variable.Name)
}
try {
    $env:HUSHFACEBOOK_SKIP_PRE_PUSH = $null
    New-Item -ItemType Directory -Path (Join-Path $hookRoot 'scripts') -Force | Out-Null
    # A repository from the start, empty until the cases below commit to it: every push is scanned
    # for machine names now, a run by hand included, and the scan reads the root through git.
    & git -C $hookRoot init --quiet
    $actualHookGitDir = (& git -C $hookRoot rev-parse --absolute-git-dir).Trim()
    Assert-True ([IO.Path]::GetFullPath($actualHookGitDir).TrimEnd('\', '/') -ieq
        [IO.Path]::GetFullPath((Join-Path $hookRoot '.git')).TrimEnd('\', '/')) `
        'The hook fixture resolved outside its temporary repository; refusing to write.'
    & git -C $hookRoot config user.name 'Hook Contract'
    & git -C $hookRoot config user.email 'hook@example.invalid'
    $factsMarker = Join-Path $hookRoot 'facts-ran.txt'
    $contractsMarker = Join-Path $hookRoot 'contracts-ran.txt'
    $toolingMarker = Join-Path $hookRoot 'tooling-ran.txt'
    Set-Content -LiteralPath (Join-Path $hookRoot 'scripts/validate-release-facts.ps1') -Encoding UTF8 -Value @(
        'param([string]$Root, [switch]$SkipDescriptionTestCount, [switch]$AllowPublishedIndexLag,',
        '    [switch]$VerifyPublishedAsset, [string]$ArtifactPath, [switch]$ArtifactIsHosted)',
        ("Set-Content -LiteralPath '$factsMarker' -Value " +
            "`"lag=`$AllowPublishedIndexLag skip=`$SkipDescriptionTestCount verify=`$VerifyPublishedAsset artifact=`$ArtifactPath hosted=`$ArtifactIsHosted`""),
        'exit 0')
    Set-Content -LiteralPath (Join-Path $hookRoot 'scripts/test-script-contracts.ps1') -Encoding UTF8 -Value @(
        'param([string]$Root)',
        "Set-Content -LiteralPath '$contractsMarker' -Value 'ran'",
        'exit 0')
    Set-Content -LiteralPath (Join-Path $hookRoot 'scripts/test-tooling-classpaths.ps1') -Encoding UTF8 -Value @(
        'param([string]$Root)',
        "Set-Content -LiteralPath '$toolingMarker' -Value 'ran'",
        'exit 0')
    # The runtime tests start through HUSHFACEBOOK_BUILD_WRAPPER, which the hook reads from the
    # user's environment when this process lacks it, so a stub stands in for it here: it records
    # that the tests were asked for and builds nothing. The credentials the build path wants first
    # are dummies. The build sections further down put stubs of their own in its place.
    $buildMarker = Join-Path $hookRoot 'build-ran.txt'
    $routingBuild = Join-Path $hookRoot 'routing-build.ps1'
    Set-Content -LiteralPath $routingBuild -Encoding UTF8 -Value @(
        'param([string]$ProjectDir, [string[]]$Tasks)',
        "Set-Content -LiteralPath '$buildMarker' -Value (`$Tasks -join ',')",
        'exit 0')
    $env:HUSHFACEBOOK_BUILD_WRAPPER = $routingBuild
    $env:GITHUB_ACTOR = 'contract'
    $env:GITHUB_TOKEN = 'contract'

    function Invoke-Hook {
        param([string[]]$Paths)
        Remove-Item -LiteralPath $factsMarker, $contractsMarker, $buildMarker, $toolingMarker -Force -ErrorAction SilentlyContinue
        $global:LASTEXITCODE = 0
        & $prePushScript -Root $hookRoot -ChangedPaths $Paths 6> $null
        if ($LASTEXITCODE -ne 0) { throw "pre-push exited $LASTEXITCODE for $($Paths -join ', ')" }
    }

    # The control: a file no gate reads runs no gate, so a marker below is the routing talking.
    Invoke-Hook -Paths @('LICENSE')
    Assert-True (-not (Test-Path -LiteralPath $factsMarker)) `
        'The release check ran for a push that changed nothing it reads.'
    Assert-True (-not (Test-Path -LiteralPath $toolingMarker)) `
        'The tooling compatibility check ran for an unrelated documentation push.'

    foreach ($toolingInput in @('build.gradle.kts', 'settings.gradle.kts', 'patches/build.gradle.kts',
            'gradle/libs.versions.toml', 'gradle/verification-metadata.xml', 'gradle/tooling-scopes.txt',
            'scripts/ToolingClasspathSmoke.java', 'scripts/ToolingHttpSmoke.java', 'scripts/test-tooling-classpaths.ps1')) {
        Invoke-Hook -Paths @($toolingInput)
        Assert-True ((Get-Content -LiteralPath $buildMarker -Raw) -like '*:patches:releaseTooling*' -and
            (Test-Path -LiteralPath $toolingMarker)) `
            "A change to $toolingInput skipped the resolved tooling graph or its compatibility check."
    }
    Invoke-Hook -Paths @('extensions/facebook/src/main/java/Any.java')
    Assert-True ((Get-Content -LiteralPath $buildMarker -Raw) -notlike '*:patches:releaseTooling*' -and
        -not (Test-Path -LiteralPath $toolingMarker)) `
        'An ordinary payload change unexpectedly ran the tooling classpath gate.'
    $toolingStubPath = Join-Path $hookRoot 'scripts/test-tooling-classpaths.ps1'
    $toolingStubText = Get-Content -LiteralPath $toolingStubPath -Raw
    Remove-Item -LiteralPath $toolingStubPath -Force
    try {
        Assert-Throws { Invoke-Hook -Paths @('settings.gradle.kts') } '*test-tooling-classpaths.ps1 is missing*' `
            'A dependency change passed with its compatibility check missing.'
        Set-Content -LiteralPath $toolingStubPath -Encoding UTF8 -Value 'param([string]$Root); exit 7'
        Assert-Throws { Invoke-Hook -Paths @('settings.gradle.kts') } '*compatibility check did not pass*' `
            'A dependency change passed after its compatibility check failed.'
    } finally {
        Set-Content -LiteralPath $toolingStubPath -Value $toolingStubText -Encoding UTF8 -NoNewline
    }

    # HUSHFACEBOOK_SKIP_PRE_PUSH is the way through the hook's own messages offer, and it has to
    # work whatever the working tree holds. A copy of the hook beside a common.ps1 with conflict
    # markers in it, as mid-rebase, stops on the parse error without the switch and goes through
    # with it. The hook used to load common.ps1 first and stop either way.
    $brokenHook = Join-Path $hookRoot 'broken-hook'
    New-Item -ItemType Directory -Path (Join-Path $brokenHook 'scripts') -Force | Out-Null
    Copy-Item -LiteralPath $prePushScript -Destination (Join-Path $brokenHook 'scripts/pre-push.ps1')
    Set-Content -LiteralPath (Join-Path $brokenHook 'scripts/common.ps1') -Encoding ASCII -Value @(
        '<<<<<<< HEAD', 'function Find-MachineNames {', '=======', '>>>>>>> theirs')
    $brokenPrePush = Join-Path $brokenHook 'scripts/pre-push.ps1'
    try {
        Assert-Throws { & $brokenPrePush -Root $brokenHook -ChangedPaths @('README.md') 6> $null } '*common.ps1*' `
            'The hook ran with a common.ps1 that does not parse, so the case below would prove nothing.'
        $env:HUSHFACEBOOK_SKIP_PRE_PUSH = '1'
        $global:LASTEXITCODE = 0
        try {
            $skipped = @(& $brokenPrePush -Root $brokenHook -ChangedPaths @('README.md') 6>&1 | ForEach-Object { "$_" }) -join "`n"
        } catch {
            throw "HUSHFACEBOOK_SKIP_PRE_PUSH=1 did not get a push past a common.ps1 that does not parse: $($_.Exception.Message)"
        }
        Assert-True ($LASTEXITCODE -eq 0 -and $skipped -like '*skipped by HUSHFACEBOOK_SKIP_PRE_PUSH*') `
            "HUSHFACEBOOK_SKIP_PRE_PUSH=1 did not say it skipped the hook: $skipped"
    } finally {
        $env:HUSHFACEBOOK_SKIP_PRE_PUSH = $null
        Remove-Item -LiteralPath $brokenHook -Recurse -Force -ErrorAction SilentlyContinue
    }

    Invoke-Hook -Paths @('release-receipt-0.31.0.json')
    Assert-True (Test-Path -LiteralPath $factsMarker) `
        'A push that changed only the release receipt ran no release check.'
    Assert-True ((Get-Content -LiteralPath $factsMarker -Raw) -like 'lag=True*') `
        'A receipt-only push took the strict published-index path.'

    Invoke-Hook -Paths @('scripts/manifest-delta-allowlist.txt')
    Assert-True (Test-Path -LiteralPath $factsMarker) `
        'A push that changed only the manifest delta allowlist ran no release check.'
    Assert-True (Test-Path -LiteralPath $contractsMarker) `
        'A push that changed the manifest delta allowlist skipped the script contract tests.'

    Invoke-Hook -Paths @('scripts/patch-catalog-compatibility.json')
    Assert-True (Test-Path -LiteralPath $factsMarker) `
        'A push changing only scripts/patch-catalog-compatibility.json skipped the release facts gate.'
    Assert-True ((Get-Content -LiteralPath $factsMarker -Raw) -like 'lag=True*') `
        'A compatibility-only push took the strict published-index path.'

    # Any script change runs the contract tests, and a gate that finds them gone stops the push
    # instead of noting it and carrying on, which is how this suite was missing for Hushfacebook's
    # first commits.
    Invoke-Hook -Paths @('scripts/patch-report.ps1')
    Assert-True (Test-Path -LiteralPath $contractsMarker) `
        'A push that changed a script outside every named suite skipped the script contract tests.'
    $contractsStubPath = Join-Path $hookRoot 'scripts/test-script-contracts.ps1'
    $contractsStubText = Get-Content -LiteralPath $contractsStubPath -Raw
    Remove-Item -LiteralPath $contractsStubPath -Force
    try {
        Assert-Throws { Invoke-Hook -Paths @('scripts/patch-report.ps1') } '*test-script-contracts.ps1 is missing*' `
            'A script push went through with the contract tests missing.'
        Assert-Throws { Invoke-Hook -Paths @('scripts/test-script-contracts.ps1') } '*was deleted in this push*' `
            'A push that deleted the contract tests went through.'
    } finally {
        Set-Content -LiteralPath $contractsStubPath -Value $contractsStubText -Encoding UTF8 -NoNewline
    }

    # The seven verifier suites run only when their own files move, and pre-push.ps1 decides which
    # files those are. A push of pre-push.ps1 runs this suite and no other, so an edit that put a
    # suite line behind a dead branch, or dropped a file from a suite's list, went out through the
    # gate it switched off. This suite is the one place that holds the routing, then: all eight
    # suite lines read through the parser (script-wiring.ps1), each also tried behind a dead
    # branch so the check can't pass by passing everything, and every file a verifier suite guards
    # pushed through the hook against stub suites that record they ran. A file starts exactly the
    # suites whose lists hold it. The source ledger's and the release text's suites also guard
    # files outside scripts/, which are pushed after.
    $prePushSource = [System.IO.File]::ReadAllText($prePushScript)
    $deadSuiteCopy = Join-Path $hookRoot 'pre-push-dead-suite.ps1'
    $verifierRoutes = [ordered]@{
        'scripts/test-injected-registers.ps1' = @('BadDexFixture.java', 'DexDiff.java', 'injected-mutation-contracts.txt',
            'injected-register-contracts.ps1', 'injected-register-removal-allowlist.txt', 'script-wiring.ps1',
            'test-injected-registers.ps1', 'verify-all-patches.ps1', 'verify-injected-registers.ps1')
        'scripts/test-resource-table-check.ps1' = @('MergeSplits.java', 'ResourceTableCheck.java', 'test-resource-table-check.ps1',
            'verify-all-patches.ps1')
        'scripts/test-injected-register-device.ps1' = @('injected-register-device.ps1', 'script-wiring.ps1',
            'test-injected-register-device.ps1', 'verify-injected-registers.ps1')
        'scripts/test-fingerprint-candidates.ps1' = @('FingerprintCandidates.java', 'FingerprintFixture.java',
            'fingerprint-calibration.txt', 'fingerprint-calibration-582-arm64.txt', 'fingerprint-calibration-582-armv7.txt',
            'fingerprint-candidates.ps1', 'fingerprint-history.ps1', 'fingerprint-history.txt',
            'fingerprint-signature.schema.json', 'test-fingerprint-candidates.ps1')
        'scripts/test-facebook-sources.ps1' = @('audit-facebook-sources.ps1', 'facebook-sources.ps1', 'gitlab-coverage.ps1',
            'patch-target.ps1', 'scan-gitlab-coverage.ps1', 'test-facebook-sources.ps1')
        'scripts/test-release-text.ps1' = @('release/release.ps1', 'test-release-text.ps1')
        'scripts/test-phone-smoke.ps1' = @('phone-smoke.ps1', 'test-phone-smoke.ps1', 'device-audit.ps1', 'walk-receipt.ps1',
            'device-audit-expectations.json', 'device-audit-expectations-s22.json')
    }
    foreach ($suite in @('scripts/test-script-contracts.ps1') + @($verifierRoutes.Keys)) {
        Assert-True (Test-PushGateRunsSuite $prePushScript $suite) "The push gate does not run $suite."
        $suiteLine = { param($Node)
            $Node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
            $Node.Left.Extent.Text -eq '$suites' -and $Node.Extent.Text -like "*'$suite'*" }.GetNewClosure()
        [System.IO.File]::WriteAllText($deadSuiteCopy,
            (Edit-ScriptNode $prePushSource $suiteLine { param($Text) "if (`$false) { $Text }" }))
        Assert-True (-not (Test-PushGateRunsSuite $deadSuiteCopy $suite)) `
            "The wiring check read $suite as running from behind a dead branch."
    }
    $verifierMarker = { param([string]$Suite) Join-Path $hookRoot ([IO.Path]::GetFileNameWithoutExtension($Suite) + '-ran.txt') }
    foreach ($suite in $verifierRoutes.Keys) {
        Set-Content -LiteralPath (Join-Path $hookRoot $suite) -Encoding UTF8 -Value @(
            'param([string]$Root)', "Set-Content -LiteralPath '$(& $verifierMarker $suite)' -Value 'ran'", 'exit 0')
    }
    foreach ($file in @(@($verifierRoutes.Values | ForEach-Object { $_ }) + @('patch-report.ps1') | Select-Object -Unique)) {
        foreach ($suite in $verifierRoutes.Keys) { Remove-Item -LiteralPath (& $verifierMarker $suite) -Force -ErrorAction SilentlyContinue }
        Invoke-Hook -Paths @("scripts/$file")
        $ran = @($verifierRoutes.Keys | Where-Object { Test-Path -LiteralPath (& $verifierMarker $_) }) -join ', '
        $expected = @($verifierRoutes.Keys | Where-Object { $verifierRoutes[$_] -contains $file }) -join ', '
        Assert-True ($ran -eq $expected) "A push of scripts/$file ran [$ran], not [$expected]."
    }
    # The ledger's rules read NOTICE, provenance.json and the catalog, and hold docs/sources.md to
    # the ledger. A push of any of those, or of the ledger alone, runs its suite and no other
    # verifier; the catalog also runs the release facts and the contract tests, held below.
    foreach ($file in @('sources/facebook-sources.json', 'NOTICE', 'provenance.json', 'docs/sources.md', 'patches-list.json')) {
        foreach ($suite in $verifierRoutes.Keys) { Remove-Item -LiteralPath (& $verifierMarker $suite) -Force -ErrorAction SilentlyContinue }
        Invoke-Hook -Paths @($file)
        $ran = @($verifierRoutes.Keys | Where-Object { Test-Path -LiteralPath (& $verifierMarker $_) }) -join ', '
        Assert-True ($ran -eq 'scripts/test-facebook-sources.ps1') "A push of $file ran [$ran], not the source ledger's suite alone."
        if ($file -ne 'patches-list.json') {
            Assert-True (-not (Test-Path -LiteralPath $contractsMarker) -and -not (Test-Path -LiteralPath $factsMarker)) `
                "A push of $file ran the contract tests or the release facts, which read nothing it changes."
        }
    }
    # tools/ is release_text.py and its tests. A push of either ran no check before; now it runs
    # their suite and the contract tests, whose release stage cases hold what release.ps1 hands it.
    foreach ($file in @('tools/release_text.py', 'tools/test_release_text.py')) {
        foreach ($suite in $verifierRoutes.Keys) { Remove-Item -LiteralPath (& $verifierMarker $suite) -Force -ErrorAction SilentlyContinue }
        Invoke-Hook -Paths @($file)
        $ran = @($verifierRoutes.Keys | Where-Object { Test-Path -LiteralPath (& $verifierMarker $_) }) -join ', '
        Assert-True ($ran -eq 'scripts/test-release-text.ps1') "A push of $file ran [$ran], not the release text suite alone."
        Assert-True (Test-Path -LiteralPath $contractsMarker) "A push of $file skipped the script contract tests."
    }

    # The catalog is held to Meta's two signers, the builds every patch declares and the internal
    # dependencies the release scripts expect, and only the contract tests read it for those. A
    # push of the catalog alone ran the release facts and never them.
    Invoke-Hook -Paths @('patches-list.json')
    Assert-True ((Test-Path -LiteralPath $factsMarker) -and (Test-Path -LiteralPath $contractsMarker)) `
        'A push that changed only the catalog did not run both the release check and the script contract tests.'

    # These tests end with the marketing asset check, which holds the artwork and the README's hero
    # and links. A push of only an icon ran no gate, and one of only the README ran the release
    # check alone.
    foreach ($artwork in 'assets/icons/icon-16.png', 'assets/readme-hero.png', 'README.md') {
        Invoke-Hook -Paths @($artwork)
        Assert-True (Test-Path -LiteralPath $contractsMarker) `
            "A push that changed only $artwork did not run the script contract tests, which hold the marketing assets."
    }

    Invoke-Hook -Paths @('CHANGELOG.md')
    Assert-True (Test-Path -LiteralPath $factsMarker) `
        'A push that changed only the CHANGELOG ran no release check.'
    Assert-True (-not (Test-Path -LiteralPath $contractsMarker)) `
        'A push that changed only the CHANGELOG ran the script contract tests; the release check is what holds that file.'

    Invoke-Hook -Paths @('.github/ISSUE_TEMPLATE/bug_report.yml')
    Assert-True (Test-Path -LiteralPath $factsMarker) `
        'A push that changed only the bug report form ran no release check.'

    Invoke-Hook -Paths @('CONTRIBUTING.md')
    Assert-True (Test-Path -LiteralPath $factsMarker) `
        'A push that changed only CONTRIBUTING.md ran no release check, which holds its patcher and Manager floor.'

    Invoke-Hook -Paths @('patches-bundle.json')
    Assert-True (Test-Path -LiteralPath $factsMarker) 'An index change ran no release check.'
    Assert-True ((Get-Content -LiteralPath $factsMarker -Raw) -like 'lag=False*') `
        'An index change was allowed to lag behind the published release.'
    # With no bundle built here the hosted one is checked on its own, never skipped; the release
    # root section below runs that against the real check.
    Assert-True ((Get-Content -LiteralPath $factsMarker -Raw) -like '*verify=True artifact= hosted=True*') `
        "An index push with no bundle built here did not have the hosted one checked: $(Get-Content -LiteralPath $factsMarker -Raw)"

    # The order that shipped a dexless bundle: buildAndroid, then any task that reruns
    # :patches:jar, which leaves the plain jar in build/libs under the bundle's own name. The
    # finished bundle sits in build/release, and the index push must be compared against that
    # one. A plain jar left beside it in build/libs is the state :patches:test produces.
    $releaseDirectory = Join-Path $hookRoot 'patches/build/release'
    $libsDirectory = Join-Path $hookRoot 'patches/build/libs'
    New-Item -ItemType Directory -Path $releaseDirectory, $libsDirectory -Force | Out-Null
    # Normalized, because the hook hands over the listing's own full name.
    $releaseCopy = [System.IO.Path]::GetFullPath((Join-Path $releaseDirectory 'patches-9.9.9.mpp'))
    Set-Content -LiteralPath $releaseCopy -Value 'bundle with classes.dex' -Encoding ASCII
    Set-Content -LiteralPath (Join-Path $libsDirectory 'patches-9.9.9.mpp') -Value 'plain jar' -Encoding ASCII
    Invoke-Hook -Paths @('patches-bundle.json')
    $routed = Get-Content -LiteralPath $factsMarker -Raw
    Assert-True ($routed -like '*verify=True*') `
        'An index push with a built release bundle did not compare it against the published asset.'
    Assert-True ($routed -like "*artifact=$releaseCopy hosted=False*") `
        "The index push compared something other than the release copy: $routed"
    Remove-Item -LiteralPath (Join-Path $hookRoot 'patches') -Recurse -Force

    # The files outside the source trees that the runtime tests read. ReadmePatchNamesTest holds the
    # README's patch rows to the catalog, ProvenanceTest holds NOTICE and every source's header to
    # provenance.json, PatchFamilyTest reads the catalog, and ShortcutCallsTest reads the mutation
    # contracts. The Gradle files declare them as test inputs, and a push of one alone still never
    # started the tests. Files no test reads still don't start them.
    foreach ($route in @(
            @{ Path = 'README.md'; Build = $true }, @{ Path = 'NOTICE'; Build = $true },
            @{ Path = 'provenance.json'; Build = $true }, @{ Path = 'patches-list.json'; Build = $true },
            @{ Path = 'scripts/injected-mutation-contracts.txt'; Build = $true },
            @{ Path = 'scripts/injected-register-removal-allowlist.txt'; Build = $false },
            @{ Path = 'CHANGELOG.md'; Build = $false }, @{ Path = 'docs/sources.md'; Build = $false },
            @{ Path = 'sources/facebook-sources.json'; Build = $false }, @{ Path = 'patches-bundle.json'; Build = $false },
            @{ Path = 'assets/icons/icon-16.png'; Build = $false }, @{ Path = 'CONTRIBUTING.md'; Build = $false })) {
        Invoke-Hook -Paths @($route.Path)
        $started = Test-Path -LiteralPath $buildMarker
        Assert-True ($started -eq $route.Build) `
            "A push of only $($route.Path) $(if ($started) { 'started' } else { 'did not start' }) the runtime tests."
    }

    # What a push changes, read the way git lists it. Taken for a rename, a moved file was listed
    # under its new path alone: patches-bundle.json moved away ran no release check, a source moved
    # out of extensions/ no runtime tests, and a verifier suite renamed was never looked for, so the
    # push went out without it. And git quotes a path with a byte outside ASCII unless told not to,
    # and "extensions/.../\303\234ber.java" in quotes matched no route. A repository of its own,
    # clean and pushed from HEAD, so every check runs in place from the stubs its commits carry.
    $listingRepo = Join-Path ([System.IO.Path]::GetTempPath()) ("hushfacebook-listing-" + [guid]::NewGuid().ToString('N'))
    $listingRan = "$listingRepo-ran"
    New-Item -ItemType Directory -Path (Join-Path $listingRepo 'scripts'), $listingRan -Force | Out-Null
    try {
        & git -C $listingRepo init --quiet
        $listingGitDir = (& git -C $listingRepo rev-parse --absolute-git-dir).Trim()
        Assert-True ([IO.Path]::GetFullPath($listingGitDir).TrimEnd('\', '/') -ieq
            [IO.Path]::GetFullPath((Join-Path $listingRepo '.git')).TrimEnd('\', '/')) `
            'The listing fixture resolved outside its temporary repository; refusing to write.'
        & git -C $listingRepo config user.name 'Listing Contract'
        & git -C $listingRepo config user.email 'listing@example.invalid'
        & git -C $listingRepo config core.autocrlf false
        $listingStubs = [ordered]@{
            'scripts/validate-release-facts.ps1' = ('param([string]$Root, [switch]$SkipDescriptionTestCount, ' +
                '[switch]$AllowPublishedIndexLag, [switch]$VerifyPublishedAsset, [string]$ArtifactPath, ' +
                '[switch]$ArtifactIsHosted, [switch]$SkipTestResults)')
        }
        foreach ($suite in @('scripts/test-script-contracts.ps1') + @($verifierRoutes.Keys)) {
            $listingStubs[$suite] = 'param([string]$Root)'
        }
        foreach ($stub in $listingStubs.Keys) {
            $ranFile = Join-Path $listingRan ([IO.Path]::GetFileNameWithoutExtension($stub) + '.txt')
            $markerValue = if ($stub -eq 'scripts/validate-release-facts.ps1') {
                "Set-Content -LiteralPath '$ranFile' -Value `"lag=`$AllowPublishedIndexLag skip=`$SkipDescriptionTestCount`""
            } else {
                "Set-Content -LiteralPath '$ranFile' -Value 'ran'"
            }
            Set-Content -LiteralPath (Join-Path $listingRepo $stub) -Encoding UTF8 -Value @(
                $listingStubs[$stub], $markerValue, 'exit 0')
        }
        # Commits what -Change leaves, pushes it the way git hands the hook a push, and answers
        # with what ran: the stubs by name, and build for the runtime tests.
        function Push-ListingChange([scriptblock]$Change, [switch]$NewBranch) {
            $before = if ($NewBranch) { '0' * 40 } else { (& git -C $listingRepo rev-parse HEAD).Trim() }
            & $Change
            & git -C $listingRepo add -A
            & git -C $listingRepo commit --quiet -m 'listing case'
            $after = (& git -C $listingRepo rev-parse HEAD).Trim()
            Remove-Item -Path (Join-Path $listingRan '*') -Force -ErrorAction SilentlyContinue
            Remove-Item -LiteralPath $buildMarker -Force -ErrorAction SilentlyContinue
            $global:LASTEXITCODE = 0
            & $prePushScript -Root $listingRepo -PushedRefs "refs/heads/main $after refs/heads/main $before" 6> $null
            if ($LASTEXITCODE -ne 0) { throw "pre-push exited $LASTEXITCODE" }
            $ran = @(Get-ChildItem -LiteralPath $listingRan -File | ForEach-Object { $_.BaseName } | Sort-Object)
            if (Test-Path -LiteralPath $buildMarker) { $ran = @('build') + $ran }
            return ($ran -join ', ')
        }

        # A new branch is read from its whole tree, where the one source has a name outside ASCII.
        $javaFolder = Join-Path $listingRepo 'extensions/facebook/src/main/java'
        $ran = Push-ListingChange -NewBranch {
            New-Item -ItemType Directory -Path $javaFolder -Force | Out-Null
            Set-Content -LiteralPath (Join-Path $javaFolder "$([char]0x00DC)ber.java") -Encoding ASCII -Value 'final class U {}'
        }
        Assert-True ($ran -like 'build, *') "A new branch whose only source has a name outside ASCII ran [$ran], without the runtime tests."
        $ran = Push-ListingChange {
            Set-Content -LiteralPath (Join-Path $listingRepo 'patches-bundle.json') -Encoding ASCII -Value '{}'
            Set-Content -LiteralPath (Join-Path $javaFolder 'Moved.java') -Encoding ASCII -Value 'final class Moved {}'
        }
        Assert-True ($ran -eq 'build, validate-release-facts') "A push adding the index and a source ran [$ran]."
        $featureTip = (& git -C $listingRepo rev-parse HEAD).Trim()
        Remove-Item -Path (Join-Path $listingRan '*') -Force -ErrorAction SilentlyContinue
        $global:LASTEXITCODE = 0
        & $prePushScript -Root $listingRepo -PushedRefs "refs/heads/feature $featureTip refs/heads/feature $('0' * 40)" 6> $null
        Assert-True ($LASTEXITCODE -eq 0) 'A new feature branch with an index file failed its push gate.'
        $featureFacts = Get-Content -LiteralPath (Join-Path $listingRan 'validate-release-facts.txt') -Raw
        Assert-True ($featureFacts -like 'lag=True skip=True*') `
            'A new feature branch was checked as if it published the index.'
        # The same new branch pushed beside main, whose own change leaves the index alone. Whether a
        # push rewrites the index is a fact about each ref: pooled, main's ref and the branch's whole
        # tree, which always holds patches-bundle.json, made one index push of the two.
        Set-Content -LiteralPath (Join-Path $listingRepo 'README.md') -Encoding ASCII -Value 'readme only'
        & git -C $listingRepo add -A
        & git -C $listingRepo commit --quiet -m 'readme only'
        $readmeTip = (& git -C $listingRepo rev-parse HEAD).Trim()
        Remove-Item -Path (Join-Path $listingRan '*') -Force -ErrorAction SilentlyContinue
        $global:LASTEXITCODE = 0
        & $prePushScript -Root $listingRepo -PushedRefs ("refs/heads/main $readmeTip refs/heads/main $featureTip`n" +
            "refs/heads/topic $readmeTip refs/heads/topic $('0' * 40)") 6> $null
        Assert-True ($LASTEXITCODE -eq 0) 'A push of main and a new branch together failed its gate.'
        $mixedFacts = Get-Content -LiteralPath (Join-Path $listingRan 'validate-release-facts.txt') -Raw
        Assert-True ($mixedFacts -like 'lag=True skip=True*') `
            "A push of main beside a new branch, neither rewriting main's index, was checked as an index push: $mixedFacts"

        # Nor may a file the push changes hold an unresolved merge conflict. A CHANGELOG reached main
        # with one in it and every gate passed it. ======= on its own is a Markdown heading's
        # underline, not a conflict, and passes.
        Assert-Throws { Push-ListingChange {
                Set-Content -LiteralPath (Join-Path $listingRepo 'CHANGELOG.md') -Encoding ASCII -Value @(
                    '## Unreleased', '', '<<<<<<< HEAD', '* one side.', '||||||| base', '=======',
                    '* the other side.', '>>>>>>> 0123abc (the other side)') } } `
            '*unresolved merge conflict*CHANGELOG.md:3:<<<<<<< HEAD*' 'A push carrying an unresolved merge conflict went out.'
        $ran = Push-ListingChange {
            Set-Content -LiteralPath (Join-Path $listingRepo 'CHANGELOG.md') -Encoding ASCII -Value @(
                '## Unreleased', '', '* one side.', '* the other side.')
            New-Item -ItemType Directory -Path (Join-Path $listingRepo 'docs') -Force | Out-Null
            Set-Content -LiteralPath (Join-Path $listingRepo 'docs/heading.md') -Encoding ASCII -Value @('A heading', '=======', '', 'Text.')
        }
        Assert-True ($ran -eq 'validate-release-facts') `
            "A push resolving the conflict, beside a heading underlined with =======, ran [$ran] instead of the release check."

        # Each end of a move counts where it is.
        $ran = Push-ListingChange {
            New-Item -ItemType Directory -Path (Join-Path $listingRepo 'attic') -Force | Out-Null
            & git -C $listingRepo mv patches-bundle.json attic/patches-bundle.json
        }
        Assert-True ($ran -eq 'validate-release-facts') "A push that moved patches-bundle.json away ran [$ran], not the release check."
        $ran = Push-ListingChange { & git -C $listingRepo mv extensions/facebook/src/main/java/Moved.java attic/Moved.java }
        Assert-True ($ran -eq 'build') "A push that moved a source out of extensions/ ran [$ran], not the runtime tests."

        # A gate suite renamed away is one the gate still expects, by the name it had.
        foreach ($suite in @('scripts/test-script-contracts.ps1') + @($verifierRoutes.Keys)) {
            $renamed = "scripts/retired-$(Split-Path -Leaf $suite)"
            Assert-Throws { Push-ListingChange { & git -C $listingRepo mv $suite $renamed } } "*$suite was deleted in this push*" `
                "A push that renamed $suite went out without it."
            & git -C $listingRepo mv $renamed $suite
            & git -C $listingRepo commit --quiet -m 'the suite back'
        }

        # And a changed path with a name outside ASCII, where the push is a range.
        $ran = Push-ListingChange {
            Set-Content -LiteralPath (Join-Path $listingRepo "scripts/n$([char]0x00F6)tes.txt") -Encoding ASCII -Value 'notes'
        }
        Assert-True ($ran -eq 'test-script-contracts') "A push of a script with a name outside ASCII ran [$ran], not the contract tests."
        $ran = Push-ListingChange {
            Set-Content -LiteralPath (Join-Path $javaFolder "Caf$([char]0x00E9).java") -Encoding ASCII -Value 'final class Cafe {}'
        }
        Assert-True ($ran -eq 'build') "A push of a source with a name outside ASCII ran [$ran], not the runtime tests."
    } finally {
        foreach ($line in @(& git -C $listingRepo worktree list --porcelain)) {
            if ($line -like 'worktree *') {
                $listed = $line.Substring('worktree '.Length)
                if ([IO.Path]::GetFullPath($listed).TrimEnd('\', '/') -ine [IO.Path]::GetFullPath($listingRepo).TrimEnd('\', '/')) {
                    & git -C $listingRepo worktree remove --force $listed
                }
            }
        }
        Remove-Item -LiteralPath $listingRepo, $listingRan -Recurse -Force -ErrorAction SilentlyContinue
    }

    # A new remote branch can contain several unpublished commits. The code change here is in
    # the first commit and the tip changes only documentation. Looking at HEAD^..HEAD silently
    # misses the code and skips every build gate.
    $newBranchSource = Join-Path $hookRoot 'extensions/facebook/src/main/java/FirstCommit.java'
    New-Item -ItemType Directory -Path (Split-Path -Parent $newBranchSource) -Force | Out-Null
    Set-Content -LiteralPath $newBranchSource -Encoding UTF8 -Value 'final class FirstCommit {}'
    & git -C $hookRoot add extensions/facebook/src/main/java/FirstCommit.java
    & git -C $hookRoot commit --quiet -m 'code first'
    $firstCommit = (& git -C $hookRoot rev-parse HEAD).Trim()
    New-Item -ItemType Directory -Path (Join-Path $hookRoot 'docs') -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $hookRoot 'docs/guide.md') -Encoding UTF8 -Value 'tip only'
    & git -C $hookRoot add docs/guide.md
    & git -C $hookRoot commit --quiet -m 'docs tip'
    $newBranchHead = (& git -C $hookRoot rev-parse HEAD).Trim()
    # A stale local remote-tracking ref that already points at the code commit must not subtract
    # that commit from a new branch push. The destination advertises this branch as new.
    & git -C $hookRoot update-ref refs/remotes/origin/stale $firstCommit
    $newBranchRefs = "refs/heads/new $newBranchHead refs/heads/new $('0' * 40)"
    $savedNewBranchPath = $env:PATH
    $savedNewBranchActor = $env:GITHUB_ACTOR
    $savedNewBranchToken = $env:GITHUB_TOKEN
    try {
        $env:PATH = Split-Path -Parent (Get-Command git).Source
        $env:GITHUB_ACTOR = $null
        $env:GITHUB_TOKEN = $null
        Assert-Throws { & $prePushScript -Root $hookRoot -PushedRefs $newBranchRefs 6> $null } `
            '*GITHUB_ACTOR*' 'A new branch checked only its documentation tip and skipped earlier code.'
    } finally {
        $env:PATH = $savedNewBranchPath
        $env:GITHUB_ACTOR = $savedNewBranchActor
        $env:GITHUB_TOKEN = $savedNewBranchToken
    }


    # A release tag can name the exact tree already on a remote branch. It adds a pointer,
    # not a new index: treating it as a first branch push creates a publication deadlock.
    $tagRemote = Join-Path $hookRoot 'tag-remote.git'
    & git init --bare --quiet $tagRemote
    & git -C $hookRoot push --quiet $tagRemote "${newBranchHead}:refs/heads/main"
    & git -C $hookRoot tag -a release-same-tree -m 'release' $newBranchHead
    $tagObject = (& git -C $hookRoot rev-parse refs/tags/release-same-tree).Trim()
    $tagRefs = "refs/tags/release-same-tree $tagObject refs/tags/release-same-tree $('0' * 40)"
    # Git hooks put git-core first. Keeping only that directory breaks Windows Git's local
    # transport because its runtime DLLs live elsewhere. Hide gh, not Git's dependencies.
    $pathWithoutGh = (@($savedNewBranchPath -split [IO.Path]::PathSeparator | Where-Object {
        $directory = $_.Trim('"')
        $directory -and -not (@('gh', 'gh.exe', 'gh.cmd', 'gh.bat') | Where-Object {
            Test-Path -LiteralPath (Join-Path $directory $_) -PathType Leaf
        })
    }) -join [IO.Path]::PathSeparator)
    try {
        $env:PATH = $pathWithoutGh
        Assert-True (-not (Get-Command gh -ErrorAction SilentlyContinue)) 'The tag fixture still exposes gh.'
        $env:GITHUB_ACTOR = $null
        $env:GITHUB_TOKEN = $null
        & $prePushScript -Root $hookRoot -RemoteUrl $tagRemote -PushedRefs $tagRefs 6> $null
        Assert-True ($LASTEXITCODE -eq 0) 'An annotated tag of the advertised remote tree reran file-change gates.'
        $lightTagRefs = "refs/tags/light $newBranchHead refs/tags/light $('0' * 40)"
        & $prePushScript -Root $hookRoot -RemoteUrl $tagRemote -PushedRefs $lightTagRefs 6> $null
        Assert-True ($LASTEXITCODE -eq 0) 'A lightweight tag of the advertised remote tree reran file-change gates.'

        # A stale local tracking ref is not proof that the target is hosted. Only the live
        # remote advertisement above can qualify; unknown targets retain the full-tree gate.
        $unknownTagRefs = "refs/tags/unknown $firstCommit refs/tags/unknown $('0' * 40)"
        Assert-Throws { & $prePushScript -Root $hookRoot -RemoteUrl $tagRemote -PushedRefs $unknownTagRefs 6> $null } `
            '*GITHUB_ACTOR*' 'A tag not matching an advertised remote branch skipped code checks.'
        Assert-Throws { & $prePushScript -Root $hookRoot -RemoteUrl $tagRemote -PushedRefs "$tagRefs`n$newBranchRefs" 6> $null } `
            '*GITHUB_ACTOR*' 'A known tag suppressed checks for a new branch in the same push.'
        Assert-Throws { & $prePushScript -Root $hookRoot -RemoteUrl (Join-Path $hookRoot 'missing-remote.git') -PushedRefs $tagRefs 6> $null } `
            '*remote branches*' 'An unavailable remote was treated as proof that a tag target was already hosted.'
    } finally {
        $env:PATH = $savedNewBranchPath
        $env:GITHUB_ACTOR = $savedNewBranchActor
        $env:GITHUB_TOKEN = $savedNewBranchToken
    }

    # The scan every push gets. A doc naming the maintainer's notes folder or a phone's serial
    # routes to no gate at all, and a test under patches/ or extensions/ only to the Gradle
    # gates, which never read for them, so neither reached the contract tests' scan. The hook
    # reads the pushed commit, whatever the working tree holds, and .gitignore stays exempt.
    $notesFolder = '.' + 'cla' + 'ude'
    $phoneSerial = 'R5' + 'C' + 'ZZZZ9999'
    $phoneDoc = Join-Path $hookRoot 'docs/phone.md'
    New-Item -ItemType Directory -Path (Split-Path -Parent $phoneDoc) -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $hookRoot 'docs/guide.md') -Encoding UTF8 -Value "Fixtures live in ~/$notesFolder/fixtures."
    Set-Content -LiteralPath $phoneDoc -Encoding UTF8 -Value "adb -s $phoneSerial install app.apk"
    Set-Content -LiteralPath (Join-Path $hookRoot '.gitignore') -Encoding UTF8 -Value "$notesFolder/"
    & git -C $hookRoot add docs/guide.md docs/phone.md .gitignore
    & git -C $hookRoot commit --quiet -m 'names the machine'
    $namingCommit = (& git -C $hookRoot rev-parse HEAD).Trim()
    Assert-Throws { & $prePushScript -Root $hookRoot -PushedRefs "refs/heads/main $namingCommit refs/heads/main $newBranchHead" 6> $null } `
        "*$namingCommit*docs/guide.md*docs/phone.md*" 'A push of docs naming the machine and a phone went through.'
    Assert-Throws { & $prePushScript -Root $hookRoot -ChangedPaths @('docs/phone.md') 6> $null } `
        '*the working tree*docs/guide.md*docs/phone.md*' 'A run by hand passed a working tree whose docs name the machine.'
    # The control: the same files naming nothing, .gitignore still naming the folder, go through.
    Set-Content -LiteralPath (Join-Path $hookRoot 'docs/guide.md') -Encoding UTF8 -Value 'Fixtures live in HUSHFACEBOOK_FIXTURE_DIR.'
    Set-Content -LiteralPath $phoneDoc -Encoding UTF8 -Value 'adb -s $env:HUSHFACEBOOK_DEVICE_SERIAL install app.apk'
    & git -C $hookRoot add docs/guide.md docs/phone.md
    & git -C $hookRoot commit --quiet -m 'names nothing'
    $cleanCommit = (& git -C $hookRoot rev-parse HEAD).Trim()
    $global:LASTEXITCODE = 0
    & $prePushScript -Root $hookRoot -PushedRefs "refs/heads/main $cleanCommit refs/heads/main $namingCommit" 6> $null
    Assert-True ($LASTEXITCODE -eq 0) 'A push whose files name nothing, .gitignore aside, was stopped.'

    # Both cases above push HEAD of a tree that matches it, so a scan of the working tree passed
    # them too. Here the pushed commit and the working tree disagree, both ways round, and the
    # refusal is read for the <commit>:<path>: prefix git grep gives only a hit it read out of a
    # commit: the naming commit is refused while the tree holds the clean text, and the clean
    # commit goes through while the tree names the machine.
    Assert-Throws { & $prePushScript -Root $hookRoot -PushedRefs "refs/heads/main $namingCommit refs/heads/main $newBranchHead" 6> $null } `
        "*${namingCommit}:docs/guide.md:1:*${namingCommit}:docs/phone.md:1:*" `
        'A push of a commit naming the machine went through because the working tree does not.'
    Set-Content -LiteralPath (Join-Path $hookRoot 'docs/guide.md') -Encoding UTF8 -Value "Fixtures live in ~/$notesFolder/fixtures."
    Set-Content -LiteralPath $phoneDoc -Encoding UTF8 -Value "adb -s $phoneSerial install app.apk"
    try {
        $global:LASTEXITCODE = 0
        & $prePushScript -Root $hookRoot -PushedRefs "refs/heads/main $cleanCommit refs/heads/main $namingCommit" 6> $null
        Assert-True ($LASTEXITCODE -eq 0) "pre-push exited $LASTEXITCODE."
    } catch {
        throw "A push of a clean commit was stopped by what the working tree names: $($_.Exception.Message)"
    } finally {
        & git -C $hookRoot checkout --quiet -- docs/guide.md docs/phone.md
    }

    # Every commit the push publishes, not only each ref's tip: a serial one commit adds and the
    # next removes still goes out in the first, and the refusal names that commit by the prefix git
    # grep gives a hit read out of it. Pushed as an update of a branch, and as a new branch to a
    # remote whose main already holds the clean commit, as its tracking ref here says too.
    Set-Content -LiteralPath $phoneDoc -Encoding UTF8 -Value "adb -s $phoneSerial install app.apk"
    & git -C $hookRoot add docs/phone.md
    & git -C $hookRoot commit --quiet -m 'names the phone again'
    $servedSerial = (& git -C $hookRoot rev-parse HEAD).Trim()
    & git -C $hookRoot rm --quiet docs/phone.md
    Set-Content -LiteralPath (Join-Path $hookRoot 'docs/guide.md') -Encoding UTF8 -Value 'Fixtures live in HUSHFACEBOOK_FIXTURE_DIR, not in docs.'
    & git -C $hookRoot add docs/guide.md
    & git -C $hookRoot commit --quiet -m 'drops the doc'
    $droppedSerial = (& git -C $hookRoot rev-parse HEAD).Trim()
    $scanRemote = Join-Path $hookRoot 'scan-remote.git'
    & git init --bare --quiet $scanRemote
    & git -C $hookRoot push --quiet $scanRemote "${cleanCommit}:refs/heads/main"
    & git -C $hookRoot update-ref refs/remotes/origin/main $cleanCommit
    # And more branches than one rev-list call takes, none of them ever fetched here, sorted so
    # main lands in the middle batch: what a new branch publishes has to hold for every batch.
    foreach ($side in @(@{ Prefix = 'a'; From = 1 }, @{ Prefix = 'zz'; From = 1000 })) {
        foreach ($n in 0..209) {
            [IO.File]::WriteAllText((Join-Path $scanRemote ("refs/heads/$($side.Prefix){0:d3}" -f $n)),
                ('{0:x40}' -f ($side.From + $n)) + "`n")
        }
    }
    # A new branch is checked from its whole tree, code included, so a scan that let it through
    # would reach the build gates. With no credentials and no gh they refuse by name instead.
    $savedScanPath = $env:PATH
    $savedScanActor = $env:GITHUB_ACTOR
    $savedScanToken = $env:GITHUB_TOKEN
    try {
        $env:PATH = $pathWithoutGh
        $env:GITHUB_ACTOR = $null
        $env:GITHUB_TOKEN = $null
        foreach ($refs in @("refs/heads/main $droppedSerial refs/heads/main $cleanCommit",
                "refs/heads/topic $droppedSerial refs/heads/topic $('0' * 40)")) {
            Assert-Throws { & $prePushScript -Root $hookRoot -RemoteUrl $scanRemote -PushedRefs $refs 6> $null } `
                "*commit $servedSerial name*${servedSerial}:docs/phone.md:1:*" `
                "A push went through with a phone serial in an earlier commit than its tip: $refs"
        }
    } finally {
        $env:PATH = $savedScanPath
        $env:GITHUB_ACTOR = $savedScanActor
        $env:GITHUB_TOKEN = $savedScanToken
    }

    # Commits that cancel each other out change no file, and each still goes out. The scan runs
    # before the changed paths decide there's nothing to check.
    New-Item -ItemType Directory -Path (Split-Path -Parent $phoneDoc) -Force | Out-Null
    Set-Content -LiteralPath $phoneDoc -Encoding UTF8 -Value "adb -s $phoneSerial install app.apk"
    & git -C $hookRoot add docs/phone.md
    & git -C $hookRoot commit --quiet -m 'names the phone once more'
    $namesAgain = (& git -C $hookRoot rev-parse HEAD).Trim()
    & git -C $hookRoot rm --quiet docs/phone.md
    & git -C $hookRoot commit --quiet -m 'takes it out again'
    $cancelledOut = (& git -C $hookRoot rev-parse HEAD).Trim()
    Assert-Throws { & $prePushScript -Root $hookRoot -PushedRefs "refs/heads/main $cancelledOut refs/heads/main $droppedSerial" 6> $null } `
        "*commit $namesAgain name*${namesAgain}:docs/phone.md:1:*" `
        'A push whose commits cancel each other out went through with them unscanned.'

    # A remote-tracking ref can outlive its branch on the remote, deleted there and never pruned
    # here, and a commit only it held is still published by a new branch that reaches it. What the
    # remote holds is read off the remote. The whole tree of a new branch reaches the build gates,
    # so they refuse by name again if the scan ever lets it through. The remote's main moves on by
    # its ref file, since only what the remote advertises is read and git won't push to a remote
    # whose other branches name commits it doesn't have.
    [IO.File]::WriteAllText((Join-Path $scanRemote 'refs/heads/main'), "$cancelledOut`n")
    New-Item -ItemType Directory -Path (Split-Path -Parent $phoneDoc) -Force | Out-Null
    Set-Content -LiteralPath $phoneDoc -Encoding UTF8 -Value "adb -s $phoneSerial install app.apk"
    & git -C $hookRoot add docs/phone.md
    & git -C $hookRoot commit --quiet -m 'names the phone on a branch deleted since'
    $staleNamed = (& git -C $hookRoot rev-parse HEAD).Trim()
    & git -C $hookRoot update-ref refs/remotes/origin/old $staleNamed
    & git -C $hookRoot rm --quiet docs/phone.md
    & git -C $hookRoot commit --quiet -m 'takes it out on a new branch'
    $staleDropped = (& git -C $hookRoot rev-parse HEAD).Trim()
    try {
        $env:PATH = $pathWithoutGh
        $env:GITHUB_ACTOR = $null
        $env:GITHUB_TOKEN = $null
        Assert-Throws { & $prePushScript -Root $hookRoot -RemoteUrl $scanRemote `
                -PushedRefs "refs/heads/topic2 $staleDropped refs/heads/topic2 $('0' * 40)" 6> $null } `
            "*commit $staleNamed name*${staleNamed}:docs/phone.md:1:*" `
            'A new branch went through with a commit only a stale remote-tracking ref held.'
    } finally {
        $env:PATH = $savedScanPath
        $env:GITHUB_ACTOR = $savedScanActor
        $env:GITHUB_TOKEN = $savedScanToken
    }

    # The scan hands git grep its commits a batch at a time. Batches of two over five commits
    # reach the third batch, a short one, where the only commit naming the phone sits.
    $batched = @(Find-MachineNames -Root $hookRoot -BatchSize 2 `
        -Commit @($cleanCommit, $droppedSerial, $newBranchHead, $cancelledOut, $servedSerial))
    Assert-True (($batched -join "`n") -like "*${servedSerial}:docs/phone.md:1:*") `
        "The scan missed a commit past its first batch: $($batched -join '; ')"

    # A hit in a binary file: git grep prints the file's bytes, and a carriage return among them
    # splits the printed line into pieces that don't open with a commit. The refusal puts the hit
    # down to the one commit that carries it, and none of the pieces.
    $binaryDoc = Join-Path $hookRoot 'docs/logo.bin'
    New-Item -ItemType Directory -Path (Split-Path -Parent $binaryDoc) -Force | Out-Null
    [IO.File]::WriteAllBytes($binaryDoc, [byte[]](@(0x89, 0x50, 0x4E, 0x47, 0x00, 0x0D) +
        [Text.Encoding]::ASCII.GetBytes("x$([char]13)adb -s $phoneSerial") + @(0x0D, 0x00, 0x0A)))
    & git -C $hookRoot add docs/logo.bin
    & git -C $hookRoot commit --quiet -m 'a picture that names the phone'
    $binaryNamed = (& git -C $hookRoot rev-parse HEAD).Trim()
    try {
        & $prePushScript -Root $hookRoot -PushedRefs "refs/heads/main $binaryNamed refs/heads/main $staleDropped" 6> $null
        $binaryRefusal = 'no refusal'
    } catch {
        $binaryRefusal = $_.Exception.Message
    }
    Assert-True ($binaryRefusal -like "Tracked files in commit $binaryNamed name the maintainer's machine or phone*" -and
        $binaryRefusal -like "*${binaryNamed}:docs/logo.bin:1:*") `
        "A hit in a binary file was not put down to its commit alone: $binaryRefusal"
    & git -C $hookRoot rm --quiet docs/logo.bin
    & git -C $hookRoot commit --quiet -m 'takes the picture out'
    $pictureGone = (& git -C $hookRoot rev-parse HEAD).Trim()

    # Text in UTF-16, which is what Windows PowerShell's > writes (little-endian, with a byte order
    # mark), or big-endian: git grep reads bytes, where every letter sits beside a NUL. The phone
    # and the notes folder are both found that way, in a pushed commit and in the working tree.
    # Mid-sentence each byte order's pattern also finds the other, the NULs of the letters around
    # lining up, so each file puts its name where only its own pattern can: first thing after the
    # little-endian byte order mark, or last thing in a big-endian file.
    $notesName = $notesFolder.Substring(1)
    $wideDocs = [ordered]@{
        'docs/notes-be.txt' = @([Text.Encoding]::BigEndianUnicode, "Fixtures live in ~/.$notesName")
        'docs/notes-le.txt' = @([Text.Encoding]::Unicode, "$notesName keeps the working notes.")
        'docs/phone-be.txt' = @([Text.Encoding]::BigEndianUnicode, "adb -s $phoneSerial")
        'docs/phone-le.txt' = @([Text.Encoding]::Unicode, "$phoneSerial is the test phone.")
    }
    New-Item -ItemType Directory -Path (Join-Path $hookRoot 'docs') -Force | Out-Null
    foreach ($doc in $wideDocs.Keys) {
        [IO.File]::WriteAllText((Join-Path $hookRoot $doc), $wideDocs[$doc][1], $wideDocs[$doc][0])
    }
    & git -C $hookRoot add -- @($wideDocs.Keys)
    & git -C $hookRoot commit --quiet -m 'wide text names the machine'
    $wideNamed = (& git -C $hookRoot rev-parse HEAD).Trim()
    Assert-Throws { & $prePushScript -Root $hookRoot -PushedRefs "refs/heads/main $wideNamed refs/heads/main $pictureGone" 6> $null } `
        ("*${wideNamed}:docs/notes-be.txt:1:*${wideNamed}:docs/notes-le.txt:1:*" +
            "${wideNamed}:docs/phone-be.txt:1:*${wideNamed}:docs/phone-le.txt:1:*") `
        'A push went through with UTF-16 text naming the machine and a phone.'
    try {
        & $prePushScript -Root $hookRoot -ChangedPaths @('docs/phone-le.txt') 6> $null
        $wideHandRun = 'no refusal'
    } catch {
        $wideHandRun = $_.Exception.Message
    }
    Assert-True ($wideHandRun -like '*the working tree*docs/notes-be.txt:1:*docs/notes-le.txt:1:*docs/phone-be.txt:1:*docs/phone-le.txt:1:*') `
        "A run by hand passed a working tree whose UTF-16 text names the machine: $wideHandRun"
    & git -C $hookRoot rm --quiet -- @($wideDocs.Keys)
    & git -C $hookRoot commit --quiet -m 'takes the wide text out'

    # The build branch, which runs the Gradle gates that hold the Bouncy Castle graphs to the
    # reviewed release. Starting a real build from a contract test would be absurd, so the case
    # reads the first thing that branch does instead: with no GitHub credentials and no gh on
    # the path, it refuses by name, and nothing else in the hook says that. A push that moved
    # only a pin used to take the release path and never reach this. Git stays on the path for
    # the scan every push makes; only gh goes.
    $savedPath = $env:PATH
    $savedActor = $env:GITHUB_ACTOR
    $savedToken = $env:GITHUB_TOKEN
    try {
        $env:PATH = $pathWithoutGh
        $env:GITHUB_ACTOR = $null
        $env:GITHUB_TOKEN = $null
        foreach ($pin in @('gradle/libs.versions.toml', 'gradle/verification-metadata.xml',
                'gradle/tooling-scopes.txt', 'settings.gradle.kts', 'build.gradle.kts', 'patches/build.gradle.kts')) {
            Assert-Throws { & $prePushScript -Root $hookRoot -ChangedPaths @($pin) 6> $null } `
                '*GITHUB_ACTOR*' "A push that changed $pin did not reach the build gates."
        }
        # And the control: a file the build branch has no interest in must not reach it.
        & $prePushScript -Root $hookRoot -ChangedPaths @('LICENSE') 6> $null
        Assert-True ($LASTEXITCODE -eq 0 -or $null -eq $LASTEXITCODE) `
            'A file no gate reads was routed into the build gates.'
    } finally {
        $env:PATH = $savedPath
        $env:GITHUB_ACTOR = $savedActor
        $env:GITHUB_TOKEN = $savedToken
    }

    # The build wrapper. HUSHFACEBOOK_BUILD_WRAPPER names the script that runs Gradle on this
    # machine, and the hook hands it the repository and the tasks. A stub stands in for it and
    # records what it was given, so no build starts.
    $savedWrapper = $env:HUSHFACEBOOK_BUILD_WRAPPER
    $savedActor = $env:GITHUB_ACTOR
    $savedToken = $env:GITHUB_TOKEN
    try {
        $env:GITHUB_ACTOR = 'contract'
        $env:GITHUB_TOKEN = 'contract'
        $wrapperMarker = Join-Path $hookRoot 'wrapper-ran.txt'
        $wrapperStub = Join-Path $hookRoot 'build-wrapper.ps1'
        Set-Content -LiteralPath $wrapperStub -Encoding UTF8 -Value @(
            'param([string]$ProjectDir, [string[]]$Tasks)',
            "Set-Content -LiteralPath '$wrapperMarker' -Value (`"dir=`$ProjectDir tasks=`" + (`$Tasks -join ','))",
            'exit 0')
        $env:HUSHFACEBOOK_BUILD_WRAPPER = $wrapperStub
        & $prePushScript -Root $hookRoot -ChangedPaths @('extensions/facebook/src/main/java/Any.java') 6> $null
        Assert-True (Test-Path -LiteralPath $wrapperMarker) `
            'The hook did not run the build through the wrapper HUSHFACEBOOK_BUILD_WRAPPER names.'
        $wrapped = Get-Content -LiteralPath $wrapperMarker -Raw
        Assert-True ($wrapped -like "dir=$hookRoot tasks=*:extensions:facebook:test*:patches:test*") `
            "The build wrapper was not handed the repository and the test tasks: $wrapped"

        # The Gradle file that writes the release bundle. The contract tests hold it to the
        # directory common.ps1 reads the bundle from, and a push that moved only it ran the build
        # and never them; the bundle path then went unchecked until the next script push.
        Remove-Item -LiteralPath $contractsMarker, $wrapperMarker -Force -ErrorAction SilentlyContinue
        & $prePushScript -Root $hookRoot -ChangedPaths @('patches/build.gradle.kts') 6> $null
        Assert-True ((Test-Path -LiteralPath $contractsMarker) -and (Test-Path -LiteralPath $wrapperMarker)) `
            'A push that changed only patches/build.gradle.kts did not run both the script contract tests and the build.'

        $env:HUSHFACEBOOK_BUILD_WRAPPER = Join-Path $hookRoot 'no-such-wrapper.ps1'
        Assert-Throws { & $prePushScript -Root $hookRoot -ChangedPaths @('patches/build.gradle.kts') 6> $null } `
            '*HUSHFACEBOOK_BUILD_WRAPPER*' 'A build wrapper that is not there was ignored rather than reported.'

        # Two Gradle passes, the quick one first. Everything but the tests that read the Facebook
        # fixtures runs before them, so a slip in a quick test or a lint stops the push before the
        # fixture scans start, and the full run comes second. The stub logs every pass it's handed
        # and fails the quick one while a marker file exists.
        $passLog = Join-Path $hookRoot 'gradle-passes.txt'
        $quickFails = Join-Path $hookRoot 'quick-pass-fails.txt'
        $passStub = Join-Path $hookRoot 'pass-wrapper.ps1'
        Set-Content -LiteralPath $passStub -Encoding UTF8 -Value @(
            'param([string]$ProjectDir, [string[]]$Tasks)',
            "Add-Content -LiteralPath '$passLog' -Value (`$Tasks -join ' ')",
            "if ((`$Tasks -contains '-x') -and (Test-Path -LiteralPath '$quickFails')) { exit 1 }",
            'exit 0')
        $env:HUSHFACEBOOK_BUILD_WRAPPER = $passStub
        try {
            Remove-Item -LiteralPath $passLog, $quickFails -Force -ErrorAction SilentlyContinue
            & $prePushScript -Root $hookRoot -ChangedPaths @('extensions/facebook/src/main/java/Any.java') 6> $null
            $passes = @(Get-Content -LiteralPath $passLog -ErrorAction SilentlyContinue)
            Assert-True ($passes.Count -eq 2) "The gate did not run Gradle twice, quick then full: $($passes -join ' | ')"
            $quickPass = " $($passes[0]) "
            $fullPass = " $($passes[1]) "
            Assert-True ($quickPass -like '* :patches:test *' -and $quickPass -like '* :extensions:facebook:test *' -and
                $quickPass -like '* :extensions:facebook:lint *' -and $quickPass -like '* -x :patches:fixtureTest *' -and
                $quickPass -like '* -x :patches:verifyPatchTestSelection *') `
                "The first pass was not the quick one without the fixture tests: $($passes[0])"
            Assert-True ($fullPass -like '* :patches:test *' -and $fullPass -like '* :extensions:facebook:lint *' -and
                $fullPass -notlike '* -x *') `
                "The second pass left the fixture tests or their selection check out: $($passes[1])"

            # A failing quick pass stops the push there: the fixture run never starts.
            Remove-Item -LiteralPath $passLog -Force -ErrorAction SilentlyContinue
            Set-Content -LiteralPath $quickFails -Value 'fail' -Encoding ASCII
            Assert-Throws { & $prePushScript -Root $hookRoot -ChangedPaths @('extensions/facebook/src/main/java/Any.java') 6> $null } `
                '*did not pass*' 'A failing quick pass did not stop the push.'
            $passes = @(Get-Content -LiteralPath $passLog -ErrorAction SilentlyContinue)
            Assert-True ($passes.Count -eq 1 -and " $($passes[0]) " -like '* -x :patches:fixtureTest *') `
                "A failing quick pass still started the full run: $($passes -join ' | ')"
        } finally {
            Remove-Item -LiteralPath $passLog, $quickFails, $passStub -Force -ErrorAction SilentlyContinue
        }

        # The fixtures, patched with the release bundle when the patch sources or the version move,
        # each passing run kept in patches/build/fixture-apply/<fixture file> for the receipt, and
        # an earlier push's kept runs cleared first. The build stub leaves a release bundle behind
        # only when buildAndroid is asked for, and the verify stub logs what it's handed and keeps
        # a stamp the way a passing run does.
        $applyLog = Join-Path $hookRoot 'apply-ran.txt'
        $applyBuildLog = Join-Path $hookRoot 'apply-build.txt'
        $applyFixtures = Join-Path $hookRoot 'apply-fixtures'
        $applyJar = Join-Path $hookRoot 'apply-cli.jar'
        $applyBuild = Join-Path $hookRoot 'apply-build.ps1'
        $keptRoot = Join-Path $hookRoot 'patches/build/fixture-apply'
        $releaseBundle = Join-Path $hookRoot 'patches/build/release/patches-9.9.9.mpp'
        $applyFiles = @('gradle.properties', 'patches-list.json', 'scripts/verify-all-patches.ps1') |
            ForEach-Object { Join-Path $hookRoot $_ }
        $applySaved = @{ Fixtures = $env:HUSHFACEBOOK_FIXTURE_DIR; Jar = $env:HUSHFACEBOOK_DESKTOP_JAR }
        try {
            New-Item -ItemType Directory -Path $applyFixtures -Force | Out-Null
            foreach ($name in @('facebook-586.0.0.52.53-arm64.apkm', 'facebook-586.0.0.52.53-armv7.apkm', 'facebook-580.0.0.51.74.apkm')) {
                Set-Content -LiteralPath (Join-Path $applyFixtures $name) -Value 'fixture' -Encoding ASCII
            }
            Set-Content -LiteralPath $applyJar -Value 'cli' -Encoding ASCII
            Set-Content -LiteralPath $applyFiles[0] -Value 'version = 9.9.9' -Encoding ASCII
            Set-Content -LiteralPath $applyFiles[1] -Encoding UTF8 -Value ('{"patches":[{"name":"A","compatibility":' +
                '[{"packageName":"com.facebook.katana","targets":[{"version":"586.0.0.52.53"}]}]}]}')
            Set-Content -LiteralPath $applyFiles[2] -Encoding UTF8 -Value @(
                'param([string]$Apk, [string]$DesktopJar, [string]$WorkDir, [string]$Bundle, [string]$KeepIn)',
                "Add-Content -LiteralPath '$applyLog' -Value (""apk="" + (Split-Path -Leaf `$Apk) + "" bundle=`$Bundle keep=`$KeepIn"")",
                'New-Item -ItemType Directory -Force -Path $KeepIn | Out-Null',
                'Set-Content -LiteralPath (Join-Path $KeepIn ''stamp.json'') -Value ''kept''',
                'exit 0')
            Set-Content -LiteralPath $applyBuild -Encoding UTF8 -Value @(
                'param([string]$ProjectDir, [string[]]$Tasks)',
                "Add-Content -LiteralPath '$applyBuildLog' -Value (' ' + (`$Tasks -join ' ') + ' ')",
                "if (`$Tasks -contains ':patches:buildAndroid') {",
                "    New-Item -ItemType Directory -Force -Path (Split-Path -Parent '$releaseBundle') | Out-Null",
                "    Set-Content -LiteralPath '$releaseBundle' -Value 'release bundle'",
                '}',
                'exit 0')
            $env:HUSHFACEBOOK_BUILD_WRAPPER = $applyBuild
            $env:HUSHFACEBOOK_FIXTURE_DIR = $applyFixtures
            $env:HUSHFACEBOOK_DESKTOP_JAR = $applyJar
            foreach ($moved in @('gradle.properties', 'patches/src/main/kotlin/app/morphe/Any.kt')) {
                Remove-Item -LiteralPath $applyLog, $applyBuildLog -Force -ErrorAction SilentlyContinue
                Remove-Item -LiteralPath (Join-Path $hookRoot 'patches') -Recurse -Force -ErrorAction SilentlyContinue
                New-Item -ItemType Directory -Path (Join-Path $keptRoot 'stale.apkm') -Force | Out-Null
                Set-Content -LiteralPath (Join-Path $keptRoot 'stale.apkm/stamp.json') -Value 'stale' -Encoding ASCII
                & $prePushScript -Root $hookRoot -ChangedPaths @($moved) 6> $null
                $builds = @(Get-Content -LiteralPath $applyBuildLog -ErrorAction SilentlyContinue)
                Assert-True ($builds.Count -eq 2 -and $builds[0] -notlike '* :patches:buildAndroid *' -and
                    $builds[1] -like '* :patches:buildAndroid *') `
                    "A push of $moved did not build the release bundle in the full pass alone: $($builds -join ' | ')"
                $applied = @(Get-Content -LiteralPath $applyLog -ErrorAction SilentlyContinue | Sort-Object)
                $declared = @('facebook-586.0.0.52.53-arm64.apkm', 'facebook-586.0.0.52.53-armv7.apkm')
                $rightRuns = @(for ($at = 0; $at -lt $applied.Count; $at++) {
                    $run = [regex]::Match($applied[$at], '^apk=(?<apk>\S+) bundle=(?<bundle>.+) keep=(?<keep>.+)$')
                    if ($run.Success -and $at -lt $declared.Count -and $run.Groups['apk'].Value -eq $declared[$at] -and
                        [IO.Path]::GetFullPath($run.Groups['bundle'].Value) -ieq [IO.Path]::GetFullPath($releaseBundle) -and
                        [IO.Path]::GetFullPath($run.Groups['keep'].Value) -ieq
                            [IO.Path]::GetFullPath((Join-Path $keptRoot $declared[$at]))) { $at }
                })
                Assert-True ($applied.Count -eq 2 -and $rightRuns.Count -eq 2) `
                    "A push of $moved did not patch each declared fixture with the release bundle and keep it: $($applied -join ' | ')"
                Assert-True (-not (Test-Path -LiteralPath (Join-Path $keptRoot 'stale.apkm'))) `
                    "A push of $moved left an earlier push's kept run beside the new ones."
            }
            # Anything else builds no release bundle and patches nothing.
            Remove-Item -LiteralPath $applyLog, $applyBuildLog -Force -ErrorAction SilentlyContinue
            & $prePushScript -Root $hookRoot -ChangedPaths @('extensions/facebook/src/main/java/Any.java') 6> $null
            Assert-True (-not (Test-Path -LiteralPath $applyLog) -and
                @(Get-Content -LiteralPath $applyBuildLog | Where-Object { $_ -like '* :patches:buildAndroid *' }).Count -eq 0) `
                'An extension-only push built the release bundle or patched the fixtures.'
            # A desktop CLI bundling another patcher major than the pin stops the push before the
            # build starts, not at the fixture apply after it (the 0.17.0 push got 17 minutes in).
            $pinFile = Join-Path $hookRoot 'gradle/libs.versions.toml'
            New-Item -ItemType Directory -Path (Split-Path -Parent $pinFile) -Force | Out-Null
            Set-Content -LiteralPath $pinFile -Encoding ASCII -Value @('[versions]', 'morphe-patcher = "2.0.0"')
            New-DesktopJarStandIn -Path $applyJar -Patcher '1.15.1'
            Remove-Item -LiteralPath $applyLog, $applyBuildLog -Force -ErrorAction SilentlyContinue
            Assert-Throws { & $prePushScript -Root $hookRoot -ChangedPaths @('patches/src/main/kotlin/app/morphe/Any.kt') 6> $null } `
                "*$applyJar bundles morphe-patcher 1.15.1, but gradle/libs.versions.toml needs 2.0.0*" `
                'A push with a 1.x desktop CLI on the 2.0 pin was not refused up front.'
            Assert-True (-not (Test-Path -LiteralPath $applyBuildLog) -and -not (Test-Path -LiteralPath $applyLog)) `
                'A push with a desktop CLI on the wrong patcher built or patched something before it was refused.'
            New-DesktopJarStandIn -Path $applyJar -Patcher ''
            Assert-Throws { & $prePushScript -Root $hookRoot -ChangedPaths @('patches/src/main/kotlin/app/morphe/Any.kt') 6> $null } `
                "*$applyJar has no app/morphe/patcher/version.properties*" `
                'A push with a desktop CLI that names no patcher was not refused up front.'
            # On the pin's major the push goes on to build and patch as before.
            New-DesktopJarStandIn -Path $applyJar -Patcher '2.0.0'
            & $prePushScript -Root $hookRoot -ChangedPaths @('patches/src/main/kotlin/app/morphe/Any.kt') 6> $null
            Assert-True (@(Get-Content -LiteralPath $applyLog -ErrorAction SilentlyContinue).Count -eq 2) `
                'A push with a desktop CLI on the pinned patcher major did not patch the fixtures.'
        } finally {
            Remove-Item -LiteralPath (Join-Path $hookRoot 'gradle') -Recurse -Force -ErrorAction SilentlyContinue
            $env:HUSHFACEBOOK_FIXTURE_DIR = $applySaved.Fixtures
            $env:HUSHFACEBOOK_DESKTOP_JAR = $applySaved.Jar
            $env:HUSHFACEBOOK_BUILD_WRAPPER = $wrapperStub
            Remove-Item -LiteralPath (@($applyLog, $applyBuildLog, $applyJar, $applyBuild) + $applyFiles) -Force -ErrorAction SilentlyContinue
            Remove-Item -LiteralPath $applyFixtures, (Join-Path $hookRoot 'patches') -Recurse -Force -ErrorAction SilentlyContinue
        }

        # The gate builds what is pushed, not what happens to be in the working tree. A stub build
        # fails on any tree whose marker says broken, and records the tree it was handed.
        $gateRepo = Join-Path ([System.IO.Path]::GetTempPath()) ("hushfacebook-gate-" + [guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path (Join-Path $gateRepo 'extensions') -Force | Out-Null
        & git -C $gateRepo init --quiet
        $actualGateGitDir = (& git -C $gateRepo rev-parse --absolute-git-dir).Trim()
        Assert-True ([IO.Path]::GetFullPath($actualGateGitDir).TrimEnd('\', '/') -ieq
            [IO.Path]::GetFullPath((Join-Path $gateRepo '.git')).TrimEnd('\', '/')) `
            'The gate fixture resolved outside its temporary repository; refusing to write.'
        & git -C $gateRepo config user.name 'Gate Contract'
        & git -C $gateRepo config user.email 'gate@example.invalid'
        $gateMarker = Join-Path $hookRoot 'gate-ran.txt'
        $gateStub = Join-Path $hookRoot 'gate-wrapper.ps1'
        Set-Content -LiteralPath $gateStub -Encoding UTF8 -Value @(
            'param([string]$ProjectDir, [string[]]$Tasks)',
            '$state = (Get-Content -LiteralPath (Join-Path $ProjectDir ''extensions/marker.txt'') -Raw).Trim()',
            "Set-Content -LiteralPath '$gateMarker' -Value (`"dir=`$ProjectDir marker=`$state gitdir=`$env:GIT_DIR`")",
            'if ($state -eq ''broken'') { exit 1 }',
            'exit 0')
        $env:HUSHFACEBOOK_BUILD_WRAPPER = $gateStub
        $gateFile = Join-Path $gateRepo 'extensions/marker.txt'
        function Save-GateCommit([string]$State) {
            Set-Content -LiteralPath $gateFile -Value $State -Encoding ASCII
            & git -C $gateRepo add extensions/marker.txt
            & git -C $gateRepo commit --quiet -m $State
            return (& git -C $gateRepo rev-parse HEAD).Trim()
        }
        try {
            $good = Save-GateCommit 'good'
            Set-Content -LiteralPath $gateFile -Value 'broken' -Encoding ASCII
            & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $good refs/heads/main $('0' * 40)" 6> $null
            Assert-True ($LASTEXITCODE -eq 0) 'An uncommitted edit in the working tree failed a clean commit.'
            $built = Get-Content -LiteralPath $gateMarker -Raw
            Assert-True ($built -like '*marker=good*' -and $built -notlike "*dir=$gateRepo marker*") `
                "The gate built the working tree instead of the pushed commit: $built"

            $broken = Save-GateCommit 'broken'
            Set-Content -LiteralPath $gateFile -Value 'good' -Encoding ASCII
            Assert-Throws { & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $broken refs/heads/main $good" 6> $null } `
                '*did not pass*' 'An uncommitted fix in the working tree passed a broken commit.'
            Assert-True ((& git -C $gateRepo status --porcelain) -like '*extensions/marker.txt*') `
                'Building the pushed commit touched the working tree it was kept apart from.'

            # A clean tree still builds in place.
            $fixed = Save-GateCommit 'good'
            & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $fixed refs/heads/main $broken" 6> $null
            Assert-True ($LASTEXITCODE -eq 0) 'A clean tree with a good commit did not pass.'
            Assert-True ((Get-Content -LiteralPath $gateMarker -Raw) -like "*dir=$gateRepo marker=good*") `
                'A clean tree was not built in place.'

            # A tree that changes while it is built in place: an edit landing mid-build was tested
            # along with the commit, so that build says nothing about the commit alone.
            $meddler = Join-Path $hookRoot 'gate-wrapper-meddles.ps1'
            $meddled = Join-Path $gateRepo 'README.md'
            Set-Content -LiteralPath $meddler -Encoding UTF8 -Value @(
                'param([string]$ProjectDir, [string[]]$Tasks)',
                'Set-Content -LiteralPath (Join-Path $ProjectDir ''README.md'') -Value ''edited mid-build'' -Encoding ASCII',
                'exit 0')
            $env:HUSHFACEBOOK_BUILD_WRAPPER = $meddler
            try {
                Assert-Throws { & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $fixed refs/heads/main $broken" 6> $null } `
                    '*changed while the runtime test build ran in place*' `
                    'A working tree that changed during an in-place build passed on that build.'
            } finally {
                $env:HUSHFACEBOOK_BUILD_WRAPPER = $gateStub
                Remove-Item -LiteralPath $meddled -Force -ErrorAction SilentlyContinue
            }
            & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $fixed refs/heads/main $broken" 6> $null
            Assert-True ($LASTEXITCODE -eq 0) 'A clean tree failed once the mid-build edit was gone.'

            # But only for HEAD. A clean tree whose HEAD is good says nothing about an older commit
            # pushed by name, or another branch, and those used to have HEAD built in their place.
            Assert-Throws { & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/other $broken refs/heads/other $good" 6> $null } `
                '*did not pass*' 'A clean tree passed a broken commit that was pushed but is not HEAD.'

            # Uncommitted files anywhere count, not only under the source folders: the tests read
            # README.md and patches-list.json from the root.
            $rootFile = Join-Path $gateRepo 'README.md'
            Set-Content -LiteralPath $rootFile -Value 'uncommitted' -Encoding ASCII
            & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $fixed refs/heads/main $broken" 6> $null
            $built = Get-Content -LiteralPath $gateMarker -Raw
            Assert-True ($LASTEXITCODE -eq 0 -and $built -like '*marker=good*' -and $built -notlike "*dir=$gateRepo marker*") `
                "An uncommitted root file was built in place with the push: $built"
            Remove-Item -LiteralPath $rootFile -Force

            # Git hands a hook GIT_DIR and GIT_WORK_TREE. Neither may steer the worktree commands
            # into this working tree, nor reach the build.
            Set-Content -LiteralPath $gateFile -Value 'broken' -Encoding ASCII
            $headBefore = (& git -C $gateRepo symbolic-ref HEAD).Trim()
            try {
                $env:GIT_DIR = Join-Path $gateRepo '.git'
                $env:GIT_WORK_TREE = $gateRepo
                & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $fixed refs/heads/main $broken" 6> $null
            } finally {
                Remove-Item -LiteralPath Env:\GIT_DIR, Env:\GIT_WORK_TREE -ErrorAction SilentlyContinue
            }
            $built = Get-Content -LiteralPath $gateMarker -Raw
            Assert-True ($LASTEXITCODE -eq 0 -and $built.Trim() -like '*marker=good gitdir=') `
                "The build ran with git's hook variables set: $built"
            Assert-True ((Get-Content -LiteralPath $gateFile -Raw).Trim() -eq 'broken' -and
                (& git -C $gateRepo symbolic-ref HEAD).Trim() -eq $headBefore) `
                "With GIT_DIR set, building the pushed commit rewrote the working tree it was kept apart from."

            # One push at a time through the gate worktree. With the lock held here, a hook in
            # another process has to give up rather than check its commit out under a running build.
            $gateHasher = [System.Security.Cryptography.SHA256]::Create()
            try {
                $gateDigest = $gateHasher.ComputeHash([Text.Encoding]::UTF8.GetBytes(
                    [IO.Path]::GetFullPath($gateRepo).ToLowerInvariant()))
            } finally {
                $gateHasher.Dispose()
            }
            $gateKey = -join ($gateDigest[0..5] | ForEach-Object { $_.ToString('x2') })
            $shell = (Get-Process -Id $PID).Path
            $childRefs = "refs/heads/main $fixed refs/heads/main $broken"
            function Invoke-ChildPush {
                # Windows PowerShell stops on a native command's first line of standard error.
                $preference = $ErrorActionPreference
                $ErrorActionPreference = 'Continue'
                try {
                    return (& $shell -NoProfile -File $prePushScript -Root $gateRepo -PushedRefs $childRefs `
                        -GateLockTimeoutSeconds 1 2>&1 | Out-String)
                } finally {
                    $ErrorActionPreference = $preference
                }
            }
            $held = New-Object System.Threading.Mutex($false, "Local\hushfacebook-pre-push-$gateKey")
            Assert-True ($held.WaitOne(0)) 'The contract could not take the gate lock itself.'
            try {
                $waited = Invoke-ChildPush
                Assert-True ($LASTEXITCODE -ne 0 -and $waited -like '*held the gate worktree*') `
                    "A second push used the gate worktree while another push held it: $waited"
            } finally {
                $held.ReleaseMutex()
                $held.Dispose()
            }
            # The control: the same child push, with the lock free, goes through.
            $free = Invoke-ChildPush
            Assert-True ($LASTEXITCODE -eq 0) "The child push failed with the gate lock free: $free"

            # The release facts half checks the files a push carries as well. A stub check, committed
            # the way the real one is, fails on a README that says broken and records where it ran
            # and whether it read test results. Its own commit is never in a pushed range, so no
            # push below touches scripts/. A README push asks for the contract tests too, since they
            # end with the marketing asset check, so a stub suite that passes is committed with it.
            $gateFacts = Join-Path $hookRoot 'gate-facts-ran.txt'
            & git -C $gateRepo checkout --quiet -- extensions/marker.txt
            New-Item -ItemType Directory -Path (Join-Path $gateRepo 'scripts') -Force | Out-Null
            Set-Content -LiteralPath (Join-Path $gateRepo 'scripts/test-script-contracts.ps1') -Encoding UTF8 -Value @(
                'param([string]$Root)', 'exit 0')
            & git -C $gateRepo add scripts/test-script-contracts.ps1
            Set-Content -LiteralPath (Join-Path $gateRepo 'scripts/validate-release-facts.ps1') -Encoding UTF8 -Value @(
                'param([string]$Root, [switch]$SkipDescriptionTestCount, [switch]$AllowPublishedIndexLag,',
                '    [switch]$VerifyPublishedAsset, [string]$ArtifactPath, [switch]$SkipTestResults)',
                '$state = (Get-Content -LiteralPath (Join-Path $Root ''README.md'') -Raw).Trim()',
                "Set-Content -LiteralPath '$gateFacts' -Value (`"root=`$Root readme=`$state results=`$(-not `$SkipTestResults)`")",
                'if ($state -eq ''broken'') { exit 1 }',
                'exit 0')
            $gateReadme = Join-Path $gateRepo 'README.md'
            function Save-GateReadme([string]$State) {
                Set-Content -LiteralPath $gateReadme -Value $State -Encoding ASCII
                & git -C $gateRepo add README.md
                & git -C $gateRepo commit --quiet -m "readme $State"
                return (& git -C $gateRepo rev-parse HEAD).Trim()
            }
            & git -C $gateRepo add scripts/validate-release-facts.ps1
            $factsBase = Save-GateReadme 'base'
            $factsGood = Save-GateReadme 'good'

            # An uncommitted README that would fail the check doesn't fail a push without it.
            Set-Content -LiteralPath $gateReadme -Value 'broken' -Encoding ASCII
            & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $factsGood refs/heads/main $factsBase" 6> $null
            $checked = Get-Content -LiteralPath $gateFacts -Raw
            Assert-True ($LASTEXITCODE -eq 0 -and $checked -like '*readme=good results=False*' -and
                $checked -notlike "*root=$gateRepo *") `
                "The release facts were read from the working tree instead of the pushed commit: $checked"

            # And an uncommitted fix doesn't pass a push whose own README fails.
            $factsBroken = Save-GateReadme 'broken'
            Set-Content -LiteralPath $gateReadme -Value 'good' -Encoding ASCII
            Assert-Throws { & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $factsBroken refs/heads/main $factsGood" 6> $null } `
                '*release facts do not agree*' 'An uncommitted README fix passed a push whose README fails the release facts.'

            # An index push is checked against the bundle and results this checkout built, so from a
            # dirty tree it is refused by name rather than checked against the wrong files.
            Set-Content -LiteralPath (Join-Path $gateRepo 'patches-bundle.json') -Value '{}' -Encoding ASCII
            & git -C $gateRepo add patches-bundle.json
            & git -C $gateRepo commit --quiet -m 'index'
            $factsIndex = (& git -C $gateRepo rev-parse HEAD).Trim()
            Assert-Throws { & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $factsIndex refs/heads/main $factsBroken" 6> $null } `
                '*clean checkout of the commit it pushes*' 'An index push from a dirty tree was checked against files it does not carry.'

            # The control: a clean tree pushing HEAD is checked in place, results and all.
            & git -C $gateRepo checkout --quiet -- README.md
            $factsFixed = Save-GateReadme 'good'
            & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $factsFixed refs/heads/main $factsIndex" 6> $null
            $checked = Get-Content -LiteralPath $gateFacts -Raw
            Assert-True ($LASTEXITCODE -eq 0 -and $checked -like "*root=$gateRepo readme=good results=True*") `
                "A clean tree pushing HEAD was not checked in place: $checked"

            # The script suites are the pushed commit's too, run against that commit: they copy the
            # root files into their fixtures, and ran from the working tree until 2026-09-21. A stub
            # suite records where it ran and the state it was committed with.
            $gateContracts = Join-Path $hookRoot 'gate-contracts-ran.txt'
            $contractsStub = Join-Path $gateRepo 'scripts/test-script-contracts.ps1'
            function Save-GateContracts([string]$State) {
                Set-Content -LiteralPath $contractsStub -Encoding UTF8 -Value @(
                    'param([string]$Root)',
                    "Set-Content -LiteralPath '$gateContracts' -Value (`"root=`$Root state=$State`")",
                    $(if ($State -eq 'broken') { 'exit 1' } else { 'exit 0' }))
                & git -C $gateRepo add scripts/test-script-contracts.ps1
                & git -C $gateRepo commit --quiet -m "contracts $State"
                return (& git -C $gateRepo rev-parse HEAD).Trim()
            }
            $contractsGood = Save-GateContracts 'good'
            Set-Content -LiteralPath $contractsStub -Encoding UTF8 -Value @('param([string]$Root)', 'exit 1')
            & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $contractsGood refs/heads/main $factsFixed" 6> $null
            $ran = Get-Content -LiteralPath $gateContracts -Raw
            Assert-True ($LASTEXITCODE -eq 0 -and $ran -like '*state=good*' -and $ran -notlike "*root=$gateRepo *") `
                "The script contract tests ran from the working tree instead of the pushed commit: $ran"
            & git -C $gateRepo checkout --quiet -- scripts/test-script-contracts.ps1
            & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $contractsGood refs/heads/main $factsFixed" 6> $null
            $ran = Get-Content -LiteralPath $gateContracts -Raw
            Assert-True ($LASTEXITCODE -eq 0 -and $ran -like "*root=$gateRepo state=good*") `
                "A clean tree pushing HEAD did not run its script contract tests in place: $ran"
            $contractsBroken = Save-GateContracts 'broken'
            Assert-Throws { & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $contractsBroken refs/heads/main $contractsGood" 6> $null } `
                '*script contract tests did not pass*' 'A push whose own script contract tests fail was let through.'

            # A pushed commit's suite and facts check run in a process of their own. Run in the
            # hook's, they saw every function the hook and the working tree's common.ps1 define, so
            # a suite calling a helper that exists only uncommitted passed the hook and failed for
            # anyone who checked the commit out. A copy of the hook sits in this working tree beside
            # a common.ps1 that defines such a helper, uncommitted; the commit's own common.ps1
            # doesn't, until the control commits it. The suite and the check each call it.
            $helperMarker = Join-Path $hookRoot 'gate-helper-ran.txt'
            $gateCommon = Join-Path $gateRepo 'scripts/common.ps1'
            $gateHook = Join-Path $gateRepo 'scripts/pre-push.ps1'
            $helperCalls = @('$ErrorActionPreference = ''Stop''', '. (Join-Path $PSScriptRoot ''common.ps1'')',
                "Set-Content -LiteralPath '$helperMarker' -Value (Get-UncommittedHelper)")
            function Save-GateHelperCommit([string]$Message, [string[]]$Common, [string[]]$Suite, [string[]]$Facts) {
                Set-Content -LiteralPath $gateCommon -Encoding UTF8 -Value $Common
                if ($Suite) { Set-Content -LiteralPath $contractsStub -Encoding UTF8 -Value $Suite }
                if ($Facts) { Set-Content -LiteralPath (Join-Path $gateRepo 'scripts/validate-release-facts.ps1') -Encoding UTF8 -Value $Facts }
                # By name: the hook's copy sits in scripts/ too, and it isn't part of any commit.
                & git -C $gateRepo add scripts/common.ps1 scripts/test-script-contracts.ps1 scripts/validate-release-facts.ps1
                & git -C $gateRepo commit --quiet -m $Message
                return (& git -C $gateRepo rev-parse HEAD).Trim()
            }
            $withoutHelper = @('function Get-CommittedHelper { ''committed'' }')
            $suiteCallingHelper = @('param([string]$Root)') + $helperCalls + @('exit 0')
            $factsCallingHelper = @('param([string]$Root, [switch]$SkipDescriptionTestCount, [switch]$AllowPublishedIndexLag,',
                '    [switch]$VerifyPublishedAsset, [string]$ArtifactPath, [switch]$SkipTestResults)') + $helperCalls + @('exit 0')
            $helperBase = (& git -C $gateRepo rev-parse HEAD).Trim()
            $suiteNeedsHelper = Save-GateHelperCommit 'suite calls a helper' -Common $withoutHelper -Suite $suiteCallingHelper
            # The working tree's common.ps1: the hook's own helpers, and the one nobody committed.
            Set-Content -LiteralPath $gateCommon -Encoding UTF8 -Value (@(Get-Content -LiteralPath (Join-Path $PSScriptRoot 'common.ps1')) +
                @('function Get-UncommittedHelper { ''uncommitted'' }'))
            Copy-Item -LiteralPath $prePushScript -Destination $gateHook
            try {
                Remove-Item -LiteralPath $helperMarker -Force -ErrorAction SilentlyContinue
                Assert-Throws { & $gateHook -Root $gateRepo -PushedRefs "refs/heads/main $suiteNeedsHelper refs/heads/main $helperBase" 6> $null } `
                    '*script contract tests did not pass*' `
                    'A pushed suite calling a helper only the working tree defines passed the hook.'
                Assert-True (-not (Test-Path -LiteralPath $helperMarker)) 'The pushed suite reached a helper its commit does not define.'
                # The facts check the same way, on a README push, with a suite that calls nothing.
                $factsNeedsHelper = Save-GateHelperCommit 'facts check calls a helper' -Common $withoutHelper `
                    -Suite @('param([string]$Root)', 'exit 0') -Facts $factsCallingHelper
                Set-Content -LiteralPath $gateCommon -Encoding UTF8 -Value (@(Get-Content -LiteralPath (Join-Path $PSScriptRoot 'common.ps1')) +
                    @('function Get-UncommittedHelper { ''uncommitted'' }'))
                $factsReadme = Save-GateReadme 'helper'
                Assert-Throws { & $gateHook -Root $gateRepo -PushedRefs "refs/heads/main $factsReadme refs/heads/main $factsNeedsHelper" 6> $null } `
                    '*release facts do not agree*' 'A pushed facts check calling a helper only the working tree defines passed the hook.'
                # The control: the helper committed, and the same pushes go through from the same
                # dirty tree, each script finding it in its own commit's common.ps1.
                $helperCommitted = Save-GateHelperCommit 'helper committed' -Common @($withoutHelper + @('function Get-UncommittedHelper { ''committed now'' }')) `
                    -Suite $suiteCallingHelper -Facts $factsCallingHelper
                Set-Content -LiteralPath $gateCommon -Encoding UTF8 -Value (@(Get-Content -LiteralPath (Join-Path $PSScriptRoot 'common.ps1')) +
                    @('function Get-UncommittedHelper { ''uncommitted'' }'))
                $controlReadme = Save-GateReadme 'control'
                Remove-Item -LiteralPath $helperMarker -Force -ErrorAction SilentlyContinue
                $global:LASTEXITCODE = 0
                & $gateHook -Root $gateRepo -PushedRefs "refs/heads/main $controlReadme refs/heads/main $helperBase" 6> $null
                Assert-True ($LASTEXITCODE -eq 0 -and (Get-Content -LiteralPath $helperMarker -Raw).Trim() -eq 'committed now') `
                    "A pushed commit whose own common.ps1 defines the helper did not pass from a dirty tree: $LASTEXITCODE"
            } finally {
                Remove-Item -LiteralPath $gateHook -Force -ErrorAction SilentlyContinue
                & git -C $gateRepo checkout --quiet -- scripts
            }
        } finally {
            foreach ($line in @(& git -C $gateRepo worktree list --porcelain)) {
                if ($line -like 'worktree *') {
                    $listed = $line.Substring('worktree '.Length)
                    if ([IO.Path]::GetFullPath($listed).TrimEnd('\', '/') -ine [IO.Path]::GetFullPath($gateRepo).TrimEnd('\', '/')) {
                        & git -C $gateRepo worktree remove --force $listed
                    }
                }
            }
            Remove-Item -LiteralPath $gateRepo -Recurse -Force -ErrorAction SilentlyContinue
        }
    } finally {
        $env:HUSHFACEBOOK_BUILD_WRAPPER = $savedWrapper
        $env:GITHUB_ACTOR = $savedActor
        $env:GITHUB_TOKEN = $savedToken
    }
} finally {
    $env:HUSHFACEBOOK_SKIP_PRE_PUSH = $savedSkip
    $env:HUSHFACEBOOK_BUILD_WRAPPER = $savedRoutingWrapper
    $env:GITHUB_ACTOR = $savedRoutingActor
    $env:GITHUB_TOKEN = $savedRoutingToken
    foreach ($name in $savedHookGit.Keys) { Set-Item -LiteralPath ('Env:\' + $name) -Value $savedHookGit[$name] }
    Remove-Item -LiteralPath $hookRoot -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host '[scripts] pre-push routing contracts passed'

# --- Test-ChangelogVersions ------------------------------------------------------------------
#
# A released version's heading is the only record a reader has that it shipped. On 2026-09-14 a
# post-release commit renamed "## 0.31.0" to "## Unreleased" and the file then said that release
# never happened; nothing read this file at all. Both directions are exercised here, and the
# real CHANGELOG is the control.

$headingShapes = @"
## 0.32.0
some text
## 0.31.0 (2026-09-14)
more text
## [0.1.5](https://example.invalid/compare/v0.1.4...v0.1.5) (2026-06-01)
older text
"@
$shapes = @(Get-ChangelogVersions -Text $headingShapes)
Assert-True (($shapes -join ',') -eq '0.32.0,0.31.0,0.1.5') `
    "The heading shapes this file uses were not all read: $($shapes -join ',')"
Assert-True (@(Get-ChangelogVersions -Text "## Unreleased`n## Notes").Count -eq 0) `
    'A heading that names no version was read as one.'

$previousChangelog = "## 0.31.0`nshipped`n## 0.30.2`nshipped"
$goodChangelog = "## 0.32.0`nnew`n" + $previousChangelog
$good = Test-ChangelogVersions -Current $goodChangelog -ExpectedVersion '0.32.0' `
    -Previous $previousChangelog -PreviousLabel 'tag v0.31.0'
Assert-True $good.Valid "A CHANGELOG that kept every shipped version was refused: $($good.Reason)"

# The 2026-09-14 defect, exactly: the previous version's heading renamed to Unreleased.
$renamed = Test-ChangelogVersions -Current ("## 0.32.0`nnew`n## Unreleased`nshipped`n## 0.30.2`nshipped") `
    -ExpectedVersion '0.32.0' -Previous $previousChangelog -PreviousLabel 'tag v0.31.0'
Assert-True (-not $renamed.Valid) 'A released version renamed to Unreleased was accepted.'
Assert-True ($renamed.Reason -like '*0.31.0*tag v0.31.0*') `
    "The renamed heading was refused for the wrong reason: $($renamed.Reason)"

$dropped = Test-ChangelogVersions -Current "## 0.32.0`nnew`n## 0.31.0`nshipped" `
    -ExpectedVersion '0.32.0' -Previous $previousChangelog
Assert-True (-not $dropped.Valid) 'A shipped version deleted from the CHANGELOG was accepted.'

$noHeading = Test-ChangelogVersions -Current $previousChangelog -ExpectedVersion '0.32.0' `
    -Previous $previousChangelog
Assert-True (-not $noHeading.Valid) 'A CHANGELOG with no heading for the built version was accepted.'
Assert-True ($noHeading.Reason -like '*no heading for 0.32.0*') `
    "The missing heading was refused for the wrong reason: $($noHeading.Reason)"

Assert-True (-not (Test-ChangelogVersions -Current "# Changelog`nnothing here" -ExpectedVersion '0.32.0').Valid) `
    'A CHANGELOG naming no version at all was accepted.'

# With no earlier file to compare against, the version being built is still required and a
# CHANGELOG that has it is still accepted. A first release has no tag behind it.
Assert-True (Test-ChangelogVersions -Current $goodChangelog -ExpectedVersion '0.32.0').Valid `
    'A checkout with no earlier tag was refused.'

# The control. The real file, held to the real version, must pass: every case above is this
# same shape with one heading moved.
$realVersion = Get-BundleVersion -Root $Root
$realChangelog = Get-Content -LiteralPath (Join-Path $Root 'CHANGELOG.md') -Raw
$realTag = "$(& git -C $Root describe --tags --abbrev=0 HEAD 2>$null | Select-Object -First 1)".Trim()
$realPrevious = if ($realTag) { (& git -C $Root show "${realTag}:CHANGELOG.md" 2>$null) -join "`n" } else { '' }
$realCheck = if ([string]::IsNullOrWhiteSpace($realPrevious)) {
    Test-ChangelogVersions -Current $realChangelog -ExpectedVersion $realVersion
} else {
    Test-ChangelogVersions -Current $realChangelog -ExpectedVersion $realVersion -Previous $realPrevious
}
Assert-True $realCheck.Valid "This repository's own CHANGELOG was refused: $($realCheck.Reason)"

Write-Host '[scripts] changelog history contracts passed'

# --- Test-ChangelogManagerEntry --------------------------------------------------------------
#
# Morphe Manager skips a heading with no date and flags an app only for bullets scoped to it.
# Every heading from 0.23.0 to 0.40.0 was bare and Manager's list stopped at 0.22.0, while the
# history check above passed them all. Each refusal below is the readable entry with one thing
# taken away, and the real CHANGELOG is the control.

$readable = @"
## Unreleased

* not held to anything yet

## 0.42.0 (2026-09-20)

A sentence about the release.

### Settings

* one change.
* another change.

### On the video

* a third.

## 0.41.0

* an older entry, frozen as it shipped
"@
$entry = Test-ChangelogManagerEntry -Current $readable -ExpectedVersion '0.42.0'
Assert-True $entry.Valid "A dated entry with scoped bullets was refused: $($entry.Reason)"
Assert-True ($entry.Date -eq '2026-09-20' -and $entry.Bullets -eq 3) `
    "The readable entry was misread: date $($entry.Date), $($entry.Bullets) bullets"
$crlf = Test-ChangelogManagerEntry -Current ($readable -replace "`r?`n", "`r`n") -ExpectedVersion '0.42.0'
Assert-True ($crlf.Valid -and $crlf.Bullets -eq 3) "CRLF line endings changed the reading: $($crlf.Reason)"

$undated = Test-ChangelogManagerEntry -Current ($readable -replace '## 0\.42\.0 \(2026-09-20\)', '## 0.42.0') `
    -ExpectedVersion '0.42.0'
Assert-True (-not $undated.Valid) 'An undated heading, which Manager skips, was accepted.'
Assert-True ($undated.Reason -like '*no date*') "The undated heading was refused for the wrong reason: $($undated.Reason)"

# A bullet with no label is a Facebook change. The app's own label still counts, a leftover from before
# the labels were dropped, and any other label is refused as not this app's change.
$unscoped = Test-ChangelogManagerEntry -Current $readable -ExpectedVersion '0.42.0'
Assert-True ($unscoped.Valid -and $unscoped.Bullets -eq 3) "Unlabeled bullets were refused or miscounted: $($unscoped.Reason)"
$leftoverLabel = Test-ChangelogManagerEntry -Current ($readable -replace '\* another', '* **Facebook:** another') `
    -ExpectedVersion '0.42.0'
Assert-True ($leftoverLabel.Valid -and $leftoverLabel.Bullets -eq 3) `
    "A bullet with a leftover Facebook label was refused or miscounted: $($leftoverLabel.Reason)"
$foreignLabel = Test-ChangelogManagerEntry -Current ($readable -replace '\* another', '* **Feed:** another') `
    -ExpectedVersion '0.42.0'
Assert-True (-not $foreignLabel.Valid) 'A bullet labeled for something other than Facebook was accepted.'
Assert-True ($foreignLabel.Reason -like 'Line 12 *') "The foreign label was refused for the wrong reason: $($foreignLabel.Reason)"

$wrapped = Test-ChangelogManagerEntry -Current ($readable -replace 'one change\.', "one`n  change.") `
    -ExpectedVersion '0.42.0'
Assert-True (-not $wrapped.Valid) 'A wrapped bullet, whose second line Manager drops, was accepted.'
Assert-True ($wrapped.Reason -like '*continues the bullet*') "The wrapped bullet was refused for the wrong reason: $($wrapped.Reason)"

$noBullets = Test-ChangelogManagerEntry -Current "## 0.42.0 (2026-09-20)`n`nOnly prose.`n" -ExpectedVersion '0.42.0'
Assert-True (-not $noBullets.Valid) 'An entry with no scoped bullet, which gets no update badge, was accepted.'

$otherScope = Test-ChangelogManagerEntry -Current ($readable -replace '\* a third', '* **Instagram:** a third') `
    -ExpectedVersion '0.42.0'
Assert-True (-not $otherScope.Valid) 'A bullet scoped to another app was accepted.'

# A development-only change is written "* **Tooling:** ...", and a release made from the entries
# under Unreleased carries them. Manager shows a line only to the app it's scoped to, so it shows
# these to nobody, which is what they're for: allowed, and not counted as Facebook changes. The
# release check refused every one, so a release had to drop them or relabel them for users.
$withTooling = Test-ChangelogManagerEntry -Current ($readable -replace '(\* a third\.)',
    "`$1`n* **Tooling:** a development-only change.") -ExpectedVersion '0.42.0'
Assert-True ($withTooling.Valid -and $withTooling.Bullets -eq 3) `
    "A Tooling bullet in the released entry was refused or counted: $($withTooling.Reason), $($withTooling.Bullets) bullets"
$toolingOnly = Test-ChangelogManagerEntry -Current "## 0.42.0 (2026-09-20)`n`n* **Tooling:** only this.`n" -ExpectedVersion '0.42.0'
Assert-True (-not $toolingOnly.Valid -and $toolingOnly.Reason -like '*no Facebook change bullet*') `
    "An entry with Tooling bullets alone, which gets no update badge, was not refused for that: $($toolingOnly.Reason)"
$wrappedTooling = Test-ChangelogManagerEntry -Current ($readable -replace '(\* a third\.)',
    "`$1`n* **Tooling:** a development-only`n  change.") -ExpectedVersion '0.42.0'
Assert-True ($wrappedTooling.Reason -like '*continues the bullet*') `
    "A wrapped Tooling bullet was not held to one line like the others: $($wrappedTooling.Reason)"

Assert-True (-not (Test-ChangelogManagerEntry -Current $readable -ExpectedVersion '0.43.0').Valid) `
    'An entry for a version the CHANGELOG does not name was accepted.'

# The control. The real file, held to the real version, must pass.
$realEntry = Test-ChangelogManagerEntry -Current $realChangelog -ExpectedVersion $realVersion
Assert-True $realEntry.Valid "Morphe Manager could not read this repository's own $realVersion entry: $($realEntry.Reason)"

Write-Host '[scripts] changelog Manager contracts passed'

# --- Resolve-D8 ------------------------------------------------------------------------------
#
# The build-tools directory is chosen by version number. Sorted as text, 9.0.0 wins over 37.0.0
# and 100.0.0 loses to it; the installed set happens to be 34 through 37, which is why nobody
# had seen it pick wrong.

. (Join-Path $PSScriptRoot 'injected-register-contracts.ps1')
$sdkRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("hushfacebook-sdk-" + [guid]::NewGuid().ToString('N'))
try {
    $checkout = Join-Path $sdkRoot 'checkout'
    $sdk = Join-Path $sdkRoot 'sdk'
    foreach ($version in @('9.0.0', '37.0.0', '100.0.0', 'not-a-version')) {
        $tools = Join-Path (Join-Path $sdk 'build-tools') $version
        New-Item -ItemType Directory -Path $tools -Force | Out-Null
        Set-Content -LiteralPath (Join-Path $tools 'd8.bat') -Value '@echo off' -Encoding ASCII
    }
    # 38.0.0 is there but carries no d8, so it must not be picked over 37.0.0.
    New-Item -ItemType Directory -Path (Join-Path (Join-Path $sdk 'build-tools') '38.0.0') -Force | Out-Null
    New-Item -ItemType Directory -Path $checkout -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $checkout 'local.properties') -Encoding ASCII `
        -Value ('sdk.dir=' + ($sdk -replace '\\', '\\'))

    $chosen = Resolve-D8 -Root $checkout
    Assert-True ($chosen -like '*100.0.0*d8.bat') "Resolve-D8 chose $chosen, not the newest build-tools by version."

    Remove-Item -LiteralPath (Join-Path (Join-Path $sdk 'build-tools') '100.0.0') -Recurse -Force
    $chosen = Resolve-D8 -Root $checkout
    Assert-True ($chosen -like '*37.0.0*d8.bat') "Resolve-D8 chose $chosen over 37.0.0; 9.0.0 sorts above it as text."

    # Android Studio escapes the drive's colon as well as each backslash.
    Set-Content -LiteralPath (Join-Path $checkout 'local.properties') -Encoding ASCII `
        -Value ('sdk.dir=' + (($sdk -replace '\\', '\\') -replace ':', '\:'))
    $chosen = Resolve-D8 -Root $checkout
    Assert-True ($chosen -like '*37.0.0*d8.bat') "Resolve-D8 chose $chosen from Android Studio's escaped sdk.dir."
    # Resolve-Aapt2 reads the same line, and takes the newest build-tools whatever it carries.
    Set-Content -LiteralPath (Join-Path (Join-Path (Join-Path $sdk 'build-tools') '38.0.0') 'aapt2.exe') -Value '' -Encoding ASCII
    $keptAapt2 = $env:HUSHFACEBOOK_AAPT2
    try {
        $env:HUSHFACEBOOK_AAPT2 = $null
        $chosen = Resolve-Aapt2 -Root $checkout
        Assert-True ($chosen -like '*38.0.0*aapt2.exe') "Resolve-Aapt2 chose $chosen from Android Studio's escaped sdk.dir."
    } finally {
        $env:HUSHFACEBOOK_AAPT2 = $keptAapt2
    }

    $explicit = Join-Path (Join-Path (Join-Path $sdk 'build-tools') '9.0.0') 'd8.bat'
    Assert-True ((Resolve-D8 -Explicit $explicit -Root $checkout) -eq $explicit) `
        'An explicit d8 path was not honoured.'
    Assert-Throws { Resolve-D8 -Root (Join-Path $sdkRoot 'nowhere') } '*local.properties*' `
        'A checkout with no local.properties resolved a d8 from somewhere.'
} finally {
    Remove-Item -LiteralPath $sdkRoot -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host '[scripts] d8 resolution contracts passed'

# --- common.ps1 ------------------------------------------------------------------------------
#
# The helpers four release scripts used to carry copies of. They had already drifted: the
# cleanup helper recursed unconditionally in one script and only on request in another, and two
# of the four version reads were missing -LiteralPath, which turns a repository path holding a
# bracket into a wildcard that matches nothing.

$commonRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("hushfacebook-common-" + [guid]::NewGuid().ToString('N'))
$savedJar = $env:HUSHFACEBOOK_DESKTOP_JAR
$savedWork = $env:HUSHFACEBOOK_WORKDIR
try {
    New-Item -ItemType Directory -Path $commonRoot -Force | Out-Null
    $work = Join-Path $commonRoot 'work'
    New-Item -ItemType Directory -Path $work -Force | Out-Null

    # The path guard, which is what keeps a generated name from reaching outside the directory
    # the caller owns.
    $inside = Resolve-WithinRoot -Path (Join-Path $work 'run/output.apk') -Root $work
    Assert-True ($inside -like "$work*") 'A path inside the work directory was refused.'
    Assert-Throws { Resolve-WithinRoot -Path (Join-Path $commonRoot 'elsewhere.apk') -Root $work } `
        '*outside the work directory*' 'A path outside the work directory was accepted.'
    Assert-Throws { Resolve-WithinRoot -Path (Join-Path $work '..\escape.apk') -Root $work } `
        '*outside the work directory*' 'A path that climbs out with .. was accepted.'
    # The prefix trap: a sibling directory whose name starts with the work directory's name.
    Assert-Throws { Resolve-WithinRoot -Path ($work + '-other\file.apk') -Root $work } `
        '*outside the work directory*' 'A sibling sharing the name prefix was accepted as inside.'

    # Cleanup, recursive by default, and refusing anything outside the work directory.
    $tree = Join-Path $work 'run/deep'
    New-Item -ItemType Directory -Path $tree -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $tree 'leaf.txt') -Value 'x' -Encoding ASCII
    Remove-GeneratedPath -Path (Join-Path $work 'run') -Root $work
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $work 'run'))) `
        'A generated directory tree was left behind by the default cleanup.'

    $single = Join-Path $work 'one.txt'
    Set-Content -LiteralPath $single -Value 'x' -Encoding ASCII
    Remove-GeneratedPath -Path $single -Root $work -NoRecurse
    Assert-True (-not (Test-Path -LiteralPath $single)) 'A single generated file was not removed.'

    $outside = Join-Path $commonRoot 'keep.txt'
    Set-Content -LiteralPath $outside -Value 'x' -Encoding ASCII
    Remove-GeneratedPath -Path $outside -Root $work -WarningAction SilentlyContinue
    Assert-True (Test-Path -LiteralPath $outside) 'Cleanup deleted a path outside the work directory.'
    # A path that is simply not there is nothing to do, not a failure.
    Remove-GeneratedPath -Path (Join-Path $work 'never-existed') -Root $work

    # A link in an ancestor must not turn a generated path into a path outside this run.
    $outsideTree = Join-Path $commonRoot 'outside-tree'
    $outsideRun = Join-Path $outsideTree 'fixture-apply'
    New-Item -ItemType Directory -Path $outsideRun -Force | Out-Null
    $keepThroughLink = Join-Path $outsideRun 'keep.txt'
    Set-Content -LiteralPath $keepThroughLink -Value 'keep' -Encoding ASCII
    $linkedBuild = Join-Path $work 'build'
    New-Item -ItemType Junction -Path $linkedBuild -Target $outsideTree | Out-Null
    try {
        Remove-GeneratedPath -Path (Join-Path $linkedBuild 'fixture-apply') -Root $work -WarningAction SilentlyContinue
        Assert-True (Test-Path -LiteralPath $keepThroughLink) 'Generated cleanup followed a linked ancestor outside its root.'
    } finally { Remove-TreeWithoutFollowingLinks -Path $linkedBuild }

    # The version read. -LiteralPath is the difference that had already drifted, so the fixture
    # directory carries the bracket that makes a wildcard read find nothing.
    $bracketRoot = Join-Path $commonRoot 'repo [1]'
    New-Item -ItemType Directory -Path $bracketRoot -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $bracketRoot 'gradle.properties') -Encoding ASCII -Value @(
        'org.gradle.caching = true', 'version = 9.9.9', 'android.useAndroidX = true')
    Assert-True ((Get-BundleVersion -Root $bracketRoot) -eq '9.9.9') `
        'The version read failed on a path containing a bracket.'
    Assert-Throws { Get-BundleVersion -Root (Join-Path $commonRoot 'no-such-repo') } `
        '*no gradle.properties*' 'A missing gradle.properties was not reported.'
    Set-Content -LiteralPath (Join-Path $bracketRoot 'gradle.properties') -Value 'name = x' -Encoding ASCII
    Assert-Throws { Get-BundleVersion -Root $bracketRoot } '*names no version*' `
        'A gradle.properties with no version was accepted.'
    Set-Content -LiteralPath (Join-Path $bracketRoot 'gradle.properties') -Value 'version =' -Encoding ASCII
    Assert-Throws { Get-BundleVersion -Root $bracketRoot } '*empty version*' `
        'A gradle.properties with an empty version was accepted.'

    # The desktop CLI lookup, which was two functions with different search orders. The jar
    # ships under its version, so the newest by write time is taken: sorting names as text puts
    # 1.9.0 above 1.15.0.
    $env:HUSHFACEBOOK_DESKTOP_JAR = $null
    $env:HUSHFACEBOOK_WORKDIR = $null
    $tools = Join-Path $commonRoot 'repo/build/morphe-tools'
    New-Item -ItemType Directory -Path $tools -Force | Out-Null
    $older = Join-Path $tools 'morphe-desktop-1.9.0-all.jar'
    $newer = Join-Path $tools 'morphe-desktop-1.15.0-all.jar'
    Set-Content -LiteralPath $older -Value 'old' -Encoding ASCII
    Set-Content -LiteralPath $newer -Value 'new' -Encoding ASCII
    (Get-Item -LiteralPath $older).LastWriteTime = (Get-Date).AddDays(-2)
    (Get-Item -LiteralPath $newer).LastWriteTime = (Get-Date)
    $repoRoot = Join-Path $commonRoot 'repo'
    Assert-True ((Resolve-DesktopCli -Root $repoRoot) -eq $newer) `
        'The desktop CLI lookup did not take the newest jar by write time.'

    Assert-True ($null -eq (Resolve-DesktopCli -Root (Join-Path $commonRoot 'empty'))) `
        'The lookup invented a jar where there is none.'
    Assert-Throws { Resolve-DesktopCli -Root (Join-Path $commonRoot 'empty') -Required } `
        '*No Morphe desktop CLI*' 'A required lookup with nothing to find did not say so.'
    Assert-Throws { Resolve-DesktopCli -Explicit (Join-Path $commonRoot 'absent.jar') -Root $repoRoot } `
        '*at the path given*' 'A named jar that is not there was quietly replaced by a search.'
    Assert-True ((Resolve-DesktopCli -Explicit $older -Root $repoRoot) -eq $older) `
        'An explicitly named jar was not honoured.'

    $env:HUSHFACEBOOK_DESKTOP_JAR = $newer
    Assert-True ((Resolve-DesktopCli -Root $repoRoot) -eq $newer) `
        'HUSHFACEBOOK_DESKTOP_JAR was not read.'

    # Split bundles. Facebook ships as an .apkm, and aapt2, apksigner and the resource check each
    # read one APK: the base, whose manifest and resource table describe the app. A split is
    # often the larger file, so the base is found by name first and by size only when no entry
    # carries its name, the way an .xapk names it after the package.
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    function New-TestBundleArchive {
        param([string]$Path, [System.Collections.Specialized.OrderedDictionary]$Entries)
        $archive = [System.IO.Compression.ZipFile]::Open($Path, [System.IO.Compression.ZipArchiveMode]::Create)
        try {
            foreach ($name in $Entries.Keys) {
                $writer = New-Object System.IO.StreamWriter($archive.CreateEntry($name).Open())
                try { $writer.Write($Entries[$name]) } finally { $writer.Dispose() }
            }
        } finally { $archive.Dispose() }
    }
    function Test-SamePath([string]$A, [string]$B) {
        return [System.IO.Path]::GetFullPath($A).Equals([System.IO.Path]::GetFullPath($B),
            [System.StringComparison]::OrdinalIgnoreCase)
    }
    $plainApk = Join-Path $commonRoot 'plain.apk'
    Set-Content -LiteralPath $plainApk -Value 'one apk' -Encoding ASCII
    Assert-True (Test-SamePath (Get-BaseApk -Apk $plainApk -Destination (Join-Path $commonRoot 'never.apk')) $plainApk) `
        'A plain APK was copied or replaced instead of being read as it is.'

    $apkm = Join-Path $commonRoot 'facebook.apkm'
    $apkmEntries = [ordered]@{
        'info.json' = '{"versioncode":"475019344"}'
        'base.apk' = 'base'
        'split_config.arm64_v8a.apk' = ('native code ' * 64)
    }
    New-TestBundleArchive -Path $apkm -Entries $apkmEntries
    $baseOut = Join-Path $commonRoot 'out/base.apk'
    Assert-True ((Test-SamePath (Get-BaseApk -Apk $apkm -Destination $baseOut) $baseOut) -and
        (Get-Content -LiteralPath $baseOut -Raw) -eq 'base') `
        'The base APK was not the one taken out of an .apkm with a larger split beside it.'

    # Relative paths, the way the recipe in the notes passes a fixture, from a session whose
    # process directory is somewhere else. PowerShell reads them against its own location and
    # .NET against the process's, so an unresolved path named a file in the wrong folder.
    $savedProcessDirectory = [Environment]::CurrentDirectory
    Push-Location -LiteralPath $commonRoot
    try {
        [Environment]::CurrentDirectory = [System.IO.Path]::GetTempPath()
        $relativeOut = Get-BaseApk -Apk 'facebook.apkm' -Destination 'out\relative-base.apk'
        Assert-True ((Test-SamePath $relativeOut (Join-Path $commonRoot 'out\relative-base.apk')) -and
            (Get-Content -LiteralPath (Join-Path $commonRoot 'out\relative-base.apk') -Raw) -eq 'base') `
            'A relative bundle path was read against the process directory instead of the current location.'
    } finally {
        [Environment]::CurrentDirectory = $savedProcessDirectory
        Pop-Location
    }

    $xapk = Join-Path $commonRoot 'facebook.xapk'
    $xapkEntries = [ordered]@{
        'config.en.apk' = 'language'
        'com.example.app.apk' = ('app ' * 64)
    }
    New-TestBundleArchive -Path $xapk -Entries $xapkEntries
    $xapkOut = Join-Path $commonRoot 'out/xapk-base.apk'
    Assert-True ((Test-SamePath (Get-BaseApk -Apk $xapk -Destination $xapkOut) $xapkOut) -and
        (Get-Content -LiteralPath $xapkOut -Raw).StartsWith('app ')) `
        'A bundle with no base.apk did not fall back to its largest APK.'

    $noApk = Join-Path $commonRoot 'empty.apkm'
    New-TestBundleArchive -Path $noApk -Entries ([ordered]@{ 'info.json' = '{}' })
    Assert-Throws { Get-BaseApk -Apk $noApk -Destination (Join-Path $commonRoot 'out/none.apk') } `
        '*holds no APK*' 'A bundle with no APK in it handed back something to read.'

    # The fetch the release facts make of the indexed bundle and the add-source page. A real
    # request first: nothing listens on port 1, so it is refused before it leaves this machine.
    # Then the two answers a stand-in gives, a 404 the way both shells raise it and a 200.
    Assert-Throws {
        Assert-UrlReachable -Uri ([Uri]'http://127.0.0.1:1/patches.mpp') -Description 'test bundle URL' -FailureHint 'hint'
    } '*Could not reach the test bundle URL http://127.0.0.1:1/patches.mpp*' 'An address nothing answers passed the reachability check.'
    & {
        function Invoke-WebRequest { throw (New-NotFoundAnswer) }
        Assert-Throws {
            Assert-UrlReachable -Uri ([Uri]'https://github.com/example/none.mpp') -Description 'test bundle URL' `
                -FailureHint 'Publish it first.'
        } '*test bundle URL https://github.com/example/none.mpp answered HTTP 404. Publish it first.' `
            'An address answering 404 passed the reachability check.'
    }
    & {
        function Invoke-WebRequest { [pscustomobject]@{ StatusCode = 200 } }
        Assert-UrlReachable -Uri ([Uri]'https://github.com/example/patches.mpp') -Description 'test bundle URL' `
            -FailureHint 'hint' 6> $null
    }
} finally {
    $env:HUSHFACEBOOK_DESKTOP_JAR = $savedJar
    $env:HUSHFACEBOOK_WORKDIR = $savedWork
    Remove-Item -LiteralPath $commonRoot -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host '[scripts] shared helper contracts passed'

# --- split bundle callers ----------------------------------------------------------------------
#
# The morphe CLI merges an .apkm's splits into one APK before it patches, and since 1.17.0 deletes
# that merge when it's done. The patched manifest and resource table have to be compared with the
# merge, not with the base APK: the merge itself moves the manifest, and base.apk lacks every
# resource the splits carry. So the receipt builder and verify-all-patches.ps1 merge first
# (Get-MergedApk) and hand the CLI the merge, and both are run against exactly that in the release
# root section below, a merge that yields nothing among the cases. Every caller that reads the
# manifest facts out of a bundle goes through Get-BaseApk, and no caller looks for a merge the CLI
# left behind: there is none to find.
foreach ($name in @('build-release-receipt.ps1', 'verify-all-patches.ps1', 'verify-injected-registers.ps1')) {
    Assert-True ((Get-Content -LiteralPath (Join-Path $PSScriptRoot $name) -Raw) -match 'Get-BaseApk -Apk') `
        "$name reads a bundle without taking its base APK out first."
}
foreach ($name in @('build-release-receipt.ps1', 'verify-all-patches.ps1')) {
    $text = Get-Content -LiteralPath (Join-Path $PSScriptRoot $name) -Raw
    Assert-True ($text -match '(?m)^[^#\r\n]*\$patchInput = Get-MergedApk -Apk ' -and $text -notmatch '\*-merged\.apk') `
        "$name does not merge a bundle before it patches, or still looks for the merge the CLI deletes."
}

Write-Host '[scripts] split bundle contracts passed'

# --- the machine's build queue -------------------------------------------------------------------
#
# Gradle reached the machine's build queue through the governor, while the desktop CLI runs, the
# merges and the DexDiff and fingerprint runs started whenever they were asked for, beside two
# builds, and the queue's status never showed them. Invoke-HeavyJob in common.ps1 runs each one in
# a slot when BUILD_QUEUE_SCRIPT names the queue's script, the stand-in at the top of this suite
# here, and straight away when it doesn't. Either way the block's exit code comes back, the block
# reads the caller's variables, a throw in it stops the caller, and the queue script's parameters
# don't land on the caller's names. The release root below runs the scripts through it.
$null = @(Read-QueueLog)
$heldOutput = @{ Lines = @() }
$jobInput = 'from the caller'
Invoke-HeavyJob -Label 'contract job' -ScriptBlock {
    $heldOutput.Lines = @("read $jobInput")
    & cmd.exe /d /c exit 3
}
Assert-True ($LASTEXITCODE -eq 3) "Invoke-HeavyJob gave back exit $LASTEXITCODE for a queued job that ended with 3."
Assert-True (($heldOutput.Lines -join '') -eq 'read from the caller') `
    "A queued job didn't read the caller's variables or keep what it put in the caller's table: $($heldOutput.Lines)"
$seen = @(Read-QueueLog)
Assert-True (($seen -join '|') -eq 'hushfacebook contract job') "The queue saw $($seen -join ', ') for one job labeled contract job."
function Test-QueueKeepsCallerNames {
    $Label = 'mine'; $Priority = 'mine'; $Run = 'mine'; $Status = 'mine'
    Invoke-HeavyJob -Label 'names' -ScriptBlock { }
    return "$Label/$Priority/$Run/$Status"
}
Assert-True ((Test-QueueKeepsCallerNames) -eq 'mine/mine/mine/mine') `
    "Loading the queue script reset the caller's own Label, Priority, Run or Status."
# A job started from inside one is left to the queue, which runs it at once (the stand-in marks it).
Invoke-HeavyJob -Label 'outer' -ScriptBlock { Invoke-HeavyJob -Label 'inner' -ScriptBlock { } }
Assert-Throws { Invoke-HeavyJob -Label 'failing' -ScriptBlock { throw 'the job broke' } } '*the job broke*' `
    'A queued job that threw did not stop its caller.'
$seen = @(Read-QueueLog)
Assert-True (($seen -join '|') -eq 'hushfacebook names|hushfacebook outer|hushfacebook inner (inside a slot)|hushfacebook failing') `
    "The queue saw $($seen -join ', ') for the names, nested and failing jobs."
# With no queue named, or one that isn't there, the job runs at once, and the second warns.
$savedQueueScript = $env:BUILD_QUEUE_SCRIPT
try {
    Remove-Item -LiteralPath Env:\BUILD_QUEUE_SCRIPT
    $heldOutput.Lines = @()
    Invoke-HeavyJob -Label 'direct' -ScriptBlock { $heldOutput.Lines = @("read $jobInput"); & cmd.exe /d /c exit 4 }
    Assert-True ($LASTEXITCODE -eq 4 -and ($heldOutput.Lines -join '') -eq 'read from the caller') `
        "With no queue named, the job didn't run here and give back its exit code (exit $LASTEXITCODE)."
    Assert-Throws { Invoke-HeavyJob -Label 'failing' -ScriptBlock { throw 'the job broke' } } '*the job broke*' `
        'A job that threw outside the queue did not stop its caller.'
    $env:BUILD_QUEUE_SCRIPT = Join-Path $queueStandIn 'no-such-queue.ps1'
    $warned = @(Invoke-HeavyJob -Label 'missing' -ScriptBlock { & cmd.exe /d /c exit 5 } 3>&1 | ForEach-Object { "$_" })
    Assert-True ($LASTEXITCODE -eq 5 -and ($warned -join ' ') -like '*no-such-queue.ps1*not there*') `
        "A queue script that isn't there didn't warn and run the job here: exit $LASTEXITCODE, $($warned -join ' ')"
} finally {
    $env:BUILD_QUEUE_SCRIPT = $savedQueueScript
}
$seen = @(Read-QueueLog)
Assert-True ($seen.Count -eq 0) "Jobs went through the queue with none named, or one that isn't there: $($seen -join ', ')"

# And every heavy run the scripts make sits in such a job, where the script reaches it
# (script-wiring.ps1): the desktop CLI, the merge, ResourceTableCheck, DexDiff and
# FingerprintCandidates. A block handed to anything else, or stored, runs outside the queue.
function Get-UnqueuedHeavyRuns {
    param([string]$Path)
    $live = @(Get-LiveCommands (Get-ScriptAst $Path))
    $heavy = @($live | Where-Object {
        ($_.InvocationOperator -eq [System.Management.Automation.Language.TokenKind]::Ampersand -and
            ($_.CommandElements[0].Extent.Text -match '^\$(script:)?\w*java\w*$' -or
                $_.CommandElements[0].Extent.Text -match 'gradlew')) -or $_.GetCommandName() -eq 'Get-MergedApk' })
    if ($heavy.Count -eq 0) { return 'no heavy run at all' }
    foreach ($call in $heavy) {
        $queued = $false
        for ($up = $call.Parent; $null -ne $up -and -not $queued; $up = $up.Parent) {
            if ($up -isnot [System.Management.Automation.Language.ScriptBlockExpressionAst] -or -not (Test-RunsScriptBlock $up)) { continue }
            $holder = $up.Parent
            if ($holder -is [System.Management.Automation.Language.CommandParameterAst]) { $holder = $holder.Parent }
            $queued = $holder -is [System.Management.Automation.Language.CommandAst] -and $holder.GetCommandName() -eq 'Invoke-HeavyJob'
        }
        if (-not $queued) { ($call.Extent.Text -split "`n")[0].Trim() }
    }
}
# The release script's Gradle run too, when no build wrapper takes it to the queue.
$heavyScripts = @('verify-all-patches.ps1', 'build-release-receipt.ps1', 'patch-for-device.ps1',
    'validate-release-facts.ps1', 'verify-injected-registers.ps1', 'fingerprint-candidates.ps1', 'release/release.ps1')
foreach ($name in $heavyScripts) {
    $unqueued = @(Get-UnqueuedHeavyRuns (Join-Path $PSScriptRoot $name))
    Assert-True ($unqueued.Count -eq 0) "$name runs these outside the build queue (Invoke-HeavyJob): $($unqueued -join '; ')"
}
$queueCopy = Join-Path $queueStandIn 'verify-all-patches.ps1'
foreach ($unqueuedShape in @(
        @{ Name = 'handed to another command'; From = 'Invoke-HeavyJob -Label'; To = 'Register-HeavyJob -Label' },
        @{ Name = 'run with & instead'; From = 'Invoke-HeavyJob -Label "verify $(Split-Path -Leaf $Apk)" -ScriptBlock {'; To = '& {' })) {
    $text = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot 'verify-all-patches.ps1'))
    Assert-True ($text.Contains($unqueuedShape.From)) "verify-all-patches.ps1 no longer has the job the '$($unqueuedShape.Name)' case edits."
    [System.IO.File]::WriteAllText($queueCopy, $text.Replace($unqueuedShape.From, $unqueuedShape.To))
    Assert-True (@(Get-UnqueuedHeavyRuns $queueCopy).Count -gt 0) `
        "The queue check passed verify-all-patches.ps1 with its heavy runs $($unqueuedShape.Name)."
}
Remove-Item -LiteralPath $queueCopy -Force
# The pre-push hook imports the variable from the user's registry like its own, since a hook can
# start without it, and the fixture applies it runs would then skip the queue.
$importList = (Get-ScriptAst (Join-Path $PSScriptRoot 'pre-push.ps1')).Find({ param($node)
    $node -is [System.Management.Automation.Language.ForEachStatementAst] -and
    $node.Variable.VariablePath.UserPath -eq 'envName' }, $true)
Assert-True ($null -ne $importList -and $importList.Condition.Extent.Text -match "'BUILD_QUEUE_SCRIPT'") `
    'pre-push.ps1 does not import BUILD_QUEUE_SCRIPT from the user environment.'

Write-Host '[scripts] build queue contracts passed'

# --- relative paths ------------------------------------------------------------------------------
#
# .NET reads a relative path against the process directory, which Set-Location doesn't move, while
# Test-Path and native commands use PowerShell's location. A path a script had just found could then
# open as a file somewhere else, or not at all: a relative -DesktopJar reached DexDiff as a jar that
# wasn't there. Every reader that hands a caller's path to .NET is asked here from a location the
# process directory isn't. The scripts that take such paths are run the same way in the release
# root below.

$relativeRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("hushfacebook-relative-" + [guid]::NewGuid().ToString('N'))
$savedProcessDirectory = [Environment]::CurrentDirectory
$savedDesktopJar = $env:HUSHFACEBOOK_DESKTOP_JAR
try {
    $here = Join-Path $relativeRoot 'here'
    $elsewhere = Join-Path $relativeRoot 'elsewhere'
    New-Item -ItemType Directory -Path (Join-Path $here 'tools'), $elsewhere -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $here 'tools\morphe-desktop.jar') -Value 'jar' -Encoding ASCII
    Set-Content -LiteralPath (Join-Path $here 'tools\d8.bat') -Value '@echo off' -Encoding ASCII
    New-TestBundleArchive -Path (Join-Path $here 'patches.mpp') -Entries ([ordered]@{
        'META-INF/MANIFEST.MF' = "Manifest-Version: 1.0`nVersion: 9.9.9`nTimestamp: 1000`nPatcher-Version: 1.14.1`n`n" })
    New-TestBundleArchive -Path (Join-Path $here 'app.apk') -Entries ([ordered]@{
        'AndroidManifest.xml' = 'manifest'; 'classes.dex' = "dex`n035" })
    New-TestBundleArchive -Path (Join-Path $here 'app.apkm') -Entries ([ordered]@{ 'base.apk' = 'base' })

    Push-Location -LiteralPath $here
    try {
        [Environment]::CurrentDirectory = $elsewhere
        $jar = Resolve-DesktopCli -Explicit 'tools\morphe-desktop.jar'
        Assert-True (Test-SamePath $jar (Join-Path $here 'tools\morphe-desktop.jar')) "A relative -DesktopJar came back as $jar."
        $env:HUSHFACEBOOK_DESKTOP_JAR = 'tools\morphe-desktop.jar'
        $jar = Resolve-DesktopCli
        Assert-True (Test-SamePath $jar (Join-Path $here 'tools\morphe-desktop.jar')) "A relative HUSHFACEBOOK_DESKTOP_JAR came back as $jar."
        $d8 = Resolve-D8 -Explicit 'tools\d8.bat' -Root $here
        Assert-True (Test-SamePath $d8 (Join-Path $here 'tools\d8.bat')) "A relative -D8 came back as $d8."
        $facts = Get-BundleManifestFacts -BundlePath 'patches.mpp'
        Assert-True ($facts.patcherVersion -eq '1.14.1') "A relative bundle was read as $($facts.patcherVersion)."
        Assert-True (Test-ApkFile -Path 'app.apk') 'A relative APK was not read as one.'
        $base = Get-BaseApk -Apk 'app.apkm' -Destination 'out\base.apk'
        Assert-True ((Test-SamePath $base (Join-Path $here 'out\base.apk')) -and (Test-Path -LiteralPath $base)) `
            "A relative bundle's base APK went to $base."
        Assert-True (@(Get-ChildItem -LiteralPath $elsewhere -Recurse -Force).Count -eq 0) `
            'A relative path was written in the process directory instead.'
    } finally {
        [Environment]::CurrentDirectory = $savedProcessDirectory
        Pop-Location
    }
} finally {
    [Environment]::CurrentDirectory = $savedProcessDirectory
    $env:HUSHFACEBOOK_DESKTOP_JAR = $savedDesktopJar
    Remove-Item -LiteralPath $relativeRoot -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host '[scripts] relative path contracts passed'

# --- a release root ------------------------------------------------------------------------------
#
# The release files of this checkout, copied into a fixture repository and committed, the way the
# tree looks at a release commit. The release check reads the commit a receipt names out of git,
# so a copied tree that is not a repository never reaches the receipt, and the facts fixture above
# holds none: every case there took the "no receipt" exit, and the receipt half was covered only
# by a pattern over the script's text, which also matched a mutation that held the receipt to the
# newest build alone. The receipt builder runs here too, on stand-ins for the tools it starts,
# since patterns over its text passed with the merged APK ignored and with forced runs decided
# against the newest build, and so does the device build, on the same stand-ins.

$releaseRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("hushfacebook-release-" + [guid]::NewGuid().ToString('N'))
try {
    $releaseRepo = Join-Path $releaseRoot 'repo'
    # The source ledger and the two files its rules hold an adopted source to go in as well: a
    # release is held to the census, and .gitignore has to let the ledger be committed.
    $releaseFiles = @('patches-list.json', 'patches-bundle.json', 'gradle.properties', 'README.md', 'CHANGELOG.md',
        'gradle/libs.versions.toml', 'gradle/tooling-scopes.txt', '.github/ISSUE_TEMPLATE/bug_report.yml', '.gitignore',
        'sources/facebook-sources.json', 'NOTICE', 'provenance.json', 'CONTRIBUTING.md')
    foreach ($relative in $releaseFiles) {
        $destination = Join-Path $releaseRepo $relative
        New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
        Copy-Item -LiteralPath (Join-Path $Root $relative) -Destination $destination
    }
    # The ledger, dated today, listed on every index and with every adopted source proved on the
    # copied catalog's declared builds, so the census a release is held to passes and each
    # published-asset case below is refused for its own fact. The checked-in ledger's date moves with
    # every audit, its listings wait on submissions and its fixture evidence waits on the release
    # after a retarget, and none of that is what those cases are about. The census cases further down
    # move one fact at a time.
    $releaseLedgerPath = Join-Path $releaseRepo 'sources/facebook-sources.json'
    $releaseLedgerSource = [System.IO.File]::ReadAllText($releaseLedgerPath)
    $ledgerTarget = Get-PatchTarget -PatchList (Get-Content -LiteralPath (Join-Path $releaseRepo 'patches-list.json') -Raw |
        ConvertFrom-Json)
    $ledgerFixtures = [string[]]@(foreach ($version in $ledgerTarget.PackageVersions) {
        foreach ($code in $ledgerTarget.PackageVersionCodes[$version]) { "$version ($code)" } })
    function Save-ReleaseLedger([int]$AgeDays = 0, [switch]$Pending) {
        $document = $releaseLedgerSource | ConvertFrom-Json
        $checked = [datetime]::UtcNow.Date.AddDays(-$AgeDays).ToString('yyyy-MM-dd')
        $document.census.checkedAt = $checked
        foreach ($entry in @($document.entries)) {
            $adopted = if ($entry) { $entry.PSObject.Properties['adopted'] }
            if ($adopted -and $adopted.Value) { $adopted.Value.fixtures.builds = $ledgerFixtures }
        }
        foreach ($index in $document.indexes) {
            $index.hushfacebook = if ($Pending) { [pscustomobject]@{ status = 'not-listed'; checked = $checked } } else {
                [pscustomobject]@{ status = 'listed'; url = 'https://example.com/listing'; checked = $checked } }
        }
        foreach ($record in @(@($document.entries) + @($document.outOfScope))) { if ($record) { $record.lastChecked = $checked } }
        [System.IO.File]::WriteAllText($releaseLedgerPath, ($document | ConvertTo-Json -Depth 20),
            (New-Object System.Text.UTF8Encoding($false)))
    }
    Save-ReleaseLedger
    # The copied source can be one commit ahead of the published index while a release is being
    # prepared. Here the copied tree is the release, so the index is written up to the catalog
    # before any strict index-push case runs.
    $releaseVersionForIndex = Get-BundleVersion -Root $releaseRepo
    $releaseCatalogForIndex = Get-Content -LiteralPath (Join-Path $releaseRepo 'patches-list.json') -Raw |
        ConvertFrom-Json
    $releaseTargetForIndex = Get-PatchTarget -PatchList $releaseCatalogForIndex
    $releasePatchCountForIndex = @($releaseCatalogForIndex.patches).Count
    $releaseFloor = (Read-CatalogToolchain -Source 'the copied catalog' `
        -Text (Get-Content -LiteralPath (Join-Path $releaseRepo 'gradle/libs.versions.toml') -Raw)).ManagerFloor
    $releaseIndexPath = Join-Path $releaseRepo 'patches-bundle.json'
    $releaseIndexText = Get-Content -LiteralPath $releaseIndexPath -Raw
    $releaseIndex = $releaseIndexText | ConvertFrom-Json
    $copiedIndexVersion = [string]$releaseIndex.version
    if ($copiedIndexVersion -ne $releaseVersionForIndex) {
        $releaseIndexText = $releaseIndexText -replace
            ('(?<![\d.])' + [regex]::Escape($copiedIndexVersion) + '(?!\d)'), $releaseVersionForIndex
    }
    $releaseIndexText = $releaseIndexText -replace
        '(?<!\d)\d+ patches\b', "$releasePatchCountForIndex patches"
    $describedBuild = [regex]::Match([string]$releaseIndex.description, 'Facebook\s+(\d+(?:\.\d+)+)')
    if ($describedBuild.Success -and $describedBuild.Groups[1].Value -ne $releaseTargetForIndex.PackageVersion) {
        $releaseIndexText = $releaseIndexText -replace
            ('(?<![\d.])' + [regex]::Escape($describedBuild.Groups[1].Value) + '(?!\d)'),
            $releaseTargetForIndex.PackageVersion
    }
    $releaseIndexText = $releaseIndexText -replace
        '(Morphe Manager )\d+(?:\.\d+)+( or newer)', "`${1}$releaseFloor`${2}"
    Set-Content -LiteralPath $releaseIndexPath -Encoding UTF8 -NoNewline -Value $releaseIndexText
    $releaseBugFormPath = Join-Path $releaseRepo '.github/ISSUE_TEMPLATE/bug_report.yml'
    $releaseBugFormText = Get-Content -LiteralPath $releaseBugFormPath -Raw
    $releaseBugFormText = $releaseBugFormText -replace
        '(placeholder:\s*Version )\S+( for Facebook)', "`${1}$releaseVersionForIndex`${2}"
    $releaseBugFormText = $releaseBugFormText -replace
        '(placeholder:\s*Version \S+ for Facebook )\d+(?:\.\d+)+',
        "`${1}$($releaseTargetForIndex.PackageVersion)"
    Set-Content -LiteralPath $releaseBugFormPath -Encoding UTF8 -NoNewline -Value $releaseBugFormText
    # And the README's version badge and the sentence naming the latest release, which the release
    # moves with the index.
    $releaseReadmePath = Join-Path $releaseRepo 'README.md'
    $releaseReadmeText = (Get-Content -LiteralPath $releaseReadmePath -Raw) -replace
        'badge/version-\d+(?:\.\d+)+-', "badge/version-$releaseVersionForIndex-" -replace
        'alt="Version \d+(?:\.\d+)+"', "alt=`"Version $releaseVersionForIndex`"" -replace
        '(latest (?:published )?release is (?:still )?\[?v)\d+(?:\.\d+)+', "`${1}$releaseVersionForIndex" -replace
        '(latest release is \[v[^\]]*\]\([^)\s]*/tag/v)\d+(?:\.\d+)+', "`${1}$releaseVersionForIndex" -replace
        '(latest release is \[v[^\]]*\]\([^)\s]*\), with )\d+( patches)', "`${1}$releasePatchCountForIndex`${2}"
    Set-Content -LiteralPath $releaseReadmePath -Encoding UTF8 -NoNewline -Value $releaseReadmeText
    # Through Invoke-FixtureGit, with the git directory proved before anything is written, for
    # the reason the toolchain fixture above gives.
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('init', '--quiet') | Out-Null
    $releaseGitDir = "$(Invoke-FixtureGit -Root $releaseRepo -Arguments @('rev-parse', '--absolute-git-dir') |
        Select-Object -First 1)".Trim()
    Assert-True ($releaseGitDir -and ([IO.Path]::GetFullPath($releaseGitDir).TrimEnd('\', '/') -ieq
            [IO.Path]::GetFullPath((Join-Path $releaseRepo '.git')).TrimEnd('\', '/'))) `
        "The release fixture resolved to $releaseGitDir, not its own repository. Refusing to write."
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('config', 'user.email', 'contracts@example.invalid') | Out-Null
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('config', 'user.name', 'Contracts') | Out-Null
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('add', '-A') | Out-Null
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('commit', '-m', 'release', '--quiet') | Out-Null
    $releaseCommitted = @(Invoke-FixtureGit -Root $releaseRepo -Arguments @('show', '--name-only', '--format=', 'HEAD') |
        Where-Object { "$_".Trim() })
    Assert-True ($releaseCommitted.Count -eq $releaseFiles.Count) `
        ("The release fixture's commit touched $($releaseCommitted.Count) files, not the $($releaseFiles.Count) " +
            'copied: ' + (($releaseCommitted | Select-Object -First 5) -join ', '))
    $releaseCommit = "$(Invoke-FixtureGit -Root $releaseRepo -Arguments @('rev-parse', 'HEAD') | Select-Object -First 1)".Trim()
    $releaseSeconds = [long]"$(Invoke-FixtureGit -Root $releaseRepo -Arguments @('log', '-1', '--format=%ct') |
        Select-Object -First 1)".Trim()

    $releaseVersionHere = Get-BundleVersion -Root $releaseRepo
    $releaseCatalog = Get-Content -LiteralPath (Join-Path $releaseRepo 'patches-list.json') -Raw | ConvertFrom-Json
    $releaseTarget = Get-PatchTarget -PatchList $releaseCatalog
    $releaseNames = @($releaseCatalog.patches | ForEach-Object { [string]$_.name })
    $releaseToolchain = Read-CatalogToolchain -Source 'the release fixture catalog' `
        -Text (Get-Content -LiteralPath (Join-Path $releaseRepo 'gradle/libs.versions.toml') -Raw)
    $releaseReceipt = Join-Path $releaseRepo "release-receipt-$releaseVersionHere.json"
    # The tag the check reads the index's floor through, named for the version the index publishes.
    $indexVersionHere = [string]($releaseIndexText | ConvertFrom-Json).version
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('tag', "v$indexVersionHere", $releaseCommit) | Out-Null

    # A receipt for this commit with a run of each build given, every patch applied and the
    # manifest changes the checked-in allowlist approves, the permission rename, written where the
    # release check looks for it. A schema 1 receipt names no SBOM, as the ones cut before it
    # existed don't.
    $approvedDelta = [ordered]@{ permissionsAdded = @(); permissionsRemoved = @()
        exportedComponentsAdded = @(); exportedComponentsRemoved = @() }
    foreach ($entry in $checkedInAllowlist) {
        $kind, $value = $entry -split ' ', 2
        switch ($kind) {
            'permission-added' { $approvedDelta.permissionsAdded += $value }
            'permission-removed' { $approvedDelta.permissionsRemoved += $value }
            'exported-added' { $approvedDelta.exportedComponentsAdded += $value }
            'exported-removed' { $approvedDelta.exportedComponentsRemoved += $value }
        }
    }
    function Save-ReleaseReceipt([string[]]$Builds, [string]$Commit = $releaseCommit, [long]$Seconds = $releaseSeconds,
            [int]$Schema = (Get-ReleaseReceiptSchemaVersion)) {
        $targets = @(for ($i = 0; $i -lt $Builds.Count; $i++) {
            # Each build at the version code the catalog pins it to, as a run of the declared build.
            # A build the catalog doesn't declare is recorded as the builder records it, forced.
            $code = @(@($releaseTarget.PackageVersionCodes[$Builds[$i]]) + @("47500000$i") | Where-Object { $_ })[0]
            $forced = -not (Test-DeclaredBuild -Target $releaseTarget -VersionName $Builds[$i] -VersionCode $code)
            [ordered]@{
                source        = [ordered]@{ file = "facebook-$($Builds[$i])-arm64-v8a.apkm"
                    package = $releaseTarget.PackageName; versionName = $Builds[$i]; versionCode = $code
                    sha256 = ([string]'ABCDEF'[$i % 6] * 64); forced = $forced }
                patches       = @($releaseNames | ForEach-Object { [ordered]@{ name = $_; applied = $true; reason = $null } })
                manifestDelta = $approvedDelta
            }
        })
        $document = [ordered]@{
            schemaVersion = $Schema
            release   = [ordered]@{ version = $releaseVersionHere; tag = "v$releaseVersionHere"; commit = $Commit
                commitTimestamp = $Seconds; patchCount = $releaseNames.Count }
            bundle    = [ordered]@{ file = "patches-$releaseVersionHere.mpp"; sizeBytes = 10; sha256 = ('E' * 64)
                timestamp = $Seconds * 1000 }
            sbom      = [ordered]@{ file = "patches-$releaseVersionHere.cdx.json"; sha256 = ('D' * 64); components = 3 }
            tooling   = [ordered]@{ file = "patches-$releaseVersionHere.tooling.json"; sha256 = ('a' * 64)
                components = 1; scopes = 2; audit = [ordered]@{ status = 'checked' } }
            toolchain = [ordered]@{ patcherVersion = $releaseToolchain.PatcherVersion; managerFloor = $releaseToolchain.ManagerFloor }
            extension = [ordered]@{ dexPayloads = @([ordered]@{ name = 'extensions/facebook.mpe'; sizeBytes = 10; sha256 = ('F' * 64) }) }
            targets   = $targets
        }
        if ($Schema -lt 2) { $document.Remove('sbom') }
        if ($Schema -lt 3) { $document.Remove('tooling') }
        Set-Content -LiteralPath $releaseReceipt -Encoding UTF8 -Value ($document | ConvertTo-Json -Depth 12)
    }
    # Every release check also holds the patch names and defaults to the published release's
    # catalog. That release is this fixture's own commit, published through a bare repository that
    # answers for origin, with its hosted receipt and checksum list frozen here before the cases
    # below edit the working receipt. The receipt's applied verdict is the opposite of the default.
    $releaseCatalogPreviousWeb = Get-Item Function:\Invoke-WebRequest -ErrorAction SilentlyContinue
    $releaseCatalogRemote = (Join-Path $releaseRoot 'published.git').Replace('\', '/')
    Invoke-FixtureGit -Root $releaseRoot -Arguments @('init', '--bare', '--quiet', $releaseCatalogRemote) | Out-Null
    $releaseCatalogUrl = [Uri]($releaseIndexText | ConvertFrom-Json).download_url
    $releaseCatalogRepository = 'https://github.com/' + ((@($releaseCatalogUrl.AbsolutePath.Trim('/') -split '/') | Select-Object -First 2) -join '/') + '.git'
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('config', "url.$releaseCatalogRemote.insteadOf", $releaseCatalogRepository) | Out-Null
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('remote', 'add', 'origin', $releaseCatalogRepository) | Out-Null
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('push', '--quiet', $releaseCatalogRemote, "refs/tags/v$indexVersionHere") | Out-Null
    $releaseCatalogReceiptPath = Join-Path $releaseRoot "catalog-release-receipt-$indexVersionHere.json"
    [IO.File]::WriteAllText($releaseCatalogReceiptPath, (@{
        release = @{ version = $indexVersionHere; commit = $releaseCommit }
        bundle = @{ file = "patches-$indexVersionHere.mpp"; sha256 = ('d' * 64) }
        patches = @(@{ name = $releaseNames[0]; applied = -not $releaseCatalog.patches[0].use })
    } | ConvertTo-Json -Depth 5), (New-Object Text.UTF8Encoding($false)))
    function Invoke-WebRequest {
        param([Uri]$Uri, $Method, $OutFile, $MaximumRedirection, $TimeoutSec, [switch]$PassThru, [switch]$UseBasicParsing)
        if (-not $UseBasicParsing) { throw "Invoke-WebRequest $Uri without -UseBasicParsing" }
        $receiptUri = $releaseCatalogUrl.AbsoluteUri -replace '[^/]+$', "release-receipt-$indexVersionHere.json"
        $checksumUri = $releaseCatalogUrl.AbsoluteUri -replace '[^/]+$', 'SHA256SUMS.txt'
        $bytes = if ($Uri.AbsoluteUri -ceq $receiptUri) { [IO.File]::ReadAllBytes($releaseCatalogReceiptPath) }
        elseif ($Uri.AbsoluteUri -ceq $checksumUri) {
            [Text.Encoding]::UTF8.GetBytes("$(Get-Sha256Hex $releaseCatalogReceiptPath)  release-receipt-$indexVersionHere.json`n$('d' * 64)  patches-$indexVersionHere.mpp`n")
        } else { throw "No local release catalog response for $Uri" }
        if ($OutFile) { [IO.File]::WriteAllBytes($OutFile, $bytes) }
        return [pscustomobject]@{ StatusCode = 200; Content = $bytes }
    }

    # The run the hook makes for a push that carries a receipt: lenient, since the index may lag
    # the source, and with no network. What it says is kept, to tell a receipt it compared from
    # one it never found.
    function Invoke-ReleaseCheck {
        $global:LASTEXITCODE = 0
        $said = @(& $factsScript -Root $releaseRepo -SkipDescriptionTestCount -AllowPublishedIndexLag -SkipUrlCheck 6>&1 |
            ForEach-Object { "$_" }) -join "`n"
        if ($LASTEXITCODE -ne 0) { throw "The release check exited $LASTEXITCODE on the release root: $said" }
        return $said
    }

    # A run of every declared build, which is what a release needs, compared rather than skipped.
    Save-ReleaseReceipt -Builds $releaseTarget.PackageVersions
    try {
        $said = Invoke-ReleaseCheck
    } catch {
        throw "The release check refused a receipt with a run of every declared build: $($_.Exception.Message)"
    }
    $proved = "the receipt proves $($releaseNames.Count) patches on $($releaseTarget.PackageVersions -join ', ') " +
        "from commit $($releaseCommit.Substring(0, 8))"
    Assert-True ($said -like "*$proved*") "The release check did not compare the receipt it was given: $said"
    Assert-True ($said -like "*the index asks for Morphe Manager $releaseFloor or newer, as tag v$indexVersionHere pins*") `
        "The release check did not hold the index's Manager floor to the release tag: $said"

    # An index still naming the floor of the release before it, which is what reusing its text
    # does. Nothing read the description's floor until 2026-09-25.
    Set-Content -LiteralPath $releaseIndexPath -Encoding UTF8 -NoNewline -Value (
        $releaseIndexText -replace '(Morphe Manager )\d+(?:\.\d+)+( or newer)', '${1}1.20.0${2}')
    try {
        Assert-Throws { Invoke-ReleaseCheck } '*Morphe Manager 1.20.0 or newer*' `
            'An index naming another Manager floor than its release tag pins was accepted.'
    } finally {
        Set-Content -LiteralPath $releaseIndexPath -Encoding UTF8 -NoNewline -Value $releaseIndexText
    }

    # A receipt missing a run of a declared build, the one a release that skipped a fixture would
    # write. Refused, naming the build it never ran. The catalog declares one build, so the
    # receipt's only run is a forced one of the build before it; with several, it runs the others.
    $unproved = @($releaseTarget.PackageVersions | Select-Object -Last 1)
    $ranInstead = @($releaseTarget.PackageVersions | Where-Object { $_ -ne $unproved[0] })
    if ($ranInstead.Count -eq 0) { $ranInstead = @('580.0.0.51.74') }
    Save-ReleaseReceipt -Builds $ranInstead
    Assert-Throws { Invoke-ReleaseCheck } ("*No target in the receipt is the declared $($releaseTarget.PackageName) " +
            "$($unproved -join ', ') patched without -f*") `
        'The release check accepted a receipt with no run of a declared build.'

    # build-release-receipt.ps1 itself, on the same root. Stand-ins take the tools' places: a JDK
    # that answers -version, plays MergeSplits.java (the merged APK, and a note beside it naming
    # the bundle it came from), and does what the desktop CLI leaves behind for each input (the
    # result report and the patched APK), and an aapt2 that prints the manifest lines written for
    # each APK. One .apkm per declared build, and a bundle stamped with the commit's time where
    # buildAndroid leaves it, carrying the classes.dex the published asset check at the end of
    # this section looks for. The merge carries a component the base APK's manifest lacks, so a
    # delta taken against the base instead of the merge records it as the patches' own, and the
    # allowlist, which approves only the permission rename, refuses the receipt. The JDK also plays ResourceTableCheck.java, keeping a
    # copy of the stock APK it was handed, and DexDiff.java, and an apksigner beside aapt2 names
    # Meta's signer, so verify-all-patches.ps1 runs on the same stand-ins.
    $tools = Join-Path $releaseRoot 'tools'
    $fixtures = Join-Path $releaseRoot 'fixtures'
    New-Item -ItemType Directory -Path $tools, $fixtures -Force | Out-Null
    $stubJava = Join-Path $tools 'java.cmd'
    $stubAapt2 = Join-Path $tools 'aapt2.cmd'
    $stubJar = Join-Path $tools 'morphe-desktop.jar'
    $javaLog = Join-Path $tools 'java.log'
    $mergeLog = Join-Path $tools 'merge.log'
    $resourceStock = Join-Path $tools 'resource-stock.txt'
    [System.IO.File]::WriteAllText($stubJava, ((@(
        '@echo off',
        'setlocal EnableExtensions EnableDelayedExpansion',
        'if "%~1"=="-version" (',
        '    echo openjdk version "21.0.5" 2024-10-15',
        '    exit /b 0',
        ')',
        'rem Its own folder, read before shift moves %0 along with the arguments.',
        'set "HERE=%~dp0"',
        'rem -Xmx -cp <jar> <tool>.java and the tool''s arguments.',
        'if /i "%~nx4"=="MergeSplits.java" goto merge',
        'if /i "%~nx4"=="ResourceTableCheck.java" goto resources',
        'if /i "%~nx4"=="DexDiff.java" goto dexdiff',
        'set "OUT=" & set "RESULT=" & set "LAST=" & set "PREV=" & set "FORCED=0" & set "VALIDATED=0"',
        'shift',
        'shift',
        'rem patch-for-device.ps1 hands the CLI one argument file, a quoted value a line with',
        'rem backslashes doubled; the receipt builder passes the arguments themselves.',
        'set "FIRST=%~1"',
        'if not "!FIRST:~0,1!"=="@" goto next',
        'set "ARGUMENTS=!FIRST:~1!"',
        'for /f "usebackq delims=" %%L in ("%ARGUMENTS%") do (',
        '    set "V=%%~L"',
        '    set "V=!V:\\=\!"',
        '    call :take',
        ')',
        'goto run',
        ':next',
        'if "%~1"=="" goto run',
        'set "V=%~1"',
        'call :take',
        'shift',
        'goto next',
        ':take',
        'rem Each -O with the -e before it, the patch the CLI gives that option to.',
        'if "!PREV!"=="-O" >>"!HERE!options.log" echo !ENABLED! !V!',
        'if "!PREV!"=="-e" set "ENABLED=!V!"',
        'if "!PREV!"=="-o" set "OUT=!V!"',
        'if "!PREV!"=="-r" set "RESULT=!V!"',
        'if "!V!"=="-f" set "FORCED=1"',
        'if "!V!"=="--validate" set "VALIDATED=1"',
        'set "PREV=!V!"',
        'set "LAST=!V!"',
        'exit /b 0',
        ':run',
        '>>"!HERE!validation.log" echo !VALIDATED!',
        'rem A merged APK names the bundle it came from, whose report and patched manifest these are.',
        'set "SRC=!LAST!"',
        'set "VIA="',
        'if exist "!LAST!.source" (',
        '    set /p SRC=<"!LAST!.source"',
        '    set "VIA= merged"',
        ')',
        '>>"!HERE!java.log" echo patch !SRC!!VIA! forced=!FORCED!',
        'rem The CLI logs WARNING and SEVERE on standard error, which Windows PowerShell 5.1 makes a',
        'rem terminating error under Stop unless the caller steps down to Continue for the call.',
        'echo WARNING: a patch named a target this build lacks, and still applied 1>&2',
        'rem A case that needs something to change while a fixture is patched leaves this behind.',
        'if exist "!HERE!during-patch.cmd" call "!HERE!during-patch.cmd"',
        'copy /y "!SRC!.result.json" "!RESULT!" >nul || exit /b 3',
        'copy /y "!HERE!patched.apk" "!OUT!" >nul || exit /b 4',
        'copy /y "!SRC!.patched.txt" "!OUT!.xmltree" >nul || exit /b 5',
        'exit /b 0',
        'rem MergeSplits.java <bundle> <merged.apk>. A case can make it fail, or leave no APK behind.',
        ':merge',
        '>>"!HERE!merge.log" echo merge %~5',
        'if exist "!HERE!merge-fails.txt" (',
        '    echo [merge] could not read the bundle 1>&2',
        '    exit /b 9',
        ')',
        'if exist "!HERE!merge-writes-nothing.txt" exit /b 0',
        'copy /y "%~5.merged.txt" "%~6" >nul || exit /b 7',
        '>"%~6.source" echo %~5',
        'exit /b 0',
        'rem ResourceTableCheck.java <stock> <patched> <report>: keeps what it was handed as the stock side.',
        ':resources',
        'copy /y "%~5" "!HERE!resource-stock.txt" >nul || exit /b 8',
        'echo Note: the source launcher compiled with a warning 1>&2',
        'echo [resources] stand-in: every stock resource resolves in the patched table',
        'exit /b 0',
        ':dexdiff',
        'echo [diff] structural findings: 0',
        'exit /b 0') -join "`r`n") + "`r`n"), [System.Text.Encoding]::ASCII)
    [System.IO.File]::WriteAllText($stubAapt2, ((@(
        '@echo off',
        'setlocal EnableExtensions DisableDelayedExpansion',
        'rem Taken before the loop, since shift moves %0 along with the arguments.',
        'set "HERE=%~dp0"',
        'set "APK="',
        ':next',
        'if "%~1"=="" goto run',
        'set "APK=%~1"',
        'shift',
        'goto next',
        ':run',
        '>>"%HERE%aapt2.log" echo %APK%',
        'rem A kept run''s copy is tried the way anyone running as the same user could, and the try is logged.',
        'if "%APK:\kept\=%"=="%APK%" goto dump',
        '(>>"%APK%" echo swapped) 2>nul && (>>"%HERE%aapt2.log" echo swap=went-through) || (>>"%HERE%aapt2.log" echo swap=refused)',
        ':dump',
        'if exist "%APK%.xmltree" (type "%APK%.xmltree") else (type "%APK%")',
        'exit /b %errorlevel%') -join "`r`n") + "`r`n"), [System.Text.Encoding]::ASCII)
    # The 16 KB check runs the zipalign beside aapt2. Aligned unless a case below says otherwise.
    [System.IO.File]::WriteAllText((Join-Path $tools 'zipalign.cmd'), ((@(
        '@echo off',
        'if "%HUSHFACEBOOK_CONTRACT_ZIPALIGN%"=="old" goto old',
        'echo Verifying alignment of patched.apk (4)...',
        'if "%HUSHFACEBOOK_CONTRACT_ZIPALIGN%"=="bad" goto bad',
        'echo   49152 lib/arm64-v8a/libstub.so (OK)',
        'echo Verification succesful',
        'exit /b 0',
        ':bad',
        'echo   49152 lib/arm64-v8a/libstub.so (BAD - 4096)',
        'echo Verification FAILED',
        'exit /b 1',
        ':old',
        'echo Unknown option -P',
        'exit /b 1') -join "`r`n") + "`r`n"), [System.Text.Encoding]::ASCII)
    # A jar with the patcher's version file and nothing else: verify-all-patches.ps1 reads which
    # patcher a CLI bundles before it runs one, and the stand-in java never opens the jar.
    New-DesktopJarStandIn -Path $stubJar -Patcher $releaseToolchain.PatcherVersion
    New-TestBundleArchive -Path (Join-Path $tools 'patched.apk') -Entries ([ordered]@{
        'AndroidManifest.xml' = 'binary manifest'; 'classes.dex' = "dex`n035" })

    $androidName = 'http://schemas.android.com/apk/res/android:name(0x01010003)='
    $androidExported = '          A: http://schemas.android.com/apk/res/android:exported(0x01010010)=true'
    # Facebook asks for the two permissions it shares with Meta's other apps, for Google's push and
    # for five of its own, and the patched build asks for the two under the names Install beside
    # Meta's apps gives them, for microG-RE's push in place of Google's and for its own five under
    # the copy's package (Clone install), and exports Open in Facebook's share target: the changes
    # the checked-in allowlist approves, and the only ones the patches make here.
    function Get-FixtureManifest([string]$Build, [string]$Code, [switch]$WithSplit, [switch]$Renamed,
            [string]$Package = $releaseTarget.PackageName) {
        $prefix = if ($Renamed) { 'app.hushfacebook.' } else { 'com.facebook.' }
        $push = if ($Renamed) { 'app.revanced.android.c2dm.' } else { 'com.google.android.c2dm.' }
        $own = if ($Renamed) { 'com.facebook.katana.hush.' } else { 'com.facebook.katana.' }
        $ownLines = @($clonePermissionNames | ForEach-Object {
            '      E: uses-permission (line=14)'
            "        A: $androidName`"$own$_`" (Raw: `"$own$_`")" })
        $lines = @(
            'N: android=http://schemas.android.com/apk/res/android (line=1)',
            '  E: manifest (line=1)',
            "    A: http://schemas.android.com/apk/res/android:versionCode(0x0101021b)=$Code",
            "    A: http://schemas.android.com/apk/res/android:versionName(0x0101021c)=`"$Build`" (Raw: `"$Build`")",
            "    A: package=`"$Package`" (Raw: `"$Package`")",
            '      E: uses-permission (line=10)',
            "        A: $androidName`"android.permission.INTERNET`" (Raw: `"android.permission.INTERNET`")",
            '      E: uses-permission (line=11)',
            "        A: $androidName`"${prefix}permission.prod.FB_APP_COMMUNICATION`" (Raw: `"${prefix}permission.prod.FB_APP_COMMUNICATION`")",
            '      E: uses-permission (line=12)',
            "        A: $androidName`"${prefix}receiver.permission.ACCESS`" (Raw: `"${prefix}receiver.permission.ACCESS`")",
            '      E: uses-permission (line=13)',
            "        A: $androidName`"${push}permission.RECEIVE`" (Raw: `"${push}permission.RECEIVE`")") + $ownLines + @(
            '      E: application (line=20)',
            '        E: activity (line=21)',
            "          A: $androidName`"com.facebook.katana.LoginActivity`" (Raw: `"com.facebook.katana.LoginActivity`")",
            $androidExported)
        if ($Renamed) {
            $lines += @('        E: activity (line=30)',
                "          A: $androidName`"app.morphe.extension.facebook.misc.OpenInFacebook`" (Raw: `"app.morphe.extension.facebook.misc.OpenInFacebook`")",
                $androidExported)
        }
        if ($WithSplit) {
            $lines += @('        E: activity (line=40)',
                "          A: $androidName`"com.facebook.split.FeatureActivity`" (Raw: `"com.facebook.split.FeatureActivity`")",
                $androidExported)
        }
        return ($lines -join "`n") + "`n"
    }
    $dependencyNamesHere = @(Get-PatchDependencyNames -PatchList $releaseCatalog -RequestedNames $releaseNames)
    $fixturePaths = @{}
    # Beside the declared builds, a newer one the catalog doesn't declare, the kind a release run
    # patches under -f to see what still applies on it. Each declared build carries the version code
    # the catalog pins it to; the fixtures used to count down from a code of their own, which only
    # the version name made declared.
    $newerBuild = "$([int]($releaseTarget.PackageVersion -split '\.')[0] + 1).0.0.1.1"
    foreach ($build in @($newerBuild) + @($releaseTarget.PackageVersions)) {
        $versionCode = if ($build -eq $newerBuild) { 475119344 } else { [long]@($releaseTarget.PackageVersionCodes[$build])[0] }
        $apkm = Join-Path $fixtures "facebook-$build-arm64-v8a.apkm"
        New-TestBundleArchive -Path $apkm -Entries ([ordered]@{
            'info.json' = "{`"versioncode`":`"$versionCode`"}"
            'base.apk' = Get-FixtureManifest -Build $build -Code "$versionCode"
            'split_config.arm64_v8a.apk' = ('native code ' * 64) })
        Set-Content -LiteralPath "$apkm.merged.txt" -Encoding ASCII -NoNewline `
            -Value (Get-FixtureManifest -Build $build -Code "$versionCode" -WithSplit)
        Set-Content -LiteralPath "$apkm.patched.txt" -Encoding ASCII -NoNewline `
            -Value (Get-FixtureManifest -Build $build -Code "$versionCode" -WithSplit -Renamed)
        # The report the CLI writes: every patch and the internal dependencies applied, one step,
        # and the input's own version, which is what the CLI reports.
        Set-Content -LiteralPath "$apkm.result.json" -Encoding ASCII -Value ([ordered]@{
            success = $true
            stepResults = @([ordered]@{ step = 'PATCHING'; success = $true })
            appliedPatches = @(@($releaseNames) + @($dependencyNamesHere) | ForEach-Object { [ordered]@{ name = $_ } })
            failedPatches = @()
            packageName = $releaseTarget.PackageName
            packageVersion = $build } | ConvertTo-Json -Depth 6)
        $fixturePaths[$build] = $apkm
    }
    # And another build of the oldest declared version, as APKMirror lists several arm64 builds of one
    # Facebook release: the declared name at a code the catalog doesn't pin. Only its base manifest,
    # since every script has to refuse it before anything is merged or patched.
    $variantBuild = @($releaseTarget.PackageVersions)[-1]
    $variantCode = [long]@($releaseTarget.PackageVersionCodes[$variantBuild])[0] - 61
    $variantApkm = Join-Path $fixtures "facebook-$variantBuild-variant-arm64-v8a.apkm"
    New-TestBundleArchive -Path $variantApkm -Entries ([ordered]@{
        'info.json' = "{`"versioncode`":`"$variantCode`"}"
        'base.apk' = Get-FixtureManifest -Build $variantBuild -Code "$variantCode" })
    $releaseBundle = Get-ReleaseBundlePath -Root $releaseRepo
    New-Item -ItemType Directory -Path (Split-Path -Parent $releaseBundle) -Force | Out-Null
    New-TestBundleArchive -Path $releaseBundle -Entries ([ordered]@{
        'META-INF/MANIFEST.MF' = ("Manifest-Version: 1.0`nVersion: $releaseVersionHere`nTimestamp: $($releaseSeconds * 1000)`n" +
            "Patcher-Version: $($releaseToolchain.PatcherVersion)`n`n")
        'classes.dex' = "dex`n035" + ('patches' * 8)
        'extensions/facebook.mpe' = "dex`n035" + ('payload' * 8) })
    # And the SBOM buildAndroid writes beside it, listing a library OSV has nothing against.
    $releaseSbom = [System.IO.Path]::ChangeExtension($releaseBundle, '.cdx.json')
    New-TestSbom -Path $releaseSbom -Bundle $releaseBundle

    $releaseTooling = [IO.Path]::ChangeExtension($releaseBundle, '.tooling.json')
    $releaseScopeText = [IO.File]::ReadAllText((Join-Path $releaseRepo 'gradle/tooling-scopes.txt'))
    New-TestTooling -Path $releaseTooling -Bundle $releaseBundle -Commit $releaseCommit -ScopeText $releaseScopeText

    # The builder reads git with a plain `git -C`, which a GIT_DIR inherited from a hook would
    # override, so every GIT_* variable is cleared for the length of a run. OSV is the stand-in
    # above, and what the builder says is kept in $builderSaid, warnings included.
    $builderSaid = ''
    function Invoke-ReceiptBuilder([string[]]$Fixtures, [string]$Bundle,
            [string]$WorkDir = (Join-Path $releaseRoot 'work'), [string]$DesktopJar = $stubJar, [switch]$SkipAdvisoryCheck,
            [string]$AppliedDir) {
        Remove-Item -LiteralPath $javaLog, $mergeLog -Force -ErrorAction SilentlyContinue
        $saved = @{}
        foreach ($variable in @(Get-ChildItem Env: | Where-Object { $_.Name -like 'GIT_*' })) {
            $saved[$variable.Name] = $variable.Value
            Remove-Item -LiteralPath ('Env:\' + $variable.Name)
        }
        try {
            . $osvStandIn
            $global:LASTEXITCODE = 0
            $arguments = @{ Root = $releaseRepo; Fixture = $Fixtures; WorkDir = $WorkDir; DesktopJar = $DesktopJar
                Java = $stubJava; Aapt2 = $stubAapt2 }
            if ($Bundle) { $arguments['Bundle'] = $Bundle }
            if ($SkipAdvisoryCheck) { $arguments['SkipAdvisoryCheck'] = $true }
            if ($AppliedDir) { $arguments['AppliedDir'] = $AppliedDir }
            $script:builderSaid = @(& (Join-Path $PSScriptRoot 'build-release-receipt.ps1') @arguments 3>&1 6>&1 |
                ForEach-Object { "$_" }) -join "`n"
            if ($LASTEXITCODE -ne 0) { throw "build-release-receipt.ps1 exited $LASTEXITCODE." }
        } finally {
            foreach ($name in $saved.Keys) { Set-Item -LiteralPath ('Env:\' + $name) -Value $saved[$name] }
        }
    }

    # A fixture for every declared build and the newer one: one target each, only the newer build
    # forced, every patch applied, and no manifest change but the reviewed ones, because the patched
    # manifest is held to the merge and not to the base.
    $builtBuilds = @($releaseTarget.PackageVersions) + @($newerBuild)
    $allFixtures = @($builtBuilds | ForEach-Object { $fixturePaths[$_] })
    $null = @(Read-QueueLog)
    try {
        Invoke-ReceiptBuilder -Fixtures $allFixtures
    } catch {
        throw "build-release-receipt.ps1 refused a run of every declared build: $($_.Exception.Message)"
    }
    $validationLog = Join-Path $tools 'validation.log'
    Assert-True (@(Get-Content -LiteralPath $validationLog | Where-Object { $_ -ne '1' }).Count -eq 0) `
        'A fresh release receipt patched without validating fingerprint uniqueness.'
    # Every merge and patch run in one job of the build queue (the stand-in at the top).
    $seen = @(Read-QueueLog)
    Assert-True (($seen -join '|') -eq 'hushfacebook receipt') `
        "The receipt's merges and patch runs did not share one job of the build queue: $($seen -join ', ')"
    $built = Get-Content -LiteralPath $releaseReceipt -Raw | ConvertFrom-Json
    $builtTargets = @($built.targets)
    $builtVersions = @($builtTargets | ForEach-Object { [string]$_.source.versionName })
    Assert-True (($builtVersions -join ',') -eq ($builtBuilds -join ',')) `
        "The receipt does not hold one run of each fixture: $($builtVersions -join ', ')"
    foreach ($builtTarget in $builtTargets) {
        $label = [string]$builtTarget.source.versionName
        $declared = $releaseTarget.PackageVersions -contains $label
        Assert-True ($builtTarget.source.forced -eq (-not $declared)) `
            "The receipt says $label was $(if ($builtTarget.source.forced) { 'forced' } else { 'not forced' })."
        Assert-True ($builtTarget.source.sha256 -eq (Get-Sha256Hex -Path $fixturePaths[$label])) `
            "The receipt does not hash the $label fixture it was given."
        Assert-True (@($builtTarget.patches | Where-Object { $_.applied }).Count -eq $releaseNames.Count) `
            "The receipt does not record every patch applied to $label."
        # The reviewed changes are the patches'. The split's activity the merge brings in is the merge's.
        $changes = @(ConvertTo-ManifestDeltaEntries -Delta $builtTarget.manifestDelta | Sort-Object -Unique -CaseSensitive)
        Assert-True (($changes -join "`n") -ceq (@($checkedInAllowlist | Sort-Object -Unique -CaseSensitive) -join "`n")) `
            "The receipt records other manifest changes for $label than the patches' reviewed ones: $($changes -join ', ')"
    }
    # Each fixture merged once, and the CLI handed that merge rather than the bundle: the CLI deletes
    # its own merge, and the manifest delta above is taken against this one.
    $mergeRuns = @(Get-Content -LiteralPath $mergeLog)
    Assert-True (($mergeRuns -join "`n") -eq (@($builtBuilds | ForEach-Object { "merge $($fixturePaths[$_])" }) -join "`n")) `
        "Each fixture was not merged once, before it was patched: $($mergeRuns -join '; ')"
    $patchRuns = @(Get-Content -LiteralPath $javaLog)
    $expectedRuns = @($builtBuilds | ForEach-Object {
        "patch $($fixturePaths[$_]) merged forced=$(if ($releaseTarget.PackageVersions -contains $_) { 0 } else { 1 })" })
    Assert-True (($patchRuns -join "`n") -eq ($expectedRuns -join "`n")) `
        "The CLI was not run once per fixture, on its merge, with -f for the undeclared build only: $($patchRuns -join '; ')"
    # The SBOM beside the bundle, recorded by name, hash and count, once OSV had been asked about it.
    Assert-True ($built.sbom.file -eq "patches-$releaseVersionHere.cdx.json" -and
        $built.sbom.sha256 -ceq (Get-Sha256Hex -Path $releaseSbom) -and [int]$built.sbom.components -eq 3) `
        "The receipt does not record the SBOM beside the bundle: $($built.sbom | ConvertTo-Json -Compress)"
    $builtTooling = Read-ReleaseTooling $releaseTooling
    Assert-True ($built.tooling.file -ceq "patches-$releaseVersionHere.tooling.json" -and
        $built.tooling.sha256 -ceq $builtTooling.Sha256 -and $built.tooling.components -eq $builtTooling.Components.Count -and
        $built.tooling.scopes -eq $builtTooling.Scopes.Count -and $built.tooling.audit.status -ceq 'checked') `
        'The receipt omitted its tooling graph, source coverage, counts or successful audit status.'
    Assert-True ($builderSaid -like "*OSV has no advisory for the libraries patches-$releaseVersionHere.cdx.json lists: gson 2.14.0*") `
        "The receipt builder did not put the SBOM's libraries to OSV: $builderSaid"
    # And the receipt the builder writes is one the release check accepts.
    $said = Invoke-ReleaseCheck
    $builtProved = "the receipt proves $($releaseNames.Count) patches on $($builtVersions -join ', ') " +
        "from commit $($releaseCommit.Substring(0, 8))"
    Assert-True ($said -like "*$builtProved*") "The release check did not accept the receipt the builder wrote: $said"
    # And the publish stage's check, on the release staged as the bundle stage stages it and held to
    # what the release commit carries. The fixture commits no allowlist, so the checked-in one stands
    # in. This bundle carries no build identity, which the builder still takes from a bundle built
    # before bundles carried one, so the folder holds together and is refused for that alone. The
    # identity cases further down stage a bundle that carries one, and the check takes it.
    $stagedHere = Join-Path $releaseRoot 'staged'
    function Save-StagedHere {
        Remove-Item -LiteralPath $stagedHere -Recurse -Force -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Path $stagedHere -Force | Out-Null
        Copy-Item -LiteralPath $releaseBundle, $releaseSbom, $releaseTooling, $releaseReceipt -Destination $stagedHere
        $lines = @(Get-ChildItem -LiteralPath $stagedHere -File | Sort-Object Name |
            ForEach-Object { '{0}  {1}' -f (Get-Sha256Hex -Path $_.FullName).ToLowerInvariant(), $_.Name })
        [IO.File]::WriteAllText((Join-Path $stagedHere 'SHA256SUMS.txt'), (($lines -join "`n") + "`n"))
    }
    function Test-StagedHere([string]$Commit = $releaseCommit) {
        Test-StagedReleaseAt -Root $releaseRepo -Folder $stagedHere -Version $releaseVersionHere -Commit $Commit `
            -AllowlistPath (Join-Path $PSScriptRoot 'manifest-delta-allowlist.txt')
    }
    try {
        Save-StagedHere
        $stagedCheck = Test-StagedHere
        Assert-True (-not $stagedCheck.Valid -and $stagedCheck.Reason -like '*carries no intact build identity*') `
            "The publish stage took a staged bundle with no build identity: $($stagedCheck.Reason)"
        $otherCommit = Test-StagedHere -Commit ('f' * 40)
        Assert-True (-not $otherCommit.Valid) 'The publish stage held the staged release to a commit the fixture lacks.'
    } finally {
        Remove-Item -LiteralPath $stagedHere -Recurse -Force -ErrorAction SilentlyContinue
    }

    # A merge that fails, and one that exits 0 and writes nothing, stop the run before the CLI
    # patches anything. base.apk is not what the CLI patches, so there's no receipt to fall back to.
    $builtReceiptBeforeMerge = [System.IO.File]::ReadAllBytes($releaseReceipt)
    $brokenMerges = @(
        @{ Flag = 'merge-fails.txt'; Pattern = '*Could not merge facebook-*.apkm into one APK (exit 9)*could not read the bundle*' },
        @{ Flag = 'merge-writes-nothing.txt'; Pattern = '*The merge of facebook-*.apkm wrote no APK at *stock-merged.apk*' })
    foreach ($broken in $brokenMerges) {
        $flag = Join-Path $tools $broken.Flag
        Set-Content -LiteralPath $flag -Value 'on' -Encoding ASCII
        Remove-Item -LiteralPath $releaseReceipt -Force
        try {
            Assert-Throws { Invoke-ReceiptBuilder -Fixtures $allFixtures } $broken.Pattern `
                "build-release-receipt.ps1 went ahead when $($broken.Flag -replace '\.txt$', '')."
            Assert-True (-not (Test-Path -LiteralPath $javaLog) -and -not (Test-Path -LiteralPath $releaseReceipt)) `
                "build-release-receipt.ps1 patched or wrote a receipt when $($broken.Flag -replace '\.txt$', '')."
            Assert-True (@(Get-ChildItem -LiteralPath (Join-Path $releaseRoot 'work') -Directory -Filter 'receipt-*').Count -eq 0) `
                "build-release-receipt.ps1 left its run folder behind when $($broken.Flag -replace '\.txt$', '')."
        } finally {
            Remove-Item -LiteralPath $flag -Force -ErrorAction SilentlyContinue
            [System.IO.File]::WriteAllBytes($releaseReceipt, $builtReceiptBeforeMerge)
        }
    }

    # verify-all-patches.ps1 on the same stand-ins, with an apksigner beside aapt2 that names Meta's
    # signer. Once the CLI stopped leaving its merge behind (1.17.0) it held the patched table to
    # base.apk, so the 7,588 resources 580's splits carry were never compared. It merges first now:
    # the CLI is handed the merge, the resource check's stock side is that merge, and a bundle that
    # yields no merged APK stops the run before anything is patched. A plain APK goes to the CLI as
    # it is and is its own stock side.
    $releaseSigner = @($releaseCatalog.patches | ForEach-Object { $_.compatibility } |
        Where-Object { $_.packageName -eq $releaseTarget.PackageName } | ForEach-Object { $_.signatures } |
        Sort-Object -Unique | Select-Object -First 1)
    Assert-True ($releaseSigner.Count -eq 1) 'The release catalog names no signer for the stand-in apksigner.'
    Set-Content -LiteralPath (Join-Path $tools 'apksigner.bat') -Encoding ASCII -Value @(
        '@echo off', "echo Signer #1 certificate SHA-256 digest: $($releaseSigner[0])", 'exit /b 0')
    $verifyAllScript = Join-Path $PSScriptRoot 'verify-all-patches.ps1'
    function Invoke-VerifyAll([string]$Apk, [string]$KeepIn, [switch]$Force) {
        Remove-Item -LiteralPath $javaLog, $mergeLog, $resourceStock -Force -ErrorAction SilentlyContinue
        $global:LASTEXITCODE = 0
        $keep = @{}
        if ($KeepIn) { $keep['KeepIn'] = $KeepIn }
        if ($Force) { $keep['Force'] = $true }
        $said = @(& $verifyAllScript -Apk $Apk -DesktopJar $stubJar -WorkDir (Join-Path $releaseRoot 'verify-work') `
            -Bundle $releaseBundle -PatchList (Join-Path $releaseRepo 'patches-list.json') -Java $stubJava `
            -Aapt2 $stubAapt2 @keep 3>&1 6>&1 | ForEach-Object { "$_" }) -join "`n"
        if ($LASTEXITCODE -ne 0) { throw "verify-all-patches.ps1 exited $LASTEXITCODE`: $said" }
        return $said
    }
    $newestFixture = $fixturePaths[$releaseTarget.PackageVersion]
    $null = @(Read-QueueLog)
    $said = Invoke-VerifyAll -Apk $newestFixture
    Assert-True ($said -like "*merged $(Split-Path -Leaf $newestFixture) into one APK for the CLI*" -and
        $said -like '*success: every requested patch applied*') "verify-all-patches.ps1 did not merge the bundle and pass: $said"
    # The merge, the CLI and the checks in one job of the build queue, and the register check's
    # DexDiff inside that same slot rather than in line behind it.
    $seen = @(Read-QueueLog)
    Assert-True (($seen -join '|') -eq "hushfacebook verify $(Split-Path -Leaf $newestFixture)|hushfacebook register check (inside a slot)") `
        "verify-all-patches.ps1 did not run its heavy part as one job of the build queue: $($seen -join ', ')"
    Assert-True ((@(Get-Content -LiteralPath $mergeLog) -join "`n") -eq "merge $newestFixture" -and
        (@(Get-Content -LiteralPath $javaLog) -join "`n") -eq "patch $newestFixture merged forced=0") `
        ("verify-all-patches.ps1 did not merge the bundle once and hand the CLI that merge: " +
            "$(@(Get-Content -LiteralPath $javaLog) -join '; ')")
    Assert-True ((Get-Content -LiteralPath $resourceStock -Raw) -ceq (Get-Content -LiteralPath "$newestFixture.merged.txt" -Raw)) `
        'The resource check was not handed the merge as its stock side.'
    # A jar bundling another patcher major than the bundle's is refused before anything is merged
    # or patched, naming the jar and both versions: the 0.17.0 push reached the CLI with a 1.18.1
    # jar on the 2.0 pin. A jar that doesn't say which patcher it bundles is refused too.
    $otherPatcher = if ([int](($releaseToolchain.PatcherVersion -split '\.')[0]) -gt 1) { '1.15.1' } else { '2.0.0' }
    $otherJar = Join-Path $tools 'morphe-desktop-other.jar'
    New-DesktopJarStandIn -Path $otherJar -Patcher $otherPatcher
    $bareJar = Join-Path $tools 'morphe-desktop-bare.jar'
    New-DesktopJarStandIn -Path $bareJar -Patcher ''
    foreach ($wrongJar in @(
            @{ Jar = $otherJar; Pattern = "*$otherJar bundles morphe-patcher $otherPatcher, but the bundle $(Split-Path -Leaf $releaseBundle) needs $($releaseToolchain.PatcherVersion)*" },
            @{ Jar = $bareJar; Pattern = "*$bareJar has no app/morphe/patcher/version.properties*" })) {
        Remove-Item -LiteralPath $javaLog, $mergeLog -Force -ErrorAction SilentlyContinue
        Assert-Throws {
            & $verifyAllScript -Apk $newestFixture -DesktopJar $wrongJar.Jar -WorkDir (Join-Path $releaseRoot 'verify-work') `
                -Bundle $releaseBundle -PatchList (Join-Path $releaseRepo 'patches-list.json') -Java $stubJava `
                -Aapt2 $stubAapt2 *> $null
        } $wrongJar.Pattern "verify-all-patches.ps1 took $(Split-Path -Leaf $wrongJar.Jar) for a $($releaseToolchain.PatcherVersion) bundle."
        Assert-True (-not (Test-Path -LiteralPath $javaLog) -and -not (Test-Path -LiteralPath $mergeLog)) `
            "verify-all-patches.ps1 merged or ran the CLI before refusing $(Split-Path -Leaf $wrongJar.Jar)."
    }
    # A bundle naming no Patcher-Version is held to the checkout's pin instead.
    $checkoutPin = [regex]::Match((Get-Content -LiteralPath (Join-Path (Split-Path -Parent $PSScriptRoot) 'gradle/libs.versions.toml') -Raw),
        '(?m)^\s*morphe-patcher\s*=\s*"([^"]+)"').Groups[1].Value
    $pinOther = if ([int](($checkoutPin -split '\.')[0]) -gt 1) { '1.15.1' } else { '2.0.0' }
    New-DesktopJarStandIn -Path $otherJar -Patcher $pinOther
    $pinlessBundle = Join-Path $tools 'pinless.mpp'
    New-DesktopJarStandIn -Path $pinlessBundle -Patcher ''
    Remove-Item -LiteralPath $javaLog, $mergeLog -Force -ErrorAction SilentlyContinue
    Assert-Throws {
        & $verifyAllScript -Apk $newestFixture -DesktopJar $otherJar -WorkDir (Join-Path $releaseRoot 'verify-work') `
            -Bundle $pinlessBundle -PatchList (Join-Path $releaseRepo 'patches-list.json') -Java $stubJava `
            -Aapt2 $stubAapt2 *> $null
    } "*$otherJar bundles morphe-patcher $pinOther, but gradle/libs.versions.toml needs $checkoutPin*" `
        "verify-all-patches.ps1 took a $pinOther CLI for a bundle naming no patcher, on the $checkoutPin pin."
    Assert-True (-not (Test-Path -LiteralPath $javaLog) -and -not (Test-Path -LiteralPath $mergeLog)) `
        'verify-all-patches.ps1 merged or ran the CLI before refusing a CLI for a bundle naming no patcher.'
    Remove-Item -LiteralPath $otherJar, $bareJar, $pinlessBundle -Force
    # version.properties is a Java properties file: CRLF, a colon, spaces round the separator.
    # An empty value doesn't run into the next line.
    $parsedJar = Join-Path $tools 'morphe-desktop-parsed.jar'
    foreach ($case in @(
            @{ Text = "#Generated`r`nversion=2.0.0`r`n"; Want = '2.0.0' },
            @{ Text = "version : 1.15.1"; Want = '1.15.1' },
            @{ Text = "  version=2.1.0-dev.3  `n"; Want = '2.1.0-dev.3' },
            @{ Text = "version=`nname=patcher`n"; Want = $null })) {
        New-DesktopJarStandIn -Path $parsedJar -Properties $case.Text
        $parsed = Get-DesktopPatcherVersion -Jar $parsedJar
        Assert-True ($parsed -eq $case.Want) `
            "Get-DesktopPatcherVersion read '$parsed' from version.properties '$($case.Text -replace "`r", '\r' -replace "`n", '\n')', not '$($case.Want)'."
    }
    Remove-Item -LiteralPath $parsedJar -Force
    foreach ($broken in $brokenMerges) {
        $flag = Join-Path $tools $broken.Flag
        Set-Content -LiteralPath $flag -Value 'on' -Encoding ASCII
        try {
            Assert-Throws { Invoke-VerifyAll -Apk $newestFixture } $broken.Pattern `
                "verify-all-patches.ps1 went ahead when $($broken.Flag -replace '\.txt$', '')."
            Assert-True (-not (Test-Path -LiteralPath $javaLog) -and -not (Test-Path -LiteralPath $resourceStock)) `
                "verify-all-patches.ps1 patched or compared a table when $($broken.Flag -replace '\.txt$', '')."
        } finally {
            Remove-Item -LiteralPath $flag -Force -ErrorAction SilentlyContinue
        }
    }
    $plainFixture = Join-Path $fixtures "facebook-$($releaseTarget.PackageVersion)-arm64-v8a.apk"
    $newestCode = [regex]::Match((Get-Content -LiteralPath "$newestFixture.merged.txt" -Raw),
        'versionCode\(0x0101021b\)=(\d+)').Groups[1].Value
    Set-Content -LiteralPath $plainFixture -Encoding ASCII -NoNewline `
        -Value (Get-FixtureManifest -Build $releaseTarget.PackageVersion -Code $newestCode)
    Copy-Item -LiteralPath "$newestFixture.result.json" -Destination "$plainFixture.result.json"
    # Patched from the plain APK itself: its manifest with the patches' change and no split's
    # component, which verify-all-patches.ps1 would refuse as a change nobody approved.
    Set-Content -LiteralPath "$plainFixture.patched.txt" -Encoding ASCII -NoNewline `
        -Value (Get-FixtureManifest -Build $releaseTarget.PackageVersion -Code $newestCode -Renamed)
    try {
        $said = Invoke-VerifyAll -Apk $plainFixture
        Assert-True ($said -like '*success: every requested patch applied*' -and $said -notlike '*into one APK for the CLI*' -and
            -not (Test-Path -LiteralPath $mergeLog) -and
            (@(Get-Content -LiteralPath $javaLog) -join "`n") -eq "patch $plainFixture forced=0" -and
            (Get-Content -LiteralPath $resourceStock -Raw) -ceq (Get-Content -LiteralPath $plainFixture -Raw)) `
            "verify-all-patches.ps1 did not patch a plain APK as it is and hold the table to it: $said"
    } finally {
        Remove-Item -LiteralPath $plainFixture, "$plainFixture.result.json", "$plainFixture.patched.txt" -Force -ErrorAction SilentlyContinue
    }
    Assert-True (@(Get-ChildItem -LiteralPath (Join-Path $releaseRoot 'verify-work') -Directory -Filter 'verify-*').Count -eq 0) `
        'verify-all-patches.ps1 left a run folder behind.'

    # Kept runs. verify-all-patches.ps1 -KeepIn keeps a passing run of each fixture with a stamp
    # naming what it patched with, and build-release-receipt.ps1 -AppliedDir reads a kept run whose
    # stamp names its own bundle, APK, patch list and CLI instead of patching that fixture again.
    # The checks run on what was kept, so the receipt comes out as a fresh one does. The stand-in
    # aapt2 reads a patched APK's manifest from the .xmltree file the stand-in CLI writes beside its
    # output, which a keep doesn't carry, so each case puts it back beside the kept APK.
    $appliedRoot = Join-Path $releaseRoot 'fixture-apply'
    $null = @(Read-QueueLog)
    Invoke-ReceiptBuilder -Fixtures $allFixtures
    $freshTargets = (Get-Content -LiteralPath $releaseReceipt -Raw | ConvertFrom-Json).targets | ConvertTo-Json -Depth 12
    $listHash = Get-Sha256Hex -Path (Join-Path $releaseRepo 'patches-list.json')
    foreach ($build in $builtBuilds) {
        $fixture = $fixturePaths[$build]
        $keptDir = Join-Path $appliedRoot (Split-Path -Leaf $fixture)
        $forcedHere = $releaseTarget.PackageVersions -notcontains $build
        $said = Invoke-VerifyAll -Apk $fixture -KeepIn $keptDir -Force:$forcedHere
        Assert-True ($said -like "*kept this run for the release receipt in $keptDir*") "verify-all-patches.ps1 did not keep its run of ${build}: $said"
        $keptNames = @(Get-ChildItem -LiteralPath $keptDir -File | ForEach-Object { $_.Name } | Sort-Object)
        Assert-True (($keptNames -join ',') -eq 'patched.apk,result.json,stamp.json,stock-merged.apk') `
            "The kept run of $build is not the patched APK, the report, the merge and the stamp: $($keptNames -join ', ')"
        $stamp = Get-Content -LiteralPath (Join-Path $keptDir 'stamp.json') -Raw | ConvertFrom-Json
        Assert-True ($stamp.bundleSha256 -ceq (Get-Sha256Hex -Path $releaseBundle) -and $stamp.apkSha256 -ceq (Get-Sha256Hex -Path $fixture) -and
            $stamp.patchListSha256 -ceq $listHash -and $stamp.desktopJarSha256 -ceq (Get-Sha256Hex -Path $stubJar) -and
            $stamp.versionName -ceq $build -and $stamp.forced -eq $forcedHere -and $stamp.merged -eq $true -and $stamp.schema -eq 3 -and
            $stamp.validated -is [bool] -and $stamp.validated -eq $true -and
            $stamp.patchedSha256 -ceq (Get-Sha256Hex -Path (Join-Path $keptDir 'patched.apk')) -and
            $stamp.resultSha256 -ceq (Get-Sha256Hex -Path (Join-Path $keptDir 'result.json')) -and
            $stamp.mergedSha256 -ceq (Get-Sha256Hex -Path (Join-Path $keptDir 'stock-merged.apk'))) `
            "The kept run of $build is stamped with something else than what it patched: $($stamp | ConvertTo-Json -Compress)"
        Copy-Item -LiteralPath "$fixture.patched.txt" -Destination (Join-Path $keptDir 'patched.apk.xmltree')
    }
    function Get-KeptHashes {
        @(Get-ChildItem -LiteralPath $appliedRoot -File -Recurse | Sort-Object FullName |
            ForEach-Object { "$($_.FullName)=$(Get-Sha256Hex -Path $_.FullName)" }) -join "`n"
    }
    $keptBefore = Get-KeptHashes
    $null = @(Read-QueueLog)
    $aapt2Log = Join-Path $tools 'aapt2.log'
    Remove-Item -LiteralPath $aapt2Log -Force -ErrorAction SilentlyContinue
    Invoke-ReceiptBuilder -Fixtures $allFixtures -AppliedDir $appliedRoot
    Assert-True (-not (Test-Path -LiteralPath $javaLog) -and -not (Test-Path -LiteralPath $mergeLog)) `
        "The receipt patched or merged a fixture whose kept run matched: $(@(Get-Content -LiteralPath $javaLog -ErrorAction SilentlyContinue) -join '; ')"
    Assert-True (@([regex]::Matches($builderSaid, "reading the gate's run of")).Count -eq $builtBuilds.Count) `
        "The receipt did not say it read each kept run: $builderSaid"
    # What it read was its own copy, hashed there. Reading the kept folder after hashing it left a
    # window for a file to change between the hash and the read.
    $manifestReads = @(Get-Content -LiteralPath $aapt2Log | ForEach-Object { "$_".Trim() })
    Assert-True (@($manifestReads | Where-Object { $_.StartsWith($appliedRoot, [StringComparison]::OrdinalIgnoreCase) }).Count -eq 0 -and
        @($manifestReads | Where-Object { $_ -like (Join-Path $releaseRoot 'work\receipt-*\kept\patched.apk') }).Count -eq $builtBuilds.Count) `
        "The receipt read a kept run in place rather than its own copy: $($manifestReads -join '; ')"
    # And that copy is held from its hash until its checks are done, so nothing can change it between.
    $swaps = @($manifestReads | Where-Object { $_ -like 'swap=*' })
    Assert-True ($swaps.Count -ge $builtBuilds.Count -and @($swaps | Where-Object { $_ -ne 'swap=refused' }).Count -eq 0) `
        "The receipt's copy of a kept run could be changed while its checks read it: $($swaps -join '; ')"
    Assert-True (((Get-Content -LiteralPath $releaseReceipt -Raw | ConvertFrom-Json).targets | ConvertTo-Json -Depth 12) -ceq $freshTargets) `
        'The receipt read from kept runs differs from the one fresh runs gave.'
    Assert-True ((Get-KeptHashes) -ceq $keptBefore) 'The receipt changed the kept runs it read.'

    # A kept run that doesn't match is patched again, that fixture alone: a stamp naming another
    # bundle, one gone, one that says a split bundle wasn't merged, one from before stamps named
    # the outputs, and one missing the hash of an output.
    $declaredFixture = $fixturePaths[$releaseTarget.PackageVersion]
    $declaredStamp = Join-Path $appliedRoot "$(Split-Path -Leaf $declaredFixture)/stamp.json"
    $stampText = [System.IO.File]::ReadAllText($declaredStamp)
    function Get-StampWithout([string[]]$Fields) {
        $older = $stampText | ConvertFrom-Json
        foreach ($field in $Fields) { $older.PSObject.Properties.Remove($field) }
        $older | ConvertTo-Json -Depth 4
    }
    $staleStamps = @(
        @{ Name = 'unvalidated schema 2'; Text = ($stampText -replace '"schema":\s*3', '"schema": 2'); Said = '*older stamp*' },
        @{ Name = 'a text schema'; Text = ($stampText -replace '"schema":\s*3', '"schema": "3"'); Said = '*older stamp*' },
        @{ Name = 'a fractional schema'; Text = ($stampText -replace '"schema":\s*3', '"schema": 3.5'); Said = '*older stamp*' },
        @{ Name = 'no validation mode'; Text = (Get-StampWithout 'validated'); Said = '*without fingerprint validation*' },
        @{ Name = 'validation disabled'; Text = ($stampText -replace '"validated":\s*true', '"validated": false'); Said = '*without fingerprint validation*' },
        @{ Name = 'a text validation flag'; Text = ($stampText -replace '"validated":\s*true', '"validated": "true"'); Said = '*without fingerprint validation*' },
        @{ Name = 'another bundle'; Text = $stampText.Replace((Get-Sha256Hex -Path $releaseBundle), ('0' * 64)); Said = '*made with another bundle*' },
        @{ Name = 'no stamp'; Text = $null; Said = '*kept no run of*' },
        @{ Name = 'no merge'; Text = ($stampText -replace '"merged":\s*true', '"merged": false'); Said = '*made with another bundle*' },
        @{ Name = 'an older stamp'; Text = (Get-StampWithout 'schema', 'patchedSha256', 'resultSha256', 'mergedSha256'); Said = '*older stamp*' },
        @{ Name = 'no hash of the patched APK'; Text = (Get-StampWithout 'patchedSha256'); Said = '*no longer holds the patched.apk it checked*' })
    foreach ($stale in $staleStamps) {
        if ($null -eq $stale.Text) { Remove-Item -LiteralPath $declaredStamp -Force }
        else {
            Assert-True ($stale.Text -cne $stampText) "The '$($stale.Name)' stamp case changed nothing."
            [System.IO.File]::WriteAllText($declaredStamp, $stale.Text)
        }
        try {
            Invoke-ReceiptBuilder -Fixtures $allFixtures -AppliedDir $appliedRoot
            Assert-True ((@(Get-Content -LiteralPath $javaLog) -join "`n") -eq "patch $declaredFixture merged forced=0" -and
                $builderSaid -like $stale.Said) `
                "With $($stale.Name) the receipt did not patch that fixture alone: $(@(Get-Content -LiteralPath $javaLog -ErrorAction SilentlyContinue) -join '; ')"
        } finally {
            [System.IO.File]::WriteAllText($declaredStamp, $stampText)
        }
    }

    # An output changed after the gate checked it is patched again too, though the stamp still
    # names the same bundle, APK, patch list and CLI. One byte more keeps a patched APK's manifest
    # and the report readable, the way a changed dex payload would, and keeps the file's time.
    $declaredKept = Split-Path -Parent $declaredStamp
    foreach ($output in @('patched.apk', 'result.json', 'stock-merged.apk')) {
        $outputPath = Join-Path $declaredKept $output
        $outputBytes = [System.IO.File]::ReadAllBytes($outputPath)
        $outputTime = (Get-Item -LiteralPath $outputPath).LastWriteTimeUtc
        [System.IO.File]::WriteAllBytes($outputPath, [byte[]]($outputBytes + [byte]10))
        (Get-Item -LiteralPath $outputPath).LastWriteTimeUtc = $outputTime
        try {
            Invoke-ReceiptBuilder -Fixtures $allFixtures -AppliedDir $appliedRoot
            Assert-True ((@(Get-Content -LiteralPath $javaLog -ErrorAction SilentlyContinue) -join "`n") -eq "patch $declaredFixture merged forced=0" -and
                $builderSaid -like "*no longer holds the $output it checked*") `
                "With its $output changed the receipt did not patch that fixture alone: $(@(Get-Content -LiteralPath $javaLog -ErrorAction SilentlyContinue) -join '; ')"
        } finally {
            [System.IO.File]::WriteAllBytes($outputPath, $outputBytes)
        }
    }
    $null = @(Read-QueueLog)
    Invoke-ReceiptBuilder -Fixtures $allFixtures -AppliedDir $appliedRoot
    Assert-True (-not (Test-Path -LiteralPath $javaLog)) 'The receipt patched a fixture again once its kept outputs were put back.'

    # A run that fails leaves no stamp behind, and a keep folder holding anything else is left alone.
    Remove-Item -LiteralPath (Join-Path $declaredKept 'patched.apk.xmltree') -Force
    Set-Content -LiteralPath (Join-Path $tools 'merge-fails.txt') -Value 'on' -Encoding ASCII
    try {
        Assert-Throws { Invoke-VerifyAll -Apk $declaredFixture -KeepIn $declaredKept } '*Could not merge*' `
            'verify-all-patches.ps1 went ahead with a merge that failed.'
    } finally {
        Remove-Item -LiteralPath (Join-Path $tools 'merge-fails.txt') -Force
    }
    Assert-True (-not (Test-Path -LiteralPath $declaredStamp)) 'A failed run left the stamp of the run before it to be read.'
    $foreignKeep = Join-Path $appliedRoot 'not-a-keep'
    New-Item -ItemType Directory -Path $foreignKeep -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $foreignKeep 'notes.txt') -Value 'mine' -Encoding ASCII
    Assert-Throws { Invoke-VerifyAll -Apk $declaredFixture -KeepIn $foreignKeep } '*as well as a kept run*' `
        'verify-all-patches.ps1 emptied a keep folder that holds something else.'
    Assert-True ((Test-Path -LiteralPath (Join-Path $foreignKeep 'notes.txt')) -and -not (Test-Path -LiteralPath $javaLog)) `
        'verify-all-patches.ps1 touched a keep folder holding something else, or patched first.'
    Remove-Item -LiteralPath $appliedRoot -Recurse -Force

    # Fixtures missing a declared build are not enough for a receipt: that build has no run. An
    # undeclared build alone, or beside every other declared one, is refused before anything is
    # patched. The builder used to patch every fixture first, which for Facebook unpacks
    # gigabytes, and then refuse its own receipt.
    $declaredOthers = @($releaseTarget.PackageVersions | Where-Object { $unproved -notcontains $_ })
    foreach ($partial in @(@($newerBuild), @($declaredOthers + @($newerBuild)))) {
        Assert-Throws { Invoke-ReceiptBuilder -Fixtures @($partial | ForEach-Object { $fixturePaths[$_] }) } `
            "*No fixture is the declared $($releaseTarget.PackageName) $($unproved -join ', ')*Nothing was patched*" `
            "build-release-receipt.ps1 went ahead with fixtures of $($partial -join ', ') only."
        if (Test-Path -LiteralPath $javaLog) {
            throw ("build-release-receipt.ps1 patched before it found a declared build missing: " +
                (@(Get-Content -LiteralPath $javaLog) -join '; '))
        }
    }
    # Another build of the older declared version in its place is no run of it either. The builder
    # took it by its name, patched it without -f and wrote a receipt proving a build nobody ran.
    $variantRefusal = "*No fixture is the declared $($releaseTarget.PackageName) $variantBuild*" +
        "$variantBuild ($(@($releaseTarget.PackageVersionCodes[$variantBuild]) -join ' or '))*" +
        "$variantBuild ($variantCode)*Nothing was patched*"
    Assert-Throws { Invoke-ReceiptBuilder -Fixtures @(@($releaseTarget.PackageVersions | Select-Object -SkipLast 1 |
                ForEach-Object { $fixturePaths[$_] }) + @($variantApkm)) } $variantRefusal `
        'build-release-receipt.ps1 took another build of a declared version for the declared one.'
    Assert-True (-not (Test-Path -LiteralPath $javaLog)) 'build-release-receipt.ps1 patched another build of a declared version.'
    # verify-all-patches.ps1 would patch it as a declared build too, without -f; it needs -Force now.
    Assert-Throws { Invoke-VerifyAll -Apk $variantApkm } "*$variantBuild, which the bundle does not declare, at version code $variantCode*-Force*" `
        'verify-all-patches.ps1 patched another build of a declared version as the declared one.'
    Assert-True (-not (Test-Path -LiteralPath $javaLog)) 'verify-all-patches.ps1 started the CLI on another build of a declared version.'

    # The SBOM and what OSV says about it come before anything is patched. The deliberately
    # vulnerable fixture is this bundle with an SBOM listing gson 2.8.8, and no receipt comes of it.
    # Offline, -SkipAdvisoryCheck gets a receipt with a warning, and the index push below asks OSV
    # again. An OSV out of reach stops the run, and so does an SBOM of another build or none at all.
    $cleanSbomBytes = [System.IO.File]::ReadAllBytes($releaseSbom)
    $cleanReceiptBytes = [System.IO.File]::ReadAllBytes($releaseReceipt)
    try {
        New-TestSbom -Path $releaseSbom -Bundle $releaseBundle -Libraries @($gsonPurl)
        Assert-Throws { Invoke-ReceiptBuilder -Fixtures $allFixtures } `
            '*high or critical*GHSA-4jrv-ppp4-jm57 (HIGH, CVE-2022-25647) in com.google.code.gson:gson 2.8.8*' `
            'build-release-receipt.ps1 wrote a receipt for a bundle carrying gson 2.8.8.'
        Assert-True (-not (Test-Path -LiteralPath $javaLog)) 'build-release-receipt.ps1 patched before it asked OSV about the SBOM.'
        Invoke-ReceiptBuilder -Fixtures $allFixtures -SkipAdvisoryCheck
        Assert-True ($builderSaid -like '*-SkipAdvisoryCheck: OSV was not asked about the libraries*' -and
            (Get-Content -LiteralPath $releaseReceipt -Raw | ConvertFrom-Json).sbom.sha256 -ceq (Get-Sha256Hex -Path $releaseSbom)) `
            "An offline receipt run did not say the advisory check was skipped, or did not record the SBOM: $builderSaid"
        foreach ($refused in @(
                @{ Name = 'an OSV out of reach'; Sbom = { New-TestSbom -Path $releaseSbom -Bundle $releaseBundle -Libraries @('pkg:maven/com.example/unknown@1.0') }
                    Pattern = '*could not be asked about pkg:maven/com.example/unknown@1.0*fails closed*' },
                @{ Name = 'an SBOM of another build'; Pattern = '*The SBOM does not describe the bundle*written for another build*'
                    Sbom = { New-TestSbom -Path $releaseSbom -Bundle $releaseBundle -Mutate { param($d) $d.metadata.component.hashes[0].content = ('0' * 64) } } },
                @{ Name = 'no SBOM'; Sbom = { Remove-Item -LiteralPath $releaseSbom }; Pattern = "*No SBOM for the bundle: $releaseSbom*" })) {
            & $refused.Sbom
            Assert-Throws { Invoke-ReceiptBuilder -Fixtures $allFixtures } $refused.Pattern "build-release-receipt.ps1 went ahead with $($refused.Name)."
            Assert-True (-not (Test-Path -LiteralPath $javaLog)) "build-release-receipt.ps1 patched with $($refused.Name)."
        }
        # The receipt names the SBOM still beside the bundle when it's written: another buildAndroid
        # during the patch runs, which take long enough for one, replaces it.
        [System.IO.File]::WriteAllBytes($releaseSbom, $cleanSbomBytes)
        $replacement = Join-Path $releaseRoot 'replacement.cdx.json'
        New-TestSbom -Path $replacement -Bundle $releaseBundle
        $duringPatch = Join-Path $tools 'during-patch.cmd'
        [System.IO.File]::WriteAllText($duringPatch, "@copy /y `"$replacement`" `"$releaseSbom`" >nul`r`n", [System.Text.Encoding]::ASCII)
        try {
            Assert-Throws { Invoke-ReceiptBuilder -Fixtures $allFixtures } `
                "*does not pass validation: The receipt says patches-$releaseVersionHere.cdx.json hashes to*" `
                'build-release-receipt.ps1 wrote a receipt naming an SBOM that was replaced while it patched.'
        } finally {
            Remove-Item -LiteralPath $duringPatch -Force -ErrorAction SilentlyContinue
        }
    } finally {
        [System.IO.File]::WriteAllBytes($releaseSbom, $cleanSbomBytes)
        [System.IO.File]::WriteAllBytes($releaseReceipt, $cleanReceiptBytes)
    }

    # A settings-only finding prevents patching even when the payload SBOM is clean. An audited
    # report replaced during patching cannot be recorded as the report that passed that audit.
    $cleanToolingBytes = [IO.File]::ReadAllBytes($releaseTooling)
    $receiptBeforeToolingCases = [IO.File]::ReadAllBytes($releaseReceipt)
    try {
        New-TestTooling -Path $releaseTooling -Bundle $releaseBundle -Commit $releaseCommit -ScopeText $releaseScopeText -Libraries @($gsonPurl) -SettingsOnly
        Assert-Throws { Invoke-ReceiptBuilder -Fixtures $allFixtures } '*Tooling advisories require remediation*HIGH*' `
            'The builder patched with a serious settings-only advisory and a clean payload.'
        Assert-True (-not (Test-Path -LiteralPath $javaLog)) 'The tooling gate ran after the fixture patching.'
        [IO.File]::WriteAllBytes($releaseTooling, $cleanToolingBytes)
        $replacementTooling = Join-Path $releaseRoot 'replacement.tooling.json'
        New-TestTooling -Path $replacementTooling -Bundle $releaseBundle -Commit $releaseCommit -ScopeText $releaseScopeText `
            -Mutate { param($d) $d.source.tree = 'dirty' }
        $duringPatch = Join-Path $tools 'during-patch.cmd'
        [IO.File]::WriteAllText($duringPatch, "@copy /y `"$replacementTooling`" `"$releaseTooling`" >nul`r`n", [Text.Encoding]::ASCII)
        Assert-Throws { Invoke-ReceiptBuilder -Fixtures $allFixtures } '*tooling report changed after its advisory audit*' `
            'A report replaced after its audit was recorded as checked.'
    } finally {
        Remove-Item -LiteralPath $duringPatch -Force -ErrorAction SilentlyContinue
        [IO.File]::WriteAllBytes($releaseTooling, $cleanToolingBytes)
        [IO.File]::WriteAllBytes($releaseReceipt, $receiptBeforeToolingCases)
    }

    # A bundle that isn't a build of the commit, from a tree that is clean by the time the receipt
    # is cut. The build stamps a bundle 0 when the tree had uncommitted changes as it started, and
    # another commit's bundle carries that commit's time. The receipt check at the end refused
    # both, but only after every fixture had been patched; they're refused before anything is now.
    # And a source written after the bundle, which is what an edit put back since leaves: the tree
    # is clean and the stamp right, yet the bundle may have been built from the edit. Nothing
    # refused that one at all, so a bundle built from someone else's edits in a shared tree could
    # ship as the tag once the edits were gone.
    $builtBundleBytes = [System.IO.File]::ReadAllBytes($releaseBundle)
    $builtSbomBytes = [System.IO.File]::ReadAllBytes($releaseSbom)
    $builtReceiptBytes = [System.IO.File]::ReadAllBytes($releaseReceipt)
    try {
        foreach ($stamped in @(
                @{ Name = 'a bundle built from a tree with uncommitted changes'; Stamp = 0L
                    Pattern = "*stamped 0*uncommitted changes*isn't a build of commit $releaseCommit*Nothing was patched*" },
                @{ Name = 'a bundle built from another commit'; Stamp = ($releaseSeconds - 60) * 1000
                    Pattern = "*stamped $(($releaseSeconds - 60) * 1000), but commit $releaseCommit was made at $($releaseSeconds * 1000)*Nothing was patched*" })) {
            Remove-Item -LiteralPath $releaseBundle -Force
            New-TestBundleArchive -Path $releaseBundle -Entries ([ordered]@{
                'META-INF/MANIFEST.MF' = ("Manifest-Version: 1.0`nVersion: $releaseVersionHere`nTimestamp: $($stamped.Stamp)`n" +
                    "Patcher-Version: $($releaseToolchain.PatcherVersion)`n`n")
                'classes.dex' = "dex`n035" + ('patches' * 8)
                'extensions/facebook.mpe' = "dex`n035" + ('payload' * 8) })
            # The SBOM that build writes beside it, so nothing but the stamp is out of place.
            New-TestSbom -Path $releaseSbom -Bundle $releaseBundle
            Assert-Throws { Invoke-ReceiptBuilder -Fixtures $allFixtures } $stamped.Pattern `
                "build-release-receipt.ps1 went ahead with $($stamped.Name)."
            Assert-True (-not (Test-Path -LiteralPath $javaLog)) "build-release-receipt.ps1 patched with $($stamped.Name)."
        }
    } finally {
        [System.IO.File]::WriteAllBytes($releaseBundle, $builtBundleBytes)
        [System.IO.File]::WriteAllBytes($releaseSbom, $builtSbomBytes)
        [System.IO.File]::WriteAllBytes($releaseReceipt, $builtReceiptBytes)
    }
    # The catalog touched after the build: git sees no change in it, and it's newer than the bundle.
    $touchedCatalog = Join-Path $releaseRepo 'gradle/libs.versions.toml'
    $catalogWritten = [System.IO.File]::GetLastWriteTimeUtc($touchedCatalog)
    try {
        [System.IO.File]::SetLastWriteTimeUtc($touchedCatalog, [System.IO.File]::GetLastWriteTimeUtc($releaseBundle).AddMinutes(1))
        Assert-Throws { Invoke-ReceiptBuilder -Fixtures $allFixtures } `
            "*1 source file(s) changed after the bundle was built, the newest *libs.versions.toml*Nothing was patched*" `
            'build-release-receipt.ps1 went ahead with a bundle older than a source it was built from.'
        Assert-True (-not (Test-Path -LiteralPath $javaLog)) 'build-release-receipt.ps1 patched with a bundle older than its sources.'
    } finally {
        [System.IO.File]::SetLastWriteTimeUtc($touchedCatalog, $catalogWritten)
        [System.IO.File]::WriteAllBytes($releaseReceipt, $builtReceiptBytes)
    }
    # The control: with the catalog as old as it was, the same run writes its receipt.
    try {
        Invoke-ReceiptBuilder -Fixtures $allFixtures
    } catch {
        throw "build-release-receipt.ps1 refused the built bundle once its sources were older again: $($_.Exception.Message)"
    } finally {
        [System.IO.File]::WriteAllBytes($releaseReceipt, $builtReceiptBytes)
    }

    # The 16 KB alignment check, through the zipalign stand-in beside aapt2: a stored library off a
    # 16 KB boundary stops the receipt and is named, and a zipalign too old for -P, which names no
    # entry, stops it with its own words rather than an empty list of misses.
    foreach ($alignment in @(
            @{ Mode = 'bad'; Pattern = '*not 16 KB aligned (zipalign exit 1)*libstub.so (BAD - 4096)*' },
            @{ Mode = 'old'; Pattern = "*zipalign exited 1*without naming an entry*the 16 KB check didn't run*Unknown option*" })) {
        $env:HUSHFACEBOOK_CONTRACT_ZIPALIGN = $alignment.Mode
        try {
            Assert-Throws { Invoke-ReceiptBuilder -Fixtures @($allFixtures | Select-Object -First 1) } $alignment.Pattern `
                "build-release-receipt.ps1 went ahead past a zipalign that answered '$($alignment.Mode)'."
        } finally {
            Remove-Item Env:\HUSHFACEBOOK_CONTRACT_ZIPALIGN -ErrorAction SilentlyContinue
            [System.IO.File]::WriteAllBytes($releaseReceipt, $builtReceiptBytes)
        }
    }

    # What the libraries' own ELF headers say, which zipalign above can't. A library whose ZIP
    # offset and LOAD segments line up and whose RELRO ends off a 16 KB boundary used to be
    # recorded as aligned, because zipalign was the only thing asked. The receipt holds the three
    # answers apart now, and its verdict passes only when all of them do. The stand-in APK is
    # written byte for byte, so an entry is stored or deflated because this says so:
    # System.IO.Compression picks the method itself, and not the same way on every runtime.
    function New-TestElf([long[]]$LoadAlign = @(16384, 16384), [long]$RelroEnd = -1, [switch]$Narrow) {
        $count = $LoadAlign.Length + $(if ($RelroEnd -ge 0) { 1 } else { 0 })
        $memory = New-Object System.IO.MemoryStream
        $writer = New-Object System.IO.BinaryWriter($memory)
        $writer.Write([byte[]](0x7F, 0x45, 0x4C, 0x46, $(if ($Narrow) { 1 } else { 2 }), 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        $writer.Write([uint16]3); $writer.Write([uint16]$(if ($Narrow) { 40 } else { 183 })); $writer.Write([uint32]1)
        if ($Narrow) {
            # Entry, program header offset, section header offset, flags, then the three sizes and counts.
            $writer.Write([uint32]0); $writer.Write([uint32]52); $writer.Write([uint32]0); $writer.Write([uint32]0)
            $writer.Write([uint16]52); $writer.Write([uint16]32); $writer.Write([uint16]$count)
            $writer.Write([uint16]0); $writer.Write([uint16]0); $writer.Write([uint16]0)
            # Type, offset, address, physical address, file size, memory size, flags, alignment.
            foreach ($align in $LoadAlign) {
                foreach ($value in @(1, 0, 0, 0, 4096, 4096, 5, $align)) { $writer.Write([uint32]$value) }
            }
            if ($RelroEnd -ge 0) {
                foreach ($value in @(0x6474E552, 0, ($RelroEnd - 4096), 0, 4096, 4096, 4, 1)) { $writer.Write([uint32]$value) }
            }
        } else {
            $writer.Write([uint64]0); $writer.Write([uint64]64); $writer.Write([uint64]0); $writer.Write([uint32]0)
            $writer.Write([uint16]64); $writer.Write([uint16]56); $writer.Write([uint16]$count)
            $writer.Write([uint16]0); $writer.Write([uint16]0); $writer.Write([uint16]0)
            # Type and flags, then offset, address, physical address, file size, memory size, alignment.
            foreach ($align in $LoadAlign) {
                $writer.Write([uint32]1); $writer.Write([uint32]5)
                foreach ($value in @(0, 0, 0, 4096, 4096, $align)) { $writer.Write([uint64]$value) }
            }
            if ($RelroEnd -ge 0) {
                $writer.Write([uint32]0x6474E552); $writer.Write([uint32]4)
                foreach ($value in @(0, ($RelroEnd - 4096), 0, 4096, 4096, 1)) { $writer.Write([uint64]$value) }
            }
        }
        $writer.Flush()
        return ,$memory.ToArray()
    }
    function New-TestLibraryApk([string]$Path, [object[]]$Entries) {
        $crcTable = New-Object 'long[]' 256
        for ($n = 0; $n -lt 256; $n++) {
            $value = [long]$n
            for ($bit = 0; $bit -lt 8; $bit++) {
                $value = if ($value -band 1) { 3988292384L -bxor ($value -shr 1) } else { $value -shr 1 }
            }
            $crcTable[$n] = $value
        }
        $memory = New-Object System.IO.MemoryStream
        $writer = New-Object System.IO.BinaryWriter($memory)
        $directory = New-Object System.IO.MemoryStream
        $index = New-Object System.IO.BinaryWriter($directory)
        foreach ($entry in $Entries) {
            [byte[]]$plain = $entry.Bytes
            $crc = 4294967295L
            foreach ($byte in $plain) { $crc = $crcTable[[int](($crc -bxor $byte) -band 255)] -bxor ($crc -shr 8) }
            $crc = $crc -bxor 4294967295L
            [byte[]]$data = $plain
            if (-not $entry.Stored) {
                $packed = New-Object System.IO.MemoryStream
                $deflate = New-Object System.IO.Compression.DeflateStream($packed, [System.IO.Compression.CompressionMode]::Compress, $true)
                $deflate.Write($plain, 0, $plain.Length)
                $deflate.Dispose()
                $data = $packed.ToArray()
            }
            [byte[]]$name = [System.Text.Encoding]::UTF8.GetBytes([string]$entry.Name)
            $method = if ($entry.Stored) { 0 } else { 8 }
            $at = $memory.Position
            # The local header: version, flags, method, time, 1980-01-01, CRC, both sizes, name length.
            $writer.Write([uint32]0x04034B50); $writer.Write([uint16]20); $writer.Write([uint16]0); $writer.Write([uint16]$method)
            $writer.Write([uint16]0); $writer.Write([uint16]33)
            $writer.Write([uint32]$crc); $writer.Write([uint32]$data.Length); $writer.Write([uint32]$plain.Length)
            $writer.Write([uint16]$name.Length); $writer.Write([uint16]0)
            $writer.Write($name); $writer.Write($data)
            # Its directory record repeats those and adds where the local header is.
            $index.Write([uint32]0x02014B50); $index.Write([uint16]20); $index.Write([uint16]20); $index.Write([uint16]0)
            $index.Write([uint16]$method); $index.Write([uint16]0); $index.Write([uint16]33)
            $index.Write([uint32]$crc); $index.Write([uint32]$data.Length); $index.Write([uint32]$plain.Length)
            $index.Write([uint16]$name.Length); $index.Write([uint16]0); $index.Write([uint16]0)
            $index.Write([uint16]0); $index.Write([uint16]0); $index.Write([uint32]0); $index.Write([uint32]$at)
            $index.Write($name)
        }
        $index.Flush()
        $directoryAt = $memory.Position
        $writer.Write($directory.ToArray())
        $writer.Write([uint32]0x06054B50); $writer.Write([uint16]0); $writer.Write([uint16]0)
        $writer.Write([uint16]$Entries.Count); $writer.Write([uint16]$Entries.Count)
        $writer.Write([uint32]$directory.Length); $writer.Write([uint32]$directoryAt); $writer.Write([uint16]0)
        $writer.Flush()
        [System.IO.File]::WriteAllBytes($Path, $memory.ToArray())
    }
    $apkStandIn = Join-Path $tools 'patched.apk'
    $apkStandInBytes = [System.IO.File]::ReadAllBytes($apkStandIn)
    $apkStandInEntries = @(
        @{ Name = 'AndroidManifest.xml'; Bytes = [System.Text.Encoding]::ASCII.GetBytes('binary manifest'); Stored = $false },
        @{ Name = 'classes.dex'; Bytes = [System.Text.Encoding]::ASCII.GetBytes("dex`n035"); Stored = $false })
    $alignedElf = New-TestElf -RelroEnd 32768
    function Get-BuiltNativeAlignment([object[]]$Libraries) {
        New-TestLibraryApk -Path $apkStandIn -Entries ($apkStandInEntries + $Libraries)
        try {
            Invoke-ReceiptBuilder -Fixtures $allFixtures
        } catch {
            throw "build-release-receipt.ps1 refused a patched APK over what its libraries' ELF headers say: $($_.Exception.Message)"
        }
        return (Get-Content -LiteralPath $releaseReceipt -Raw | ConvertFrom-Json).targets[0].nativeAlignment
    }
    try {
        # One stored and aligned in every way, one deflated whose RELRO ends 4 KB past a boundary.
        $native = Get-BuiltNativeAlignment @(
            @{ Name = 'lib/arm64-v8a/libgood.so'; Bytes = $alignedElf; Stored = $true },
            @{ Name = 'lib/arm64-v8a/librelro.so'; Bytes = (New-TestElf -RelroEnd 36864); Stored = $false })
        Assert-True ($native.zipOffsets.aligned -eq $true -and $native.zipOffsets.stored -eq 1 -and $native.zipOffsets.compressed -eq 1) `
            "The receipt didn't count one stored and one compressed library at aligned ZIP offsets: $($native.zipOffsets | ConvertTo-Json -Compress)"
        Assert-True ($native.loadSegments.aligned -eq $true -and $native.loadSegments.libraries -eq 2 -and $native.loadSegments.segments -eq 4) `
            "The receipt didn't record four aligned LOAD segments in two libraries: $($native.loadSegments | ConvertTo-Json -Compress)"
        Assert-True ($native.relroEnds.aligned -eq $false -and $native.relroEnds.present -eq 2 -and
            (@($native.relroEnds.misaligned) -join ',') -ceq 'lib/arm64-v8a/librelro.so') `
            "The receipt didn't name the one library whose RELRO ends off a 16 KB boundary: $($native.relroEnds | ConvertTo-Json -Compress)"
        Assert-True ($native.staticChecksPass -eq $false) `
            'A library whose RELRO ends off a 16 KB boundary got a passing 16 KB verdict from its ZIP offset and LOAD segments.'
        $relroRecord = @($native.libraries | Where-Object { $_.entry -ceq 'lib/arm64-v8a/librelro.so' })[0]
        Assert-True ($relroRecord.relro -ceq 'misaligned' -and $relroRecord.relroEnd -eq 36864 -and $relroRecord.stored -eq $false -and
            $relroRecord.format -ceq 'elf64' -and $relroRecord.sha256 -match '^[0-9A-F]{64}$') `
            "The receipt's record of the misaligned library isn't the library that was patched: $($relroRecord | ConvertTo-Json -Compress)"
        Assert-True ($builderSaid -like '*1 of 2 RELRO ends off 16 KB*not ready for 16 KB pages as built*') `
            "build-release-receipt.ps1 didn't say the libraries aren't ready for 16 KB pages: $builderSaid"

        # Everything a 64-bit library can pass, beside a DEX named .so and a 32-bit library: the
        # only run that passes, with the other two listed and left out of the verdict.
        $native = Get-BuiltNativeAlignment @(
            @{ Name = 'lib/arm64-v8a/libgood.so'; Bytes = $alignedElf; Stored = $true },
            @{ Name = 'lib/arm64-v8a/libplain.dex.so'; Bytes = [System.Text.Encoding]::ASCII.GetBytes("dex`n035 and no library"); Stored = $false },
            @{ Name = 'lib/armeabi-v7a/libold.so'; Bytes = (New-TestElf -LoadAlign @(4096) -Narrow); Stored = $false })
        Assert-True ($native.staticChecksPass -eq $true -and $native.loadSegments.libraries -eq 1 -and $native.relroEnds.present -eq 1) `
            "A library aligned in the ZIP, in its LOAD segments and at its RELRO end didn't pass: $($native | ConvertTo-Json -Depth 4 -Compress)"
        Assert-True ((@($native.notElf) -join ',') -ceq 'lib/arm64-v8a/libplain.dex.so' -and @($native.malformed).Count -eq 0) `
            "A DEX named .so wasn't listed as not ELF: $($native | ConvertTo-Json -Depth 4 -Compress)"
        $oldRecord = @($native.libraries | Where-Object { $_.entry -ceq 'lib/armeabi-v7a/libold.so' })[0]
        Assert-True ($oldRecord.format -ceq 'elf32' -and $null -eq $oldRecord.PSObject.Properties['relro']) `
            "A 32-bit library was judged for 16 KB pages: $($oldRecord | ConvertTo-Json -Compress)"

        # A LOAD segment aligned to 4 KB and no RELRO at all, a file cut off inside its ELF header,
        # a 32-bit ELF in the 64-bit folder and an ELF with nothing to load: each named under its
        # own check, none passed.
        $native = Get-BuiltNativeAlignment @(
            @{ Name = 'lib/arm64-v8a/libload.so'; Bytes = (New-TestElf -LoadAlign @(16384, 4096)); Stored = $false },
            @{ Name = 'lib/arm64-v8a/libcut.so'; Bytes = [byte[]]($alignedElf[0..39]); Stored = $false },
            @{ Name = 'lib/arm64-v8a/libnarrow.so'; Bytes = (New-TestElf -Narrow); Stored = $false },
            @{ Name = 'lib/arm64-v8a/libstub.so'; Bytes = (New-TestElf -LoadAlign @() -RelroEnd 32768); Stored = $false })
        Assert-True ($native.staticChecksPass -eq $false -and $native.loadSegments.aligned -eq $false -and
            (@($native.loadSegments.misaligned) -join ',') -ceq 'lib/arm64-v8a/libload.so') `
            "A LOAD segment aligned to 4 KB wasn't named as off 16 KB: $($native.loadSegments | ConvertTo-Json -Compress)"
        Assert-True ($native.relroEnds.aligned -eq $true -and $native.relroEnds.present -eq 0 -and
            (@($native.relroEnds.absent) -join ',') -ceq 'lib/arm64-v8a/libload.so') `
            "A library with no RELRO wasn't recorded as having none: $($native.relroEnds | ConvertTo-Json -Compress)"
        Assert-True ((@($native.malformed) -join '|') -ceq
            ('lib/arm64-v8a/libcut.so (ends inside the ELF header)|lib/arm64-v8a/libnarrow.so (a 32-bit ELF in a 64-bit folder)|' +
            'lib/arm64-v8a/libstub.so (no loadable segment)')) `
            "The unreadable libraries weren't named with why: $(@($native.malformed) -join '|')"

        # Entry names are case-sensitive in a ZIP: two that differ only by case stay two libraries,
        # each with its own storage, hash and verdict.
        $native = Get-BuiltNativeAlignment @(
            @{ Name = 'lib/x86_64/libPair.so'; Bytes = $alignedElf; Stored = $true },
            @{ Name = 'lib/x86_64/libpair.so'; Bytes = (New-TestElf -RelroEnd 36864); Stored = $false })
        $pair = @($native.libraries)
        Assert-True ($pair.Count -eq 2 -and $pair[0].entry -ceq 'lib/x86_64/libPair.so' -and $pair[0].stored -eq $true -and
            $pair[1].entry -ceq 'lib/x86_64/libpair.so' -and $pair[1].stored -eq $false -and $pair[0].sha256 -cne $pair[1].sha256 -and
            $native.zipOffsets.stored -eq 1 -and $native.zipOffsets.compressed -eq 1 -and
            (@($native.relroEnds.misaligned) -join ',') -ceq 'lib/x86_64/libpair.so') `
            "Two libraries whose names differ only by case weren't kept apart: $($native | ConvertTo-Json -Depth 5 -Compress)"
    } finally {
        [System.IO.File]::WriteAllBytes($apkStandIn, $apkStandInBytes)
        [System.IO.File]::WriteAllBytes($releaseReceipt, $builtReceiptBytes)
    }

    # patch-for-device.ps1 on the same root and stand-ins. It held every run's report to the
    # newest declared build, so an older declared build (README: 577 works too) was patched and
    # then refused with "unexpected package or version". Each declared build is held to its own
    # version now, the default fixture is still the newest build, and an undeclared build is
    # refused before the CLI starts. No -Serial, so nothing goes near adb.
    $deviceOut = Join-Path $releaseRoot 'device'
    # patch-for-device holds the bundle to the input digest it carries, and the receipt runs' bundle
    # carries none, so each device run gets that bundle stamped with this tree's digest (or the one
    # given) and the receipt runs get it back as it was. The receipt cases that need a release's
    # identity pass the clean commit.
    function Use-StampedBundle([scriptblock]$Run, [string]$InputSha256, [string]$Commit) {
        if (-not $InputSha256) { $InputSha256 = Get-ProducerInputSha256 -Root $releaseRepo }
        $source = if ($Commit) {
            "Hushfacebook-Source-State: clean`nHushfacebook-Source-Commit: $Commit`nHushfacebook-Source-Tree: $('a' * 40)`n"
        } else {
            "Hushfacebook-Source-State: unknown`nHushfacebook-Source-Commit: unknown`nHushfacebook-Source-Tree: unknown`n"
        }
        $legacy = [IO.File]::ReadAllBytes($releaseBundle)
        try {
            $zip = [IO.Compression.ZipFile]::Open($releaseBundle, [IO.Compression.ZipArchiveMode]::Update)
            try {
                $manifest = $zip.GetEntry('META-INF/MANIFEST.MF')
                $reader = [IO.StreamReader]::new($manifest.Open())
                try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
                $manifest.Delete()
                $writer = [IO.StreamWriter]::new($zip.CreateEntry('META-INF/MANIFEST.MF').Open())
                try {
                    $writer.Write($text.TrimEnd("`n") + "`n$source" + "Hushfacebook-Input-SHA256: $InputSha256`n`n")
                } finally { $writer.Dispose() }
            } finally { $zip.Dispose() }
            $zip = [IO.Compression.ZipFile]::OpenRead($releaseBundle)
            try { $payload = Get-BundlePayloadSha256 -Archive $zip } finally { $zip.Dispose() }
            $zip = [IO.Compression.ZipFile]::Open($releaseBundle, [IO.Compression.ZipArchiveMode]::Update)
            try {
                $writer = [IO.StreamWriter]::new($zip.CreateEntry('META-INF/hushfacebook-build.identity').Open())
                try { $writer.Write("hushfacebook-bundle-1`n$payload`n") } finally { $writer.Dispose() }
            } finally { $zip.Dispose() }
            & $Run
        } finally { [IO.File]::WriteAllBytes($releaseBundle, $legacy) }
    }
    function Invoke-DeviceBuild([string]$Apk, [string]$OutDir = $deviceOut, [string]$DesktopJar = $stubJar,
        [string]$InputSha256, [switch]$AllowStaleBundle) {
        Remove-Item -LiteralPath $javaLog -Force -ErrorAction SilentlyContinue
        $arguments = @{ Root = $releaseRepo; DesktopJar = $DesktopJar; Java = $stubJava; Aapt2 = $stubAapt2; OutDir = $OutDir }
        if ($Apk) { $arguments['Apk'] = $Apk }
        if ($AllowStaleBundle) { $arguments['AllowStaleBundle'] = $true }
        Use-StampedBundle -InputSha256 $InputSha256 -Run { & (Join-Path $PSScriptRoot 'patch-for-device.ps1') @arguments 6> $null }
    }
    $deviceApk = Join-Path $deviceOut "hushfacebook-$releaseVersionHere-signed.apk"
    foreach ($build in $releaseTarget.PackageVersions) {
        $null = @(Read-QueueLog)
        try {
            Invoke-DeviceBuild -Apk $fixturePaths[$build]
        } catch {
            throw "patch-for-device.ps1 refused the declared build ${build}: $($_.Exception.Message)"
        }
        $deviceRuns = @(Get-Content -LiteralPath $javaLog)
        Assert-True ($deviceRuns.Count -eq 1 -and $deviceRuns[0] -eq "patch $($fixturePaths[$build]) forced=0" -and
            (Test-Path -LiteralPath $deviceApk -PathType Leaf)) `
            "patch-for-device.ps1 did not build $build once, without -f: $($deviceRuns -join '; ')"
        $seen = @(Read-QueueLog)
        Assert-True (($seen -join '|') -eq "hushfacebook device $(Split-Path -Leaf $fixturePaths[$build])") `
            "patch-for-device.ps1 did not run the CLI as one job of the build queue: $($seen -join ', ')"
        Assert-True (-not (Test-Path -LiteralPath (Join-Path $deviceOut 'stock-base.apk'))) `
            "patch-for-device.ps1 left the base APK it read for $build behind."
    }
    # A second invocation can arrive while the first waits for the build queue. It must
    # neither overwrite the first argument file nor remove its temporary/output files.
    $overlapQueue = Join-Path $tools 'overlap-queue.ps1'
    $overlapResult = Join-Path $tools 'overlap-result.txt'
    $overlapArguments = @{
        Root = $releaseRepo; DesktopJar = $stubJar; Java = $stubJava; Aapt2 = $stubAapt2;
        OutDir = $deviceOut; Apk = $fixturePaths[$releaseTarget.PackageVersion]
    } | ConvertTo-Json -Compress
    $overlapBody = @'
function Invoke-InBuildQueue {
    param([string]$Label, [scriptblock]$ScriptBlock)
    if (-not $global:hushDeviceOverlapStarted) {
        $global:hushDeviceOverlapStarted = $true
        $parsed = 'OVERLAP_ARGUMENTS' | ConvertFrom-Json
        $second = @{}
        foreach ($property in $parsed.PSObject.Properties) { $second[$property.Name] = $property.Value }
        try {
            & 'DEVICE_SCRIPT' @second 6> $null
            [IO.File]::WriteAllText('OVERLAP_RESULT', 'second ran')
        } catch { [IO.File]::WriteAllText('OVERLAP_RESULT', $_.Exception.Message) }
    }
    & $ScriptBlock | Out-Host
    return $LASTEXITCODE
}
'@
    $overlapBody = $overlapBody.Replace('OVERLAP_ARGUMENTS', $overlapArguments.Replace("'", "''")).
        Replace('DEVICE_SCRIPT', (Join-Path $PSScriptRoot 'patch-for-device.ps1').Replace("'", "''")).
        Replace('OVERLAP_RESULT', $overlapResult.Replace("'", "''"))
    [IO.File]::WriteAllText($overlapQueue, $overlapBody)
    $savedDeviceQueue = $env:BUILD_QUEUE_SCRIPT
    try {
        $env:BUILD_QUEUE_SCRIPT = $overlapQueue
        $global:hushDeviceOverlapStarted = $false
        Invoke-DeviceBuild -Apk $fixturePaths[$releaseTarget.PackageVersion]
        Assert-True ((Get-Content -LiteralPath $overlapResult -Raw) -like '*already using the output directory*') `
            'An overlapping device build was allowed to replace the active run.'
        Assert-True (@(Get-Content -LiteralPath $javaLog).Count -eq 1 -and (Test-Path -LiteralPath $deviceApk)) `
            'The original device build lost its argument file or output to an overlapping run.'
    } finally {
        $env:BUILD_QUEUE_SCRIPT = $savedDeviceQueue
        Remove-Variable -Name hushDeviceOverlapStarted -Scope Global -ErrorAction SilentlyContinue
    }
    $savedFixtureDir = $env:HUSHFACEBOOK_FIXTURE_DIR
    try {
        $env:HUSHFACEBOOK_FIXTURE_DIR = $fixtures
        Invoke-DeviceBuild
        Assert-True ((@(Get-Content -LiteralPath $javaLog) -join '; ') -eq
            "patch $($fixturePaths[$releaseTarget.PackageVersion]) forced=0") `
            "With no -Apk, patch-for-device.ps1 did not take the newest declared build from the fixture folder."
    } finally {
        $env:HUSHFACEBOOK_FIXTURE_DIR = $savedFixtureDir
    }
    Assert-Throws { Invoke-DeviceBuild -Apk $fixturePaths[$newerBuild] } "*$newerBuild, which the bundle does not declare*" `
        'patch-for-device.ps1 took a build the catalog does not declare.'
    Assert-True (-not (Test-Path -LiteralPath $javaLog)) 'patch-for-device.ps1 started the CLI on an undeclared build.'
    Assert-Throws { Invoke-DeviceBuild -Apk $variantApkm } "*$variantBuild, which the bundle does not declare, at version code $variantCode*" `
        'patch-for-device.ps1 took another build of a declared version.'
    Assert-True (-not (Test-Path -LiteralPath $javaLog)) 'patch-for-device.ps1 started the CLI on another build of a declared version.'
    # -PatchOptions only takes key=value options for a patch this build has, checked before the CLI starts.
    $firstPatchHere = @((Get-Content -LiteralPath (Join-Path $releaseRepo 'patches-list.json') -Raw | ConvertFrom-Json).patches)[0].name
    foreach ($badOptions in @(
            @{ Options = @{ 'No such patch' = @('appName=HushBook') }; Pattern = "*No patch named 'No such patch' in this build to give options to*" },
            @{ Options = @{ $firstPatchHere = @('no equals sign') }; Pattern = "*The option 'no equals sign' for*isn't key=value*" })) {
        Remove-Item -LiteralPath $javaLog -Force -ErrorAction SilentlyContinue
        $beforeInvalidOptions = Get-Sha256Hex -Path $deviceApk
        Assert-Throws {
            Use-StampedBundle {
                & (Join-Path $PSScriptRoot 'patch-for-device.ps1') -Root $releaseRepo -DesktopJar $stubJar -Java $stubJava `
                    -Aapt2 $stubAapt2 -OutDir $deviceOut -Apk $fixturePaths[$releaseTarget.PackageVersion] `
                    -PatchOptions $badOptions.Options 6> $null
            }
        } $badOptions.Pattern 'patch-for-device.ps1 took patch options it should have refused.'
        Assert-True (-not (Test-Path -LiteralPath $javaLog)) 'patch-for-device.ps1 started the CLI with patch options it should have refused.'
        Assert-True ((Test-Path -LiteralPath $deviceApk) -and (Get-Sha256Hex -Path $deviceApk) -ceq $beforeInvalidOptions) `
            'Invalid patch options deleted the previous signed device build.'
    }
    # An option goes after its own patch's -e. The CLI gives an -O to the -e before it, so one put
    # in front landed on the patch listed before, and App name never reached Hushfacebook settings.
    $optionedHere = @((Get-Content -LiteralPath (Join-Path $releaseRepo 'patches-list.json') -Raw | ConvertFrom-Json).patches)[1].name
    $optionsLog = Join-Path $tools 'options.log'
    Remove-Item -LiteralPath $javaLog, $optionsLog -Force -ErrorAction SilentlyContinue
    Use-StampedBundle {
        & (Join-Path $PSScriptRoot 'patch-for-device.ps1') -Root $releaseRepo -DesktopJar $stubJar -Java $stubJava `
            -Aapt2 $stubAapt2 -OutDir $deviceOut -Apk $fixturePaths[$releaseTarget.PackageVersion] `
            -PatchOptions @{ $optionedHere = @('appName=HushBook') } 6> $null
    }
    $null = @(Read-QueueLog)
    $optionsSeen =@(Get-Content -LiteralPath $optionsLog -ErrorAction SilentlyContinue) -join '; '
    Assert-True ($optionsSeen -eq "$optionedHere appName=HushBook") `
        "patch-for-device.ps1 gave the option to the wrong patch: '$optionsSeen'"

    # A source edited behind an older file time, which the file-time check let through: refused
    # before the CLI starts, and -AllowStaleBundle patches anyway and says so.
    $builtInputs = Get-ProducerInputSha256 -Root $releaseRepo
    $catalogBytes = [IO.File]::ReadAllBytes($touchedCatalog)
    $catalogTime = [IO.File]::GetLastWriteTimeUtc($touchedCatalog)
    try {
        [IO.File]::AppendAllText($touchedCatalog, "`n# edited`n")
        [IO.File]::SetLastWriteTimeUtc($touchedCatalog, $catalogTime.AddDays(-1))
        Assert-Throws { Invoke-DeviceBuild -Apk $fixturePaths[$releaseTarget.PackageVersion] -InputSha256 $builtInputs } `
            "*doesn't match this checkout: it was built from other sources (input digest $builtInputs*" `
            'patch-for-device.ps1 patched with a bundle built before a backdated edit.'
        Assert-True (-not (Test-Path -LiteralPath $javaLog)) 'patch-for-device.ps1 started the CLI with a stale bundle.'
        $warned = @(Invoke-DeviceBuild -Apk $fixturePaths[$releaseTarget.PackageVersion] -InputSha256 $builtInputs `
            -AllowStaleBundle 3>&1 | ForEach-Object { "$_" }) -join "`n"
        Assert-True (@(Get-Content -LiteralPath $javaLog -ErrorAction SilentlyContinue).Count -eq 1 -and
            $warned -like '*-AllowStaleBundle: patching with*built from other sources*' -and
            $warned -like '*built with -AllowStaleBundle from a bundle that doesn''t match this checkout*') `
            "patch-for-device.ps1 -AllowStaleBundle didn't patch, or didn't say the bundle was stale: $warned"
        $null = @(Read-QueueLog)
    } finally {
        [IO.File]::WriteAllBytes($touchedCatalog, $catalogBytes)
        [IO.File]::SetLastWriteTimeUtc($touchedCatalog, $catalogTime)
    }

    # The receipt builder holds a bundle that carries its input digest to the checkout the same way.
    # Its clean-tree check already sees an edit still there, so the case is a bundle built while the
    # tree held an edit, or an input, that's gone again by the receipt run: the tree is clean, the
    # stamp right and no file newer than the bundle. Both are refused before any fixture is patched,
    # and the same tree, stamped as the clean release commit, writes its receipt.
    $removedInput = Join-Path $releaseRepo 'gradle/wrapper/removed-input.txt'
    New-Item -ItemType Directory -Path (Split-Path -Parent $removedInput) -Force | Out-Null
    [IO.File]::WriteAllText($removedInput, 'x', [Text.Encoding]::ASCII)
    $withRemoved = Get-ProducerInputSha256 -Root $releaseRepo
    Remove-Item -LiteralPath $removedInput
    [IO.File]::AppendAllText($touchedCatalog, "`n# edited`n")
    $withEdit = Get-ProducerInputSha256 -Root $releaseRepo
    [IO.File]::WriteAllBytes($touchedCatalog, $catalogBytes)
    [IO.File]::SetLastWriteTimeUtc($touchedCatalog, $catalogTime)
    $builtInputs = Get-ProducerInputSha256 -Root $releaseRepo
    $releaseSbomBytes = [IO.File]::ReadAllBytes($releaseSbom)
    $releaseToolingBytes = [IO.File]::ReadAllBytes($releaseTooling)
    $releaseReceiptBytes = [IO.File]::ReadAllBytes($releaseReceipt)
    try {
        foreach ($since in @(@{ Name = 'an input deleted since'; Inputs = $withRemoved }, @{ Name = 'an edit put back since'; Inputs = $withEdit })) {
            Use-StampedBundle -InputSha256 $since.Inputs -Commit $releaseCommit -Run {
                Assert-Throws { Invoke-ReceiptBuilder -Fixtures $allFixtures } `
                    "*doesn't match this checkout: it was built from other sources*Nothing was patched*" `
                    "build-release-receipt.ps1 went ahead with a bundle built with $($since.Name)."
                Assert-True (-not (Test-Path -LiteralPath $javaLog)) "build-release-receipt.ps1 patched with a bundle built with $($since.Name)."
            }
        }
        Use-StampedBundle -InputSha256 $builtInputs -Commit $releaseCommit -Run {
            New-TestSbom -Path $releaseSbom -Bundle $releaseBundle
            New-TestTooling -Path $releaseTooling -Bundle $releaseBundle -Commit $releaseCommit -ScopeText $releaseScopeText
            try {
                Invoke-ReceiptBuilder -Fixtures $allFixtures
            } catch {
                throw "build-release-receipt.ps1 refused a bundle stamped with this tree's inputs: $($_.Exception.Message)"
            }

            # The publish stage's check takes that release staged, its bundle naming the clean commit
            # and this tree's inputs. That's the control for the cases after it.
            $exclude = Join-Path $releaseRepo '.git/info/exclude'
            $excludeBefore = if (Test-Path -LiteralPath $exclude -PathType Leaf) { [IO.File]::ReadAllText($exclude) } else { $null }
            $ignoredInput = Join-Path $releaseRepo 'gradle/ignored-input.txt'
            try {
                Save-StagedHere
                $answer = Test-StagedHere
                Assert-True $answer.Valid "The publish stage refused a staged bundle built from its clean commit and this tree: $($answer.Reason)"
                # An input git doesn't see, as an ignored file in an input folder is: HEAD is the commit
                # and the tree clean, and the bundle still isn't a build of what the checkout holds.
                New-Item -ItemType Directory -Path (Split-Path -Parent $exclude) -Force | Out-Null
                [IO.File]::AppendAllText($exclude, "`n/gradle/ignored-input.txt`n")
                [IO.File]::WriteAllText($ignoredInput, 'x', [Text.Encoding]::ASCII)
                Assert-True (@(Invoke-FixtureGit -Root $releaseRepo -Arguments @('status', '--porcelain')).Count -eq 0) `
                    'git saw the input the case hides from it, so the case proves nothing.'
                $answer = Test-StagedHere
                Assert-True (-not $answer.Valid -and $answer.Reason -like "*isn't a build of commit*built from other sources*") `
                    "The publish stage took a bundle the checkout's inputs don't match: $($answer.Reason)"
                # Seen by git, the same file stops the check before the inputs are compared.
                if ($null -eq $excludeBefore) { Remove-Item -LiteralPath $exclude -Force } else { [IO.File]::WriteAllText($exclude, $excludeBefore) }
                $answer = Test-StagedHere
                Assert-True (-not $answer.Valid -and $answer.Reason -like "*isn't commit * with nothing changed*") `
                    "The publish stage held a bundle to a checkout with changes in it: $($answer.Reason)"
            } finally {
                Remove-Item -LiteralPath $ignoredInput -Force -ErrorAction SilentlyContinue
                if ($null -eq $excludeBefore) { Remove-Item -LiteralPath $exclude -Force -ErrorAction SilentlyContinue }
                else { [IO.File]::WriteAllText($exclude, $excludeBefore) }
                Remove-Item -LiteralPath $stagedHere -Recurse -Force -ErrorAction SilentlyContinue
            }

            # The same bundle with its identity taken out, and the SBOM, tooling report, receipt and
            # checksums made again to match, as whoever can write every staged file could. The folder
            # holds together and passed as a bundle from before identities. It's refused before the tag.
            $zip = [IO.Compression.ZipFile]::Open($releaseBundle, [IO.Compression.ZipArchiveMode]::Update)
            try {
                $zip.GetEntry('META-INF/hushfacebook-build.identity').Delete()
                $manifest = $zip.GetEntry('META-INF/MANIFEST.MF')
                $reader = [IO.StreamReader]::new($manifest.Open())
                try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
                $manifest.Delete()
                $writer = [IO.StreamWriter]::new($zip.CreateEntry('META-INF/MANIFEST.MF').Open())
                try { $writer.Write((@($text -split "`n" | Where-Object { $_ -notlike 'Hushfacebook-*' }) -join "`n")) } finally { $writer.Dispose() }
            } finally { $zip.Dispose() }
            Assert-True (-not (Get-BundleIdentityFacts -BundlePath $releaseBundle).Present) 'The stripped bundle still shows a build identity.'
            New-TestSbom -Path $releaseSbom -Bundle $releaseBundle
            New-TestTooling -Path $releaseTooling -Bundle $releaseBundle -Commit $releaseCommit -ScopeText $releaseScopeText
            Invoke-ReceiptBuilder -Fixtures $allFixtures
            try {
                Save-StagedHere
                $answer = Test-StagedHere
                Assert-True (-not $answer.Valid -and $answer.Reason -like '*carries no intact build identity*') `
                    "The publish stage took a staged bundle stripped of its identity: $($answer.Reason)"
            } finally {
                Remove-Item -LiteralPath $stagedHere -Recurse -Force -ErrorAction SilentlyContinue
            }
        }
    } finally {
        [IO.File]::WriteAllBytes($touchedCatalog, $catalogBytes)
        [IO.File]::SetLastWriteTimeUtc($touchedCatalog, $catalogTime)
        [IO.File]::WriteAllBytes($releaseSbom, $releaseSbomBytes)
        [IO.File]::WriteAllBytes($releaseTooling, $releaseToolingBytes)
        [IO.File]::WriteAllBytes($releaseReceipt, $releaseReceiptBytes)
    }

    # Another Meta app at a build the catalog declares. Neither script may hand it to the CLI: each
    # refuses it by name before anything is patched. The builder gets it beside the newest declared
    # build, so every declared build has a fixture and only the package check stands in the way.
    $otherPackage = 'com.facebook.lite'
    $otherApkm = Join-Path $fixtures "facebook-lite-$($unproved[0])-arm64-v8a.apkm"
    New-TestBundleArchive -Path $otherApkm -Entries ([ordered]@{
        'info.json' = "{`"versioncode`":`"$versionCode`"}"
        'base.apk' = Get-FixtureManifest -Build $unproved[0] -Code "$versionCode" -Package $otherPackage })
    $otherRefusal = "*$(Split-Path -Leaf $otherApkm) is $otherPackage, not the catalog's target $($releaseTarget.PackageName)*"
    Assert-Throws { Invoke-ReceiptBuilder -Fixtures @($fixturePaths[$releaseTarget.PackageVersion], $otherApkm) } $otherRefusal `
        'build-release-receipt.ps1 took a fixture of another package.'
    Assert-True (-not (Test-Path -LiteralPath $javaLog)) 'build-release-receipt.ps1 started the CLI on another package.'
    Assert-Throws { Invoke-DeviceBuild -Apk $otherApkm } $otherRefusal 'patch-for-device.ps1 took another package.'
    Assert-True (-not (Test-Path -LiteralPath $javaLog)) 'patch-for-device.ps1 started the CLI on another package.'

    # The builder, the release check and the device build given relative paths, from a location
    # the process directory isn't. Each found its file with Test-Path, in PowerShell's location,
    # and then opened or wrote it with .NET, which reads the process directory: the builder died
    # on a relative -Bundle with "Could not find file", and the release check on -ArtifactPath.
    $elsewhere = Join-Path $releaseRoot 'elsewhere'
    New-Item -ItemType Directory -Path $elsewhere -Force | Out-Null
    $inRoot = { param([string]$Path) $Path.Substring($releaseRoot.TrimEnd('\').Length + 1) }
    $savedProcessDirectory = [Environment]::CurrentDirectory
    Push-Location -LiteralPath $releaseRoot
    try {
        [Environment]::CurrentDirectory = $elsewhere
        try {
            Invoke-ReceiptBuilder -Fixtures @($allFixtures | ForEach-Object { & $inRoot $_ }) `
                -Bundle (& $inRoot $releaseBundle) -WorkDir 'work-relative' -DesktopJar (& $inRoot $stubJar)
        } catch {
            throw "build-release-receipt.ps1 refused relative paths: $($_.Exception.Message)"
        }
        $global:LASTEXITCODE = 0
        & $factsScript -Root $releaseRepo -SkipDescriptionTestCount -AllowPublishedIndexLag -SkipUrlCheck `
            -ArtifactPath (& $inRoot $releaseBundle) 6> $null
        Assert-True ($LASTEXITCODE -eq 0) "The release check exited $LASTEXITCODE on a relative -ArtifactPath."
        try {
            Invoke-DeviceBuild -Apk (& $inRoot $fixturePaths[$releaseTarget.PackageVersion]) -OutDir 'device-relative' `
                -DesktopJar (& $inRoot $stubJar)
        } catch {
            throw "patch-for-device.ps1 refused relative paths: $($_.Exception.Message)"
        }
        Assert-True (Test-Path -LiteralPath (Join-Path $releaseRoot "device-relative\hushfacebook-$releaseVersionHere-signed.apk")) `
            'patch-for-device.ps1 given a relative -OutDir left no APK there.'
        Assert-True (@(Get-ChildItem -LiteralPath $elsewhere -Recurse -Force).Count -eq 0) `
            ("A relative path was written in the process directory instead: " +
                ((Get-ChildItem -LiteralPath $elsewhere -Recurse -Force | Select-Object -First 3 | ForEach-Object { $_.Name }) -join ', '))
    } finally {
        [Environment]::CurrentDirectory = $savedProcessDirectory
        Pop-Location
    }

    # The index push. `gh release create` makes the release tag on GitHub only, so the clone that
    # pushes the new description doesn't have it, and the floor check printed that it hadn't run
    # and let the push through. The hook's published asset run reads the tag's commit off the
    # remote and holds the bundle to it, so the floor is read at that commit as well; a run with no
    # bundle asks the repository the index names. A bare repository answers for GitHub through the
    # fixture's url.insteadOf, and stand-ins serve the download, its checksum list, the repository
    # description and the CLI's patch listing, so nothing leaves the machine.
    $indexUrl = [Uri]($releaseIndexText | ConvertFrom-Json).download_url
    $indexRepository = 'https://github.com/' + ((@($indexUrl.AbsolutePath.Trim('/') -split '/') | Select-Object -First 2) -join '/') + '.git'
    $publishedRepo = (Join-Path $releaseRoot 'published.git').Replace('\', '/')
    Invoke-FixtureGit -Root $releaseRoot -Arguments @('init', '--bare', '--quiet', $publishedRepo) | Out-Null
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('config', "url.$publishedRepo.insteadOf", $indexRepository) | Out-Null
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('push', '--quiet', $publishedRepo, "refs/tags/v$indexVersionHere") | Out-Null
    Set-Content -LiteralPath (Join-Path $tools 'patch-names.txt') -Encoding ASCII -Value @($releaseNames | ForEach-Object { "Name: $_" })
    $listJava = Join-Path $tools 'list-java.cmd'
    [System.IO.File]::WriteAllText($listJava, ((@(
        '@echo off',
        'setlocal EnableExtensions',
        'rem The release check runs -jar <cli> list-patches ... --out=<file>, and cmd splits that at the',
        'rem equals sign unless the path has a space in it.',
        'set "HERE=%~dp0"',
        'set "LISTING="',
        'if "%~1"=="-version" (',
        '    echo openjdk version "21.0.5" 2024-10-15',
        '    exit /b 0',
        ')',
        ':next',
        'if "%~1"=="" exit /b 2',
        'set "ARG=%~1"',
        'if "%ARG%"=="--out" set "LISTING=%~2"',
        'if "%ARG:~0,6%"=="--out=" set "LISTING=%ARG:~6%"',
        'if defined LISTING goto list',
        'shift',
        'goto next',
        ':list',
        'echo WARNING: a stand-in warning on standard error 1>&2',
        'copy /y "%HERE%patch-names.txt" "%LISTING%" >nul || exit /b 3',
        'exit /b 0') -join "`r`n") + "`r`n"), [System.Text.Encoding]::ASCII)
    # What GitHub answers: the served bundle, SBOM and receipt, a checksum list naming all three (or
    # $servedSums, when a case wants a wrong one), and the repository description. Dot-sourced into
    # each runner, so the check it starts finds them first. The SBOM served is a copy of the one
    # beside the bundle, which the cases below take away along with the bundle, and the receipt
    # served is the release's own, as the release uploads it, unless a case serves another.
    $servedBundle = $releaseBundle
    $cleanServedSbom = Join-Path $releaseRoot "served\patches-$releaseVersionHere.cdx.json"
    New-Item -ItemType Directory -Path (Split-Path -Parent $cleanServedSbom) -Force | Out-Null
    Copy-Item -LiteralPath $releaseSbom -Destination $cleanServedSbom
    $servedSbom = $cleanServedSbom
    $cleanServedTooling = Join-Path $releaseRoot "served\patches-$releaseVersionHere.tooling.json"
    Copy-Item -LiteralPath $releaseTooling -Destination $cleanServedTooling
    $servedTooling = $cleanServedTooling
    $servedSums = $null
    $servedReceipt = $null
    # Assets a case serves under their own address, ahead of everything above, and one address a
    # case answers 404 for. The publication history cases below put a predecessor release here.
    $catalogPublishedAssets = @{}
    $catalogPublishedRequests = New-Object System.Collections.Generic.List[string]
    $catalogPublishedUnavailable = $null
    $publishedStandIns = {
        function Invoke-WebRequest {
            param($Uri, $Method, $OutFile, $MaximumRedirection, $TimeoutSec, [switch]$PassThru, [switch]$UseBasicParsing)
            # Windows PowerShell 5.1, the hook's shell wherever pwsh is off the PATH, hands a reply
            # without -UseBasicParsing to the IE parser, and since its 2025 security update it asks
            # first. A hook can't answer, so every index push through it stopped at the download.
            if (-not $UseBasicParsing) { throw "Invoke-WebRequest $Uri without -UseBasicParsing" }
            $uriText = [string]$Uri
            $catalogPublishedRequests.Add($uriText)
            if ($catalogPublishedUnavailable -ceq $uriText) { throw (New-NotFoundAnswer) }
            if ($catalogPublishedAssets.ContainsKey($uriText)) {
                $body = [IO.File]::ReadAllBytes($catalogPublishedAssets[$uriText])
            } else {
                $receiptServed = if ($servedReceipt) { $servedReceipt } else { $releaseReceipt }
                $sums = if ($servedSums) { $servedSums } else {
                    "$((Get-FileHash -LiteralPath $servedBundle -Algorithm SHA256).Hash.ToLowerInvariant())  patches-$indexVersionHere.mpp`n" +
                        "$((Get-FileHash -LiteralPath $servedSbom -Algorithm SHA256).Hash.ToLowerInvariant())  patches-$indexVersionHere.cdx.json`n" +
                        "$(Get-Sha256Hex -Path $servedTooling)  patches-$indexVersionHere.tooling.json`n" +
                        "$((Get-FileHash -LiteralPath $receiptServed -Algorithm SHA256).Hash.ToLowerInvariant())  release-receipt-$releaseVersionHere.json`n"
                }
                # The checksum list is fetched both ways: into memory by the asset check, and to a
                # file by the patch selection check, which keeps the bytes exactly as served.
                if ($uriText -like '*/SHA256SUMS.txt') { $body = [Text.Encoding]::UTF8.GetBytes($sums) }
                else {
                    $served = if ($uriText -like '*.cdx.json') { $servedSbom } elseif ($uriText -like '*.tooling.json') {
                        $servedTooling } elseif ($uriText -like '*/release-receipt-*.json') {
                        $receiptServed } else { $servedBundle }
                    $body = [IO.File]::ReadAllBytes($served)
                }
            }
            if ($OutFile) { [IO.File]::WriteAllBytes($OutFile, $body) }
            [pscustomobject]@{ StatusCode = 200; Content = $body }
        }
        function gh {
            $global:LASTEXITCODE = 0
            "Hushfacebook v${indexVersionHere}: $($releaseNames.Count) patches for Facebook $($releaseTarget.PackageVersion)."
        }
    }
    function Invoke-IndexPushCheck([hashtable]$Arguments) {
        $saved = @{}
        foreach ($variable in @(Get-ChildItem Env: | Where-Object { $_.Name -like 'GIT_*' })) {
            $saved[$variable.Name] = $variable.Value
            Remove-Item -LiteralPath ('Env:\' + $variable.Name)
        }
        # The asset check asks git for the published tag from where it runs, not through -Root.
        Push-Location -LiteralPath $releaseRepo
        try {
            . $publishedStandIns
            . $osvStandIn
            $global:LASTEXITCODE = 0
            $said = @(& $factsScript -Root $releaseRepo @Arguments 3>&1 6>&1 | ForEach-Object { "$_" }) -join "`n"
            if ($LASTEXITCODE -ne 0) { throw "The release check exited $LASTEXITCODE on the index push: $said" }
            return $said
        } finally {
            Pop-Location
            foreach ($name in $saved.Keys) { Set-Item -LiteralPath ('Env:\' + $name) -Value $saved[$name] }
        }
    }
    $publishedRun = @{ VerifyPublishedAsset = $true; ArtifactPath = $releaseBundle; SkipDescriptionTestCount = $true
        SkipUrlCheck = $true; DesktopJar = $stubJar; Java = $listJava }
    try {
        Invoke-FixtureGit -Root $releaseRepo -Arguments @('tag', '-d', "v$indexVersionHere") | Out-Null
        $null = @(Read-QueueLog)
        $said = Invoke-IndexPushCheck $publishedRun
        # The CLI counted the published bundle's patches as a job of the build queue.
        $seen = @(Read-QueueLog)
        Assert-True (@($seen | Where-Object { $_ -eq 'hushfacebook list published patches' }).Count -eq 1) `
            "The release check did not count the published patches as one job of the build queue: $($seen -join ', ')"
        Assert-True ($said -like "*published bundle is pinned to v$indexVersionHere ($releaseCommit)*" -and
            $said -like "*the index asks for Morphe Manager $releaseFloor or newer, as tag v$indexVersionHere pins*") `
            "The index push was not held to the Manager floor its published tag pins: $said"
        # The SBOM the receipt names, as the release hosts it, and OSV asked about it again.
        Assert-True ($said -like ("*the hosted patches-$indexVersionHere.cdx.json is the SBOM the receipt names and " +
                "SHA256SUMS.txt lists, and it describes patches-$indexVersionHere.mpp, payloads and all*") -and
            $said -like "*OSV has no advisory for the libraries patches-$indexVersionHere.cdx.json lists: gson 2.14.0*") `
            "The index push did not hold the hosted SBOM to the receipt, or did not ask OSV about it: $said"
        Assert-True ($said -like '*the Facebook-family source census is 0 day(s) old*every index lists Hushfacebook or has its submission*') `
            "The index push was not held to the Facebook-family source census: $said"
        # The receipt the release hosts, held to SHA256SUMS.txt and to the receipt checked here.
        Assert-True ($said -like "*the hosted release-receipt-$releaseVersionHere.json is the receipt checked here, as SHA256SUMS.txt lists it*") `
            "The index push did not hold the hosted receipt to the one it checked: $said"
        # And each way it can fail: SHA256SUMS.txt not listing it, listing another hash, or the
        # release hosting another receipt, one cut again after the upload. That one differs in its
        # last byte alone, and the push stops all the same.
        $recutReceipt = Join-Path $releaseRoot "served\recut\release-receipt-$releaseVersionHere.json"
        New-Item -ItemType Directory -Path (Split-Path -Parent $recutReceipt) -Force | Out-Null
        $recutBytes = New-Object System.Collections.Generic.List[byte] (, [System.IO.File]::ReadAllBytes($releaseReceipt))
        $recutBytes.Add(10)
        [System.IO.File]::WriteAllBytes($recutReceipt, $recutBytes.ToArray())
        $bundleAndSbomSums = "$((Get-FileHash -LiteralPath $releaseBundle -Algorithm SHA256).Hash.ToLowerInvariant())  patches-$indexVersionHere.mpp`n" +
            "$((Get-FileHash -LiteralPath $cleanServedSbom -Algorithm SHA256).Hash.ToLowerInvariant())  patches-$indexVersionHere.cdx.json`n" +
            "$(Get-Sha256Hex $cleanServedTooling)  patches-$indexVersionHere.tooling.json`n"
        try {
            $servedSums = $bundleAndSbomSums
            Assert-Throws { Invoke-IndexPushCheck $publishedRun } "*SHA256SUMS.txt has no entry for release-receipt-$releaseVersionHere.json*" `
                'An index push went through with a receipt SHA256SUMS does not list.'
            $servedSums = $bundleAndSbomSums + "$('2' * 64)  release-receipt-$releaseVersionHere.json`n"
            Assert-Throws { Invoke-IndexPushCheck $publishedRun } `
                "*SHA256SUMS.txt lists $('2' * 64) for release-receipt-$releaseVersionHere.json, but the hosted receipt is*" `
                'An index push went through with a receipt SHA256SUMS lists under another hash.'
            $servedSums = $null
            $servedReceipt = $recutReceipt
            Assert-Throws { Invoke-IndexPushCheck $publishedRun } `
                "*The hosted release-receipt-$releaseVersionHere.json is not the receipt checked here*" `
                'An index push went through with a hosted receipt that is not the one it checked.'
        } finally {
            $servedSums = $null
            $servedReceipt = $null
        }

        # The census, one fact at a time. Fourteen days old is still a release; fifteen isn't. An
        # index that doesn't list Hushfacebook yet is named in what the release says, not a refusal:
        # a submission is a public request on someone else's project. The lenient check every other
        # push runs reads none of it, so a README fix never waits on an audit.
        try {
            Save-ReleaseLedger -AgeDays 14
            $said = Invoke-IndexPushCheck $publishedRun
            Assert-True ($said -like '*the Facebook-family source census is 14 day(s) old*') `
                "A release on a census 14 days old did not say so: $said"
            Save-ReleaseLedger -AgeDays 15
            Assert-Throws { Invoke-IndexPushCheck $publishedRun } '*source census*the census is 15 days old*audit-facebook-sources.ps1*' `
                'A release went out on a census 15 days old.'
            try {
                Invoke-ReleaseCheck | Out-Null
            } catch {
                throw "The lenient check an ordinary push runs refused a census 15 days old: $($_.Exception.Message)"
            }
            Save-ReleaseLedger -Pending
            $said = Invoke-IndexPushCheck $publishedRun
            Assert-True ($said -like '*Hushfacebook is not listed on *yet and has no submission recorded there*') `
                "A release with an index that doesn't list Hushfacebook didn't name it: $said"
        } finally {
            Save-ReleaseLedger
        }

        # The hosted tooling graph is checked with a clean payload SBOM. A receipt whose own
        # source commit predates this requirement is exercised separately below.
        $receiptBeforeHostedTooling = [IO.File]::ReadAllBytes($releaseReceipt)
        try {
            $bundleSum = "$(Get-Sha256Hex $releaseBundle)  patches-$indexVersionHere.mpp`n"
            $sbomSum = "$(Get-Sha256Hex $servedSbom)  patches-$indexVersionHere.cdx.json`n"
            $receiptSum = "$(Get-Sha256Hex $releaseReceipt)  release-receipt-$releaseVersionHere.json`n"
            $servedSums = $bundleSum + $sbomSum + $receiptSum
            Assert-Throws { Invoke-IndexPushCheck $publishedRun } '*SHA256SUMS.txt has no entry for*.tooling.json*' `
                'Publication accepted a tooling report absent from SHA256SUMS.'
            $servedSums += "$('0' * 64)  patches-$indexVersionHere.tooling.json`n"
            Assert-Throws { Invoke-IndexPushCheck $publishedRun } '*SHA256SUMS.txt lists another hash*.tooling.json*' `
                'Publication accepted a tooling report with the wrong listed hash.'
            $servedSums = $null
            $script:servedTooling = Join-Path $releaseRoot "served/case/patches-$releaseVersionHere.tooling.json"
            New-Item -ItemType Directory -Path (Split-Path -Parent $servedTooling) -Force | Out-Null
            $settingsPurl = 'pkg:maven/com.example/settings-only@1.0'
            $answersWithUnrated = $osvRecorded.Clone()
            $answersWithUnrated[$settingsPurl] = "{`"vulns`":[$log4jVectorFour]}"
            $osvAnswers = $answersWithUnrated
            foreach ($finding in @(
                    @{ Purl = $gsonPurl; Pattern = '*Tooling advisories require remediation*HIGH*' },
                    @{ Purl = 'pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1'; Pattern = '*Tooling advisories require remediation*CRITICAL*' },
                    @{ Purl = $settingsPurl; Pattern = '*Tooling advisories require remediation*UNRATED*' })) {
                New-TestTooling -Path $servedTooling -Bundle $releaseBundle -Commit $releaseCommit -ScopeText $releaseScopeText `
                    -Libraries @($finding.Purl) -SettingsOnly
                $document = Get-Content -LiteralPath $releaseReceipt -Raw | ConvertFrom-Json
                $document.tooling.sha256 = Get-Sha256Hex $servedTooling
                Set-Content -LiteralPath $releaseReceipt -Encoding UTF8 -Value ($document | ConvertTo-Json -Depth 16)
                Assert-Throws { Invoke-IndexPushCheck $publishedRun } $finding.Pattern 'Publication accepted a settings-only security finding.'
            }
            $servedTooling = $cleanServedTooling
            [IO.File]::WriteAllBytes($releaseReceipt, $receiptBeforeHostedTooling)
            $document = Get-Content -LiteralPath $releaseReceipt -Raw | ConvertFrom-Json
            $document.tooling.audit.status = 'skipped'
            Set-Content -LiteralPath $releaseReceipt -Encoding UTF8 -Value ($document | ConvertTo-Json -Depth 16)
            Assert-Throws { Invoke-IndexPushCheck $publishedRun } '*skipped tooling advisory audit cannot authorize publication*' `
                'A skipped local tooling audit authorized publication.'
        } finally {
            $servedSums = $null
            $servedTooling = $cleanServedTooling
            $osvAnswers = $osvRecorded
            [IO.File]::WriteAllBytes($releaseReceipt, $receiptBeforeHostedTooling)
        }

        # Each way the hosted SBOM can fail it. A receipt that names the SBOM served has its hash
        # and count written in, so that what refuses the push is the case's own difference. The
        # deliberately vulnerable fixture goes out here too: the receipt for it was built offline,
        # and this is the check that asks OSV again.
        $cleanReceiptBytes = [System.IO.File]::ReadAllBytes($releaseReceipt)
        function Use-ServedSbom([string[]]$Libraries, [switch]$KeepReceipt) {
            $script:servedSbom = Join-Path $releaseRoot "served\case\patches-$releaseVersionHere.cdx.json"
            New-Item -ItemType Directory -Path (Split-Path -Parent $script:servedSbom) -Force | Out-Null
            New-TestSbom -Path $script:servedSbom -Bundle $releaseBundle -Libraries $Libraries
            if (-not $KeepReceipt) {
                $document = Get-Content -LiteralPath $releaseReceipt -Raw | ConvertFrom-Json
                $document.sbom.sha256 = Get-Sha256Hex -Path $script:servedSbom
                $document.sbom.components = @($Libraries).Count + 2
                Set-Content -LiteralPath $releaseReceipt -Encoding UTF8 -Value ($document | ConvertTo-Json -Depth 12)
            }
        }
        try {
            $servedSums = "$((Get-FileHash -LiteralPath $releaseBundle -Algorithm SHA256).Hash.ToLowerInvariant())  patches-$indexVersionHere.mpp`n"
            Assert-Throws { Invoke-IndexPushCheck $publishedRun } "*SHA256SUMS.txt has no entry for patches-$indexVersionHere.cdx.json*" `
                'An index push went through with an SBOM SHA256SUMS does not list.'
            $servedSums += "$('1' * 64)  patches-$indexVersionHere.cdx.json`n"
            Assert-Throws { Invoke-IndexPushCheck $publishedRun } "*SHA256SUMS.txt lists $('1' * 64) for patches-$indexVersionHere.cdx.json, but the hosted SBOM is*" `
                'An index push went through with an SBOM SHA256SUMS lists under another hash.'
            $servedSums = $null
            # Another SBOM of the same bundle: its serial number alone makes it another file.
            Use-ServedSbom @($cleanPurl) -KeepReceipt
            Assert-Throws { Invoke-IndexPushCheck $publishedRun } "*The receipt says patches-$indexVersionHere.cdx.json hashes to*" `
                'An index push went through with a hosted SBOM the receipt does not name.'
            Use-ServedSbom @($gsonPurl)
            Assert-Throws { Invoke-IndexPushCheck $publishedRun } '*high or critical*GHSA-4jrv-ppp4-jm57 (HIGH, CVE-2022-25647) in com.google.code.gson:gson 2.8.8*' `
                'An index push went through with a bundle carrying gson 2.8.8.'
            # A current publication cannot opt out of the tooling audit.
            $offlineRun = $publishedRun.Clone()
            $offlineRun['SkipAdvisoryCheck'] = $true
            Assert-Throws { Invoke-IndexPushCheck $offlineRun } '*SkipAdvisoryCheck cannot satisfy schema-3 tooling publication checks*' `
                'A schema 3 index push bypassed its tooling audit.'
            Use-ServedSbom @('pkg:maven/com.example/unknown@1.0')
            Assert-Throws { Invoke-IndexPushCheck $publishedRun } '*could not be asked about pkg:maven/com.example/unknown@1.0*fails closed*' `
                'An index push went through without OSV answering.'
            $document = Get-Content -LiteralPath $releaseReceipt -Raw | ConvertFrom-Json
            $document.sbom.file = 'patches-9.9.8.cdx.json'
            Set-Content -LiteralPath $releaseReceipt -Encoding UTF8 -Value ($document | ConvertTo-Json -Depth 12)
            Assert-Throws { Invoke-IndexPushCheck $publishedRun } "*receipt names the SBOM patches-9.9.8.cdx.json; the one for $releaseVersionHere is patches-$releaseVersionHere.cdx.json*" `
                'An index push fetched an SBOM the receipt names under another version.'
        } finally {
            $servedSbom = $cleanServedSbom
            $servedSums = $null
            $osvAnswers = $osvRecorded
            [System.IO.File]::WriteAllBytes($releaseReceipt, $cleanReceiptBytes)
        }
        $said = Invoke-IndexPushCheck @{ SkipDescriptionTestCount = $true }
        Assert-True ($said -like "*the index asks for Morphe Manager $releaseFloor or newer, as tag v$indexVersionHere on $indexRepository pins*") `
            "A run with no bundle did not read the floor at the tag the index's repository has: $said"
        Set-Content -LiteralPath $releaseIndexPath -Encoding UTF8 -NoNewline -Value (
            $releaseIndexText -replace '(Morphe Manager )\d+(?:\.\d+)+( or newer)', '${1}1.20.0${2}')
        Assert-Throws { Invoke-IndexPushCheck @{ SkipDescriptionTestCount = $true } } '*Morphe Manager 1.20.0 or newer*' `
            'An index push from a clone without the release tag named a Manager floor the tag does not pin, and went through.'
        Set-Content -LiteralPath $releaseIndexPath -Encoding UTF8 -NoNewline -Value $releaseIndexText

        # The index push through the hook, which decides what the hosted asset is held to. A bundle
        # built here is compared with it byte for byte when there's one for the version the index
        # publishes: the only bundle, whatever its name, or among several the one named for that
        # version. With none of those the check downloads the asset and holds it to everything else
        # a local build gets. The hook runs the check the pushed tree carries, strict, so the scripts
        # are copied beside the release files and the test results the description quotes are
        # written. The CLI, its JDK and GitHub are stand-ins, and the temporary folder is the
        # fixture's own, so a download left behind shows.
        $hookTemp = Join-Path $releaseRoot 'hook-temp'
        $releaseBuilds = Split-Path -Parent $releaseBundle
        $parkedBundle = Join-Path $releaseRoot "parked\patches-$releaseVersionHere.mpp"
        $parkedSbom = [System.IO.Path]::ChangeExtension($parkedBundle, '.cdx.json')
        $parkedTooling = [IO.Path]::ChangeExtension($parkedBundle, '.tooling.json')
        $otherBuilds = @('patches-9.9.8.mpp', 'patches-9.9.9.mpp' | ForEach-Object { Join-Path $releaseBuilds $_ })
        $releaseDescription = [string]($releaseIndexText | ConvertFrom-Json).description
        function Invoke-IndexPushHook {
            $saved = @{}
            foreach ($variable in @(Get-ChildItem Env: | Where-Object { $_.Name -like 'GIT_*' -or $_.Name -in @('TMP', 'TEMP',
                    'HUSHFACEBOOK_DESKTOP_JAR', 'HUSHFACEBOOK_JAVA', 'HUSHFACEBOOK_SKIP_PRE_PUSH') })) {
                $saved[$variable.Name] = $variable.Value
                Remove-Item -LiteralPath ('Env:\' + $variable.Name) -ErrorAction SilentlyContinue
            }
            $env:TMP = $hookTemp
            $env:TEMP = $hookTemp
            $env:HUSHFACEBOOK_DESKTOP_JAR = $stubJar
            $env:HUSHFACEBOOK_JAVA = $listJava
            try {
                . $publishedStandIns
                . $osvStandIn
                $global:LASTEXITCODE = 0
                $said = @(& $prePushScript -Root $releaseRepo -ChangedPaths @('patches-bundle.json') 3>&1 6>&1 |
                    ForEach-Object { "$_" }) -join "`n"
                if ($LASTEXITCODE -ne 0) { throw "The index push exited $LASTEXITCODE`: $said" }
                return $said
            } finally {
                foreach ($name in @('TMP', 'TEMP', 'HUSHFACEBOOK_DESKTOP_JAR', 'HUSHFACEBOOK_JAVA')) {
                    Remove-Item -LiteralPath ('Env:\' + $name) -ErrorAction SilentlyContinue
                }
                foreach ($name in $saved.Keys) { Set-Item -LiteralPath ('Env:\' + $name) -Value $saved[$name] }
            }
        }
        # Pass or fail, the downloads are gone afterwards and build/release holds what it held.
        function Assert-LeftAlone([string]$Case, [string[]]$Builds) {
            $left = @(Get-ChildItem -LiteralPath $hookTemp -File -Recurse | Where-Object { $_.Name -like '*.mpp' -or $_.Name -like '*.cdx.json' -or $_.Name -like '*.tooling.json' } |
                ForEach-Object { $_.Name })
            Assert-True ($left.Count -eq 0) "The $Case left its download behind: $($left -join ', ')"
            $now = @(Get-ChildItem -LiteralPath $releaseBuilds -File | ForEach-Object { $_.Name } | Sort-Object) -join ', '
            Assert-True ($now -eq (@($Builds | Sort-Object) -join ', ')) "The $Case changed patches/build/release: $now"
        }
        Copy-Item -LiteralPath $PSScriptRoot -Destination (Join-Path $releaseRepo 'scripts') -Recurse
        foreach ($results in @(
                @{ Folder = 'extensions/facebook/build/test-results/testDebugUnitTest'; Suite = 'RuntimeTest'; Quote = '\b(\d+) runtime tests passed\b' },
                @{ Folder = 'patches/build/test-results/test'; Suite = 'PatchTest'; Quote = '\b(\d+) patch tests passed\b' })) {
            $quoted = [regex]::Match($releaseDescription, $results.Quote).Groups[1].Value
            Assert-True ($quoted -match '^[1-9]\d*$') "The copied description quotes no $($results.Suite) count: $releaseDescription"
            $directory = Join-Path $releaseRepo $results.Folder
            New-Item -ItemType Directory -Path $directory -Force | Out-Null
            $cases = (1..[int]$quoted | ForEach-Object { "<testcase name=`"t$_`" classname=`"fixture.$($results.Suite)`"/>" }) -join ''
            Set-Content -LiteralPath (Join-Path $directory "TEST-fixture.$($results.Suite).xml") -Encoding UTF8 -Value (
                "<?xml version=`"1.0`" encoding=`"UTF-8`"?><testsuite name=`"fixture.$($results.Suite)`" tests=`"$quoted`" " +
                "skipped=`"0`" failures=`"0`" errors=`"0`">$cases</testsuite>")
        }
        New-Item -ItemType Directory -Path $hookTemp, (Split-Path -Parent $parkedBundle) -Force | Out-Null
        try {
            # No bundle built here, nor the SBOM beside it. The hosted one passes every check a local
            # build gets, and the check says the byte-for-byte comparison had nothing to compare with.
            Move-Item -LiteralPath $releaseBundle -Destination $parkedBundle
            Move-Item -LiteralPath $releaseSbom -Destination $parkedSbom
            Move-Item -LiteralPath $releaseTooling -Destination $parkedTooling
            $servedBundle = $parkedBundle
            $said = Invoke-IndexPushHook
            foreach ($line in @('no local bundle here, so the hosted asset is downloaded and checked on its own',
                    "no bundle built here is compared with the hosted patches-$indexVersionHere.mpp byte for byte",
                    "published bundle is pinned to v$indexVersionHere ($releaseCommit)",
                    'the published bundle carries classes.dex',
                    "the published bundle carries $($releaseNames.Count) patches, as described",
                    "the bundle stamps patcher $($releaseToolchain.PatcherVersion), as the catalog pins",
                    "the receipt proves $($releaseNames.Count) patches on",
                    "the hosted patches-$indexVersionHere.cdx.json is the SBOM the receipt names",
                    "OSV has no advisory for the libraries patches-$indexVersionHere.cdx.json lists")) {
                Assert-True ($said -like "*$line*") "An index push with no bundle built here left the hosted one unchecked ($line): $said"
            }
            Assert-LeftAlone 'hosted check' @()
            # And a hosted file SHA256SUMS doesn't list, or one without the classes.dex Manager
            # loads, is refused, with nothing left behind.
            $servedSums = "$('0' * 64)  patches-$indexVersionHere.mpp`n"
            Assert-Throws { Invoke-IndexPushHook } "*SHA256SUMS.txt lists $('0' * 64) for patches-$indexVersionHere.mpp*" `
                'An index push went through with a hosted bundle SHA256SUMS does not list.'
            Assert-LeftAlone 'refused hosted check' @()
            $servedSums = $null
            $servedBundle = Join-Path $releaseRoot "dexless\patches-$releaseVersionHere.mpp"
            New-Item -ItemType Directory -Path (Split-Path -Parent $servedBundle) -Force | Out-Null
            New-TestBundleArchive -Path $servedBundle -Entries ([ordered]@{
                'META-INF/MANIFEST.MF' = ("Manifest-Version: 1.0`nVersion: $releaseVersionHere`nTimestamp: $($releaseSeconds * 1000)`n" +
                    "Patcher-Version: $($releaseToolchain.PatcherVersion)`n`n")
                'extensions/facebook.mpe' = "dex`n035" + ('payload' * 8) })
            Assert-Throws { Invoke-IndexPushHook } '*The published bundle has no classes.dex*' `
                'An index push went through with a hosted bundle Manager would load no patches from.'
            Assert-LeftAlone 'refused hosted check' @()

            # Several bundles. The one named for the index version is compared byte for byte, and
            # the other is left alone; with neither named for it, the hosted one is checked on its own.
            Move-Item -LiteralPath $parkedBundle -Destination $releaseBundle
            Move-Item -LiteralPath $parkedSbom -Destination $releaseSbom
            Move-Item -LiteralPath $parkedTooling -Destination $releaseTooling
            $servedBundle = $releaseBundle
            New-TestBundleArchive -Path $otherBuilds[0] -Entries ([ordered]@{ 'META-INF/MANIFEST.MF' = "Manifest-Version: 1.0`nVersion: 9.9.8`n`n" })
            $said = Invoke-IndexPushHook
            Assert-True ($said -like "*found 2 bundles, so the hosted asset is compared with patches-$indexVersionHere.mpp, built here*" -and
                $said -like "*the hosted patches-$indexVersionHere.mpp matches the bundle built here byte for byte*" -and
                $said -like "*the receipt proves $($releaseNames.Count) patches on*") `
                "An index push with several bundles did not compare the hosted one with the bundle for its version: $said"
            Assert-LeftAlone 'comparison with the bundle built here' @("patches-$releaseVersionHere.mpp", "patches-$releaseVersionHere.cdx.json",
                "patches-$releaseVersionHere.tooling.json", 'patches-9.9.8.mpp')
            Move-Item -LiteralPath $releaseBundle -Destination $parkedBundle
            Move-Item -LiteralPath $releaseSbom -Destination $parkedSbom
            $servedBundle = $parkedBundle
            Move-Item -LiteralPath $releaseTooling -Destination $parkedTooling
            New-TestBundleArchive -Path $otherBuilds[1] -Entries ([ordered]@{ 'META-INF/MANIFEST.MF' = "Manifest-Version: 1.0`nVersion: 9.9.9`n`n" })
            $said = Invoke-IndexPushHook
            Assert-True ($said -like "*found 2 bundles and none is patches-$indexVersionHere.mpp, so the hosted asset is downloaded and checked on its own*" -and
                $said -like "*no bundle built here is compared with the hosted patches-$indexVersionHere.mpp byte for byte*" -and
                $said -like "*the receipt proves $($releaseNames.Count) patches on*") `
                "An index push with several bundles, none for its version, left the hosted one unchecked: $said"
            Assert-LeftAlone 'hosted check' @('patches-9.9.8.mpp', 'patches-9.9.9.mpp')
        } finally {
            $servedBundle = $releaseBundle
            $servedSums = $null
            Remove-Item -LiteralPath $otherBuilds -Force -ErrorAction SilentlyContinue
            if (-not (Test-Path -LiteralPath $releaseBundle) -and (Test-Path -LiteralPath $parkedBundle)) {
                Move-Item -LiteralPath $parkedBundle -Destination $releaseBundle
            }
            if (-not (Test-Path -LiteralPath $releaseSbom) -and (Test-Path -LiteralPath $parkedSbom)) {
                Move-Item -LiteralPath $parkedSbom -Destination $releaseSbom
            }
            if (-not (Test-Path -LiteralPath $releaseTooling) -and (Test-Path -LiteralPath $parkedTooling)) {
                Move-Item -LiteralPath $parkedTooling -Destination $releaseTooling
            }
            foreach ($folder in @('scripts', 'extensions', 'patches/build/test-results')) {
                Remove-Item -LiteralPath (Join-Path $releaseRepo $folder) -Recurse -Force -ErrorAction SilentlyContinue
            }
        }

        # The patch selections at publication. The index push has already pointed patches-bundle.json
        # at the release it publishes, so the comparison reads the predecessor index at the release's
        # own tagged commit, which still names the release before it. Here that predecessor is a
        # commit published as the version before this one, and the candidate flips one default and
        # records it. All metadata, assets and tags are local.
        & {
            $catalogOriginalFiles = @{}
            foreach ($relative in @('patches-list.json', 'patches-bundle.json', 'CHANGELOG.md')) {
                $catalogOriginalFiles[$relative] = [IO.File]::ReadAllBytes((Join-Path $releaseRepo $relative))
            }
            $catalogOriginalReceipt = [IO.File]::ReadAllBytes($releaseReceipt)
            $catalogPublishedAssets = @{}
            $catalogPublishedRequests.Clear()
            $catalogCurrent = [version]$releaseVersionHere
            $catalogPreviousVersion = if ($catalogCurrent.Build -gt 0) {
                "$($catalogCurrent.Major).$($catalogCurrent.Minor).$($catalogCurrent.Build - 1)"
            } elseif ($catalogCurrent.Minor -gt 0) { "$($catalogCurrent.Major).$($catalogCurrent.Minor - 1).0" }
            elseif ($catalogCurrent.Major -gt 0) { "$($catalogCurrent.Major - 1).0.0" }
            else { throw 'The publication history fixture needs an earlier version.' }
            $catalogMetadataPath = Join-Path $releaseRepo 'scripts/patch-catalog-compatibility.json'
            Assert-True (-not (Test-Path -LiteralPath $catalogMetadataPath)) 'The publication fixture already has compatibility metadata.'
            $catalogCaseDir = Join-Path $releaseRoot 'catalog-compatibility-cases'
            New-Item -ItemType Directory -Path $catalogCaseDir -Force | Out-Null
            # A commit beside the branch carrying this list, index (none for $null), metadata and notes.
            function New-CatalogFixtureCommit($List, $Index, $Metadata, [string]$Notes) {
                try {
                    Set-Content -LiteralPath (Join-Path $releaseRepo 'patches-list.json') -Value ($List | ConvertTo-Json -Depth 15) -Encoding UTF8
                    if ($null -eq $Index) {
                        Invoke-FixtureGit -Root $releaseRepo -Arguments @('rm', '--cached', '--quiet', '--', 'patches-bundle.json') | Out-Null
                    } else {
                        Set-Content -LiteralPath (Join-Path $releaseRepo 'patches-bundle.json') -Value ($Index | ConvertTo-Json -Depth 8) -Encoding UTF8
                        Invoke-FixtureGit -Root $releaseRepo -Arguments @('add', '--', 'patches-bundle.json') | Out-Null
                    }
                    Set-Content -LiteralPath (Join-Path $releaseRepo 'CHANGELOG.md') -Value $Notes -Encoding UTF8
                    Invoke-FixtureGit -Root $releaseRepo -Arguments @('add', '--', 'patches-list.json', 'CHANGELOG.md') | Out-Null
                    if ($null -ne $Metadata) {
                        New-Item -ItemType Directory -Path (Split-Path -Parent $catalogMetadataPath) -Force | Out-Null
                        Set-Content -LiteralPath $catalogMetadataPath -Value ($Metadata | ConvertTo-Json -Depth 6) -Encoding UTF8
                        Invoke-FixtureGit -Root $releaseRepo -Arguments @('add', '--', 'scripts/patch-catalog-compatibility.json') | Out-Null
                    }
                    $tree = "$(Invoke-FixtureGit -Root $releaseRepo -Arguments @('write-tree') | Select-Object -First 1)".Trim()
                    return "$(Invoke-FixtureGit -Root $releaseRepo -Arguments @('commit-tree', $tree, '-p', $releaseCommit,
                        '-m', 'catalog publication history') | Select-Object -First 1)".Trim()
                } finally {
                    Invoke-FixtureGit -Root $releaseRepo -Arguments @('reset', '--quiet') | Out-Null
                    foreach ($relative in $catalogOriginalFiles.Keys) {
                        [IO.File]::WriteAllBytes((Join-Path $releaseRepo $relative), $catalogOriginalFiles[$relative])
                    }
                    if (Test-Path -LiteralPath $catalogMetadataPath) { Remove-Item -LiteralPath $catalogMetadataPath -Force }
                }
            }
            # The candidate published at that commit: a bundle stamped with its time, its SBOM and
            # tooling report, a receipt naming it, and the working tree the index push runs on.
            function Serve-CatalogCandidate([string]$Commit, $List, $Metadata, [string]$Notes) {
                $seconds = [long]"$(Invoke-FixtureGit -Root $releaseRepo -Arguments @('log', '-1', '--format=%ct', $Commit) | Select-Object -First 1)".Trim()
                $bundle = Join-Path $catalogCaseDir "patches-$releaseVersionHere.mpp"
                if (Test-Path -LiteralPath $bundle) { Remove-Item -LiteralPath $bundle -Force }
                New-TestBundleArchive -Path $bundle -Entries ([ordered]@{
                    'META-INF/MANIFEST.MF' = ("Manifest-Version: 1.0`nVersion: $releaseVersionHere`nTimestamp: $($seconds * 1000)`n" +
                        "Patcher-Version: $($releaseToolchain.PatcherVersion)`n`n")
                    'classes.dex' = "dex`n035" + ('patches' * 8)
                    'extensions/facebook.mpe' = "dex`n035" + ('payload' * 8) })
                $sbom = [IO.Path]::ChangeExtension($bundle, '.cdx.json')
                New-TestSbom -Path $sbom -Bundle $bundle
                $tooling = [IO.Path]::ChangeExtension($bundle, '.tooling.json')
                New-TestTooling -Path $tooling -Bundle $bundle -Commit $Commit -ScopeText $releaseScopeText
                $document = [Text.Encoding]::UTF8.GetString($catalogOriginalReceipt).TrimStart([char]0xFEFF) | ConvertFrom-Json
                $document.release.commit = $Commit
                $document.release.commitTimestamp = $seconds
                $document.bundle.timestamp = $seconds * 1000
                $document.bundle.sha256 = Get-Sha256Hex $bundle
                $document.bundle.sizeBytes = (Get-Item -LiteralPath $bundle).Length
                $document.sbom.sha256 = Get-Sha256Hex $sbom
                $report = Read-ReleaseTooling $tooling
                $document.tooling.sha256 = $report.Sha256
                $document.tooling.components = $report.Components.Count
                $document.tooling.scopes = $report.Scopes.Count
                Set-Content -LiteralPath $releaseReceipt -Value ($document | ConvertTo-Json -Depth 16) -Encoding UTF8
                Set-Content -LiteralPath (Join-Path $releaseRepo 'patches-list.json') -Value ($List | ConvertTo-Json -Depth 15) -Encoding UTF8
                Set-Content -LiteralPath (Join-Path $releaseRepo 'CHANGELOG.md') -Value $Notes -Encoding UTF8
                if ($null -ne $Metadata) {
                    New-Item -ItemType Directory -Path (Split-Path -Parent $catalogMetadataPath) -Force | Out-Null
                    Set-Content -LiteralPath $catalogMetadataPath -Value ($Metadata | ConvertTo-Json -Depth 6) -Encoding UTF8
                } elseif (Test-Path -LiteralPath $catalogMetadataPath) { Remove-Item -LiteralPath $catalogMetadataPath -Force }
                Invoke-FixtureGit -Root $releaseRepo -Arguments @('push', '--force', '--quiet', $publishedRepo,
                    "${Commit}:refs/tags/v$indexVersionHere") | Out-Null
                return [pscustomobject]@{ Bundle = $bundle; Sbom = $sbom; Tooling = $tooling }
            }
            try {
                $catalogPrevious = [Text.Encoding]::UTF8.GetString($catalogOriginalFiles['patches-list.json']).TrimStart([char]0xFEFF) | ConvertFrom-Json
                $catalogPrevious.version = "v$catalogPreviousVersion"
                $catalogOldIndex = [Text.Encoding]::UTF8.GetString($catalogOriginalFiles['patches-bundle.json']).TrimStart([char]0xFEFF) | ConvertFrom-Json
                $catalogOldIndex.version = $catalogPreviousVersion
                $catalogOldIndex.download_url = "https://github.com/$((@($indexUrl.AbsolutePath.Trim('/') -split '/') | Select-Object -First 2) -join '/')/releases/download/v$catalogPreviousVersion/patches-$catalogPreviousVersion.mpp"
                $catalogOldNotes = [Text.Encoding]::UTF8.GetString($catalogOriginalFiles['CHANGELOG.md']).TrimStart([char]0xFEFF)
                $catalogPreviousCommit = New-CatalogFixtureCommit $catalogPrevious $catalogOldIndex $null $catalogOldNotes
                Invoke-FixtureGit -Root $releaseRepo -Arguments @('push', '--quiet', $publishedRepo,
                    "${catalogPreviousCommit}:refs/tags/v$catalogPreviousVersion") | Out-Null
                $catalogPriorReceipt = Join-Path $catalogCaseDir "release-receipt-$catalogPreviousVersion.json"
                Set-Content -LiteralPath $catalogPriorReceipt -Value (@{ release = @{ version = $catalogPreviousVersion; commit = $catalogPreviousCommit }
                    bundle = @{ file = "patches-$catalogPreviousVersion.mpp"; sha256 = ('d' * 64) }
                    patches = @(@{ name = $catalogPrevious.patches[0].name; applied = -not $catalogPrevious.patches[0].use }) } | ConvertTo-Json -Depth 5) -Encoding UTF8
                $catalogPriorSums = Join-Path $catalogCaseDir 'prior-SHA256SUMS.txt'
                Set-Content -LiteralPath $catalogPriorSums -Value ("$(Get-Sha256Hex $catalogPriorReceipt)  release-receipt-$catalogPreviousVersion.json`n$('d' * 64)  patches-$catalogPreviousVersion.mpp") -Encoding UTF8
                $catalogPriorBase = ([Uri]$catalogOldIndex.download_url).AbsoluteUri -replace '[^/]+$', ''
                $catalogPublishedAssets[$catalogPriorBase + "release-receipt-$catalogPreviousVersion.json"] = $catalogPriorReceipt
                $catalogPublishedAssets[$catalogPriorBase + 'SHA256SUMS.txt'] = $catalogPriorSums
                $catalogCandidate = [Text.Encoding]::UTF8.GetString($catalogOriginalFiles['patches-list.json']).TrimStart([char]0xFEFF) | ConvertFrom-Json
                $catalogBefore = $catalogCandidate.patches[0] | ConvertTo-Json -Depth 10 | ConvertFrom-Json
                $catalogCandidate.patches[0].use = -not $catalogBefore.use
                $catalogNote = "$($catalogBefore.name) changes its default selection."
                $catalogNotes = $catalogOldNotes -replace ('(?m)^(##\s+\[?v?' + [regex]::Escape($releaseVersionHere) + '(?:\]|\s|$)[^\r\n]*)'), ("`$1`n`n* $catalogNote")
                Assert-True ($catalogNotes -cne $catalogOldNotes) "The publication fixture's CHANGELOG has no $releaseVersionHere heading to carry the note."
                $catalogMetadata = @{ baselineVersion = $catalogPreviousVersion; baselineCommit = $catalogPreviousCommit
                    candidateVersion = $releaseVersionHere; changes = @(@{ before = @{ name = $catalogBefore.name; use = $catalogBefore.use }
                        after = @{ name = $catalogCandidate.patches[0].name; use = $catalogCandidate.patches[0].use }; migrationNote = $catalogNote }) }
                $catalogCandidateCommit = New-CatalogFixtureCommit $catalogCandidate $catalogOldIndex $catalogMetadata $catalogNotes
                $assets = Serve-CatalogCandidate $catalogCandidateCommit $catalogCandidate $catalogMetadata $catalogNotes
                $servedBundle = $assets.Bundle; $servedSbom = $assets.Sbom; $servedTooling = $assets.Tooling; $servedReceipt = $null; $servedSums = $null
                $catalogRun = $publishedRun.Clone(); $catalogRun['ArtifactPath'] = $assets.Bundle
                $catalogPublishedRequests.Clear()
                $said = Invoke-IndexPushCheck $catalogRun
                Assert-True (@($catalogPublishedRequests | Where-Object { $_ -ceq ($catalogPriorBase + "release-receipt-$catalogPreviousVersion.json") }).Count -gt 0) `
                    'Publication used the working candidate index instead of the source-commit predecessor receipt.'
                Assert-True ($said -like "*patch names and defaults are compatible with published v$catalogPreviousVersion at $($catalogPreviousCommit.Substring(0, 8))*") `
                    "Publication did not say which published catalog it held the candidate to: $said"
                $sourceRun = @{ SkipDescriptionTestCount = $true; SkipUrlCheck = $true; AllowPublishedIndexLag = $true; ArtifactPath = $assets.Bundle }
                Assert-Throws { Invoke-IndexPushCheck $sourceRun } '*exact baseline and candidate*' 'The next source push retained a consumed predecessor allowance.'
                Remove-Item -LiteralPath $catalogMetadataPath -Force
                Invoke-IndexPushCheck $sourceRun | Out-Null
                Set-Content -LiteralPath $catalogMetadataPath -Value ($catalogMetadata | ConvertTo-Json -Depth 6) -Encoding UTF8
                $catalogPublishedUnavailable = $catalogPriorBase + "release-receipt-$catalogPreviousVersion.json"
                Assert-Throws { Invoke-IndexPushCheck $catalogRun } "*release-receipt-$catalogPreviousVersion.json*" 'An unavailable predecessor receipt passed publication.'
                $catalogPublishedUnavailable = $null
                $catalogNoIndexCommit = New-CatalogFixtureCommit $catalogCandidate $null $null $catalogNotes
                $assets = Serve-CatalogCandidate $catalogNoIndexCommit $catalogCandidate $null $catalogNotes
                $servedBundle = $assets.Bundle; $servedSbom = $assets.Sbom; $servedTooling = $assets.Tooling
                $catalogRun['ArtifactPath'] = $assets.Bundle
                Assert-Throws { Invoke-IndexPushCheck $catalogRun } '*earlier version history*' 'An absent predecessor index hid earlier published releases.'
                Invoke-FixtureGit -Root $releaseRepo -Arguments @('push', '--quiet', $publishedRepo, '--delete', "v$catalogPreviousVersion") | Out-Null
                $catalogPublishedRequests.Clear()
                $said = Invoke-IndexPushCheck $catalogRun
                Assert-True (@($catalogPublishedRequests | Where-Object { $_ -ceq ($catalogPriorBase + "release-receipt-$catalogPreviousVersion.json") }).Count -eq 0) `
                    'A verified first release fetched a nonexistent predecessor asset.'
                Assert-True ($said -like '*patch names and defaults are compatible with a verified first release*') `
                    "A verified first release did not say so: $said"
            } finally {
                foreach ($relative in $catalogOriginalFiles.Keys) { [IO.File]::WriteAllBytes((Join-Path $releaseRepo $relative), $catalogOriginalFiles[$relative]) }
                [IO.File]::WriteAllBytes($releaseReceipt, $catalogOriginalReceipt)
                if (Test-Path -LiteralPath $catalogMetadataPath) { Remove-Item -LiteralPath $catalogMetadataPath -Force }
                Invoke-FixtureGit -Root $releaseRepo -Arguments @('push', '--force', '--quiet', $publishedRepo,
                    "${releaseCommit}:refs/tags/v$indexVersionHere") | Out-Null
                $catalogRemoteTags = @(Invoke-FixtureGit -Root $releaseRepo -Arguments @('ls-remote', $publishedRepo, "refs/tags/v$catalogPreviousVersion"))
                if ($catalogRemoteTags.Count -gt 0) {
                    Invoke-FixtureGit -Root $releaseRepo -Arguments @('push', '--quiet', $publishedRepo, '--delete', "v$catalogPreviousVersion") | Out-Null
                }
                Invoke-FixtureGit -Root $releaseRepo -Arguments @('reset', '--quiet') | Out-Null
            }
        }

        # Schema 2 is held to the policy its release used. Its hosted payload-only receipt still
        # works, including the explicit historical offline path, without inventing tooling data.
        $schemaTwoScript = Join-Path $releaseRoot 'schema-two-release-receipt.ps1'
        Set-Content -LiteralPath $schemaTwoScript -Encoding ASCII -Value @('function Get-ReleaseReceiptSchemaVersion {',
            '    <# Receipt shape written by this historical source. #>', '    return 2', '}')
        $schemaTwoBlob = "$(Invoke-FixtureGit -Root $releaseRepo -Arguments @('hash-object', '-w', $schemaTwoScript) | Select-Object -First 1)".Trim()
        Invoke-FixtureGit -Root $releaseRepo -Arguments @('update-index', '--add', '--cacheinfo',
            "100644,$schemaTwoBlob,scripts/release-receipt.ps1") | Out-Null
        $schemaTwoTree = "$(Invoke-FixtureGit -Root $releaseRepo -Arguments @('write-tree') | Select-Object -First 1)".Trim()
        Invoke-FixtureGit -Root $releaseRepo -Arguments @('reset', '--quiet') | Out-Null
        $schemaTwoCommit = "$(Invoke-FixtureGit -Root $releaseRepo -Arguments @('commit-tree', $schemaTwoTree, '-p', $releaseCommit,
            '-m', 'cut before tooling provenance') | Select-Object -First 1)".Trim()
        $schemaTwoSeconds = [long]"$(Invoke-FixtureGit -Root $releaseRepo -Arguments @('log', '-1', '--format=%ct', $schemaTwoCommit) | Select-Object -First 1)".Trim()
        $receiptBeforeHistorical = [IO.File]::ReadAllBytes($releaseReceipt)
        try {
            $servedBundle = Join-Path $releaseRoot "schema-two/patches-$releaseVersionHere.mpp"
            New-Item -ItemType Directory -Path (Split-Path -Parent $servedBundle) -Force | Out-Null
            New-TestBundleArchive -Path $servedBundle -Entries ([ordered]@{
                'META-INF/MANIFEST.MF' = ("Manifest-Version: 1.0`nVersion: $releaseVersionHere`nTimestamp: $($schemaTwoSeconds * 1000)`n" +
                    "Patcher-Version: $($releaseToolchain.PatcherVersion)`n`n")
                'classes.dex' = "dex`n035" + ('patches' * 8)
                'extensions/facebook.mpe' = "dex`n035" + ('payload' * 8) })
            $servedSbom = [IO.Path]::ChangeExtension($servedBundle, '.cdx.json')
            New-TestSbom -Path $servedSbom -Bundle $servedBundle
            $document = Get-Content -LiteralPath $releaseReceipt -Raw | ConvertFrom-Json
            $document.schemaVersion = 2
            $document.PSObject.Properties.Remove('tooling')
            $document.release.commit = $schemaTwoCommit
            $document.release.commitTimestamp = $schemaTwoSeconds
            $document.bundle.timestamp = $schemaTwoSeconds * 1000
            $document.bundle.sha256 = Get-Sha256Hex $servedBundle
            $document.bundle.sizeBytes = (Get-Item -LiteralPath $servedBundle).Length
            $document.sbom.sha256 = Get-Sha256Hex $servedSbom
            Set-Content -LiteralPath $releaseReceipt -Encoding UTF8 -Value ($document | ConvertTo-Json -Depth 16)
            Invoke-FixtureGit -Root $releaseRepo -Arguments @('push', '--quiet', '--force', $publishedRepo,
                "${schemaTwoCommit}:refs/tags/v$indexVersionHere") | Out-Null
            Invoke-FixtureGit -Root $releaseRepo -Arguments @('tag', '--force', "v$indexVersionHere", $schemaTwoCommit) | Out-Null
            $historicalRun = $publishedRun.Clone()
            $historicalRun['ArtifactPath'] = $servedBundle
            $said = Invoke-IndexPushCheck $historicalRun
            Assert-True ($said -like '*schema 2*predates tooling reports*the receipt proves*') 'A hosted schema 2 release was forced into schema 3.'
            $historicalRun['SkipAdvisoryCheck'] = $true
            $said = Invoke-IndexPushCheck $historicalRun
            Assert-True ($said -like '*SkipAdvisoryCheck: OSV was not asked about the libraries*') 'The historical schema 2 offline path changed silently.'
        } finally {
            $servedBundle = $releaseBundle
            $servedSbom = $cleanServedSbom
            [IO.File]::WriteAllBytes($releaseReceipt, $receiptBeforeHistorical)
            Invoke-FixtureGit -Root $releaseRepo -Arguments @('push', '--quiet', '--force', $publishedRepo,
                "${releaseCommit}:refs/tags/v$indexVersionHere") | Out-Null
            Invoke-FixtureGit -Root $releaseRepo -Arguments @('tag', '--delete', "v$indexVersionHere") | Out-Null
        }

        # A release re-cut on GitHub onto another commit, here one with no catalog, while the clone
        # keeps its tag on the first: the floor is read where the published tag points, and a
        # published asset run refuses a floor it can't read rather than noting it.
        Invoke-FixtureGit -Root $releaseRepo -Arguments @('rm', '--cached', '--quiet', '--', 'gradle/libs.versions.toml') | Out-Null
        $recutTree = "$(Invoke-FixtureGit -Root $releaseRepo -Arguments @('write-tree') | Select-Object -First 1)".Trim()
        Invoke-FixtureGit -Root $releaseRepo -Arguments @('reset', '--quiet') | Out-Null
        $recut = "$(Invoke-FixtureGit -Root $releaseRepo -Arguments @('commit-tree', $recutTree, '-p', $releaseCommit,
            '-m', 're-cut') | Select-Object -First 1)".Trim()
        $recutSeconds = [long]"$(Invoke-FixtureGit -Root $releaseRepo -Arguments @('log', '-1', '--format=%ct', $recut) |
            Select-Object -First 1)".Trim()
        $servedBundle = Join-Path $releaseRoot "recut\patches-$releaseVersionHere.mpp"
        New-Item -ItemType Directory -Path (Split-Path -Parent $servedBundle) -Force | Out-Null
        New-TestBundleArchive -Path $servedBundle -Entries ([ordered]@{
            'META-INF/MANIFEST.MF' = ("Manifest-Version: 1.0`nVersion: $releaseVersionHere`nTimestamp: $($recutSeconds * 1000)`n" +
                "Patcher-Version: $($releaseToolchain.PatcherVersion)`n`n")
            'classes.dex' = "dex`n035" + ('patches' * 8)
            'extensions/facebook.mpe' = "dex`n035" + ('payload' * 8) })
        Invoke-FixtureGit -Root $releaseRepo -Arguments @('push', '--quiet', '--force', $publishedRepo,
            "${recut}:refs/tags/v$indexVersionHere") | Out-Null
        Invoke-FixtureGit -Root $releaseRepo -Arguments @('tag', "v$indexVersionHere", $releaseCommit) | Out-Null
        $recutRun = $publishedRun.Clone()
        $recutRun['ArtifactPath'] = $servedBundle
        Assert-Throws { Invoke-IndexPushCheck $recutRun } `
            "*published release's Manager floor couldn't be read*tag v$indexVersionHere has no version catalog*" `
            'An index push was checked against the clone''s tag, or went through with its release floor unread.'
    } finally {
        Set-Content -LiteralPath $releaseIndexPath -Encoding UTF8 -NoNewline -Value $releaseIndexText
        Invoke-FixtureGit -Root $releaseRepo -Arguments @('tag', '--force', "v$indexVersionHere", $releaseCommit) | Out-Null
        Invoke-FixtureGit -Root $releaseRepo -Arguments @('config', '--unset', "url.$publishedRepo.insteadOf") | Out-Null
    }

    # The published catalog the remaining release checks compare with is still local, after the
    # cases above took away the rewrite and left the bare tag on the deliberately broken re-cut.
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('push', '--force', '--quiet', $releaseCatalogRemote,
        "${releaseCommit}:refs/tags/v$indexVersionHere") | Out-Null
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('config', "url.$releaseCatalogRemote.insteadOf", $releaseCatalogRepository) | Out-Null

    # A release cut before the receipt named an SBOM, which is where the checkout that cut 0.1.1
    # stands until the next release: its commit's receipt script writes schema 1, and its receipt
    # names no SBOM. An ordinary push holds that receipt to schema 1 and says so, and a schema 2
    # receipt for the same commit is refused. The commit sits beside the branch, made with
    # commit-tree, so nothing after this sees it.
    $schemaOneScript = Join-Path $releaseRoot 'schema-one-release-receipt.ps1'
    Set-Content -LiteralPath $schemaOneScript -Encoding ASCII -Value @('function Get-ReleaseReceiptSchemaVersion {', '    <#',
        '    .SYNOPSIS', '        Bumped when the shape changes.', '    #>', '    return 1', '}')
    $schemaOneBlob = "$(Invoke-FixtureGit -Root $releaseRepo -Arguments @('hash-object', '-w', $schemaOneScript) | Select-Object -First 1)".Trim()
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('update-index', '--add', '--cacheinfo',
        "100644,$schemaOneBlob,scripts/release-receipt.ps1") | Out-Null
    $schemaOneTree = "$(Invoke-FixtureGit -Root $releaseRepo -Arguments @('write-tree') | Select-Object -First 1)".Trim()
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('reset', '--quiet') | Out-Null
    $schemaOneCommit = "$(Invoke-FixtureGit -Root $releaseRepo -Arguments @('commit-tree', $schemaOneTree, '-p', $releaseCommit,
        '-m', 'cut before the SBOM') | Select-Object -First 1)".Trim()
    $schemaOneSeconds = [long]"$(Invoke-FixtureGit -Root $releaseRepo -Arguments @('log', '-1', '--format=%ct', $schemaOneCommit) |
        Select-Object -First 1)".Trim()
    $receiptBytes = [System.IO.File]::ReadAllBytes($releaseReceipt)
    try {
        Save-ReleaseReceipt -Builds $releaseTarget.PackageVersions -Commit $schemaOneCommit -Seconds $schemaOneSeconds -Schema 1
        $said = Invoke-ReleaseCheck
        Assert-True ($said -like "*the receipt is held to schema 1, which its own commit $($schemaOneCommit.Substring(0, 8)) wrote*" -and
            $said -like "*the receipt proves $($releaseNames.Count) patches on*from commit $($schemaOneCommit.Substring(0, 8))*") `
            "A receipt cut before the SBOM was not read as its own commit wrote it: $said"
        Save-ReleaseReceipt -Builds $releaseTarget.PackageVersions -Commit $schemaOneCommit -Seconds $schemaOneSeconds
        Assert-Throws { Invoke-ReleaseCheck } '*schema version 3; its release is read at version 1*' `
            'A schema 3 receipt was accepted for a commit whose builder wrote schema 1.'
    } finally {
        [System.IO.File]::WriteAllBytes($releaseReceipt, $receiptBytes)
    }

    # A lag window: the source has moved on to the next version and pins the next Manager floor,
    # the clone already has that version's tag, and the index still describes the release before
    # it. The description is held to the floor the index version's tag pins, not the source
    # version's; the two tags pin different floors here, so reading the wrong one refuses the tree.
    # Then the same window in a clone without the index version's tag, which says the floor
    # wasn't checked.
    function Edit-ReleaseFile([string]$Name, [scriptblock]$Edit) {
        $path = Join-Path $releaseRepo $Name
        $text = [System.IO.File]::ReadAllText($path)
        $edited = & $Edit $text
        if ($edited -ceq $text) { throw "The lag window left $Name as it was, so the case below would prove nothing." }
        [System.IO.File]::WriteAllText($path, $edited, (New-Object System.Text.UTF8Encoding($false)))
    }
    $releasedAs = [version]$releaseVersionHere
    $laterVersion = "$($releasedAs.Major).$($releasedAs.Minor).$($releasedAs.Build + 1)"
    $releasedFloor = [version]$releaseFloor
    $laterFloor = "$($releasedFloor.Major).$($releasedFloor.Minor + 1).0"
    Edit-ReleaseFile 'gradle.properties' { param($text) $text -replace '(?m)^(\s*version\s*=\s*)\S+', "`${1}$laterVersion" }
    Edit-ReleaseFile 'patches-list.json' { param($text) $text.Replace('"v' + $releaseVersionHere + '"', '"v' + $laterVersion + '"') }
    Edit-ReleaseFile 'CHANGELOG.md' {
        param($text) ([regex]'(?m)^## ').Replace($text,
            "## $laterVersion (2026-09-26)`n`n* Needs Morphe Manager $laterFloor or newer.`n`n## ", 1)
    }
    Edit-ReleaseFile 'gradle/libs.versions.toml' {
        param($text) $text -replace '(?m)^(\s*manager-floor\s*=\s*")[^"]+', "`${1}$laterFloor"
    }
    Edit-ReleaseFile 'README.md' { param($text) $text.Replace($releaseFloor, $laterFloor) }
    Edit-ReleaseFile '.github/ISSUE_TEMPLATE/bug_report.yml' {
        param($text) $text.Replace("Morphe Manager $releaseFloor", "Morphe Manager $laterFloor")
    }
    Edit-ReleaseFile 'CONTRIBUTING.md' {
        param($text) $text.Replace("Morphe Manager $releaseFloor or newer", "Morphe Manager $laterFloor or newer")
    }
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('add', '--', 'gradle.properties', 'patches-list.json', 'CHANGELOG.md',
        'gradle/libs.versions.toml', 'README.md', '.github/ISSUE_TEMPLATE/bug_report.yml', 'CONTRIBUTING.md') | Out-Null
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('commit', '-m', 'next release', '--quiet') | Out-Null
    $laterCommit = "$(Invoke-FixtureGit -Root $releaseRepo -Arguments @('rev-parse', 'HEAD') | Select-Object -First 1)".Trim()
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('tag', "v$laterVersion", $laterCommit) | Out-Null
    $said = Invoke-ReleaseCheck
    Assert-True ($said -like "*source $laterVersion is being prepared while the working index remains on $indexVersionHere*" -and
        $said -like "*the index asks for Morphe Manager $releaseFloor or newer, as tag v$indexVersionHere pins*") `
        "In a lag window the index was not held to the floor its own version's tag pins: $said"
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('tag', '-d', "v$indexVersionHere") | Out-Null
    $said = Invoke-ReleaseCheck
    Assert-True ($said -like "*tag v$indexVersionHere isn't in this clone, so the Manager floor the index names wasn't checked*") `
        "A lag window without the index version's tag did not say the floor went unchecked: $said"
} finally {
    if ($releaseCatalogPreviousWeb) { Set-Item Function:\Invoke-WebRequest -Value $releaseCatalogPreviousWeb.ScriptBlock }
    else { Remove-Item Function:\Invoke-WebRequest -ErrorAction SilentlyContinue }
    Remove-Item -LiteralPath $releaseRoot -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host '[scripts] release root contracts passed'

# --- release bundle path ---------------------------------------------------------------------
#
# Every release step reads the bundle from patches/build/release. The plugin's buildAndroid
# finishes the bundle inside the jar task's own output, so a task run after it that reruns
# :patches:jar put the plain jar back under the same name in build/libs: v0.43.0 shipped with no
# classes.dex that way. buildAndroid copies the finished bundle to build/release, where nothing
# else writes, and the Gradle file and this helper have to agree on that directory.

$bundlePathRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("hushfacebook-bundle-path-" + [guid]::NewGuid().ToString('N'))
try {
    New-Item -ItemType Directory -Path $bundlePathRoot -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $bundlePathRoot 'gradle.properties') -Value 'version = 9.9.9' -Encoding ASCII
    $expectedRelease = Join-Path $bundlePathRoot 'patches/build/release/patches-9.9.9.mpp'
    Assert-True ((Get-ReleaseBundlePath -Root $bundlePathRoot) -eq $expectedRelease) `
        'The release bundle path did not follow gradle.properties into patches/build/release.'
    Assert-True ((Get-ReleaseBundlePath -Root $bundlePathRoot -Version '1.2.3') -eq
        (Join-Path $bundlePathRoot 'patches/build/release/patches-1.2.3.mpp')) `
        'An explicit version was not used for the release bundle path.'
} finally {
    Remove-Item -LiteralPath $bundlePathRoot -Recurse -Force -ErrorAction SilentlyContinue
}

$gradleFile = Get-Content -LiteralPath (Join-Path $Root 'patches/build.gradle.kts') -Raw
Assert-True ($gradleFile -match 'buildDirectory\.dir\("release"\)' -and
    $gradleFile -match 'buildDirectory\.file\("release/\$releaseBundleName"\)' -and
    $gradleFile -match 'buildDirectory\.file\("release/bundle\.sha256"\)') `
    'patches/build.gradle.kts no longer writes and verifies the bundle in build/release, where the scripts read it.'
# The receipt builder looks for the SBOM beside the bundle, under the bundle's name with .cdx.json
# for its extension, and the release check fetches it from the release under that name.
Assert-True ($gradleFile -match 'val releaseBundleName = "patches-\$\{project\.version\}\.mpp"' -and
    $gradleFile -match 'val releaseSbomName = "patches-\$\{project\.version\}\.cdx\.json"' -and
    $gradleFile -match 'output\.set\(layout\.buildDirectory\.file\("release/\$releaseSbomName"\)\)' -and
    $gradleFile -match 'finalizedBy\(releaseSbom\)') `
    'patches/build.gradle.kts no longer writes the SBOM beside the bundle in build/release after each buildAndroid.'
# The stamp names a commit only when the tree is that commit. A tree with uncommitted changes is
# stamped 0, which the receipt refuses; stamped with HEAD's time, it passed for a clean build.
# Asked before the commit time is read, and git status is asked with no optional locks.
Assert-True ($gradleFile -match 'commandLine\("git", "--no-optional-locks", "status", "--porcelain"\)' -and
    $gradleFile -match '(?s)val sourceDateEpoch: Long = run \{.*?if \(uncommittedChanges\?\.isEmpty\(\) != true\) return@run 0L.*?"log", "-1", "--format=%ct"') `
    'patches/build.gradle.kts stamps the bundle with the commit time without asking git whether the tree has uncommitted changes.'

# The patch tests run in two partitions. fixtureTest takes the classes whose source calls Fixtures
# or FixtureDex and declares the fixture folder's files and SHA-256 digests as inputs; test runs the
# rest without HUSHFACEBOOK_FIXTURE_DIR, after fixtureTest when it's asked for by name, and
# verifyPatchTestSelection holds both result sets to every *Test.kt. The pick holds only while
# Fixtures.kt is the one patch test source that reads the environment, so that's checked too.
foreach ($wired in @(
        @{ Pattern = 'tasks\.register<Test>\("fixtureTest"\)'; What = 'registers no fixtureTest' },
        @{ Pattern = 'Regex\("""\\b\(Fixtures\|FixtureDex\)\\\.\[A-Za-z\]"""\)'; What = 'picks fixture tests by another rule' },
        @{ Pattern = '(?s)register<Test>\("fixtureTest"\).*?include\(fixtureTestPatterns\).*?inputs\.files\(fixtureFiles\)\.withPropertyName\("fixtures"\).*?inputs\.property\("fixtureBytes"'
            What = 'does not give fixtureTest the fixture classes, files and digests' },
        @{ Pattern = '(?s)\n    test \{\s*dependsOn\(fixtureTest\)\s*finalizedBy\(verifyPatchTestSelection\)\s*exclude\(fixtureTestPatterns\)\s*environment\.remove\("HUSHFACEBOOK_FIXTURE_DIR"\)'
            What = 'does not run test after fixtureTest, without the fixture classes and the fixture folder' },
        @{ Pattern = 'check\(skipped\.getValue\("fixtureTest"\)\.isEmpty\(\)\)'; What = 'lets fixture tests skip with the folder set' })) {
    Assert-True ($gradleFile -match $wired.Pattern) "patches/build.gradle.kts $($wired.What)."
}
$environmentReaders = @(Get-ChildItem -LiteralPath (Join-Path $Root 'patches/src/test') -Recurse -File -Filter '*.kt' |
    Where-Object { [System.IO.File]::ReadAllText($_.FullName) -match '\b(System\.getenv|getenv)\s*\(' } | ForEach-Object { $_.Name })
Assert-True (($environmentReaders -join ',') -eq 'Fixtures.kt') `
    "Patch test sources other than Fixtures.kt read the environment, which fixtureTest's pick can't see: $($environmentReaders -join ', ')"
Write-Host '[scripts] patch test partition contracts passed'

# Code only: a comment may say where the bundle used to be read from.
$libsReaders = New-Object System.Collections.Generic.List[string]
foreach ($script in @(Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.ps1' -File)) {
    if ($script.Name -eq 'test-script-contracts.ps1') { continue }
    $inBlockComment = $false
    $number = 0
    foreach ($line in @(Get-Content -LiteralPath $script.FullName)) {
        $number++
        $trimmed = $line.Trim()
        if ($inBlockComment) {
            if ($trimmed -like '*#>*') { $inBlockComment = $false }
            continue
        }
        if ($trimmed.StartsWith('<#')) {
            if ($trimmed -notlike '*#>*') { $inBlockComment = $true }
            continue
        }
        if ($trimmed.StartsWith('#')) { continue }
        # The push gate's fixture step included: it patches with the release bundle, so the runs it
        # keeps are runs of the bundle a receipt names.
        if ($trimmed -match 'build[\\/]+libs') { $libsReaders.Add("$($script.Name):$number") }
    }
}
Assert-True ($libsReaders.Count -eq 0) `
    ("These script lines read patches/build/libs, which :patches:jar rewrites with the plain jar: " +
        ($libsReaders -join ', '))

Write-Host '[scripts] release bundle path contracts passed'

# --- a staged bundle held to a rebuild -------------------------------------------------------
#
# The identity a bundle carries (commit, clean flag, input digest) is the build's own word, so a
# bundle built from other source with a copied identity block passed the publish stage's check. A
# release builds its commit a second time in a fresh worktree now, and the two bundles are
# compared entry by entry (Compare-BundleContent) once each has been held to its own checkout
# (Test-RebuiltBundle). The bundles here are written entry by entry so each case moves one thing,
# and the two checkouts are a fixture repository and a worktree of it: nothing is built. The
# repository's checkout holds one source with CRLF endings and the worktree holds it with LF, the
# way a real rebuild of 138edb6b found two files, and git calls both clean.

$rebuildRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('hushfacebook-rebuilt-' + [guid]::NewGuid().ToString('N'))
try {
    $rebuildRepo = Join-Path $rebuildRoot 'repo'
    $rebuildFresh = Join-Path $rebuildRoot 'fresh'
    $rebuildSource = Join-Path $rebuildRepo 'patches/src/main/kotlin/Feed.kt'
    New-Item -ItemType Directory -Path (Split-Path -Parent $rebuildSource), (Join-Path $rebuildRepo 'extensions/facebook/src/main/java') -Force | Out-Null
    [System.IO.File]::WriteAllText((Join-Path $rebuildRepo '.gitattributes'), "* text=auto eol=lf`n")
    [System.IO.File]::WriteAllText((Join-Path $rebuildRepo 'gradle.properties'), "version = 9.9.9`n")
    [System.IO.File]::WriteAllText($rebuildSource, "package app.morphe`nval feed = 1`n")
    [System.IO.File]::WriteAllText((Join-Path $rebuildRepo 'extensions/facebook/src/main/java/Hook.java'), "class Hook {}`n")
    Invoke-FixtureGit -Root $rebuildRepo -Arguments @('init', '--quiet') | Out-Null
    $rebuildGitDir = "$(Invoke-FixtureGit -Root $rebuildRepo -Arguments @('rev-parse', '--absolute-git-dir') | Select-Object -First 1)".Trim()
    Assert-True ($rebuildGitDir -and ([IO.Path]::GetFullPath($rebuildGitDir).TrimEnd('\', '/') -ieq
            [IO.Path]::GetFullPath((Join-Path $rebuildRepo '.git')).TrimEnd('\', '/'))) `
        "The rebuild fixture resolved to $rebuildGitDir, not its own repository. Refusing to write."
    Invoke-FixtureGit -Root $rebuildRepo -Arguments @('config', 'user.email', 'contracts@example.invalid') | Out-Null
    Invoke-FixtureGit -Root $rebuildRepo -Arguments @('config', 'user.name', 'Contracts') | Out-Null
    Invoke-FixtureGit -Root $rebuildRepo -Arguments @('config', 'core.autocrlf', 'false') | Out-Null
    # No warning on stderr about the line endings below, which Windows PowerShell would throw on.
    Invoke-FixtureGit -Root $rebuildRepo -Arguments @('config', 'core.safecrlf', 'false') | Out-Null
    Invoke-FixtureGit -Root $rebuildRepo -Arguments @('add', '-A') | Out-Null
    Invoke-FixtureGit -Root $rebuildRepo -Arguments @('commit', '-m', 'release', '--quiet') | Out-Null
    $rebuildCommit = "$(Invoke-FixtureGit -Root $rebuildRepo -Arguments @('rev-parse', 'HEAD') | Select-Object -First 1)".Trim()
    # Added again, the CRLF copy is the blob the commit already holds, so the index only takes the
    # file's new size and git goes on calling the checkout clean.
    [System.IO.File]::WriteAllText($rebuildSource, "package app.morphe`r`nval feed = 1`r`n")
    Invoke-FixtureGit -Root $rebuildRepo -Arguments @('add', '--', 'patches/src/main/kotlin/Feed.kt') | Out-Null
    Invoke-FixtureGit -Root $rebuildRepo -Arguments @('worktree', 'add', '--quiet', '--detach', $rebuildFresh, $rebuildCommit) | Out-Null
    $rebuildInputs = Get-ProducerInputSha256 -Root $rebuildRepo
    $freshInputs = Get-ProducerInputSha256 -Root $rebuildFresh
    Assert-True (@(Invoke-FixtureGit -Root $rebuildRepo -Arguments @('status', '--porcelain')).Count -eq 0 -and
        @(Invoke-FixtureGit -Root $rebuildFresh -Arguments @('status', '--porcelain')).Count -eq 0 -and
        [System.IO.File]::ReadAllText($rebuildSource).Contains("`r`n") -and
        -not [System.IO.File]::ReadAllText((Join-Path $rebuildFresh 'patches/src/main/kotlin/Feed.kt')).Contains("`r") -and
        $rebuildInputs -cne $freshInputs) `
        'The two checkouts are not both clean with one source in CRLF here and LF there, so the line-ending cases prove nothing.'

    # A bundle as buildAndroid leaves it, stamped for the checkout it was built in: the source
    # fields in the manifest and the payload hash in the identity record. -Reframed writes the
    # same entries in the opposite order, stored instead of deflated, with another time on each.
    function New-RebuildBundle {
        param([string]$Path, [string]$Checkout, [hashtable]$Change = @{}, [string[]]$Without = @(),
            [string]$InputSha256, [string]$Commit = $rebuildCommit, [switch]$Reframed)
        if (-not $InputSha256) { $InputSha256 = Get-ProducerInputSha256 -Root $Checkout }
        $entries = [ordered]@{
            'META-INF/MANIFEST.MF' = "Manifest-Version: 1.0`nVersion: 9.9.9`nTimestamp: 1700000000000`nPatcher-Version: 1.12.0`n" +
                "Hushfacebook-Source-State: clean`nHushfacebook-Source-Commit: $Commit`nHushfacebook-Source-Tree: $('a' * 40)`n" +
                "Hushfacebook-Input-SHA256: $InputSha256`n`n"
            'app/morphe/patches/facebook/Feed.class' = 'feed class ' * 40
            'app/morphe/util/BundleIdentity.class' = 'identity class'
            'com/google/gson/Gson.class' = 'gson class'
            'classes.dex' = "dex`n035 patches"
            'extensions/facebook.mpe' = "dex`n035 facebook"
            'extensions/shared.mpe' = "dex`n035 shared"
            'META-INF/proguard/gson.pro' = '-keep class com.google.gson.** { *; }'
            'META-INF/hushfacebook-build.identity' = 'not sealed yet'
        }
        foreach ($name in $Change.Keys) { $entries[$name] = $Change[$name] }
        foreach ($name in $Without) { $entries.Remove($name) }
        $names = @($entries.Keys)
        $level = [System.IO.Compression.CompressionLevel]::Optimal
        $time = [DateTimeOffset]::new(2021, 3, 4, 5, 6, 8, [TimeSpan]::Zero)
        if ($Reframed) {
            [array]::Reverse($names)
            $level = [System.IO.Compression.CompressionLevel]::NoCompression
            $time = [DateTimeOffset]::new(1999, 12, 31, 23, 59, 58, [TimeSpan]::Zero)
        }
        if (Test-Path -LiteralPath $Path) { Remove-Item -LiteralPath $Path -Force }
        $zip = [System.IO.Compression.ZipFile]::Open($Path, [System.IO.Compression.ZipArchiveMode]::Create)
        try {
            foreach ($name in $names) {
                $entry = $zip.CreateEntry($name, $level)
                $entry.LastWriteTime = $time
                [byte[]]$bytes = [System.Text.Encoding]::UTF8.GetBytes([string]$entries[$name])
                $stream = $entry.Open()
                try { $stream.Write($bytes, 0, $bytes.Length) } finally { $stream.Dispose() }
            }
        } finally { $zip.Dispose() }
        if ($Without -ccontains 'META-INF/hushfacebook-build.identity') { return }
        $zip = [System.IO.Compression.ZipFile]::OpenRead($Path)
        try { $payload = Get-BundlePayloadSha256 -Archive $zip } finally { $zip.Dispose() }
        $zip = [System.IO.Compression.ZipFile]::Open($Path, [System.IO.Compression.ZipArchiveMode]::Update)
        try {
            $zip.GetEntry('META-INF/hushfacebook-build.identity').Delete()
            $writer = [System.IO.StreamWriter]::new($zip.CreateEntry('META-INF/hushfacebook-build.identity').Open())
            try { $writer.Write("hushfacebook-bundle-1`n$payload`n") } finally { $writer.Dispose() }
        } finally { $zip.Dispose() }
    }
    function Get-BundleFraming([string]$Path) {
        $zip = [System.IO.Compression.ZipFile]::OpenRead($Path)
        try {
            $class = $zip.GetEntry('app/morphe/patches/facebook/Feed.class')
            return [pscustomobject]@{ Order = (@($zip.Entries | ForEach-Object { $_.FullName }) -join '|')
                Time = $class.LastWriteTime.UtcDateTime; Packed = $class.CompressedLength }
        } finally { $zip.Dispose() }
    }
    function Test-RebuiltHere([string]$Root = $rebuildRepo, [string]$RebuiltRoot = $rebuildFresh) {
        Test-RebuiltBundle -Root $Root -BundlePath $stagedBundle -RebuiltRoot $RebuiltRoot -RebuiltPath $rebuiltBundle -Commit $rebuildCommit
    }

    # The same build twice: the staged bundle from the CRLF checkout, the rebuilt one from the LF
    # worktree, written in another order, stored and with other entry times. Their identity entries
    # differ, since each names its own checkout's input digest, and nothing else does. Equal, with
    # both identity entries reported as differing and neither counted.
    $stagedBundle = Join-Path $rebuildRoot 'staged/patches-9.9.9.mpp'
    $rebuiltBundle = Join-Path $rebuildRoot 'rebuilt/patches-9.9.9.mpp'
    New-Item -ItemType Directory -Path (Split-Path -Parent $stagedBundle), (Split-Path -Parent $rebuiltBundle) -Force | Out-Null
    New-RebuildBundle -Path $stagedBundle -Checkout $rebuildRepo
    New-RebuildBundle -Path $rebuiltBundle -Checkout $rebuildFresh -Reframed
    $stagedFraming = Get-BundleFraming $stagedBundle
    $rebuiltFraming = Get-BundleFraming $rebuiltBundle
    Assert-True ($stagedFraming.Order -cne $rebuiltFraming.Order -and $stagedFraming.Time -ne $rebuiltFraming.Time -and
        $stagedFraming.Packed -ne $rebuiltFraming.Packed) `
        'The two fixture bundles share an entry order, an entry time or a packed size, so the framing case proves nothing.'
    $sameBuild = Compare-BundleContent -BundlePath $stagedBundle -ReferencePath $rebuiltBundle
    Assert-True ($sameBuild.Equal -and $sameBuild.Compared -eq 7 -and $sameBuild.Differing.Count -eq 0 -and
        $sameBuild.Missing.Count -eq 0 -and $sameBuild.Extra.Count -eq 0) `
        "Two bundles that differ only in entry order, entry times, compression and build identity did not compare equal: $($sameBuild | ConvertTo-Json -Depth 4 -Compress)"
    Assert-True ((@($sameBuild.Identity | ForEach-Object { $_.Name }) -join '|') -ceq 'META-INF/MANIFEST.MF|META-INF/hushfacebook-build.identity' -and
        @($sameBuild.Identity | Where-Object { $_.Same -or -not $_.Sha256 -or -not $_.ReferenceSha256 }).Count -eq 0) `
        "The identity entries that differ were not reported apart from the comparison: $($sameBuild.Identity | ConvertTo-Json -Compress)"
    $held = Test-RebuiltHere
    Assert-True ($held.Valid -and $held.Comparison.Equal) "A rebuild from a checkout with other line endings and the same classes was refused: $($held.Reason)"

    # One changed entry of each kind, in a staged bundle whose identity is intact and names the
    # clean commit and its checkout's inputs, the way a copied identity block reads. Each is named
    # with its kind, and the rebuild check refuses the bundle for it.
    foreach ($changed in @(
            @{ Case = 'one changed class'; Entry = 'app/morphe/patches/facebook/Feed.class'; Kind = 'class'; Bytes = 'another class' },
            @{ Case = 'a changed DEX'; Entry = 'classes.dex'; Kind = 'DEX'; Bytes = "dex`n035 other" },
            @{ Case = 'one changed extension payload'; Entry = 'extensions/facebook.mpe'; Kind = 'extension payload'; Bytes = "dex`n035 other" },
            @{ Case = 'one changed resource'; Entry = 'META-INF/proguard/gson.pro'; Kind = 'resource'; Bytes = '-dontwarn' })) {
        New-RebuildBundle -Path $stagedBundle -Checkout $rebuildRepo -Change @{ $changed.Entry = $changed.Bytes }
        Assert-True (Get-BundleIdentityFacts -BundlePath $stagedBundle).Valid "The bundle with $($changed.Case) lost its identity, so the case proves nothing."
        $found = Compare-BundleContent -BundlePath $stagedBundle -ReferencePath $rebuiltBundle
        Assert-True (-not $found.Equal -and $found.Differing.Count -eq 1 -and $found.Missing.Count -eq 0 -and $found.Extra.Count -eq 0 -and
            $found.Differing[0].Name -ceq $changed.Entry -and $found.Differing[0].Kind -ceq $changed.Kind -and
            $found.Differing[0].Sha256 -cmatch '\A[0-9A-F]{64}\z' -and $found.Differing[0].ReferenceSha256 -cmatch '\A[0-9A-F]{64}\z' -and
            $found.Differing[0].Sha256 -cne $found.Differing[0].ReferenceSha256) `
            "The comparison did not report $($changed.Case) as the one difference: $($found | ConvertTo-Json -Depth 4 -Compress)"
        $held = Test-RebuiltHere
        Assert-True (-not $held.Valid -and $held.Reason -like "*isn't what commit $($rebuildCommit.Substring(0, 8)) builds: $($changed.Kind) $($changed.Entry) differs*" -and
            $held.Comparison.Differing.Count -eq 1) "A staged bundle with $($changed.Case) passed its rebuild: $($held.Reason)"
    }

    # An entry on one side only is a difference whichever side has it.
    New-RebuildBundle -Path $stagedBundle -Checkout $rebuildRepo -Without 'com/google/gson/Gson.class'
    $found = Compare-BundleContent -BundlePath $stagedBundle -ReferencePath $rebuiltBundle
    Assert-True (-not $found.Equal -and $found.Missing.Count -eq 1 -and $found.Missing[0].Name -ceq 'com/google/gson/Gson.class' -and
        $found.Missing[0].Kind -ceq 'class' -and $null -eq $found.Missing[0].Sha256 -and $found.Extra.Count -eq 0 -and $found.Differing.Count -eq 0) `
        "An entry the staged bundle lacks was not reported as missing: $($found | ConvertTo-Json -Depth 4 -Compress)"
    $held = Test-RebuiltHere
    Assert-True (-not $held.Valid -and $held.Reason -like '*class com/google/gson/Gson.class is only in the rebuild*') `
        "A staged bundle missing an entry passed its rebuild: $($held.Reason)"
    $found = Compare-BundleContent -BundlePath $rebuiltBundle -ReferencePath $stagedBundle
    Assert-True (-not $found.Equal -and $found.Extra.Count -eq 1 -and $found.Extra[0].Name -ceq 'com/google/gson/Gson.class' -and
        $null -eq $found.Extra[0].ReferenceSha256 -and $found.Missing.Count -eq 0 -and $found.Differing.Count -eq 0) `
        "An entry only the first bundle carries was not reported as extra: $($found | ConvertTo-Json -Depth 4 -Compress)"

    # The identity entries never decide. One of them on one side only isn't a difference, and two
    # bundles that hold nothing else can't be called equal: with no extension payload there is
    # nothing to compare, and that throws. So does a bundle naming an entry twice.
    New-RebuildBundle -Path $stagedBundle -Checkout $rebuildRepo -Without 'META-INF/hushfacebook-build.identity'
    $found = Compare-BundleContent -BundlePath $stagedBundle -ReferencePath $rebuiltBundle
    $record = @($found.Identity | Where-Object { $_.Name -ceq 'META-INF/hushfacebook-build.identity' })
    Assert-True ($found.Equal -and $record.Count -eq 1 -and $null -eq $record[0].Sha256 -and $record[0].ReferenceSha256 -and -not $record[0].Same) `
        "An identity record on one side only was counted as a difference, or not reported: $($found | ConvertTo-Json -Depth 4 -Compress)"
    $held = Test-RebuiltHere
    Assert-True (-not $held.Valid -and $held.Reason -like '*staged patches-9.9.9.mpp carries no intact build identity*' -and $null -eq $held.Comparison) `
        "A staged bundle with no identity record passed its rebuild: $($held.Reason)"
    $identityOnly = @('app/morphe/patches/facebook/Feed.class', 'app/morphe/util/BundleIdentity.class', 'com/google/gson/Gson.class',
        'classes.dex', 'extensions/facebook.mpe', 'extensions/shared.mpe', 'META-INF/proguard/gson.pro')
    $bareBundle = Join-Path $rebuildRoot 'bare.mpp'
    New-RebuildBundle -Path $bareBundle -Checkout $rebuildRepo -Without $identityOnly
    Assert-Throws { Compare-BundleContent -BundlePath $bareBundle -ReferencePath $bareBundle } '*carries no extension payload*' `
        'Two bundles holding nothing but their identity entries compared equal.'
    $twiceBundle = Join-Path $rebuildRoot 'twice.mpp'
    $zip = [System.IO.Compression.ZipFile]::Open($twiceBundle, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($name in @('extensions/facebook.mpe', 'classes.dex', 'classes.dex')) {
            $writer = [System.IO.StreamWriter]::new($zip.CreateEntry($name).Open())
            try { $writer.Write("dex`n035 $name") } finally { $writer.Dispose() }
        }
    } finally { $zip.Dispose() }
    Assert-Throws { Compare-BundleContent -BundlePath $twiceBundle -ReferencePath $rebuiltBundle } '*carries classes.dex twice*' `
        'A bundle naming an entry twice was compared as if a loader could only read one of them.'

    # Each bundle is held to the files of its own checkout as they are on disk, and nothing is
    # normalized first. The staged bundle carrying the worktree's digest is the CRLF checkout's
    # sources hashed as if they had LF endings, and it's refused, as is a rebuilt bundle carrying
    # the staged checkout's digest: a copy of the staged bundle is not a second build.
    New-RebuildBundle -Path $stagedBundle -Checkout $rebuildRepo -InputSha256 $freshInputs
    $held = Test-RebuiltHere
    Assert-True (-not $held.Valid -and $held.Reason -like "*staged patches-9.9.9.mpp isn't a build of commit*built from other sources*" -and $null -eq $held.Comparison) `
        "A staged bundle carrying the digest of its sources with other line endings passed: $($held.Reason)"
    New-RebuildBundle -Path $stagedBundle -Checkout $rebuildRepo
    New-RebuildBundle -Path $rebuiltBundle -Checkout $rebuildFresh -InputSha256 $rebuildInputs -Reframed
    $held = Test-RebuiltHere
    Assert-True (-not $held.Valid -and $held.Reason -like "*rebuilt patches-9.9.9.mpp isn't a build of commit*built from other sources*") `
        "A rebuilt bundle carrying the staged checkout's digest passed as a second build: $($held.Reason)"
    # A rebuild of another commit, one from the staged bundle's own checkout, and one whose
    # worktree holds a change are refused before anything is compared.
    New-RebuildBundle -Path $rebuiltBundle -Checkout $rebuildFresh -Commit ('c' * 40) -Reframed
    $held = Test-RebuiltHere
    Assert-True (-not $held.Valid -and $held.Reason -like "*rebuilt patches-9.9.9.mpp was built from clean source at $('c' * 40), not commit*") `
        "A rebuild of another commit backed the staged bundle: $($held.Reason)"
    New-RebuildBundle -Path $rebuiltBundle -Checkout $rebuildFresh -Reframed
    $held = Test-RebuiltHere -RebuiltRoot (Join-Path $rebuildRepo '.')
    Assert-True (-not $held.Valid -and $held.Reason -like '*has to come from a checkout of its own*') `
        "A rebuild from the staged bundle's own checkout counted as a second build: $($held.Reason)"
    $strayFile = Join-Path $rebuildFresh 'stray.txt'
    [System.IO.File]::WriteAllText($strayFile, 'stray')
    try {
        $held = Test-RebuiltHere
        Assert-True (-not $held.Valid -and $held.Reason -like "*isn't commit * with nothing changed, so the rebuilt patches-9.9.9.mpp can't be held to its sources*") `
            "A rebuild from a worktree holding a change backed the staged bundle: $($held.Reason)"
    } finally { Remove-Item -LiteralPath $strayFile -Force }
    $held = Test-RebuiltHere
    Assert-True $held.Valid "The control after the refusals was refused: $($held.Reason)"
} finally {
    Remove-Item -LiteralPath $rebuildRoot -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host '[scripts] rebuilt bundle comparison contracts passed'

# --- scripts/release/release.ps1 -----------------------------------------------------------------
#
# The release stages, run in a fixture repository whose checks are stand-ins that log what they
# were handed: the contract suite, the facts check, the receipt and the build wrapper as scripts,
# and py and gh as functions here, which release.ps1 finds before the real commands. Nothing here
# builds, patches or reaches GitHub. The origin is a bare repository beside the fixture. The
# rebuild stage's worktree is a real one, of the fixture, with the stand-in wrapper as its build.

$releaseFlow = Join-Path ([System.IO.Path]::GetTempPath()) ('hushfacebook-release-flow-' + [guid]::NewGuid().ToString('N'))
$flowNames = @('HUSHFACEBOOK_BUILD_WRAPPER', 'HUSHFACEBOOK_FIXTURE_DIR', 'GITHUB_ACTOR', 'GITHUB_TOKEN', 'BUILD_QUEUE_PRIORITY')
$flowBefore = @{}
foreach ($name in $flowNames) { $flowBefore[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    $flowRepo = Join-Path $releaseFlow 'repo'
    $flowOrigin = Join-Path $releaseFlow 'origin.git'
    $flowAssets = Join-Path $flowRepo 'build/release-assets/1.2.3'
    $flowLog = Join-Path $releaseFlow 'flow.log'
    $flowFails = Join-Path $releaseFlow 'fail.txt'
    $flowTamper = $null
    $flowGitHub = @{ Release = $null; DownloadsToInterrupt = 0; PublishedAssets = (Join-Path $releaseFlow 'published-assets') }
    New-Item -ItemType Directory -Path (Join-Path $flowRepo 'scripts/release') -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'common.ps1') -Destination (Join-Path $flowRepo 'scripts')
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'patch-target.ps1') -Destination (Join-Path $flowRepo 'scripts')
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'walk-receipt.ps1') -Destination (Join-Path $flowRepo 'scripts')
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'release/release.ps1') -Destination (Join-Path $flowRepo 'scripts/release')
    Set-Content -LiteralPath (Join-Path $flowRepo 'gradle.properties') -Value 'version = 1.2.3' -Encoding ASCII
    Set-Content -LiteralPath (Join-Path $flowRepo 'CHANGELOG.md') -Value '# Changelog' -Encoding ASCII
    Set-Content -LiteralPath (Join-Path $flowRepo '.gitignore') -Value @('build/', 'patches/build/', 'extensions/facebook/build/',
        'release-receipt-*.json') -Encoding ASCII
    $flowTarget = '"compatiblePackages":{"com.facebook.katana":["586.0.0.52.53"]},' +
        '"compatibility":[{"packageName":"com.facebook.katana","targets":[{"version":"586.0.0.52.53"}]}]'
    Set-Content -LiteralPath (Join-Path $flowRepo 'patches-list.json') -Encoding ASCII -Value (
        '{"patches":[{"name":"A",' + $flowTarget + '},{"name":"B",' + $flowTarget + '}]}')
    $logLiteral = $flowLog.Replace("'", "''")
    $failLiteral = $flowFails.Replace("'", "''")
    # What a link planted in the publish copy points at, with a file that must outlive the copy.
    $flowLinkTarget = Join-Path $releaseFlow 'link-target'
    New-Item -ItemType Directory -Force -Path $flowLinkTarget | Out-Null
    Set-Content -LiteralPath (Join-Path $flowLinkTarget 'keep.txt') -Value 'keep' -Encoding ASCII
    $linkTargetLiteral = $flowLinkTarget.Replace("'", "''")
    # The receipt names the commit it saw, as the real one does, and the bundle stage copies it.
    Set-Content -LiteralPath (Join-Path $flowRepo 'scripts/build-release-receipt.ps1') -Encoding UTF8 -Value @(
        'param([string[]]$Fixture, [string]$WorkDir, [string]$Root, [string]$Bundle, [string]$AppliedDir)',
        "Add-Content -LiteralPath '$logLiteral' -Value (""receipt fixtures="" + ((`$Fixture | ForEach-Object { Split-Path -Leaf `$_ }) -join ',') +",
        "    "" bundle="" + (Split-Path -Leaf `$Bundle) + "" applied="" + `$AppliedDir.Replace('\', '/'))",
        "if ((Get-Content -LiteralPath '$failLiteral' -ErrorAction SilentlyContinue) -eq 'receipt') { exit 1 }",
        '$commit = (git -C $Root rev-parse HEAD).Trim()',
        'Set-Content -LiteralPath (Join-Path $Root ''release-receipt-1.2.3.json'') -Value (''{"release":{"commit":"'' + $commit + ''"}}'') -Encoding ASCII',
        'exit 0')
    # Each stand-in fails when fail.txt names it, so a case can stop the run at any step.
    Set-Content -LiteralPath (Join-Path $flowRepo 'scripts/test-script-contracts.ps1') -Encoding UTF8 -Value @(
        'param([string]$Root)',
        "Add-Content -LiteralPath '$logLiteral' -Value ""contracts root=`$Root""",
        "if ((Get-Content -LiteralPath '$failLiteral' -ErrorAction SilentlyContinue) -eq 'contracts') { exit 1 }",
        'exit 0')
    Set-Content -LiteralPath (Join-Path $flowRepo 'scripts/validate-release-facts.ps1') -Encoding UTF8 -Value @(
        'param([string]$Root, [switch]$SkipDescriptionTestCount, [switch]$AllowPublishedIndexLag, [switch]$SkipTestResults)',
        "Add-Content -LiteralPath '$logLiteral' -Value (""facts skipCount=`$SkipDescriptionTestCount lag=`$AllowPublishedIndexLag "" +",
        "    ""skipResults=`$SkipTestResults priority=`$env:BUILD_QUEUE_PRIORITY"")",
        "if ((Get-Content -LiteralPath '$failLiteral' -ErrorAction SilentlyContinue) -eq 'facts') { exit 1 }",
        'exit 0')
    # The publish stage's check of its copy of the staged release logs the folder it was handed and
    # what's in it, and refuses it when fail.txt says staged. It also tries to change the bundle in
    # that copy, as anyone running as the same user could once it's checked, and logs whether it could.
    # When fail.txt says link, it plants a junction in the copy, as such a user could, and passes.
    Set-Content -LiteralPath (Join-Path $flowRepo 'scripts/release-receipt.ps1') -Encoding UTF8 -Value @(
        'function Test-StagedReleaseAt([string]$Root, [string]$Folder, [string]$Version, [string]$Commit) {',
        '    try { [IO.File]::AppendAllText((Join-Path $Folder ''patches-1.2.3.mpp''), ''swapped''); $swap = ''went-through'' } catch { $swap = ''refused'' }',
        "    if ((Get-Content -LiteralPath '$failLiteral' -ErrorAction SilentlyContinue) -eq 'link') {",
        "        New-Item -ItemType Junction -Path (Join-Path `$Folder 'linked') -Target '$linkTargetLiteral' | Out-Null }",
        "    Add-Content -LiteralPath '$logLiteral' -Value (""staged version=`$Version commit=`$Commit swap=`$swap folder=`$Folder files="" +",
        '        ((Get-ChildItem -LiteralPath $Folder -File | ForEach-Object { $_.Name } | Sort-Object) -join '',''))',
        "    if ((Get-Content -LiteralPath '$failLiteral' -ErrorAction SilentlyContinue) -eq 'staged') {",
        '        return [pscustomobject]@{ Valid = $false; Reason = ''the stand-in refused it'' } }',
        '    return [pscustomobject]@{ Valid = $true; Reason = $null }',
        '}',
        # The rebuild stage's check logs the commit, the checkout it was handed and that checkout's
        # HEAD, and whether both bundles are there. It tries to change the staged bundle the same
        # way, and refuses the pair when fail.txt says rebuilt.
        'function Test-RebuiltBundle([string]$Root, [string]$BundlePath, [string]$RebuiltRoot, [string]$RebuiltPath, [string]$Commit) {',
        '    try { [IO.File]::AppendAllText($BundlePath, ''swapped''); $swap = ''went-through'' } catch { $swap = ''refused'' }',
        '    $there = (Test-Path -LiteralPath $BundlePath -PathType Leaf) -and (Test-Path -LiteralPath $RebuiltPath -PathType Leaf)',
        "    Add-Content -LiteralPath '$logLiteral' -Value (""rebuilt commit=`$Commit swap=`$swap head=`$(git -C `$RebuiltRoot rev-parse HEAD) "" +",
        '        "staged=$(Split-Path -Leaf $BundlePath) bundle=$(Split-Path -Leaf $RebuiltPath) there=$there root=$RebuiltRoot")',
        "    if ((Get-Content -LiteralPath '$failLiteral' -ErrorAction SilentlyContinue) -eq 'rebuilt') {",
        '        return [pscustomobject]@{ Valid = $false; Reason = ''the stand-in found a changed class''; Comparison = $null } }',
        '    return [pscustomobject]@{ Valid = $true; Reason = $null; Comparison = [pscustomobject]@{ Compared = 3; Identity = @() } }',
        '}',
        # The identity the stand-in bundles carry follows their bytes, so a walk of one bundle
        # names another's identity once the bytes differ.
        'function Get-BundleIdentityFacts([string]$BundlePath) {',
        '    return [pscustomobject]@{ Present = $true; Valid = $true; SourceState = ''clean''; SourceCommit = (''1'' * 40)',
        '        SourceTree = (''2'' * 40); InputSha256 = (''3'' * 64)',
        '        PayloadSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $BundlePath).Hash.ToLowerInvariant() }',
        '}')
    $flowWrapper = Join-Path $releaseFlow 'wrapper.ps1'
    Set-Content -LiteralPath $flowWrapper -Encoding UTF8 -Value @(
        'param([string]$ProjectDir, [string[]]$Tasks)',
        "Add-Content -LiteralPath '$logLiteral' -Value (""gradle dir=`$ProjectDir priority=`$env:BUILD_QUEUE_PRIORITY tasks= "" + (`$Tasks -join ' ') + ' ')",
        "if ((Get-Content -LiteralPath '$failLiteral' -ErrorAction SilentlyContinue) -eq 'gradle') { exit 1 }",
        # The bundle task leaves what :patches:buildAndroid writes beside the bundle.
        'if ($Tasks -contains '':patches:buildAndroid'') {',
        '    $out = Join-Path $ProjectDir ''patches/build/release''',
        '    New-Item -ItemType Directory -Force -Path $out | Out-Null',
        '    foreach ($kind in ''mpp'', ''cdx.json'', ''tooling.json'') { Set-Content -LiteralPath (Join-Path $out "patches-1.2.3.$kind") -Value "built $kind" -Encoding ASCII }',
        '    Set-Content -LiteralPath (Join-Path $out ''hush-unpacker-1.2.3.apk'') -Value ''built apk'' -Encoding ASCII',
        '}',
        'exit 0')
    # py and gh log their arguments and fail when fail.txt names their subcommand. release_text's
    # cut writes to the CHANGELOG and github-notes writes its --out file, so a stage that fails
    # later has something to put back, and gh hands back what the release was given.
    function py {
        $call = @($args | ForEach-Object { [string]$_ })
        Add-Content -LiteralPath $flowLog -Value ('py ' + ($call -join ' '))
        $global:LASTEXITCODE = 0
        if ((Get-Content -LiteralPath $flowFails -ErrorAction SilentlyContinue) -eq $call[3]) { $global:LASTEXITCODE = 1; return }
        if ($call[3] -eq 'cut') { Add-Content -LiteralPath (Join-Path $flowRepo 'CHANGELOG.md') -Value '## cut' }
        if ($call[3] -eq 'github-notes') { Set-Content -LiteralPath $call[[array]::IndexOf($call, '--out') + 1] -Value 'notes' -Encoding ASCII }
    }
    function gh {
        # Native gh receives each element of a trailing argument array separately. A function
        # receives the array itself, so flatten it before reading or copying the uploaded files.
        $call = @(foreach ($argument in $args) { foreach ($value in @($argument)) { [string]$value } })
        Add-Content -LiteralPath $flowLog -Value ('gh ' + ($call -join ' '))
        $global:LASTEXITCODE = 0
        $what = "$($call[0]) $($call[1])"
        if ((Get-Content -LiteralPath $flowFails -ErrorAction SilentlyContinue) -eq $what) { $global:LASTEXITCODE = 1; return }
        if ($what -eq 'release create') {
            if ($null -ne $flowGitHub.Release) {
                $global:LASTEXITCODE = 1
                'HTTP 422: Validation Failed (already_exists)'
                return
            }
            New-Item -ItemType Directory -Force -Path $flowGitHub.PublishedAssets | Out-Null
            foreach ($argument in $call) {
                if (-not (Test-Path -LiteralPath $argument -PathType Leaf)) { continue }
                $name = Split-Path -Leaf $argument
                if ($name -notlike 'patches-*' -and $name -notlike 'release-receipt-*' -and $name -notlike 'hush-unpacker-*' -and $name -cne 'SHA256SUMS.txt') { continue }
                Copy-Item -LiteralPath $argument -Destination $flowGitHub.PublishedAssets
            }
            $flowGitHub.Release = [pscustomobject]@{
                tagName = $call[2]; isDraft = $false; publishedAt = '2026-10-09T21:03:04Z'
                assets = @(Get-ChildItem -LiteralPath $flowGitHub.PublishedAssets -File | ForEach-Object {
                    [pscustomobject]@{ name = $_.Name; size = $_.Length; state = 'uploaded' }
                })
            }
        }
        if ($what -eq 'release download') {
            if ($flowGitHub.DownloadsToInterrupt -gt 0) {
                $flowGitHub.DownloadsToInterrupt--
                $global:LASTEXITCODE = 1
                'download interrupted'
                return
            }
            if ($null -eq $flowGitHub.Release -or $flowGitHub.Release.isDraft) { $global:LASTEXITCODE = 1; return }
            $into = $call[[array]::IndexOf($call, '-D') + 1]
            New-Item -ItemType Directory -Force -Path $into | Out-Null
            Get-ChildItem -LiteralPath $flowGitHub.PublishedAssets -File | Copy-Item -Destination $into
            if ($flowTamper) { Set-Content -LiteralPath (Join-Path $into $flowTamper) -Value 'tampered' -Encoding ASCII }
        }
        if ($what -eq 'release view') {
            if ($null -eq $flowGitHub.Release) {
                $global:LASTEXITCODE = 1
                'release not found'
                return
            }
            if ($call -contains '--jq') { $flowGitHub.Release.publishedAt }
            else { $flowGitHub.Release | ConvertTo-Json -Depth 5 -Compress }
        }
        if ($what -eq 'repo view') { 'Hushfacebook v1.2.2: 1 patches for Facebook 581.0.0.45.58 with Morphe.' }
    }
    & git init --quiet --bare $flowOrigin
    & git -C $flowRepo init --quiet --initial-branch=main
    & git -C $flowRepo config user.name 'Release Contract'
    & git -C $flowRepo config user.email 'release@example.invalid'
    & git -C $flowRepo config core.autocrlf false
    # No hook of the machine's runs on the fixture's pushes.
    & git -C $flowRepo config core.hooksPath (Join-Path $releaseFlow 'no-hooks')
    & git -C $flowRepo remote add origin $flowOrigin
    & git -C $flowRepo add -A
    & git -C $flowRepo commit --quiet -m 'release fixture'
    $flowScript = Join-Path $flowRepo 'scripts/release/release.ps1'
    $env:HUSHFACEBOOK_BUILD_WRAPPER = $flowWrapper
    $env:GITHUB_ACTOR = 'contract'
    $env:GITHUB_TOKEN = 'contract'
    Remove-Item -LiteralPath Env:\BUILD_QUEUE_PRIORITY -ErrorAction SilentlyContinue
    function Read-FlowLog {
        if (-not (Test-Path -LiteralPath $flowLog -PathType Leaf)) { return }
        Get-Content -LiteralPath $flowLog
        Remove-Item -LiteralPath $flowLog -Force
    }
    function Test-FlowClean { return @(& git -C $flowRepo status --porcelain --untracked-files=all).Count -eq 0 }

    # Source cuts the CHANGELOG with the summary file, bumps the version, writes the patch list and
    # checks the translations, in that order, at release priority.
    $flowSummary = Join-Path $releaseFlow 'summary.txt'
    Set-Content -LiteralPath $flowSummary -Value 'A summary.' -Encoding ASCII
    & $flowScript -Stage source -Version 1.2.4 -Summary $flowSummary -Date 2026-10-09 6> $null 3> $null
    $flow = @(Read-FlowLog)
    Assert-True ($flow.Count -eq 4 -and $flow[0] -like 'py -3.13 -I *release_text.py cut --version 1.2.4 --summary *' -and
        $flow[0].EndsWith(' --date 2026-10-09') -and $flow[1] -like 'py * bump --version 1.2.4' -and
        $flow[2] -like 'gradle * tasks= :patches:generatePatchesList ' -and $flow[2].Contains(' priority=release ') -and
        $flow[3] -like 'py * check-translations') "Source did not cut, bump, list and check in order: $($flow -join ' | ')"
    Assert-True (-not (Test-FlowClean)) 'Source left no cut behind to review.'
    & git -C $flowRepo checkout --quiet -- .

    # A failing step puts the tree back, so the stage can run again after the fix.
    Set-Content -LiteralPath $flowFails -Value 'bump' -Encoding ASCII
    Assert-Throws { & $flowScript -Stage source -Version 1.2.4 -Summary $flowSummary 6> $null 3> $null } '*version bump did not pass*' `
        'Source carried on past a failing bump.'
    Assert-True (@(Read-FlowLog).Count -eq 2 -and (Test-FlowClean)) 'A failed source stage ran on, or left its CHANGELOG cut behind.'
    Remove-Item -LiteralPath $flowFails -Force

    # No summary, a tag that exists or a branch other than main is refused before anything runs.
    Assert-Throws { & $flowScript -Stage source -Version 1.2.4 6> $null } '*-Summary*' 'Source ran without a summary.'
    & git -C $flowRepo tag v1.2.4
    Assert-Throws { & $flowScript -Stage source -Version 1.2.4 -Summary $flowSummary 6> $null } '*v1.2.4 already exists*' `
        'Source cut a version that is already tagged.'
    & git -C $flowRepo tag -d v1.2.4 | Out-Null
    & git -C $flowRepo checkout --quiet -b side
    Assert-Throws { & $flowScript -Stage source -Version 1.2.4 -Summary $flowSummary 6> $null } '*cut on main*' `
        'Source cut a release off main.'
    & git -C $flowRepo checkout --quiet main
    & git -C $flowRepo branch --quiet -D side
    Assert-True (@(Read-FlowLog).Count -eq 0) 'A refused source stage still ran a step.'

    # Preflight runs the contract suite, the release text tests, the translation check, a notes
    # draft, one quick Gradle run and the facts check with the index allowed to lag, in that order,
    # all at release priority, and puts the priority back after.
    & $flowScript -Stage preflight -Version 1.2.3 6> $null 3> $null
    $flow = @(Read-FlowLog)
    Assert-True ($flow.Count -eq 6 -and $flow[0] -like 'contracts root=*' -and $flow[1] -like 'py -3.13 -I -m unittest discover -s *tools -p test_*.py' -and
        $flow[2] -like 'py * check-translations' -and $flow[3] -like 'py * github-notes --version 1.2.3 --draft --out *notes-draft.md' -and
        $flow[4] -like 'gradle *' -and $flow[5] -like 'facts *') `
        "Preflight did not run the contracts, text checks, Gradle and the facts check in order: $($flow -join ' | ')"
    Assert-True ([IO.Path]::GetFullPath($flow[0].Substring('contracts root='.Length)).TrimEnd('\', '/') -ieq
        [IO.Path]::GetFullPath($flowRepo).TrimEnd('\', '/')) "Preflight ran the contract suite on another tree: $($flow[0])"
    Assert-True ((Test-Path -LiteralPath (Join-Path $flowAssets 'notes-draft.md')) -and (Test-FlowClean)) `
        'Preflight wrote no notes draft into the release assets, or left the tree changed.'
    foreach ($wanted in @(' :patches:test -x :patches:fixtureTest -x :patches:verifyPatchTestSelection ',
            ' :extensions:facebook:testDebugUnitTest --tests *L10nTest ', ' --tests *ReleaseCheckTest ',
            ' :extensions:shared:library:lint ', ' :extensions:facebook:lint ', ' priority=release ')) {
        Assert-True ($flow[4].Contains($wanted)) "Preflight's Gradle run lacks '$($wanted.Trim())': $($flow[4])"
    }
    Assert-True ($flow[4] -notmatch '(?<!-x) :patches:fixtureTest ' -and -not $flow[4].Contains(':patches:buildAndroid')) `
        "Preflight's Gradle run reached the fixture tests or the bundle: $($flow[4])"
    Assert-True ($flow[5] -eq 'facts skipCount=True lag=True skipResults=True priority=release') `
        "Preflight's facts check was not the lagging-index precheck at release priority: $($flow[5])"
    Assert-True (-not $env:BUILD_QUEUE_PRIORITY -and $env:HUSHFACEBOOK_BUILD_WRAPPER -eq $flowWrapper) `
        'Preflight left its release priority behind, or lost the wrapper it was given.'

    # Any step that fails stops the run there, so nothing after it reads as checked.
    foreach ($case in @(@{ Step = 'contracts'; Seen = 1 }, @{ Step = 'unittest'; Seen = 2 }, @{ Step = 'github-notes'; Seen = 4 },
            @{ Step = 'gradle'; Seen = 5 }, @{ Step = 'facts'; Seen = 6 })) {
        Set-Content -LiteralPath $flowFails -Value $case.Step -Encoding ASCII
        Assert-Throws { & $flowScript -Stage preflight -Version 1.2.3 6> $null 3> $null } '*did not pass*' `
            "Preflight carried on past a failing $($case.Step) step."
        $flow = @(Read-FlowLog)
        Assert-True ($flow.Count -eq $case.Seen) "A failing $($case.Step) step still ran what follows it: $($flow -join ' | ')"
        Assert-True (-not $env:BUILD_QUEUE_PRIORITY) "A failing $($case.Step) step left the release priority behind."
    }
    Remove-Item -LiteralPath $flowFails -Force

    # A version gradle.properties doesn't carry, or a tree with changes, is refused before any check.
    Assert-Throws { & $flowScript -Stage preflight -Version 1.2.4 6> $null } '*says 1.2.3, not 1.2.4*' `
        'Preflight checked a version the source does not carry.'
    Set-Content -LiteralPath (Join-Path $flowRepo 'stray.txt') -Value 'stray' -Encoding ASCII
    Assert-Throws { & $flowScript -Stage preflight -Version 1.2.3 6> $null } '*working tree has changes*' `
        'Preflight checked a working tree that is not a commit.'
    Remove-Item -LiteralPath (Join-Path $flowRepo 'stray.txt') -Force
    Assert-True (@(Read-FlowLog).Count -eq 0) 'A refused preflight still ran a check.'

    # Bundle, publish and index start from the pushed source commit, and a commit origin lacks is
    # refused before anything is built.
    Assert-Throws { & $flowScript -Stage bundle -Version 1.2.3 6> $null } '*Push the source commit first*' `
        'The bundle was built from a commit origin does not have.'
    & git -C $flowRepo push --quiet origin main
    $flowFixtures = Join-Path $releaseFlow 'fixtures'
    New-Item -ItemType Directory -Force -Path $flowFixtures | Out-Null
    $env:HUSHFACEBOOK_FIXTURE_DIR = $flowFixtures
    Assert-Throws { & $flowScript -Stage bundle -Version 1.2.3 6> $null 3> $null } '*no fixture of Facebook 586.0.0.52.53*' `
        'The bundle stage cut a receipt without a fixture of the declared build.'
    Assert-True (@(Read-FlowLog).Count -eq 1) 'The bundle stage went past a declared build with no fixture.'
    foreach ($abi in 'arm64-v8a', 'armeabi-v7a') { Set-Content -LiteralPath (Join-Path $flowFixtures "facebook-586.0.0.52.53-$abi.apkm") -Value $abi }
    Set-Content -LiteralPath (Join-Path $flowFixtures 'facebook-580.0.0.51.74-arm64-v8a.apkm') -Value 'undeclared'

    # Bundle builds once, hands the receipt every declared build's fixtures and the gate's kept
    # runs, and lists the six assets' hashes in SHA256SUMS.txt.
    & $flowScript -Stage bundle -Version 1.2.3 6> $null 3> $null
    $flow = @(Read-FlowLog)
    Assert-True ($flow.Count -eq 2 -and $flow[0] -like 'gradle * tasks= :patches:buildAndroid ' -and
        $flow[1] -like ('receipt fixtures=facebook-586.0.0.52.53-arm64-v8a.apkm,facebook-586.0.0.52.53-armeabi-v7a.apkm ' +
            'bundle=patches-1.2.3.mpp applied=*/repo/patches/build/fixture-apply')) `
        "The bundle stage did not build and cut the receipt over the declared fixtures: $($flow -join ' | ')"
    $sums = @(Get-Content -LiteralPath (Join-Path $flowAssets 'SHA256SUMS.txt'))
    $listed = @($sums | ForEach-Object { ($_ -split '  ', 2)[1] })
    Assert-True (($listed -join ',') -eq 'patches-1.2.3.mpp,patches-1.2.3.cdx.json,patches-1.2.3.tooling.json,hush-unpacker-1.2.3.apk,release-receipt-1.2.3.json') `
        "SHA256SUMS.txt lists $($listed -join ', ')."
    foreach ($line in $sums) {
        $hash, $name = $line -split '  ', 2
        Assert-True ($hash -ceq (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $flowAssets $name)).Hash.ToLowerInvariant()) `
            "SHA256SUMS.txt's hash of $name is not the asset's."
    }
    Assert-True (Test-FlowClean) 'The bundle stage left the tree changed.'

    # Publish writes the notes with the checked paragraph, tags and pushes the commit, creates the
    # release with the six assets and holds each downloaded copy to the local one.
    Assert-Throws { & $flowScript -Stage publish -Version 1.2.3 6> $null } '*-Checked*' 'Publish ran without a checked paragraph.'
    $flowChecked = Join-Path $releaseFlow 'checked.txt'
    Set-Content -LiteralPath $flowChecked -Value 'Checked.' -Encoding ASCII
    # Nor without the receipt of a whole-app walk.
    Assert-Throws { & $flowScript -Stage publish -Version 1.2.3 -Checked $flowChecked 6> $null } '*-Walk*' 'Publish ran without a walk receipt.'
    Assert-True (@(Read-FlowLog).Count -eq 0) 'Publish ran a step before asking for the walk receipt.'
    # The walk publish holds the bundle to: a full PASS on a release phone, written the way
    # device-audit writes it, naming the identity the staged bundle carries. -Change bends one field.
    $flowWalk = Join-Path $releaseFlow 'walk-receipt.json'
    function Write-FlowWalk([hashtable]$Change = @{}) {
        $walkIdentity = 'sha256=' + (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $flowAssets 'patches-1.2.3.mpp')).Hash.ToLowerInvariant() +
            '; source=clean:' + ('1' * 40) + '; tree=' + ('2' * 40) + '; inputs=' + ('3' * 64)
        $walkReceipt = New-WalkReceipt -Result PASS -Full $true -Serial 'TESTPHONE01' -Device 'SM-S908U1' -Facebook '586.0.0.52.53' `
            -PatchBuild $walkIdentity -InstalledApkSha256 ('4' * 64) -Started '2026-10-21T16:00:00Z' -Finished '2026-10-21T16:30:00Z' `
            -FamiliesRan 120 -Families 160 -Crashes 0
        foreach ($key in $Change.Keys) { $walkReceipt[$key] = $Change[$key] }
        Set-Content -LiteralPath $flowWalk -Value ($walkReceipt | ConvertTo-Json -Depth 4) -Encoding ASCII
    }
    Write-FlowWalk
    # Nor without the device register check's receipt, written the way verify-injected-registers.ps1
    # -ReceiptPath writes it: on the S22, Meta's build and the patched one raising the same messages,
    # with the identity the staged bundle carries read out of the verified APK.
    $flowVerifier = Join-Path $releaseFlow 'verifier-receipt.json'
    Assert-Throws { & $flowScript -Stage publish -Version 1.2.3 -Checked $flowChecked -Walk $flowWalk 6> $null } '*-Verifier*' `
        'Publish ran without a verifier receipt.'
    Assert-True (@(Read-FlowLog).Count -eq 0) 'Publish ran a step before asking for the verifier receipt.'
    function Write-FlowVerifier([hashtable]$Change = @{}, [object[]]$Deltas = @(), [bool]$StaticPassed = $true) {
        $verifierIdentity = 'sha256=' + (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $flowAssets 'patches-1.2.3.mpp')).Hash.ToLowerInvariant() +
            '; source=clean:' + ('1' * 40) + '; tree=' + ('2' * 40) + '; inputs=' + ('3' * 64)
        $patchedMessages = 0
        foreach ($delta in $Deltas) { $patchedMessages += $delta.Patched }
        $verifierReceipt = New-VerifierReceipt -Serial 'TESTPHONE01' -Device 'SM-S908U1' -Facebook '586.0.0.52.53' `
            -PatchBuild $verifierIdentity -PatchedApkSha256 ('4' * 64) -StaticPassed $StaticPassed `
            -Comparison ([pscustomobject]@{ Valid = $Deltas.Count -eq 0; CleanTotal = 0; PatchedTotal = $patchedMessages; Deltas = $Deltas }) `
            -Finished '2026-10-21T16:40:00Z'
        foreach ($key in $Change.Keys) { $verifierReceipt[$key] = $Change[$key] }
        Set-Content -LiteralPath $flowVerifier -Value ($verifierReceipt | ConvertTo-Json -Depth 5) -Encoding ASCII
    }
    Write-FlowVerifier

    # Rebuild comes first. Publish refuses a staged bundle no rebuild has backed, before the notes,
    # the tag or GitHub.
    $flowHead = "$(& git -C $flowRepo rev-parse HEAD)".Trim()
    $flowBundle = Join-Path $flowAssets 'patches-1.2.3.mpp'
    $flowNote = Join-Path $flowAssets 'rebuild-1.2.3.json'
    function Test-FlowUntagged { return -not (& git -C $flowRepo tag --list v1.2.3) -and -not (& git -C $flowOrigin tag --list v1.2.3) }
    Assert-Throws { & $flowScript -Stage publish -Version 1.2.3 -Checked $flowChecked -Walk $flowWalk -Verifier $flowVerifier 6> $null 3> $null } '*Run -Stage rebuild first*' `
        'Publish went ahead with a bundle the rebuild stage never compared.'
    Assert-True (@(Read-FlowLog).Count -eq 0 -and (Test-FlowUntagged)) 'Publish ran a step or tagged before the rebuild stage had run.'

    # The stage builds the commit once more in a worktree of its own under the temp folder, and
    # takes that worktree away again whatever happens. Another worktree of the repository, and a
    # folder in the temp folder named the way the stage names its own, are somebody else's.
    $flowBystander = Join-Path $releaseFlow 'bystander'
    & git -C $flowRepo worktree add --quiet --detach $flowBystander $flowHead
    $flowLookalike = Join-Path ([System.IO.Path]::GetTempPath()) ('hushfacebook-rebuild-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $flowLookalike | Out-Null
    try {
        foreach ($kept in $flowBystander, $flowLookalike) { Set-Content -LiteralPath (Join-Path $kept 'keep.txt') -Value 'keep' -Encoding ASCII }
        function Get-FlowWorktrees { return @(& git -C $flowRepo worktree list --porcelain | Where-Object { $_ -like 'worktree *' }) -join '|' }
        $flowWorktrees = Get-FlowWorktrees
        Assert-True (@($flowWorktrees -split '\|').Count -eq 2) "The fixture does not hold its own checkout and one more worktree: $flowWorktrees"
        # The folder the build wrapper was sent to, which has to be a fresh one in the temp folder
        # and gone again, with every other worktree and the two bystanders as they were.
        function Assert-RebuildCleanedUp([string]$Line, [string]$When) {
            $tree = [regex]::Match($Line, '^gradle dir=(.+) priority=release tasks= :patches:buildAndroid --no-build-cache $').Groups[1].Value
            Assert-True ($tree -and (Split-Path -Leaf $tree) -cmatch '\Ahushfacebook-rebuild-[0-9a-f]{32}\z' -and
                (Split-Path -Parent $tree).TrimEnd('\', '/') -ieq [System.IO.Path]::GetTempPath().TrimEnd('\', '/') -and $tree -ine $flowLookalike) `
                "$When did not build in a fresh folder of its own under the temp folder: $Line"
            Assert-True (-not (Test-Path -LiteralPath $tree)) "$When left its worktree behind: $tree"
            Assert-True ((Get-FlowWorktrees) -ceq $flowWorktrees -and
                (Test-Path -LiteralPath (Join-Path $flowBystander 'keep.txt') -PathType Leaf) -and
                (Test-Path -LiteralPath (Join-Path $flowBystander 'gradle.properties') -PathType Leaf) -and
                (Test-Path -LiteralPath (Join-Path $flowLookalike 'keep.txt') -PathType Leaf)) `
                "$When took away a worktree or a folder that wasn't its own: $(Get-FlowWorktrees)"
            return $tree
        }

        # A rebuild that fails stops the stage and leaves no note, so publish still refuses and
        # nothing is tagged.
        Set-Content -LiteralPath $flowFails -Value 'gradle' -Encoding ASCII
        Assert-Throws { & $flowScript -Stage rebuild -Version 1.2.3 6> $null 3> $null } '*Gradle :patches:buildAndroid --no-build-cache did not pass*' `
            'The rebuild stage carried on past a rebuild that failed.'
        Remove-Item -LiteralPath $flowFails -Force
        $flow = @(Read-FlowLog)
        Assert-True ($flow.Count -eq 1) "A failed rebuild still ran what follows it: $($flow -join ' | ')"
        $null = Assert-RebuildCleanedUp $flow[0] 'A failed rebuild'
        Assert-True (-not (Test-Path -LiteralPath $flowNote)) 'A failed rebuild left a note for publish to read.'
        Assert-Throws { & $flowScript -Stage publish -Version 1.2.3 -Checked $flowChecked -Walk $flowWalk -Verifier $flowVerifier 6> $null 3> $null } '*Run -Stage rebuild first*' `
            'Publish went ahead after a rebuild that failed.'
        Assert-True (@(Read-FlowLog).Count -eq 0 -and (Test-FlowUntagged)) 'Publish ran a step or tagged after a rebuild that failed.'

        # A rebuilt bundle that doesn't match stops it the same way, after its one build.
        Set-Content -LiteralPath $flowFails -Value 'rebuilt' -Encoding ASCII
        Assert-Throws { & $flowScript -Stage rebuild -Version 1.2.3 6> $null 3> $null } `
            "*doesn't back the staged bundle, so it can't be published: the stand-in found a changed class*" `
            'The rebuild stage passed a staged bundle its check refused.'
        Remove-Item -LiteralPath $flowFails -Force
        $flow = @(Read-FlowLog)
        Assert-True ($flow.Count -eq 2 -and $flow[1] -like 'rebuilt *') "A refused rebuild did not build once and compare: $($flow -join ' | ')"
        $null = Assert-RebuildCleanedUp $flow[0] 'A refused rebuild'
        Assert-True (-not (Test-Path -LiteralPath $flowNote) -and (Test-FlowUntagged)) 'A refused rebuild left a note for publish to read, or a tag.'

        # A rebuild that matches: one Gradle run for the bundle, in a worktree detached at the source
        # commit, at release priority, then the check on the staged bundle (held, so the stand-in's
        # change is refused) and the bundle the worktree's build left. The note names the commit and
        # the staged bundle's hash.
        & $flowScript -Stage rebuild -Version 1.2.3 6> $null 3> $null
        $flow = @(Read-FlowLog)
        Assert-True ($flow.Count -eq 2) "The rebuild stage did not build once and compare: $($flow -join ' | ')"
        $rebuiltTree = Assert-RebuildCleanedUp $flow[0] 'A rebuild that matched'
        Assert-True ($flow[1] -ceq ("rebuilt commit=$flowHead swap=refused head=$flowHead staged=patches-1.2.3.mpp " +
                "bundle=patches-1.2.3.mpp there=True root=$rebuiltTree")) `
            "The rebuild's check was not handed the held staged bundle and the worktree's own, at the source commit: $($flow[1])"
        $note = Get-Content -LiteralPath $flowNote -Raw | ConvertFrom-Json
        Assert-True ($note.version -ceq '1.2.3' -and $note.commit -ceq $flowHead -and $note.bundle.file -ceq 'patches-1.2.3.mpp' -and
            $note.bundle.sha256 -ceq (Get-FileHash -Algorithm SHA256 -LiteralPath $flowBundle).Hash.ToLowerInvariant() -and $note.compared -eq 3) `
            "The rebuild's note does not name the commit and the staged bundle's hash: $(Get-Content -LiteralPath $flowNote -Raw)"
        Assert-True (Test-FlowClean) 'The rebuild stage left the tree changed.'
        Assert-True (-not $env:BUILD_QUEUE_PRIORITY) 'The rebuild stage left its release priority behind.'

        # One more build a release, not one a run: with that note there the stage builds nothing.
        & $flowScript -Stage rebuild -Version 1.2.3 6> $null 3> $null
        Assert-True (@(Read-FlowLog).Count -eq 0) 'The rebuild stage built again for a bundle it had already compared.'

        # A bundle changed since isn't the one that was compared, and publish refuses it before the tag.
        $keptBundle = [System.IO.File]::ReadAllBytes($flowBundle)
        [System.IO.File]::AppendAllText($flowBundle, 'changed')
        Assert-Throws { & $flowScript -Stage publish -Version 1.2.3 -Checked $flowChecked -Walk $flowWalk -Verifier $flowVerifier 6> $null 3> $null } `
            "*isn't the bundle a rebuild of $flowHead was compared with, so nothing was tagged*" `
            'Publish went ahead with a bundle changed since its rebuild.'
        [System.IO.File]::WriteAllBytes($flowBundle, $keptBundle)
        $flow = @(Read-FlowLog)
        Assert-True ($flow.Count -eq 1 -and $flow[0] -like 'staged *' -and (Test-FlowUntagged)) `
            "Publish ran a step or tagged with a bundle changed since its rebuild: $($flow -join ' | ')"
        # Nor does a note for another commit back this one: publish refuses, and the stage builds again.
        $keptNote = Get-Content -LiteralPath $flowNote -Raw
        Set-Content -LiteralPath $flowNote -Value $keptNote.Replace($flowHead, ('b' * 40)) -Encoding ASCII -NoNewline
        Assert-Throws { & $flowScript -Stage publish -Version 1.2.3 -Checked $flowChecked -Walk $flowWalk -Verifier $flowVerifier 6> $null 3> $null } `
            "*isn't the bundle a rebuild of $flowHead was compared with, so nothing was tagged*" `
            'Publish went ahead on a rebuild of another commit.'
        Assert-True (@(Read-FlowLog).Count -eq 1 -and (Test-FlowUntagged)) 'Publish ran a step or tagged on a rebuild of another commit.'
        & $flowScript -Stage rebuild -Version 1.2.3 6> $null 3> $null
        $flow = @(Read-FlowLog)
        Assert-True ($flow.Count -eq 2 -and (Get-Content -LiteralPath $flowNote -Raw | ConvertFrom-Json).commit -ceq $flowHead) `
            "The rebuild stage did not build again over a note for another commit: $($flow -join ' | ')"
        $null = Assert-RebuildCleanedUp $flow[0] 'A second rebuild'
    } finally {
        Remove-Item -LiteralPath $flowLookalike -Recurse -Force -ErrorAction SilentlyContinue
    }

    # A receipt for another commit, or one naming none, is refused before anything is tagged: the
    # assets would go out under a tag on a commit they weren't built from.
    $flowReceipt = Join-Path $flowAssets 'release-receipt-1.2.3.json'
    $keptReceipt = Get-Content -LiteralPath $flowReceipt -Raw
    foreach ($other in @(('{"release":{"commit":"' + ('b' * 40) + '"}}'), '{"release":{}}')) {
        Set-Content -LiteralPath $flowReceipt -Value $other -Encoding ASCII -NoNewline
        Assert-Throws { & $flowScript -Stage publish -Version 1.2.3 -Checked $flowChecked -Walk $flowWalk -Verifier $flowVerifier 6> $null 3> $null } '*The receipt is for*' `
            "Publish went ahead with a receipt that isn't for HEAD: $other"
    }
    Set-Content -LiteralPath $flowReceipt -Value $keptReceipt -Encoding ASCII -NoNewline
    Assert-True (@(Read-FlowLog).Count -eq 0) 'Publish ran a step before refusing the receipt.'
    Assert-True (-not (& git -C $flowRepo tag --list v1.2.3)) 'Publish tagged the commit before refusing the receipt.'
    # A staged release its check refuses stops the stage before the notes, the tag or GitHub, and
    # the copy it checked goes.
    Set-Content -LiteralPath $flowFails -Value 'staged' -Encoding ASCII
    Assert-Throws { & $flowScript -Stage publish -Version 1.2.3 -Checked $flowChecked -Walk $flowWalk -Verifier $flowVerifier 6> $null 3> $null } '*nothing was tagged: the stand-in refused it*' `
        'Publish went ahead with a staged release its check refused.'
    Remove-Item -LiteralPath $flowFails -Force
    $flow = @(Read-FlowLog)
    Assert-True ($flow.Count -eq 1 -and $flow[0] -like 'staged *') "Publish ran a step past a refused staged release: $($flow -join ' | ')"
    Assert-True (-not (& git -C $flowRepo tag --list v1.2.3) -and -not (& git -C $flowOrigin tag --list v1.2.3)) 'Publish tagged a staged release its check refused.'
    $refusedCopy = [regex]::Match($flow[0], ' folder=(.+) files=').Groups[1].Value
    Assert-True ($refusedCopy -and -not (Test-Path -LiteralPath $refusedCopy)) 'A refused publish left the copy it checked behind.'
    # A link in the copy, which could be pointed elsewhere before gh reads it, stops the stage before
    # the notes, the tag or GitHub. Removing the copy takes the link away, not what it points at.
    Set-Content -LiteralPath $flowFails -Value 'link' -Encoding ASCII
    Assert-Throws { & $flowScript -Stage publish -Version 1.2.3 -Checked $flowChecked -Walk $flowWalk -Verifier $flowVerifier 6> $null 3> $null } '*linked in the publishing copy is a link, so nothing was tagged*' `
        'Publish went ahead with a link in the copy it checked.'
    Remove-Item -LiteralPath $flowFails -Force
    $flow = @(Read-FlowLog)
    Assert-True ($flow.Count -eq 1 -and $flow[0] -like 'staged *') "Publish ran a step past a link in its copy: $($flow -join ' | ')"
    Assert-True (-not (& git -C $flowRepo tag --list v1.2.3) -and -not (& git -C $flowOrigin tag --list v1.2.3)) 'Publish tagged a copy holding a link.'
    $linkedCopy = [regex]::Match($flow[0], ' folder=(.+) files=').Groups[1].Value
    Assert-True ($linkedCopy -and -not (Test-Path -LiteralPath $linkedCopy)) 'A publish refused for a link left its copy behind.'
    Assert-True (Test-Path -LiteralPath (Join-Path $flowLinkTarget 'keep.txt') -PathType Leaf) 'Removing the publish copy emptied what its link pointed at.'
    # A walk that failed, stopped short, crashed, ran on another phone or walked another bundle
    # stops the stage before the notes, the tag or GitHub, once the copy is checked.
    foreach ($badWalk in @(
            @{ Why = 'a failed walk'; Change = @{ result = 'FAIL' }; Pattern = '*walk ended FAIL, not PASS*' },
            @{ Why = 'a partial audit'; Change = @{ result = 'PARTIAL'; full = $false }; Pattern = '*walk ended PARTIAL, not PASS*' },
            @{ Why = 'a PASS marked partial'; Change = @{ full = $false }; Pattern = '*skipped part of the audit*' },
            @{ Why = 'a crash'; Change = @{ crashes = 1 }; Pattern = '*recorded a Facebook crash*' },
            @{ Why = 'another phone'; Change = @{ device = 'Pixel 9' }; Pattern = "*Pixel 9 isn't one of the release walk phones*" },
            @{ Why = 'a phone posing as an emulator'; Change = @{ device = 'Pixel 9'; emulator = $true }; Pattern = "*Pixel 9 isn't one of the release walk phones*" },
            @{ Why = 'another bundle'; Change = @{ patchBuild = 'sha256=' + ('9' * 64) + '; source=clean:' + ('1' * 40) }; Pattern = '*walked app reported patch build*' },
            @{ Why = 'another schema'; Change = @{ schema = 2 }; Pattern = "*walk receipt isn't schema 1*" })) {
        Write-FlowWalk $badWalk.Change
        Assert-Throws { & $flowScript -Stage publish -Version 1.2.3 -Checked $flowChecked -Walk $flowWalk -Verifier $flowVerifier 6> $null 3> $null } $badWalk.Pattern `
            "Publish went ahead on $($badWalk.Why)."
        $flow = @(Read-FlowLog)
        Assert-True ($flow.Count -eq 1 -and $flow[0] -like 'staged *' -and (Test-FlowUntagged)) `
            "Publish ran a step or tagged on $($badWalk.Why): $($flow -join ' | ')"
        $refusedCopy = [regex]::Match($flow[0], ' folder=(.+) files=').Groups[1].Value
        Assert-True ($refusedCopy -and -not (Test-Path -LiteralPath $refusedCopy)) "Publish refused for $($badWalk.Why) left its copy behind."
    }
    Set-Content -LiteralPath $flowWalk -Value 'not json' -Encoding ASCII
    Assert-Throws { & $flowScript -Stage publish -Version 1.2.3 -Checked $flowChecked -Walk $flowWalk -Verifier $flowVerifier 6> $null 3> $null } "*walk receipt doesn't read as JSON*" `
        'Publish went ahead on a walk receipt that does not read.'
    Assert-True (@(Read-FlowLog).Count -eq 1 -and (Test-FlowUntagged)) 'Publish tagged on a walk receipt that does not read.'
    # A verifier run that raised a message Meta's build doesn't (0.16.0's comment crash), lost one,
    # failed its static half, ran on another phone or checked another bundle's APK stops the stage
    # too, and the refusal names the message, which names the method.
    Write-FlowWalk
    $crashMessage = 'Verification error in X.BZ3 X.BZ1.A0G'
    foreach ($badVerifier in @(
            @{ Why = 'an extra verifier message'; Deltas = @([pscustomobject]@{ Kind = 'extra'; Message = $crashMessage; Clean = 0; Patched = 1 })
                Pattern = "*device register check doesn't back*raised $crashMessage (clean 0, patched 1)*" },
            @{ Why = 'a lost verifier message'; Deltas = @([pscustomobject]@{ Kind = 'missing'; Message = 'Rejecting class LX/Abc;'; Clean = 2; Patched = 1 })
                Pattern = '*lost Rejecting class LX/Abc; (clean 2, patched 1)*' },
            @{ Why = 'four extra messages'; Pattern = '*X.A3.m3 (clean 0, patched 1), and 1 more*'
                Deltas = @(1..4 | ForEach-Object { [pscustomobject]@{ Kind = 'extra'; Message = "Verification error in X.A$_.m$_"; Clean = 0; Patched = 1 } }) },
            # Compare-VerifierTallies sorts by message, so "Rejecting" lines come before the raised
            # "Verification error" one, and the refusal still has to name the raised one.
            @{ Why = 'a raised message behind lost ones'; Pattern = "*from Meta's: raised $crashMessage (clean 0, patched 1); lost Rejecting class LX/A1;*, and 1 more*"
                Deltas = @(@(1..3 | ForEach-Object { [pscustomobject]@{ Kind = 'missing'; Message = "Rejecting class LX/A$_;"; Clean = 1; Patched = 0 } }) +
                    @([pscustomobject]@{ Kind = 'extra'; Message = $crashMessage; Clean = 0; Patched = 1 })) },
            @{ Why = 'a failed static half'; StaticPassed = $false; Pattern = '*static half of the register check failed*' },
            @{ Why = 'a FAIL with nothing named'; Change = @{ result = 'FAIL' }; Pattern = '*verifier run ended FAIL, not PASS*' },
            @{ Why = 'tallies that differ'; Change = @{ patchedMessages = 1 }; Pattern = "*verifier tallies don't match: clean 0, patched 1*" },
            @{ Why = 'no list of differences'; Change = @{ deltas = $null }; Pattern = "*doesn't list the verifier's differences*" },
            @{ Why = 'another phone'; Change = @{ device = 'Pixel 9' }; Pattern = "*Pixel 9 isn't one of the release phones*" },
            @{ Why = 'another bundle'; Change = @{ patchBuild = 'sha256=' + ('9' * 64) + '; source=unknown; inputs=' + ('3' * 64) }
                Pattern = '*verified APK carries patch build*' },
            @{ Why = 'an APK with no identity'; Change = @{ patchBuild = '' }; Pattern = "*verified APK carries patch build ''*" },
            @{ Why = 'another schema'; Change = @{ schema = 2 }; Pattern = "*verifier receipt isn't schema 1*" })) {
        $deltas = if ($badVerifier.ContainsKey('Deltas')) { $badVerifier.Deltas } else { @() }
        $static = -not $badVerifier.ContainsKey('StaticPassed') -or $badVerifier.StaticPassed
        $change = if ($badVerifier.ContainsKey('Change')) { $badVerifier.Change } else { @{} }
        Write-FlowVerifier -Change $change -Deltas $deltas -StaticPassed $static
        Assert-Throws { & $flowScript -Stage publish -Version 1.2.3 -Checked $flowChecked -Walk $flowWalk -Verifier $flowVerifier 6> $null 3> $null } `
            $badVerifier.Pattern "Publish went ahead on $($badVerifier.Why)."
        $flow = @(Read-FlowLog)
        Assert-True ($flow.Count -eq 1 -and $flow[0] -like 'staged *' -and (Test-FlowUntagged)) `
            "Publish ran a step or tagged on $($badVerifier.Why): $($flow -join ' | ')"
        $refusedCopy = [regex]::Match($flow[0], ' folder=(.+) files=').Groups[1].Value
        Assert-True ($refusedCopy -and -not (Test-Path -LiteralPath $refusedCopy)) "Publish refused for $($badVerifier.Why) left its copy behind."
    }
    # What the producer writes for a bad run is a FAIL the gate refuses on its own.
    Write-FlowVerifier -Deltas @([pscustomobject]@{ Kind = 'extra'; Message = $crashMessage; Clean = 0; Patched = 1 })
    $written = Get-Content -LiteralPath $flowVerifier -Raw | ConvertFrom-Json
    Assert-True ($written.result -ceq 'FAIL' -and $written.static -ceq 'PASS' -and @($written.deltas).Count -eq 1 -and
        $written.deltas[0].message -ceq $crashMessage -and $written.patchedMessages -eq 1) "The verifier receipt didn't record the run's failure: $($written | ConvertTo-Json -Depth 5 -Compress)"
    Set-Content -LiteralPath $flowVerifier -Value 'not json' -Encoding ASCII
    Assert-Throws { & $flowScript -Stage publish -Version 1.2.3 -Checked $flowChecked -Walk $flowWalk -Verifier $flowVerifier 6> $null 3> $null } `
        "*verifier receipt doesn't read as JSON*" 'Publish went ahead on a verifier receipt that does not read.'
    Assert-True (@(Read-FlowLog).Count -eq 1 -and (Test-FlowUntagged)) 'Publish tagged on a verifier receipt that does not read.'
    Write-FlowVerifier
    # An emulator walks for a release as well as a phone does.
    Write-FlowWalk @{ serial = 'emulator-5554'; device = 'Hushfacebook_API36'; emulator = $true }
    & $flowScript -Stage publish -Version 1.2.3 -Checked $flowChecked -Walk $flowWalk -Verifier $flowVerifier 6> $null 3> $null
    $flow = @(Read-FlowLog | Where-Object { $_ -notlike 'gh release view *' })
    Assert-True ($flow.Count -eq 4 -and $flow[0] -like ("staged version=1.2.3 commit=$flowHead swap=refused folder=* files=hush-unpacker-1.2.3.apk,patches-1.2.3.cdx.json," +
            'patches-1.2.3.mpp,patches-1.2.3.tooling.json,release-receipt-1.2.3.json,SHA256SUMS.txt') -and
        $flow[1] -like "py * github-notes --version 1.2.3 --checked $flowChecked --out *notes.md" -and
        $flow[2] -like 'gh release create v1.2.3 -R SysAdminDoc/HushFacebook --verify-tag --title v1.2.3 --notes-file *notes.md *' -and
        $flow[3] -like 'gh release download v1.2.3 -R SysAdminDoc/HushFacebook -D *') "Publish did not check, write, create and download back: $($flow -join ' | ')"
    # The release goes out from the copy that was checked, which is gone once the stage ends. Its
    # folder's name isn't known ahead, and the swap above found every file in it held.
    $checkedCopy = [regex]::Match($flow[0], ' folder=(.+) files=').Groups[1].Value
    Assert-True ((Split-Path -Leaf $checkedCopy) -cmatch '\Ahushfacebook-publishing-1\.2\.3-[0-9a-f]{32}\z') `
        "Publish checked a copy in a folder whose name can be known ahead: $checkedCopy"
    foreach ($name in 'patches-1.2.3.mpp', 'patches-1.2.3.cdx.json', 'patches-1.2.3.tooling.json', 'hush-unpacker-1.2.3.apk', 'release-receipt-1.2.3.json', 'SHA256SUMS.txt') {
        Assert-True ($flow[2].Contains((Join-Path $checkedCopy $name))) "Publish created the release without the checked $name."
    }
    Assert-True (-not (Test-Path -LiteralPath $checkedCopy)) 'Publish left the copy it checked behind.'
    # The download back goes to a folder whose name isn't known ahead either, and it goes too.
    $downloadedTo = [regex]::Match($flow[3], ' -D (.+)\z').Groups[1].Value
    Assert-True ((Split-Path -Leaf $downloadedTo) -cmatch '\Ahushfacebook-published-1\.2\.3-[0-9a-f]{32}\z') `
        "Publish downloaded the release back into a folder whose name can be known ahead: $downloadedTo"
    Assert-True (-not (Test-Path -LiteralPath $downloadedTo)) 'Publish left the downloaded copy behind.'
    Assert-True ((& git -C $flowOrigin rev-parse 'v1.2.3^{commit}') -eq (& git -C $flowRepo rev-parse HEAD)) `
        'Publish did not push a v1.2.3 tag on the source commit.'

    # A release exists as soon as create returns, even when the following download is interrupted.
    # Its assets are the upload's own snapshot, never fresh copies of the current staging folder.
    $flowGitHub.Release = $null
    Remove-Item -LiteralPath $flowGitHub.PublishedAssets -Recurse -Force
    $flowGitHub.DownloadsToInterrupt = 1
    Assert-Throws { & $flowScript -Stage publish -Version 1.2.3 -Checked $flowChecked -Walk $flowWalk -Verifier $flowVerifier 6> $null 3> $null } `
        '*The download back did not pass*' 'The interrupted verification was reported as a completed publish.'
    Assert-True ($null -ne $flowGitHub.Release -and -not $flowGitHub.Release.isDraft) 'The fake lost the release created before verification failed.'
    $interrupted = @(Read-FlowLog)
    Assert-True (@($interrupted | Where-Object { $_ -like 'gh release create *' }).Count -eq 1) 'The interrupted publish did not create its release exactly once.'
    $publishedHashes = @{}
    foreach ($file in Get-ChildItem -LiteralPath $flowGitHub.PublishedAssets -File) {
        $publishedHashes[$file.Name] = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
    }
    & $flowScript -Stage publish -Version 1.2.3 -Checked $flowChecked -Walk $flowWalk -Verifier $flowVerifier 6> $null 3> $null
    $retried = @(Read-FlowLog)
    Assert-True (@($retried | Where-Object { $_ -like 'gh release create *' -or $_ -like 'gh release upload *' -or $_ -like 'gh release edit *' }).Count -eq 0) `
        'Retry tried to create or change the release that was already published.'
    Assert-True (@($retried | Where-Object { $_ -like 'gh release view *' }).Count -gt 0 -and
        @($retried | Where-Object { $_ -like 'gh release download *' }).Count -eq 1) 'Retry did not inspect and verify the existing release.'
    foreach ($file in Get-ChildItem -LiteralPath $flowGitHub.PublishedAssets -File) {
        Assert-True ((Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash -ceq $publishedHashes[$file.Name]) `
            "Retry replaced the already-published $($file.Name)."
    }
    Set-Content -LiteralPath $flowFails -Value 'release view' -Encoding ASCII
    try {
        Assert-Throws { & $flowScript -Stage publish -Version 1.2.3 -Checked $flowChecked -Walk $flowWalk -Verifier $flowVerifier 6> $null 3> $null } `
            '*could not check whether*' 'An unreadable release was treated as a missing one.'
        Assert-True (@(Read-FlowLog | Where-Object { $_ -like 'gh release create *' -or $_ -like 'gh release upload *' -or $_ -like 'gh release edit *' }).Count -eq 0) `
            'A release lookup failure allowed publication changes.'
    } finally { Remove-Item -LiteralPath $flowFails -Force }
    $flowGitHub.Release.isDraft = $true
    try {
        Assert-Throws { & $flowScript -Stage publish -Version 1.2.3 -Checked $flowChecked -Walk $flowWalk -Verifier $flowVerifier 6> $null 3> $null } `
            '*draft*' 'An existing draft was treated as the published release.'
        Assert-True (@(Read-FlowLog | Where-Object { $_ -like 'gh release create *' -or $_ -like 'gh release upload *' -or $_ -like 'gh release edit *' }).Count -eq 0) `
            'The draft refusal changed the release.'
    } finally { $flowGitHub.Release.isDraft = $false }
    $flowTamper = 'patches-1.2.3.mpp'
    Assert-Throws { & $flowScript -Stage publish -Version 1.2.3 -Checked $flowChecked -Walk $flowWalk -Verifier $flowVerifier 6> $null 3> $null } '*hosted patches-1.2.3.mpp differs*' `
        'Publish passed a release whose hosted bundle is not the one it built.'
    $flowTamper = $null
    $null = Read-FlowLog

    # Index reads the publish time and the gate's test counts, writes the index through
    # release_text, puts the receipt back in the root and updates the repository description.
    Assert-Throws { & $flowScript -Stage index -Version 1.2.3 6> $null } '*-ReadmeSummary*' 'Index ran without its summaries.'
    foreach ($results in @(@{ Dir = 'extensions/facebook/build/test-results/testDebugUnitTest'; Cases = 2 },
            @{ Dir = 'patches/build/test-results/test'; Cases = 3 }, @{ Dir = 'patches/build/test-results/fixtureTest'; Cases = 1 })) {
        New-Item -ItemType Directory -Force -Path (Join-Path $flowRepo $results.Dir) | Out-Null
        Set-Content -LiteralPath (Join-Path $flowRepo "$($results.Dir)/TEST-a.xml") -Encoding ASCII `
            -Value ('<testsuite>' + ('<testcase/>' * $results.Cases) + '</testsuite>')
    }
    Remove-Item -LiteralPath (Join-Path $flowRepo 'release-receipt-1.2.3.json') -Force
    # A description gh couldn't read stops the stage. Read as empty, it matched its own update, so
    # nothing was edited and the stage still said the index was done.
    Set-Content -LiteralPath $flowFails -Value 'repo view' -Encoding ASCII
    Assert-Throws { & $flowScript -Stage index -Version 1.2.3 -ReadmeSummary $flowSummary -BundleSummary $flowSummary 6> $null 3> $null } `
        "*couldn't read*description*" 'Index went on after gh could not read the repository description.'
    Remove-Item -LiteralPath $flowFails -Force
    Assert-True (-not (@(Read-FlowLog) -like 'gh repo edit*')) 'Index edited a description it could not read.'
    Remove-Item -LiteralPath (Join-Path $flowRepo 'release-receipt-1.2.3.json') -Force -ErrorAction SilentlyContinue
    & $flowScript -Stage index -Version 1.2.3 -ReadmeSummary $flowSummary -BundleSummary $flowSummary 6> $null 3> $null
    $flow = @(Read-FlowLog)
    Assert-True ($flow.Count -eq 4 -and $flow[0] -eq 'gh release view v1.2.3 -R SysAdminDoc/HushFacebook --json publishedAt --jq .publishedAt' -and
        $flow[1] -like 'py * index --version 1.2.3 --created 2026-10-09T21:03:04 --bundle-summary * --readme-summary * --runtime 2 --patch 4' -and
        $flow[2] -like 'gh repo view *' -and
        $flow[3] -eq 'gh repo edit SysAdminDoc/HushFacebook --description Hushfacebook v1.2.3: 2 patches for Facebook 586.0.0.52.53 with Morphe.') `
        "Index did not read the release, write the index and update the description: $($flow -join ' | ')"
    Assert-True (Test-Path -LiteralPath (Join-Path $flowRepo 'release-receipt-1.2.3.json') -PathType Leaf) `
        'Index did not put the receipt back where the facts check reads it.'
} finally {
    foreach ($name in $flowNames) { [Environment]::SetEnvironmentVariable($name, $flowBefore[$name], 'Process') }
    Remove-Item -LiteralPath Function:\py, Function:\gh -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $releaseFlow -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host '[scripts] release stage contracts passed'

# --- fuzz-parsers.ps1 ------------------------------------------------------------------------
#
# The parser fuzzer keeps what a run found for the next one, leaves a record of every run, clean or
# not, and runs only between 19:00 and 07:00. Each case runs the real script on a root of its own,
# holding the three sources its shims copy, against stand-ins for its tools, so nothing compiles or
# fuzzes. One small program, built here with the .NET Framework's C# compiler since the script
# looks for jazzer.exe and javac.exe by name, plays each tool by the name it's copied to. java
# answers -version and writes one seed a target. javac does nothing. Jazzer answers --version,
# notes each run's target, seed and starting corpus in calls.log, and does what mode.txt names for
# that target: by default a clean run that adds one input named by its SHA-1, the way libFuzzer
# names them, then finding (a crash), fail (an error and no crash) or, for the planted-bug check,
# miss. Without one the check finds its bug.

$fuzzScript = Join-Path $Root 'scripts/fuzz-parsers.ps1'
$fuzzCases = [System.IO.Path]::GetFullPath((Join-Path ([System.IO.Path]::GetTempPath()) `
    ('hushfacebook-fuzzcases-' + [guid]::NewGuid().ToString('N'))))
$fuzzJdk = Join-Path $fuzzCases 'tools/jdk'
$fuzzJazzerHome = Join-Path $fuzzCases 'tools/jazzer'
$fuzzCalls = Join-Path $fuzzJazzerHome 'calls.log'
$fuzzMode = Join-Path $fuzzJazzerHome 'mode.txt'
$fuzzSha1 = [System.Security.Cryptography.SHA1]::Create()
$fuzzLinks = @()
function Get-FuzzSha1([byte[]]$Bytes) {
    return [System.BitConverter]::ToString($fuzzSha1.ComputeHash($Bytes)).Replace('-', '').ToLowerInvariant()
}
function New-FuzzCaseRoot([string]$Name) {
    $caseRoot = Join-Path $fuzzCases $Name
    $download = Join-Path $caseRoot 'extensions/facebook/src/main/java/app/morphe/extension/facebook/download'
    New-Item -ItemType Directory -Force -Path $download | Out-Null
    foreach ($source in 'Downloader.java', 'VideoTranscode.java', 'AacReencode.java') {
        Copy-Item -LiteralPath (Join-Path $Root "extensions/facebook/src/main/java/app/morphe/extension/facebook/download/$source") -Destination $download
    }
    return $caseRoot
}
function Invoke-FuzzCase {
    # The script on [CaseRoot] at 21:00 unless [With] says otherwise, answering its exit code.
    # calls.log starts empty, and mode.txt holds [Mode].
    param([string]$CaseRoot, [string[]]$Mode = @(), [hashtable]$With = @{})
    Remove-Item -LiteralPath $fuzzCalls -Force -ErrorAction SilentlyContinue
    [System.IO.File]::WriteAllText($fuzzMode, ($Mode -join "`n"))
    $arguments = @{ Root = $CaseRoot; Java = $fuzzJdk; Jazzer = $fuzzJazzerHome; Seconds = 10; SelfCheckSeconds = 10
        Now = [datetime]'2026-10-13T21:00:00' }
    foreach ($key in $With.Keys) { $arguments[$key] = $With[$key] }
    $global:LASTEXITCODE = 0
    $null = & $fuzzScript @arguments 6> $null 3> $null
    return $LASTEXITCODE
}
function Read-FuzzCalls([string]$Target) {
    if (-not (Test-Path -LiteralPath $fuzzCalls -PathType Leaf)) { return }
    [System.IO.File]::ReadAllLines($fuzzCalls) | Where-Object { $_ -like "$Target *" }
}
function Read-FuzzRecord([string]$CaseRoot, [string]$Target) {
    # The newest run record of [Target].
    $newest = Get-ChildItem -LiteralPath (Join-Path $CaseRoot 'build/fuzz/runs') -File -Filter "*-$Target.json" |
        Sort-Object Name | Select-Object -Last 1
    return (Get-Content -LiteralPath $newest.FullName -Raw | ConvertFrom-Json)
}

try {
    New-Item -ItemType Directory -Force -Path (Join-Path $fuzzJdk 'bin'), $fuzzJazzerHome | Out-Null
    $csc = @('Framework64', 'Framework') | ForEach-Object { Join-Path $env:WINDIR "Microsoft.NET/$_/v4.0.30319/csc.exe" } |
        Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
    Assert-True ([bool]$csc) "The fuzz runner's cases build their tool stand-ins with the .NET Framework's csc.exe, and there's none under $env:WINDIR\Microsoft.NET."
    $standInSource = Join-Path $fuzzCases 'tools/stand-in.cs'
    $standIn = Join-Path $fuzzCases 'tools/stand-in.exe'
    [System.IO.File]::WriteAllText($standInSource, @'
using System;
using System.Collections.Generic;
using System.IO;
using System.Security.Cryptography;
using System.Text;

static class FuzzStandIn {
    static readonly string Here = AppDomain.CurrentDomain.BaseDirectory;

    static string Value(string[] args, string name) {
        foreach (string arg in args) {
            if (arg.StartsWith(name + "=", StringComparison.Ordinal)) return arg.Substring(name.Length + 1);
        }
        return null;
    }

    static string Sha1(byte[] data) {
        using (SHA1 sha = SHA1.Create()) return BitConverter.ToString(sha.ComputeHash(data)).Replace("-", "").ToLowerInvariant();
    }

    static string Mode(string target) {
        string file = Path.Combine(Here, "mode.txt");
        if (File.Exists(file)) {
            foreach (string line in File.ReadAllLines(file)) {
                if (line.StartsWith(target + "=", StringComparison.Ordinal)) return line.Substring(target.Length + 1);
            }
        }
        return "clean";
    }

    static int Main(string[] args) {
        string tool = Path.GetFileNameWithoutExtension(System.Reflection.Assembly.GetEntryAssembly().Location).ToLowerInvariant();
        if (tool == "javac") return 0;
        if (tool == "java") {
            if (args.Length > 0 && args[0] == "-version") { Console.WriteLine("openjdk version \"21.0.5\" 2024-10-15"); return 0; }
            if (args.Length < 4 || !args[2].EndsWith(".ParserFuzzSeeds", StringComparison.Ordinal)) return 9;
            foreach (string target in new string[] { "dash", "mp4", "faulty" }) {
                string folder = Path.Combine(args[3], target);
                Directory.CreateDirectory(folder);
                File.WriteAllText(Path.Combine(folder, "seed-" + target), "a seed for " + target);
            }
            return 0;
        }
        if (args.Length == 1 && args[0] == "--version") { Console.WriteLine("Jazzer v9.9.9"); return 0; }
        string targetClass = Value(args, "--target_class") ?? "";
        string name = targetClass.EndsWith(".FaultyBoxFuzz") ? "faulty" : targetClass.EndsWith(".DashManifestFuzz") ? "dash"
            : targetClass.EndsWith(".Mp4JoinFuzz") ? "mp4" : "unknown";
        string corpus = args[args.Length - 1];
        if (Value(args, "-minimize_crash") == "1") {
            File.WriteAllText(Value(args, "-exact_artifact_path"), "minimized");
            File.AppendAllText(Path.Combine(Here, "calls.log"), "minimize " + name + "\n");
            return 0;
        }
        List<string> names = new List<string>();
        foreach (string file in Directory.GetFiles(corpus)) names.Add(Path.GetFileName(file));
        names.Sort(StringComparer.Ordinal);
        string seed = Value(args, "-seed");
        File.AppendAllText(Path.Combine(Here, "calls.log"), name + " seed=" + seed + " corpus=" + string.Join(",", names.ToArray()) + "\n");
        string mode = Mode(name);
        if ((name == "faulty" && mode != "miss") || mode == "finding") {
            File.WriteAllText(Value(args, "-artifact_prefix") + "crash-" + Sha1(Encoding.ASCII.GetBytes(name)), "a crash in " + name);
            Console.WriteLine("== Java Exception: java.lang.IllegalStateException: found in " + name);
            return 77;
        }
        if (mode == "fail") { Console.WriteLine("ERROR: the stand-in failed on purpose"); return 3; }
        if (name != "faulty") {
            byte[] input = Encoding.ASCII.GetBytes(name + " " + Guid.NewGuid().ToString("N"));
            File.WriteAllBytes(Path.Combine(corpus, Sha1(input)), input);
        }
        Console.WriteLine("INFO: Seed: " + seed);
        Console.WriteLine("#2\tINITED cov: 10 ft: 20 corp: 2/64b exec/s: 0 rss: 100Mb");
        Console.WriteLine("#1234\tDONE   cov: 42 ft: 84 corp: 3/96b lim: 64 exec/s: 617 rss: 101Mb");
        Console.WriteLine("Done 1234 runs in 2 second(s)");
        Console.WriteLine("stat::number_of_executed_units: 1234");
        Console.WriteLine("stat::average_exec_per_sec:     617");
        Console.WriteLine("stat::new_units_added:          1");
        Console.WriteLine("stat::peak_rss_mb:              101");
        return 0;
    }
}
'@)
    $built = & $csc /nologo /target:exe "/out:$standIn" $standInSource
    Assert-True ($LASTEXITCODE -eq 0) "csc couldn't build the fuzz runner's tool stand-ins: $($built -join ' ')"
    foreach ($copy in (Join-Path $fuzzJdk 'bin/java.exe'), (Join-Path $fuzzJdk 'bin/javac.exe'), (Join-Path $fuzzJazzerHome 'jazzer.exe')) {
        Copy-Item -LiteralPath $standIn -Destination $copy
    }
    [System.IO.File]::WriteAllText((Join-Path $fuzzJazzerHome 'jazzer_standalone.jar'), '')
    $null = @(Read-QueueLog)

    # Two runs: the second starts from what the first kept, but not from a file whose bytes don't
    # match its name (what a run killed mid-write leaves) or one Jazzer didn't name.
    $reuseRoot = New-FuzzCaseRoot 'reuse'
    $keptDash = Join-Path $reuseRoot 'build/fuzz/corpus/dash'
    Assert-True ((Invoke-FuzzCase $reuseRoot -With @{ Target = 'dash' }) -eq 0) 'A clean fuzz run did not exit 0.'
    Assert-True (@(Read-QueueLog) -contains 'hushfacebook parser fuzzing') 'A fuzz run did not go through the build queue.'
    $firstKept = @(Get-ChildItem -LiteralPath $keptDash -File | ForEach-Object { $_.Name })
    Assert-True ($firstKept.Count -eq 1 -and $firstKept[0] -cmatch '\A[0-9a-f]{40}\z') `
        "A clean fuzz run did not keep the one input Jazzer added: $($firstKept -join ', ')"
    $torn = Join-Path $keptDash ('0' * 40)
    $stray = Join-Path $keptDash 'notes.txt'
    [System.IO.File]::WriteAllText($torn, 'not the bytes this name is the hash of')
    [System.IO.File]::WriteAllText($stray, 'not an input Jazzer kept')
    Assert-True ((Invoke-FuzzCase $reuseRoot -With @{ Target = 'dash' }) -eq 0) 'A second clean fuzz run did not exit 0.'
    $secondCall = @(Read-FuzzCalls 'dash')
    Assert-True ($secondCall.Count -eq 1 -and $secondCall[0] -like "*corpus=*$($firstKept[0])*" -and $secondCall[0] -like '*seed-dash*') `
        "The second fuzz run did not start from the input the first one kept and the seeds: $secondCall"
    Assert-True ($secondCall[0] -notlike "*$('0' * 40)*" -and $secondCall[0] -notlike '*notes.txt*') `
        "A fuzz run started from a kept file that isn't an input named by its SHA-1: $secondCall"
    $secondKept = @(Get-ChildItem -LiteralPath $keptDash -File | ForEach-Object { $_.Name })
    Assert-True ($secondKept.Count -eq 2 -and $secondKept -contains $firstKept[0] -and
        -not (Test-Path -LiteralPath $torn) -and -not (Test-Path -LiteralPath $stray)) `
        "The kept corpus after two runs isn't both runs' inputs alone: $($secondKept -join ', ')"
    # The planted-bug check starts from its seeds every time, and keeps nothing.
    Assert-True ((@(Read-FuzzCalls 'faulty') -join '|') -like '*corpus=seed-faulty' -and
        -not (Test-Path -LiteralPath (Join-Path $reuseRoot 'build/fuzz/corpus/faulty'))) `
        "The fuzzer's planted-bug check started from or kept more than its seeds: $(@(Read-FuzzCalls 'faulty'))"

    # A clean run leaves its record and its log, and its empty findings folder goes as before.
    $evidenceRoot = New-FuzzCaseRoot 'evidence'
    & git -C $evidenceRoot init --quiet
    & git -C $evidenceRoot add -A
    & git -C $evidenceRoot -c user.name=Fixture -c user.email=fixture@example.invalid commit -q -m fixture
    $evidenceCommit = (& git -C $evidenceRoot rev-parse HEAD).Trim()
    Assert-True ((Invoke-FuzzCase $evidenceRoot -With @{ Target = 'mp4'; Seed = 4242 }) -eq 0) 'A clean fuzz run with a set seed did not exit 0.'
    $evidenceRuns = Join-Path $evidenceRoot 'build/fuzz/runs'
    $evidenceFiles = @(Get-ChildItem -LiteralPath $evidenceRuns -File | ForEach-Object { $_.Name } | Sort-Object)
    Assert-True ($evidenceFiles.Count -eq 4 -and @($evidenceFiles -like '*-faulty.json').Count -eq 1 -and @($evidenceFiles -like '*-faulty.log').Count -eq 1 -and
        @($evidenceFiles -like '*-mp4.json').Count -eq 1 -and @($evidenceFiles -like '*-mp4.log').Count -eq 1) `
        "A clean fuzz run did not leave a record and a log for the check and the target: $($evidenceFiles -join ', ')"
    $evidence = Read-FuzzRecord $evidenceRoot 'mp4'
    Assert-True ($evidence.target -eq 'mp4' -and $evidence.seed -eq 4242 -and $evidence.jazzer.version -eq '9.9.9' -and
        $evidence.jdk.version -eq '21.0.5' -and $evidence.commit -eq $evidenceCommit) `
        "A clean run's record has the wrong target, seed, tool versions or commit: $($evidence | ConvertTo-Json -Depth 6 -Compress)"
    Assert-True ($evidence.requestedSeconds -eq 10 -and $evidence.actualSeconds -ge 0 -and $evidence.executions -eq 1234 -and
        $evidence.execPerSecond -eq 617 -and $evidence.coverage.edges -eq 42 -and $evidence.coverage.features -eq 84 -and
        $evidence.exitReason -eq 'clean' -and @($evidence.findings).Count -eq 0) `
        "A clean run's record has the wrong duration, executions, coverage or exit reason: $($evidence | ConvertTo-Json -Depth 6 -Compress)"
    Assert-True ($evidence.corpus.seeds -eq 1 -and $evidence.corpus.keptBefore -eq 0 -and $evidence.corpus.added -eq 1 -and
        $evidence.corpus.keptAfter -eq 1 -and $evidence.corpus.keptBytesAfter -gt 0) `
        "A clean run's record has the wrong corpus sizes: $($evidence.corpus | ConvertTo-Json -Compress)"
    Assert-True (([datetime]$evidence.window.start) -eq [datetime]'2026-10-13T19:00:00' -and
        ([datetime]$evidence.window.end) -eq [datetime]'2026-10-14T07:00:00' -and $evidence.allowDaytime -eq $false) `
        "A clean run's record has the wrong off-hours window: $($evidence.window | ConvertTo-Json -Compress)"
    $evidenceLog = Join-Path $evidenceRuns $evidence.log
    Assert-True ((Test-Path -LiteralPath $evidenceLog -PathType Leaf) -and ([System.IO.File]::ReadAllText($evidenceLog)) -like '*DONE*cov: 42*') `
        "A clean run's log did not survive beside its record: $evidenceLog"
    Assert-True ((Read-FuzzRecord $evidenceRoot 'faulty').exitReason -eq 'planted-bug-found') `
        "The planted-bug check's record does not say it found the bug."
    $evidenceLeft = @(Get-ChildItem -LiteralPath (Join-Path $evidenceRoot 'build/fuzz') -Directory | ForEach-Object { $_.Name } | Sort-Object)
    Assert-True (($evidenceLeft -join ',') -eq 'corpus,runs') "A clean fuzz run left a findings folder: $($evidenceLeft -join ', ')"

    # The bounds remove the oldest: by bytes, three kept inputs of 400,000 bytes a day apart and
    # a 1 MB limit, then by count, and the records past -KeepRuns, a target at a time.
    $boundRoot = New-FuzzCaseRoot 'bounds'
    $keptMp4 = Join-Path $boundRoot 'build/fuzz/corpus/mp4'
    $boundRuns = Join-Path $boundRoot 'build/fuzz/runs'
    New-Item -ItemType Directory -Force -Path $keptMp4, $boundRuns | Out-Null
    $plantedKept = @(foreach ($daysAgo in 3, 2, 1) {
        $bytes = New-Object byte[] 400000
        $bytes[0] = [byte]$daysAgo
        $plantedPath = Join-Path $keptMp4 (Get-FuzzSha1 $bytes)
        [System.IO.File]::WriteAllBytes($plantedPath, $bytes)
        [System.IO.File]::SetLastWriteTimeUtc($plantedPath, [datetime]::UtcNow.AddDays(-$daysAgo))
        Split-Path -Leaf $plantedPath
    })
    foreach ($stamp in '20200101-000000-000', '20200102-000000-000', '20200103-000000-000') {
        [System.IO.File]::WriteAllText((Join-Path $boundRuns "$stamp-mp4.json"), '{}')
        [System.IO.File]::WriteAllText((Join-Path $boundRuns "$stamp-mp4.log"), 'an old log')
    }
    [System.IO.File]::WriteAllText((Join-Path $boundRuns '20200101-000000-000-dash.json'), '{}')
    [System.IO.File]::WriteAllText((Join-Path $boundRuns 'notes.txt'), 'not a run record')
    Assert-True ((Invoke-FuzzCase $boundRoot -With @{ Target = 'mp4'; CorpusMaxMB = 1; KeepRuns = 2 }) -eq 0) 'A bounded fuzz run did not exit 0.'
    $byBytes = @(Get-ChildItem -LiteralPath $keptMp4 -File | ForEach-Object { $_.Name })
    Assert-True ($byBytes.Count -eq 3 -and $byBytes -notcontains $plantedKept[0] -and $byBytes -contains $plantedKept[1] -and
        $byBytes -contains $plantedKept[2]) "The corpus byte bound did not remove the oldest kept input alone: $($byBytes -join ', ')"
    $boundLeft = @(Get-ChildItem -LiteralPath $boundRuns -File | ForEach-Object { $_.Name } | Sort-Object)
    Assert-True ($boundLeft -notcontains '20200101-000000-000-mp4.json' -and $boundLeft -notcontains '20200101-000000-000-mp4.log' -and
        $boundLeft -notcontains '20200102-000000-000-mp4.json' -and $boundLeft -notcontains '20200102-000000-000-mp4.log' -and
        $boundLeft -contains '20200103-000000-000-mp4.json' -and $boundLeft -contains '20200103-000000-000-mp4.log' -and
        $boundLeft -contains '20200101-000000-000-dash.json' -and $boundLeft -contains 'notes.txt' -and
        @($boundLeft -like '*-mp4.json').Count -eq 2) `
        "The run record bound did not remove the oldest of that target's records and logs alone: $($boundLeft -join ', ')"
    $addedFirst = @($byBytes | Where-Object { $plantedKept -notcontains $_ })
    Assert-True ((Invoke-FuzzCase $boundRoot -With @{ Target = 'mp4'; CorpusMaxFiles = 2 }) -eq 0) 'A fuzz run bounded by count did not exit 0.'
    $byCount = @(Get-ChildItem -LiteralPath $keptMp4 -File | ForEach-Object { $_.Name })
    Assert-True ($byCount.Count -eq 2 -and $byCount -contains $addedFirst[0] -and $byCount -notcontains $plantedKept[1] -and
        $byCount -notcontains $plantedKept[2]) "The corpus count bound did not keep the newest inputs: $($byCount -join ', ')"

    # Saved findings are read into each run, as copies, and nothing of theirs is pruned or written,
    # whatever the bounds. Their bytes would read as text but for the line endings.
    $savedRoot = New-FuzzCaseRoot 'regressions'
    $savedFolder = Join-Path $savedRoot 'tools/fuzz/regressions/mp4'
    New-Item -ItemType Directory -Force -Path $savedFolder | Out-Null
    $savedFinding = Join-Path $savedFolder 'minimized-crash-1'
    $savedBytes = [byte[]](0x66, 0x74, 0x79, 0x70, 0x0D, 0x0A, 0x6D, 0x6F, 0x6F, 0x76, 0x0A)
    [System.IO.File]::WriteAllBytes($savedFinding, $savedBytes)
    $savedTime = [datetime]'2026-01-02T03:04:05Z'
    [System.IO.File]::SetLastWriteTimeUtc($savedFinding, $savedTime.ToUniversalTime())
    foreach ($attempt in 1, 2) {
        Assert-True ((Invoke-FuzzCase $savedRoot -With @{ Target = 'mp4'; CorpusMaxFiles = 1 }) -eq 0) "Fuzz run $attempt with a saved finding did not exit 0."
        Assert-True ((@(Read-FuzzCalls 'mp4') -join '|') -like '*regression-minimized-crash-1*') `
            "Fuzz run $attempt did not start from a copy of the saved finding: $(@(Read-FuzzCalls 'mp4'))"
    }
    $savedLeft = @(Get-ChildItem -LiteralPath $savedFolder -Force | ForEach-Object { $_.Name })
    Assert-True (($savedLeft -join ',') -eq 'minimized-crash-1' -and
        ([System.BitConverter]::ToString([System.IO.File]::ReadAllBytes($savedFinding))) -eq [System.BitConverter]::ToString($savedBytes) -and
        [System.IO.File]::GetLastWriteTimeUtc($savedFinding) -eq $savedTime.ToUniversalTime()) `
        "A fuzz run pruned or rewrote a saved finding: $($savedLeft -join ', ')"
    $savedKept = @(Get-ChildItem -LiteralPath (Join-Path $savedRoot 'build/fuzz/corpus/mp4') -File | ForEach-Object { $_.Name })
    Assert-True ($savedKept.Count -eq 1 -and $savedKept[0] -cmatch '\A[0-9a-f]{40}\z') `
        "The kept corpus took in the copy of a saved finding: $($savedKept -join ', ')"
    # A kept folder that's a link to the saved findings stops the run before anything is read.
    $linkedKept = Join-Path $savedRoot 'build/fuzz/corpus/mp4'
    Remove-Item -LiteralPath $linkedKept -Recurse -Force
    New-Item -ItemType Junction -Path $linkedKept -Target $savedFolder | Out-Null
    $fuzzLinks += $linkedKept
    Assert-True ((Invoke-FuzzCase $savedRoot -With @{ Target = 'mp4'; CorpusMaxFiles = 1 }) -eq 2) `
        'A kept corpus folder linked to the saved findings did not stop the fuzz run as a harness failure (2).'
    Assert-True (-not (Test-Path -LiteralPath $fuzzCalls) -and
        ([System.BitConverter]::ToString([System.IO.File]::ReadAllBytes($savedFinding))) -eq [System.BitConverter]::ToString($savedBytes)) `
        'A fuzz run went ahead through a kept corpus folder linked to the saved findings.'

    # .gitattributes keeps a saved finding's bytes through git. The same bytes anywhere else go in
    # with LF endings, which shows the check would see a conversion.
    $attributesRepo = Join-Path $fuzzCases 'attributes'
    New-Item -ItemType Directory -Force -Path $attributesRepo | Out-Null
    & git -C $attributesRepo init --quiet
    & git -C $attributesRepo config core.autocrlf true
    & git -C $attributesRepo config core.safecrlf false
    Copy-Item -LiteralPath (Join-Path $Root '.gitattributes') -Destination $attributesRepo
    $textLike = [byte[]](0x3C, 0x61, 0x3E, 0x0D, 0x0A, 0x3C, 0x2F, 0x61, 0x3E, 0x0D, 0x0A)
    $attributeCases = [ordered]@{ 'tools/fuzz/regressions/dash/minimized-crash-1' = $true; 'tools/fuzz/other/sample.mpd' = $false }
    foreach ($relative in $attributeCases.Keys) {
        $path = Join-Path $attributesRepo $relative
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $path) | Out-Null
        [System.IO.File]::WriteAllBytes($path, $textLike)
    }
    & git -C $attributesRepo add -A
    foreach ($relative in $attributeCases.Keys) {
        $raw = (& git -C $attributesRepo hash-object --no-filters -- $relative).Trim()
        $staged = (@(& git -C $attributesRepo ls-files -s -- $relative)[0] -split '\s+')[1]
        if ($attributeCases[$relative]) {
            Assert-True ($raw -eq $staged) "git changed the bytes of $relative on the way in, so its replay would test another input."
        } else {
            Assert-True ($raw -ne $staged) "git kept the CRLF bytes of $relative, so the saved-finding check above can't tell a conversion."
        }
    }
    & git -C $attributesRepo -c user.name=Fixture -c user.email=fixture@example.invalid commit -q -m fixture
    $checkedOut = Join-Path $attributesRepo 'tools/fuzz/regressions/dash/minimized-crash-1'
    Remove-Item -LiteralPath $checkedOut -Force
    & git -C $attributesRepo checkout -- 'tools/fuzz/regressions/dash/minimized-crash-1'
    Assert-True (([System.BitConverter]::ToString([System.IO.File]::ReadAllBytes($checkedOut))) -eq [System.BitConverter]::ToString($textLike)) `
        'A checkout changed the bytes of a saved finding.'

    # Outside 19:00 to 07:00, or too long to be over by 07:00, a run does nothing and exits 3.
    # -AllowDaytime runs it, and its record says so.
    $dayRoot = New-FuzzCaseRoot 'daytime'
    $null = @(Read-QueueLog)
    Assert-True ((Invoke-FuzzCase $dayRoot -With @{ Now = [datetime]'2026-10-13T12:00:00' }) -eq 3) 'A fuzz run at noon did not refuse with exit 3.'
    Assert-True (-not (Test-Path -LiteralPath $fuzzCalls) -and -not (Test-Path -LiteralPath (Join-Path $dayRoot 'build'))) `
        'A fuzz run refused for the hour still did something.'
    Assert-True ((Invoke-FuzzCase $dayRoot -With @{ Now = [datetime]'2026-10-14T06:58:00'; Seconds = 600 }) -eq 3) `
        'A fuzz run that would go past 07:00 did not refuse with exit 3.'
    Assert-True ((Invoke-FuzzCase $dayRoot -With @{ Now = [datetime]'2026-10-13T18:59:00' }) -eq 3) 'A fuzz run at 18:59 did not refuse with exit 3.'
    Assert-True (-not (Test-Path -LiteralPath $fuzzCalls) -and -not (Test-Path -LiteralPath (Join-Path $dayRoot 'build')) -and
        @(Read-QueueLog) -notcontains 'hushfacebook parser fuzzing') 'A fuzz run refused for the hour still did something.'
    Assert-True ((Invoke-FuzzCase $dayRoot -With @{ Now = [datetime]'2026-10-14T03:00:00'; Target = 'dash' }) -eq 0) `
        'A fuzz run at 03:00 did not go ahead.'
    Assert-True (([datetime](Read-FuzzRecord $dayRoot 'dash').window.start) -eq [datetime]'2026-10-13T19:00:00') `
        "A run after midnight did not record the window that started the evening before."
    Assert-True ((Invoke-FuzzCase $dayRoot -With @{ Now = [datetime]'2026-10-13T12:00:00'; AllowDaytime = $true; Target = 'dash' }) -eq 0) `
        'A fuzz run at noon with -AllowDaytime did not go ahead.'
    $dayRecord = Read-FuzzRecord $dayRoot 'dash'
    Assert-True ($dayRecord.allowDaytime -eq $true -and $null -eq $dayRecord.window) `
        "A daytime run's record does not say it ran outside the window: $($dayRecord | ConvertTo-Json -Depth 6 -Compress)"

    # A finding exits 1 as before, minimized, with its log, under -FindingsPath, and the other
    # target still runs. A Jazzer that fails without a finding, a planted bug it misses and a JDK
    # with no javac are the harness failing, 2, never the 1 of a finding.
    $findingRoot = New-FuzzCaseRoot 'finding'
    $findings = Join-Path $findingRoot 'found'
    Assert-True ((Invoke-FuzzCase $findingRoot -Mode @('dash=finding') -With @{ FindingsPath = $findings }) -eq 1) 'A fuzz finding did not exit 1.'
    $foundLeft = @(Get-ChildItem -LiteralPath (Join-Path $findings 'dash') -File | ForEach-Object { $_.Name })
    Assert-True ($foundLeft -contains 'jazzer.log' -and @($foundLeft -like 'crash-*').Count -eq 1 -and @($foundLeft -like 'minimized-crash-*').Count -eq 1) `
        "A fuzz finding was not left with its log and a minimized copy: $($foundLeft -join ', ')"
    Assert-True (@(Read-FuzzCalls 'minimize').Count -eq 1 -and @(Read-FuzzCalls 'mp4').Count -eq 1) `
        'A fuzz finding was not minimized once, or the other target did not run after it.'
    $findingRecord = Read-FuzzRecord $findingRoot 'dash'
    Assert-True ($findingRecord.exitReason -eq 'finding' -and @($findingRecord.findings | Where-Object { $_ -like 'crash-*' }).Count -eq 1) `
        "A finding's record does not name it: $($findingRecord | ConvertTo-Json -Depth 6 -Compress)"
    Assert-True ((Invoke-FuzzCase $findingRoot -Mode @('mp4=fail') -With @{ Target = 'mp4'; FindingsPath = (Join-Path $findingRoot 'failed') }) -eq 2) `
        'A Jazzer that failed without a finding was not a harness failure (2).'
    Assert-True ((Read-FuzzRecord $findingRoot 'mp4').exitReason -eq 'jazzer-error') "A failed Jazzer's record does not say so."
    Assert-True ((Invoke-FuzzCase $findingRoot -Mode @('faulty=miss') -With @{ Target = 'mp4' }) -eq 2) `
        'A planted bug Jazzer missed was not a harness failure (2).'
    Assert-True ((Read-FuzzRecord $findingRoot 'faulty').exitReason -eq 'planted-bug-missed' -and @(Read-FuzzCalls 'mp4').Count -eq 0) `
        'A missed planted bug was not recorded, or a target ran after it.'
    $noJavac = Join-Path $fuzzCases 'tools/no-javac'
    New-Item -ItemType Directory -Force -Path (Join-Path $noJavac 'bin') | Out-Null
    Copy-Item -LiteralPath $standIn -Destination (Join-Path $noJavac 'bin/java.exe')
    Assert-True ((Invoke-FuzzCase $findingRoot -With @{ Java = $noJavac }) -eq 2) 'A JDK with no javac was not a harness failure (2).'
    $null = @(Read-QueueLog)
} finally {
    # The junction first, so removing the cases can't reach through it.
    foreach ($link in $fuzzLinks) { if (Test-Path -LiteralPath $link) { [System.IO.Directory]::Delete($link) } }
    $fuzzSha1.Dispose()
    Remove-Item -LiteralPath $fuzzCases -Recurse -Force -ErrorAction SilentlyContinue
}
$global:LASTEXITCODE = 0

Write-Host '[scripts] parser fuzzing contracts passed'

# --- tracked files name no machine -----------------------------------------------------------
#
# No tracked file names the working-notes folder .gitignore keeps out, the backup folders on the
# maintainer's machine that share its name, or a phone's adb serial. Four fixture tests fell back
# to one of those folders, which skipped quietly on every other machine and published this one's
# layout, and five scripts carried the test phone's serial. .gitignore is the one exception: it
# has to name what it keeps out. Find-MachineNames in common.ps1 holds both patterns, built from
# parts so neither file can match itself, and the pre-push hook runs the same scan on every
# commit a push publishes, whatever the push touches; the routing section above plants hits for it.

$machineNames = @(Find-MachineNames -Root $Root)
Assert-True ($machineNames.Count -eq 0) `
    ("Tracked files name the maintainer's machine or phone: " + ($machineNames -join '; '))
# A search that does not run must not read as a clean tree.
Assert-Throws { Find-MachineNames -Root (Join-Path ([System.IO.Path]::GetTempPath()) ('hushfacebook-absent-' + [guid]::NewGuid().ToString('N'))) } `
    '*could not search*' 'A machine-name scan that could not run read as a clean tree.'

Write-Host '[scripts] tracked-file machine name contracts passed'

# The queue the suite started with, and the stand-in's folder gone.
foreach ($queueName in 'Script', 'Ticket') {
    $variable = "Env:\BUILD_QUEUE_$($queueName.ToUpperInvariant())"
    if ($queueBefore[$queueName]) { Set-Item -LiteralPath $variable -Value $queueBefore[$queueName] }
    else { Remove-Item -LiteralPath $variable -ErrorAction SilentlyContinue }
}
Remove-Item -LiteralPath $queueStandIn -Recurse -Force -ErrorAction SilentlyContinue

$global:LASTEXITCODE = 0
Write-Host '[scripts] report, target, Java and guarded replacement contracts passed'
$marketingAssets = Join-Path $Root 'scripts/test-marketing-assets.ps1'
if (-not (Test-Path -LiteralPath $marketingAssets -PathType Leaf)) {
    throw 'scripts/test-marketing-assets.ps1 is missing. Marketing changes need a repeatable asset check.'
}
& $marketingAssets -Root $Root
if ($LASTEXITCODE -ne 0) {
    throw 'The marketing asset checks did not pass.'
}
