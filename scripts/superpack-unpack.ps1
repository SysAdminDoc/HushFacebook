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

    With -Desktop there's no device at all, so it runs in CI. The input's own arm64-v8a
    libsuperpack-jni.so runs on this PC under unidbg, an ARM emulator for Android libraries, and
    the same DecodeStore checks and StorelessApk rewrite follow. The first run builds the small
    decoder in scripts/superpack-desktop with the repository's Gradle wrapper, which downloads
    unidbg from Maven Central. It needs JDK 21 or newer and runs on 64-bit Windows (x64 only), Linux
    and macOS (x64 or ARM). A 30 MB store takes about half a minute and under 1 GB of memory. A
    32-bit input has no arm64 library, so pass -LibraryFrom with the arm64 APK or bundle of the
    same Facebook version.

.PARAMETER Desktop
    Decode on this PC instead of a device. -Serial and -Adb are ignored.

.EXAMPLE
    pwsh -File scripts/superpack-unpack.ps1 -InputPath facebook-android9.apkm -OutputPath facebook-storeless.apkm

.EXAMPLE
    pwsh -File scripts/superpack-unpack.ps1 -Desktop -InputPath facebook-android9.apkm -OutputPath facebook-storeless.apkm
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
    [string]$Root,
    [switch]$Desktop
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
if (-not $Desktop) {
    $D8 = Resolve-D8 -Explicit $D8 -Root $Root
    if (-not $Adb) {
        $Adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
        foreach ($sdk in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk'))) {
            if ($Adb -or -not $sdk) { continue }
            $candidate = Join-Path $sdk 'platform-tools\adb.exe'
            if (Test-Path -LiteralPath $candidate) { $Adb = $candidate }
        }
    }
    if (-not $Adb) { throw 'No adb found. Put it on the PATH, set ANDROID_HOME or pass -Adb, or decode on this PC with -Desktop.' }
}

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

function Build-DesktopDecoder {
    # Built with the repository's Gradle wrapper, again whenever a source is newer than the jar.
    $project = Join-Path $PSScriptRoot 'superpack-desktop'
    $lib = Join-Path $project 'build/install/superpack-desktop/lib'
    $jar = Join-Path $lib 'superpack-desktop.jar'
    $files = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'superpack'), (Join-Path $project 'src') -Recurse -File) +
        @(Get-ChildItem -LiteralPath $project -Filter '*.gradle.kts' -File)
    $newest = ($files | Measure-Object -Property LastWriteTimeUtc -Maximum).Maximum
    if ((Test-Path -LiteralPath $jar -PathType Leaf) -and (Get-Item -LiteralPath $jar).LastWriteTimeUtc -gt $newest) { return $lib }

    $windows = $env:OS -eq 'Windows_NT'
    $gradlew = Join-Path $Root $(if ($windows) { 'gradlew.bat' } else { 'gradlew' })
    if (-not (Test-Path -LiteralPath $gradlew -PathType Leaf)) { throw "No Gradle wrapper at $gradlew to build the desktop decoder." }
    Write-Host '[superpack] building the desktop decoder (the first time downloads unidbg)'
    # Gradle runs on the same JDK as the decode, whatever JAVA_HOME says.
    $javaHome = $env:JAVA_HOME
    try {
        $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $Java)
        # gradlew is checked in without the executable bit, so a Linux or macOS checkout runs it through sh.
        $log = if ($windows) { & $gradlew -p $project --quiet installDist 2>&1 } else { & sh $gradlew -p $project --quiet installDist 2>&1 }
        $exitCode = $LASTEXITCODE
    } finally {
        $env:JAVA_HOME = $javaHome
    }
    if ($exitCode -ne 0 -or -not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw "Gradle didn't build the desktop decoder: $($log -join ' ')" }
    return $lib
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

