<#
.SYNOPSIS
    Check what the bundle injected into Facebook against the shapes that fail on a device.

.DESCRIPTION
    A patch that writes into a register beyond the method's register count, branches into the
    middle of an instruction, passes an invoke the wrong registers or reads a parameter as the
    wrong kind assembles without complaint and applies without complaint. The desktop CLI reports
    it as applied. It fails only when a device verifies the class, and then it fails as a
    VerifyError on whatever screen happens to reach it first.

    Two checks, either of which catches that:

    Static. scripts/DexDiff.java lists every method the patched APK does not share with the
    clean build it came from, and holds each line that was not in the clean body, and every
    line of every added method (the extension's and the helpers patches add to Facebook's own
    classes), to that method's register count. Every changed and added method is also checked
    for a register past its count, the upper half of a wide value included, for branch and
    switch targets, invoke registers, the static and wide parameter layout, move-result
    placement and try ranges, and the whole APK is held to
    scripts/injected-mutation-contracts.txt: one feed guard, first thing in the funnel's body, each
    story-flag stub calling GraphQLStory's accessor before it returns, the showcase stub calling
    the accessor of the one class answering ShowcaseFeedUnit, one Stories tray count in each of the
    two tray adapter classes and Home's composer row, the reels hook first in the pre-EOF injector, the swap guard once in
    the runnable that swaps an edge into the feed, and one tab link call in each of the three places
    Facebook looks a link's tab up in its configured tabs, each in the one method its rule's strings
    and shape pick out.

    On a device. The Android runtime's own verifier is the authority, so with -Serial the
    clean APK and the patched APK are both put through dex2oat with the verify filter and the
    verifier's messages are compared. The patched build has to raise exactly the same message
    multiset as the clean build, which for Meta's builds so far is none. The emulator works: its
    x86_64 dex2oat verifies for arm64.

    Removed host methods and DEX entries fail the static check. The allowlist beside this script
    accepts only exact reviewed removals, and a stale entry fails so an old exception cannot mask
    a later regression.

    Both halves refuse to pass on absent evidence. A dex comparison that found no difference at
    all compared the wrong things, and a device run only counts once dex2oat has read a file of
    the size that was pushed (it exits 0 on a missing one). The clean side has to be Meta's own
    build of the same version as the patched one: a patched APK on the clean side would compare
    two bundles instead of the bundle against Facebook.

    The static half compares the whole clean bundle, merged the way the CLI merges it before it
    patches (Get-MergedApk), because the patched APK carries what the splits brought as well as
    base.apk's code: 580's in-app browser ships its own dex in split_heliumcore.apk, and against
    base.apk its methods read as some 12,000 added and 212 changed. The merge carries no
    signature, so Meta's signer is checked on base.apk, DexDiff holds the merge to base.apk's
    classes*.dex byte for byte (handed base.apk as its last argument), and it fails a clean side
    that holds any of the bundle's own code. The device half runs base.apk, the same app code.

.EXAMPLE
    scripts/verify-injected-registers.ps1 -PatchedApk C:\work\patched.apk

    The clean side is the fixture of the same version in the folder HUSHFACEBOOK_FIXTURE_DIR
    names, an .apk or the .apkm bundle Facebook ships as. A bundle is merged here unless
    -CleanMerged hands over its merge (verify-all-patches.ps1 has made one to patch).

.EXAMPLE
    scripts/verify-injected-registers.ps1 -FromDevice -Serial $env:HUSHFACEBOOK_DEVICE_SERIAL

.EXAMPLE
    scripts/verify-injected-registers.ps1 -PatchedApk C:\work\patched.apk -Serial $env:HUSHFACEBOOK_DEVICE_SERIAL -ReceiptPath verifier-receipt.json

    A release's run. The receipt (New-VerifierReceipt) says which phone verified the APK, the patch
    build identity read out of the APK's own dex, both halves' results and every verifier message
    that differs, and it's written PASS or FAIL. release/release.ps1 -Stage publish takes it as
    -Verifier and refuses a bundle it doesn't back.
