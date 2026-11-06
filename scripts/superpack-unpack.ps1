<#
.SYNOPSIS
    Turn a Facebook Android 9+ APK or .apkm bundle into the layout the patcher can read.

.DESCRIPTION
    Facebook's Android 9+ builds keep all but the first DEX in a Superpack store that only the
    app's own native library decodes, so patching one finds just the classes in classes.dex. This
    decodes the store on a connected Android device over adb, with the libsuperpack-jni.so from
    the APK, checks every DEX against the APK's metadata.txt, and has StorelessApk.java write a
    copy with the DEX files in the APK root, the way the Android 11+ build ships them. Nothing is
    installed on the device: the decoder runs from /data/local/tmp as the shell user and its
    folder is removed afterwards.

    The library has to match the device's CPU. When the input's doesn't (an arm64 APK and an
    x86_64 emulator), -LibraryFrom names another APK or bundle of the same Facebook version whose
    library does, and the SHA-1 check proves the decode.

.EXAMPLE
    pwsh -File scripts/superpack-unpack.ps1 -InputPath facebook-android9.apkm -OutputPath facebook-storeless.apkm
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$InputPath,
    [Parameter(Mandatory = $true)][string]$OutputPath,
    [string]$Serial,
    [string]$LibraryFrom,
    [string]$Adb,
    [string]$Java,
    [string]$D8,
    [string]$Root
)

$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
. (Join-Path $PSScriptRoot 'Resolve-Java.ps1')
. (Join-Path $PSScriptRoot 'injected-register-contracts.ps1')
Add-Type -AssemblyName System.IO.Compression.FileSystem

$InputPath = [IO.Path]::GetFullPath($InputPath)
$OutputPath = [IO.Path]::GetFullPath($OutputPath)
if (-not (Test-Path -LiteralPath $InputPath -PathType Leaf)) { throw "No input at $InputPath." }
if ($LibraryFrom) {
    $LibraryFrom = [IO.Path]::GetFullPath($LibraryFrom)
    if (-not (Test-Path -LiteralPath $LibraryFrom -PathType Leaf)) { throw "No -LibraryFrom APK at $LibraryFrom." }
}
$Java = Resolve-Java -Explicit $Java
$D8 = Resolve-D8 -Explicit $D8 -Root $Root
if (-not $Adb) {
    $Adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
    foreach ($sdk in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk'))) {
        if ($Adb -or -not $sdk) { continue }
        $candidate = Join-Path $sdk 'platform-tools\adb.exe'
        if (Test-Path -LiteralPath $candidate) { $Adb = $candidate }
    }
}
if (-not $Adb) { throw 'No adb found. Put it on the PATH, set ANDROID_HOME or pass -Adb.' }

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    $output = & $Adb -s $script:Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb $($Arguments -join ' ') failed: $($output -join ' ')" }
    return $output
}

function Build-Decoder {
    # Rebuilt whenever a source is newer, so a stale jar never decodes.
    $sources = Join-Path $PSScriptRoot 'superpack'
    $out = Join-Path $Root 'build\superpack'
    $jar = Join-Path $out 'decoder.jar'
    $files = @(Get-ChildItem -LiteralPath $sources -Recurse -Filter '*.java')
    $newest = ($files | Measure-Object -Property LastWriteTimeUtc -Maximum).Maximum
    if ((Test-Path -LiteralPath $jar -PathType Leaf) -and (Get-Item -LiteralPath $jar).LastWriteTimeUtc -gt $newest) { return $jar }

    $classes = Join-Path $out 'classes'
    if (Test-Path -LiteralPath $classes) { Remove-Item -LiteralPath $classes -Recurse -Force }
    New-Item -ItemType Directory -Path $classes -Force | Out-Null
    $javac = Join-Path (Split-Path -Parent $Java) 'javac.exe'
    if (-not (Test-Path -LiteralPath $javac -PathType Leaf)) { $javac = Join-Path (Split-Path -Parent $Java) 'javac' }
    $log = & $javac --release 11 -nowarn -encoding UTF-8 -d $classes @($files.FullName) 2>&1
    if ($LASTEXITCODE -ne 0) { throw "javac failed on the Superpack decoder: $($log -join ' ')" }
    # The AssetManager stand-in only satisfies javac; on the device the framework's class resolves.
    $dexInputs = @(Get-ChildItem -LiteralPath $classes -Recurse -Filter '*.class' |
        Where-Object { $_.FullName -notlike "*$([IO.Path]::DirectorySeparatorChar)android$([IO.Path]::DirectorySeparatorChar)*" })
    if (Test-Path -LiteralPath $jar) { Remove-Item -LiteralPath $jar -Force }
    $log = & $D8 --release --min-api 28 --output $jar @($dexInputs.FullName) 2>&1
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw "d8 failed on the Superpack decoder: $($log -join ' ')" }
    return $jar
}

function Open-Apk {
    # A bundle's base.apk is extracted next to the other work files; a plain APK is used as it is.
    param([string]$Path, [string]$WorkDir, [string]$Name)
    $zip = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        if ($zip.GetEntry('assets/secondary-program-dex-jars/metadata.txt')) { return $Path }
        $base = $zip.GetEntry('base.apk')
        if (-not $base) { throw "$Path has no metadata.txt and no base.apk, so it isn't a Facebook APK or bundle." }
        $target = Join-Path $WorkDir $Name
        [IO.Compression.ZipFileExtensions]::ExtractToFile($base, $target, $true)
        return $target
    } finally {
        $zip.Dispose()
    }
}