if ($Desktop) {
    # unidbg emulates a 64-bit ARM phone, so the desktop decode always runs the arm64 library.
    $abi = 'arm64-v8a'
} else {
    if (-not $Serial) {
        $devices = @(& $Adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '^(\S+)\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
        if ($devices.Count -ne 1) { throw "Connect exactly one Android device or pass -Serial ($($devices.Count) connected), or decode on this PC with -Desktop." }
        $Serial = $devices[0]
    }
    $script:Serial = $Serial
    $abi = ("$(Invoke-Adb shell getprop ro.product.cpu.abi)").Trim()
    if ($abi -notmatch '^[a-z0-9_-]+$') { throw "The device reported a CPU type adb couldn't read: '$abi'." }
}

$work = Join-Path ([IO.Path]::GetTempPath()) ("hushfacebook-superpack-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
$remote = '/data/local/tmp/' + (Split-Path -Leaf $work)
New-Item -ItemType Directory -Path $work -Force | Out-Null
try {
    $decoder = if ($Desktop) { Build-DesktopDecoder } else { Build-Decoder }
    $apk = Open-Apk -Path $InputPath -WorkDir $work -Name 'input-base.apk'
    $metadata = Join-Path $work 'metadata.txt'
    Read-ZipEntry -Path $apk -Name 'assets/secondary-program-dex-jars/metadata.txt' -Target $metadata
    $metadataText = [IO.File]::ReadAllText($metadata)
    if ($metadataText -match '(?m)^\.root_relative\s*$') { throw "$InputPath already keeps its DEX files in the APK root; patch it as it is." }
    if ($metadataText -notmatch '(?m)^\.superpack_extension\s+([a-z0-9]{1,8})\s*$') { throw "$InputPath's metadata.txt names no Superpack store." }
    $store = Join-Path $work 'store.dex.spo'
    Read-ZipEntry -Path $apk -Name "assets/secondary-program-dex-jars/store-0.dex.$($Matches[1])" -Target $store

    $library = Find-Library -Path $(if ($LibraryFrom) { $LibraryFrom } else { $InputPath }) -Abi $abi -WorkDir $work
    if (-not $library -and $Desktop) {
        throw "No arm64-v8a libsuperpack-jni.so in $(if ($LibraryFrom) { $LibraryFrom } else { $InputPath }). -Desktop runs the arm64 library, so pass -LibraryFrom with the arm64-v8a APK or bundle of the same Facebook version."
    }
    if (-not $library) {
        throw "No libsuperpack-jni.so for this device's CPU ($abi) in $(if ($LibraryFrom) { $LibraryFrom } else { $InputPath }). Use a device that matches the APK, or pass -LibraryFrom with an APK of the same Facebook version built for $abi."
    }

    $decoded = Join-Path $work 'decoded'
    $storeMb = [math]::Round((Get-Item -LiteralPath $store).Length / 1MB, 1)
    if ($Desktop) {
        Write-Host "[superpack] decoding $storeMb MB store on this PC ($abi under unidbg)"
        # Every jar by name: pwsh on Linux and macOS expands a lib/* argument before java sees it.
        $classPath = @(Get-ChildItem -LiteralPath $decoder -Filter '*.jar' -File | ForEach-Object { $_.FullName }) -join [IO.Path]::PathSeparator
        # unidbg loads its emulator through System.loadLibrary, which JDK 24 and newer warn about.
        $lines = @(& $Java -Xmx1g --enable-native-access=ALL-UNNAMED -cp $classPath hushfacebook.superpack.desktop.DesktopDecode $library $store $metadata $decoded 2>&1 |
            ForEach-Object { "$_".TrimEnd() } | Where-Object { $_ })
        $exit = "EXIT=$LASTEXITCODE"
        $where = 'The desktop decoder'
    } else {
        Write-Host "[superpack] decoding $storeMb MB store on $Serial ($abi)"
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
        $where = 'The device'
    }
    $verdict = $lines | Where-Object { $_ -notmatch '^EXIT=' } | Select-Object -Last 1
    if ($Desktop) {
        # The emulator may log on its way down, after DecodeStore's last line.
        $said = $lines | Where-Object { $_ -match '^(VERIFIED|REFUSED) ' } | Select-Object -Last 1
        if ($said) { $verdict = $said }
    }
    $lines | Where-Object { $_ -match '^(MISMATCH|UNLISTED|UNWRITTEN|MISSING|REFUSED|WARN)' } | ForEach-Object { Write-Host "[superpack] $_" }
    if ($exit -ne 'EXIT=0' -or "$verdict" -notmatch '^VERIFIED ') {
        throw "$where didn't decode the store ($exit): $verdict"
    }
    Write-Host "[superpack] $verdict"

    if (-not $Desktop) { Invoke-Adb pull "$remote/out" $decoded | Out-Null }
    $log = & $Java -Xmx2g (Join-Path $PSScriptRoot 'superpack/hushfacebook/superpack/StorelessApk.java') $InputPath $decoded $OutputPath 2>&1
    $log | ForEach-Object { Write-Host "$_" }
    if ($LASTEXITCODE -ne 0) { throw "StorelessApk refused $InputPath." }
} finally {
    if (-not $Desktop -and $script:Serial) { & $Adb -s $Serial shell "rm -rf $remote" 2>&1 | Out-Null }
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
}