#>
[CmdletBinding()]
param(
    [string]$CleanApk,
    [string]$CleanMerged,
    [string]$PatchedApk,
    [switch]$FromDevice,
    [string]$Serial,
    [string]$Adb,
    [string]$ReportPath,
    [string]$ReceiptPath,
    [string]$Java,
    [string]$DesktopJar,
    [string]$Aapt2
)

$ErrorActionPreference = 'Stop'
# A receipt stands for the device half, so a run that can't take one writes none.
if ($ReceiptPath -and -not $Serial) { throw '-ReceiptPath needs -Serial: the receipt is the device verifier''s word, and a run without a device has none.' }
# A run that stops short leaves no receipt, so an older one at that path can't pass for this run's.
$receiptFile = if ($ReceiptPath) { $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($ReceiptPath) }
if ($receiptFile -and (Test-Path -LiteralPath $receiptFile -PathType Leaf)) { Remove-Item -LiteralPath $receiptFile -Force }
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'Resolve-Java.ps1')
. (Join-Path $PSScriptRoot 'common.ps1')
. (Join-Path $PSScriptRoot 'release-receipt.ps1')
. (Join-Path $PSScriptRoot 'injected-register-contracts.ps1')
. (Join-Path $PSScriptRoot 'injected-register-device.ps1')
$Java = Resolve-Java -Explicit $Java
# dexlib2 comes from the desktop CLI. Without it the comparison cannot run at all, and saying so
# beats a stack trace about a missing class.
$DesktopJar = Resolve-DesktopCli -Explicit $DesktopJar -Root $root -Required
$Aapt2 = Resolve-Aapt2 -Explicit $Aapt2 -Root $root

function Resolve-Adb {
    param([string]$Explicit)
    if ($Explicit) { return $Explicit }
    if ($env:HUSHFACEBOOK_ADB) { return $env:HUSHFACEBOOK_ADB }
    $onPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    $sdkAdb = Join-Path (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $Aapt2))) 'platform-tools\adb.exe'
    if (Test-Path -LiteralPath $sdkAdb -PathType Leaf) { return $sdkAdb }
    throw 'No adb found. Pass -Adb or set HUSHFACEBOOK_ADB.'
}

# The certificate digests of every signer apksigner reports for an APK.
function Get-SignerDigests {
    param([string]$Apk)
    $apksigner = Join-Path (Split-Path -Parent $Aapt2) 'apksigner.bat'
    if (-not (Test-Path -LiteralPath $apksigner -PathType Leaf)) { throw "No apksigner beside aapt2: $apksigner" }
    # A newer JDK prints warnings on stderr, which Windows PowerShell 5.1 turns into a terminating
    # error under Stop. The exit code is what gets judged.
    $ErrorActionPreference = 'Continue'
    $lines = @(& $apksigner verify --print-certs $Apk 2>&1 | ForEach-Object { "$_" })
    if ($LASTEXITCODE -ne 0) { throw "apksigner could not verify ${Apk}: $($lines -join ' ')" }
    return @($lines | Where-Object { $_ -match 'certificate SHA-256 digest: ([0-9a-f]{64})' } |
        ForEach-Object { ($_ -replace '^.*certificate SHA-256 digest: ', '').Trim() } | Sort-Object -Unique)
}