function Find-Library {
    # Looks in the APK, then in the bundle's ABI split when the APK is a bundle's base.
    param([string]$Path, [string]$Abi, [string]$WorkDir)
    $zip = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $entry = $zip.GetEntry("lib/$Abi/libsuperpack-jni.so")
        if ($entry) {
            $target = Join-Path $WorkDir 'libsuperpack-jni.so'
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
            return $target
        }
        $split = $zip.GetEntry("split_config.$($Abi -replace '-', '_').apk")
        if ($split) {
            $splitPath = Join-Path $WorkDir 'abi-split.apk'
            [IO.Compression.ZipFileExtensions]::ExtractToFile($split, $splitPath, $true)
            return Find-Library -Path $splitPath -Abi $Abi -WorkDir $WorkDir
        }
        $base = $zip.GetEntry('base.apk')
        if ($base) {
            $basePath = Join-Path $WorkDir 'library-base.apk'
            [IO.Compression.ZipFileExtensions]::ExtractToFile($base, $basePath, $true)
            $found = Find-Library -Path $basePath -Abi $Abi -WorkDir $WorkDir
            if ($found) { return $found }
        }
        return $null
    } finally {
        $zip.Dispose()
    }
}

function Read-ZipEntry {
    param([string]$Path, [string]$Name, [string]$Target)
    $zip = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $entry = $zip.GetEntry($Name)
        if (-not $entry) { throw "$Path has no $Name." }
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $Target, $true)
    } finally {
        $zip.Dispose()
    }
}

if (-not $Serial) {
    $devices = @(& $Adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '^(\S+)\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
    if ($devices.Count -ne 1) { throw "Connect exactly one Android device or pass -Serial ($($devices.Count) connected)." }
    $Serial = $devices[0]
}
$script:Serial = $Serial
$abi = ("$(Invoke-Adb shell getprop ro.product.cpu.abi)").Trim()
if ($abi -notmatch '^[a-z0-9_-]+$') { throw "The device reported a CPU type adb couldn't read: '$abi'." }

$work = Join-Path ([IO.Path]::GetTempPath()) ("hushfacebook-superpack-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
$remote = '/data/local/tmp/' + (Split-Path -Leaf $work)
New-Item -ItemType Directory -Path $work -Force | Out-Null
try {
    $decoder = Build-Decoder
    $apk = Open-Apk -Path $InputPath -WorkDir $work -Name 'input-base.apk'
    $metadata = Join-Path $work 'metadata.txt'
    Read-ZipEntry -Path $apk -Name 'assets/secondary-program-dex-jars/metadata.txt' -Target $metadata
    $metadataText = [IO.File]::ReadAllText($metadata)
    if ($metadataText -match '(?m)^\.root_relative\s*$') { throw "$InputPath already keeps its DEX files in the APK root; patch it as it is." }
    if ($metadataText -notmatch '(?m)^\.superpack_extension\s+([a-z0-9]{1,8})\s*$') { throw "$InputPath's metadata.txt names no Superpack store." }
    $store = Join-Path $work 'store.dex.spo'
    Read-ZipEntry -Path $apk -Name "assets/secondary-program-dex-jars/store-0.dex.$($Matches[1])" -Target $store

    $library = Find-Library -Path $(if ($LibraryFrom) { $LibraryFrom } else { $InputPath }) -Abi $abi -WorkDir $work
    if (-not $library) {
        throw "No libsuperpack-jni.so for this device's CPU ($abi) in $(if ($LibraryFrom) { $LibraryFrom } else { $InputPath }). Use a device that matches the APK, or pass -LibraryFrom with an APK of the same Facebook version built for $abi."
    }

    Write-Host "[superpack] decoding $([math]::Round((Get-Item -LiteralPath $store).Length / 1MB, 1)) MB store on $Serial ($abi)"
    Invoke-Adb shell "rm -rf $remote && mkdir -p $remote/out" | Out-Null
    Invoke-Adb push $decoder "$remote/decoder.jar" | Out-Null
    Invoke-Adb push $library "$remote/libsuperpack-jni.so" | Out-Null
    Invoke-Adb push $store "$remote/store.dex.spo" | Out-Null
    Invoke-Adb push $metadata "$remote/metadata.txt" | Out-Null
    Invoke-Adb shell "chmod 0444 $remote/decoder.jar" | Out-Null
    # adb shell's own exit code is lost on some devices, so the command prints it.
    $lines = @(& $Adb -s $Serial shell "app_process -cp $remote/decoder.jar /system/bin hushfacebook.superpack.DecodeStore $remote/libsuperpack-jni.so $remote/store.dex.spo $remote/metadata.txt $remote/out; echo EXIT=`$?" 2>&1 |
        ForEach-Object { "$_".TrimEnd() } | Where-Object { $_ })
    $exit = $lines | Where-Object { $_ -match '^EXIT=\d+$' } | Select-Object -Last 1
    $verdict = $lines | Where-Object { $_ -notmatch '^EXIT=' } | Select-Object -Last 1
    $lines | Where-Object { $_ -match '^(MISMATCH|UNLISTED|UNWRITTEN|MISSING|REFUSED|WARN)' } | ForEach-Object { Write-Host "[superpack] $_" }
    if ($exit -ne 'EXIT=0' -or "$verdict" -notmatch '^VERIFIED ') {
        throw "The device didn't decode the store ($exit): $verdict"
    }
    Write-Host "[superpack] $verdict"

    $decoded = Join-Path $work 'decoded'
    Invoke-Adb pull "$remote/out" $decoded | Out-Null
    $log = & $Java -Xmx2g (Join-Path $PSScriptRoot 'StorelessApk.java') $InputPath $decoded $OutputPath 2>&1
    $log | ForEach-Object { Write-Host "$_" }
    if ($LASTEXITCODE -ne 0) { throw "StorelessApk refused $InputPath." }
} finally {
    if ($script:Serial) { & $Adb -s $Serial shell "rm -rf $remote" 2>&1 | Out-Null }
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
}