# DexDiff over the pair, with Continue only in here, as in Get-SignerDigests. Called inside the try
# below, a java that can't start throws out of here into the script's Stop. Called outside one, it
# wouldn't, and this would read the exit code apksigner left, so the code starts at -1. The run
# waits for a slot of the machine's build queue when there is one (Invoke-HeavyJob), or runs at once
# inside the slot verify-all-patches.ps1 already holds, and its lines come back in a table.
function Invoke-DexDiff {
    $diffRun = @{ Output = @() }
    Invoke-HeavyJob -Label 'register check' -ScriptBlock {
        $ErrorActionPreference = 'Continue'
        $global:LASTEXITCODE = -1
        $diffRun.Output = @(& $Java '-Xmx8g' '-cp' $DesktopJar (Join-Path $PSScriptRoot 'DexDiff.java') `
            $CleanMerged $PatchedApk $ReportPath `
            (Join-Path $PSScriptRoot 'injected-register-removal-allowlist.txt') `
            (Join-Path $PSScriptRoot 'injected-mutation-contracts.txt') $cleanBase 2>&1 | ForEach-Object { "$_" })
    }
    [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = $diffRun.Output }
}

$work = Join-Path ([System.IO.Path]::GetTempPath()) ("hushfacebook-regs-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Force -Path $work | Out-Null
# The report stays where the caller can read it after the run: at -ReportPath when one is given,
# and otherwise in the working directory, which is kept whenever the run failed or stopped short,
# because that is when the FAIL line points at the report. The pulled and extracted APKs never
# outlive the run, because a full Facebook is hundreds of megabytes. Worked out before the default
# path is filled in: reading [bool]$ReportPath after that was always true, and read before it, it
# was false exactly when the report lived in the directory the cleanup removed.
$reportInWork = -not $ReportPath
if ($reportInWork) { $ReportPath = Join-Path $work 'injected-registers.txt' }
$failed = $false
$completed = $false
try {

$adbPath = $null
if ($FromDevice -or $Serial) { $adbPath = Resolve-Adb -Explicit $Adb }

if ($FromDevice) {
    if (-not $Serial) { throw '-FromDevice needs -Serial so it cannot pull from somebody else''s phone.' }
    $paths = @(& $adbPath -s $Serial shell pm path com.facebook.katana 2>&1 |
        ForEach-Object { "$_" } | Where-Object { $_ -match '^package:' })
    if ($paths.Count -eq 0) { throw "Facebook is not installed on $Serial." }
    if ($paths.Count -ne 1) {
        # A split install has no single APK to compare, and quietly taking the first would
        # compare the base against a whole clean build and call the rest missing.
        throw "The install on $Serial is split across $($paths.Count) APKs; pass -PatchedApk instead."
    }
    $onDevice = ($paths[0] -replace '^package:', '').Trim()
    $PatchedApk = Join-Path $work 'patched-installed.apk'
    Write-Host "[registers] pulling $onDevice"
    & $adbPath -s $Serial pull $onDevice $PatchedApk | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not pull the installed APK from $Serial." }
}

if (-not $PatchedApk -or -not (Test-Path -LiteralPath $PatchedApk -PathType Leaf)) {
    throw 'No patched APK. Pass -PatchedApk, or -FromDevice -Serial <serial>.'
}
$patched = Get-ApkManifestFacts -Apk $PatchedApk -Aapt2 $Aapt2
# Clone install (#417) renames the package, by default to com.facebook.katana.hush, and verify-all
# applies every patch, so a patched Facebook is the stock name or one under it.
$facebookPackage = '^com\.facebook\.katana(\.[A-Za-z][A-Za-z0-9_]*)*$'
if ($patched.package -notmatch $facebookPackage -or [string]::IsNullOrWhiteSpace($patched.versionName)) {
    throw "$PatchedApk is $($patched.package) $($patched.versionName), not a Facebook build."
}

if (-not $CleanApk) {
    $fixtures = if ($env:HUSHFACEBOOK_FIXTURE_DIR) { $env:HUSHFACEBOOK_FIXTURE_DIR } else { Join-Path $root 'fixtures' }
    $matching = @(Get-ChildItem -LiteralPath $fixtures -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Extension -in '.apk', '.apkm' -and $_.Name -like "*$($patched.versionName)*" })
    if ($matching.Count -ne 1) {
        throw ("No single fixture for Facebook $($patched.versionName) in $fixtures (found $($matching.Count)). " +
            'Pass -CleanApk with Meta''s build of that version.')
    }
    $CleanApk = $matching[0].FullName
}
if (-not (Test-Path -LiteralPath $CleanApk -PathType Leaf)) { throw "No clean APK at $CleanApk." }
# Facebook ships as a split bundle. Its base APK holds the manifest, Meta's signature and the app's
# own classes*.dex, which is what the device half verifies.
$cleanBase = Get-BaseApk -Apk $CleanApk -Destination (Join-Path $work 'clean-base.apk')

# The clean side has to be Meta's own build of the version that was patched, not a patched build
# or another version: two patched files differ from each other, both halves report differences,
# and the run would pass while comparing nothing against a clean baseline.
# Disable Play Store updates gives the patched manifest the highest version code Android takes, so
# the Play Store never offers Meta's next build over it. That one code stands for the clean build's
# own; the package and version name still have to match, and DexDiff still holds the clean side to
# Meta's signed base.apk.
$raisedVersionCode = [string][int]::MaxValue
$clean = Get-ApkManifestFacts -Apk $cleanBase -Aapt2 $Aapt2
if ($clean.package -ne 'com.facebook.katana' -or $clean.versionName -ne $patched.versionName -or
        ($clean.versionCode -ne $patched.versionCode -and [string]$patched.versionCode -ne $raisedVersionCode)) {
    throw ("The clean APK is $($clean.package) $($clean.versionName) ($($clean.versionCode)) and the " +
        "patched one $($patched.package) $($patched.versionName) ($($patched.versionCode)); they have to be the same build.")
}
$catalog = Get-Content -LiteralPath (Join-Path $root 'patches-list.json') -Raw | ConvertFrom-Json
$metaSigners = @($catalog.patches | ForEach-Object { $_.compatibility } |
    Where-Object { $_.packageName -eq 'com.facebook.katana' } |
    ForEach-Object { $_.signatures } | Sort-Object -Unique)
if ($metaSigners.Count -eq 0) { throw 'patches-list.json declares no Facebook signer to hold the clean APK to.' }
$cleanSigners = @(Get-SignerDigests -Apk $cleanBase)
if (@($cleanSigners | Where-Object { $_ -in $metaSigners }).Count -eq 0) {
    throw "The clean APK at $CleanApk is signed by $($cleanSigners -join ', '), not by Meta; it is not a vendor build."
}
# The dex comparison's clean side: the whole bundle, merged as the CLI merged it before patching,
# so a split's dex (580's in-app browser) is on both sides. DexDiff holds it to the signed base.apk's
# classes*.dex. A plain APK is its own merge. The merge is a job of the machine's build queue when
# there is one (Invoke-HeavyJob), and its path comes back in a table.
if (-not $CleanMerged) {
    $cleanMerge = @{ Path = $null }
    Invoke-HeavyJob -Label 'merge clean bundle' -ScriptBlock {
        $cleanMerge.Path = Get-MergedApk -Apk $CleanApk -Destination (Join-Path $work 'clean-merged.apk') -Java $Java -DesktopJar $DesktopJar
    }
    $CleanMerged = $cleanMerge.Path
}
if (-not (Test-Path -LiteralPath $CleanMerged -PathType Leaf)) { throw "No merged clean APK at $CleanMerged." }

Write-Host "[registers] clean   $CleanApk (Facebook $($clean.versionName), signed by Meta)"
Write-Host "[registers] merged  $CleanMerged (the dex comparison's clean side)"
Write-Host "[registers] patched $PatchedApk"

$diff = Invoke-DexDiff
$diff.Output | ForEach-Object { Write-Host "[registers] $_" }

if ($diff.ExitCode -ne 0) {
    Write-Host "[registers] FAIL: the dex comparison exited $($diff.ExitCode); see $ReportPath"
    $failed = $true
} else {
    Write-Host '[registers] static: every injected instruction stays inside its method, and the structure and contracts hold.'
}

if ($Serial) {
    Write-Host "[registers] running the device verifier on $Serial"
    $cleanTally = Invoke-AndroidVerifierTally -Adb $adbPath -Serial $Serial `
        -Local $cleanBase -Label 'clean'
    $patchedTally = Invoke-AndroidVerifierTally -Adb $adbPath -Serial $Serial `
        -Local $PatchedApk -Label 'patched'
    $comparison = Compare-VerifierTallies -Clean $cleanTally -Patched $patchedTally
    Write-Host "[registers] verifier messages: clean $($comparison.CleanTotal), patched $($comparison.PatchedTotal)"

    # Each tally is only returned once dex2oat has read a file of the pushed size, so two empty
    # tallies are an answer: Meta's build raises nothing, and the patched one must raise nothing more.
    if (-not $comparison.Valid) {
        $missing = @($comparison.Deltas | Where-Object Kind -eq 'missing')
        $extra = @($comparison.Deltas | Where-Object Kind -eq 'extra')
        if ($missing.Count -ne 0) {
            Write-Host "[registers] FAIL: the patched build is missing $($missing.Count) verifier message tally entry or count:"
            $missing | Select-Object -First 20 | ForEach-Object {
                Write-Host ("[registers]   {0}  (clean {1}, patched {2})" -f $_.Message, $_.Clean, $_.Patched)
            }
        }
        if ($extra.Count -ne 0) {
            Write-Host "[registers] FAIL: the patched build has $($extra.Count) extra verifier message tally entry or count:"
            $extra | Select-Object -First 20 | ForEach-Object {
                Write-Host ("[registers]   {0}  (clean {1}, patched {2})" -f $_.Message, $_.Clean, $_.Patched)
            }
        }
        $failed = $true
    } else {
        Write-Host '[registers] device: the clean and patched verifier message tallies are identical.'
    }
}

if ($ReceiptPath) {
    $model = Invoke-HushfacebookAdbCommand -Adb $adbPath -Arguments @('-s', $Serial, 'shell', 'getprop ro.product.model')
    if ($model.ExitCode -ne 0) { throw (Format-HushfacebookAdbFailure -Message "Could not read the model of $Serial" -Result $model) }
    $receipt = New-VerifierReceipt -Serial $Serial -Device ((@($model.Output) -join '').Trim()) -Facebook $patched.versionName `
        -PatchBuild (Get-ApkPatchBuild -Apk $PatchedApk) `
        -PatchedApkSha256 (Get-FileHash -LiteralPath $PatchedApk -Algorithm SHA256).Hash.ToLowerInvariant() `
        -StaticPassed ($diff.ExitCode -eq 0) -Comparison $comparison
    [System.IO.File]::WriteAllText($receiptFile, ($receipt | ConvertTo-Json -Depth 5))
    Write-Host "[registers] receipt: $ReceiptPath ($($receipt.result), patch build $(if ($receipt.patchBuild) { $receipt.patchBuild } else { 'none' }))"
}

$completed = $true
} finally {
    $keepReport = $reportInWork -and ($failed -or -not $completed) -and
        (Test-Path -LiteralPath $ReportPath -PathType Leaf)
    if ($keepReport) {
        Get-ChildItem -LiteralPath $work -Recurse -File -Filter '*.apk' -ErrorAction SilentlyContinue |
            Remove-Item -Force -ErrorAction SilentlyContinue
        Write-Host "[registers] the report is kept at $ReportPath"
    } else {
        Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# Success needs the try above to have run to its end. A launch error once left it early under a
# script-wide Continue, and the run fell through to the success line with nothing checked.
if ($failed -or -not $completed) { exit 1 }
Write-Host '[registers] success.'
exit 0
