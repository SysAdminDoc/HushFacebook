<#
.SYNOPSIS
    Exercise StorelessApk.java against small APKs and bundles built here.

.DESCRIPTION
    The APK is shaped like Facebook's Android 9+ builds: classes.dex in the root, a metadata.txt
    that names one Superpack store and two secondary DEX files, the store itself, a v1 signature,
    an uncompressed native library and resource table, and a compressed layout. The decoded
    directory holds the two DEX files the device decoder would have written.

    The rewrite puts them in the root as classes2.dex and classes3.dex, uncompressed, writes
    metadata.txt the Android 11+ way, drops the store and the old signature, copies everything
    else byte for byte, and lands every uncompressed entry on its boundary (16 KiB for native
    libraries, 4 bytes for the rest). A bundle gets its base.apk rewritten and its other APKs
    copied as they were. A DEX whose SHA-1 isn't the one metadata.txt lists, a DEX the directory
    lacks, an APK already in the Android 11+ layout, two stores, a metadata line it doesn't know
    and an out-of-order DEX number are refused, and a refusal leaves no output behind.
#>
[CmdletBinding()]
param(
    [string]$Root,
    [string]$Java
)

$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
. (Join-Path $PSScriptRoot 'Resolve-Java.ps1')
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

$Java = Resolve-Java -Explicit $Java
$tool = Join-Path $PSScriptRoot 'StorelessApk.java'
$work = Join-Path ([IO.Path]::GetTempPath()) ('hushfacebook-storeless-test-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $work -Force | Out-Null

function New-Bytes {
    param([int]$Length, [int]$Seed, [byte[]]$Head = @())
    $random = [Random]::new($Seed)
    $bytes = [byte[]]::new($Length)
    $random.NextBytes($bytes)
    for ($i = 0; $i -lt $Head.Length; $i++) { $bytes[$i] = $Head[$i] }
    return , $bytes
}

function Get-Sha1 {
    param([byte[]]$Bytes)
    return ([Security.Cryptography.SHA1]::HashData($Bytes) | ForEach-Object { $_.ToString('x2') }) -join ''
}

function New-Zip {
    # Entries as @(name, bytes, stored) in order. .NET writes NoCompression as stored.
    param([string]$Path, [object[]]$Entries)
    $stream = [IO.File]::Create($Path)
    $zip = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($entry in $Entries) {
            $level = if ($entry[2]) { [IO.Compression.CompressionLevel]::NoCompression } else { [IO.Compression.CompressionLevel]::Optimal }
            $item = $zip.CreateEntry($entry[0], $level)
            $out = $item.Open()
            $out.Write($entry[1], 0, $entry[1].Length)
            $out.Dispose()
        }
    } finally {
        $zip.Dispose()
        $stream.Dispose()
    }
}

function Read-Zip {
    # Name to @{ Bytes; Stored; DataOffset }, the offset read from the central directory and the
    # entry's own local header, which is where Android's zip reader finds the data.
    param([string]$Path)
    $raw = [IO.File]::ReadAllBytes($Path)
    $eocd = -1
    for ($i = $raw.Length - 22; $i -ge 0; $i--) {
        if ([BitConverter]::ToUInt32($raw, $i) -eq 0x06054b50) { $eocd = $i; break }
    }
    Assert-True ($eocd -ge 0) "$Path has no end of central directory."
    $count = [BitConverter]::ToUInt16($raw, $eocd + 10)
    $at = [int][BitConverter]::ToUInt32($raw, $eocd + 16)
    $result = [ordered]@{}
    $zip = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        for ($n = 0; $n -lt $count; $n++) {
            Assert-True ([BitConverter]::ToUInt32($raw, $at) -eq 0x02014b50) "$Path has a broken central directory."
            $method = [BitConverter]::ToUInt16($raw, $at + 10)
            $nameLength = [BitConverter]::ToUInt16($raw, $at + 28)
            $extraLength = [BitConverter]::ToUInt16($raw, $at + 30)
            $commentLength = [BitConverter]::ToUInt16($raw, $at + 32)
            $local = [int][BitConverter]::ToUInt32($raw, $at + 42)
            $name = [Text.Encoding]::UTF8.GetString($raw, $at + 46, $nameLength)
            $dataOffset = $local + 30 + [BitConverter]::ToUInt16($raw, $local + 26) + [BitConverter]::ToUInt16($raw, $local + 28)
            $memory = [IO.MemoryStream]::new()
            $in = $zip.GetEntry($name).Open()
            $in.CopyTo($memory)
            $in.Dispose()
            $result[$name] = @{ Bytes = $memory.ToArray(); Stored = ($method -eq 0); DataOffset = $dataOffset }
            $at += 46 + $nameLength + $extraLength + $commentLength
        }
    } finally {
        $zip.Dispose()
    }
    return $result
}

function Invoke-Storeless {
    param([string]$InputPath, [string]$Decoded, [string]$Output)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $text = (& $Java $tool $InputPath $Decoded $Output 2>&1 | ForEach-Object { "$_" }) -join "`n"
    $code = $LASTEXITCODE
    $ErrorActionPreference = $previous
    return @{ Code = $code; Text = $text }
}

function Test-Equal {
    param([byte[]]$A, [byte[]]$B)
    return $A.Length -eq $B.Length -and [Linq.Enumerable]::SequenceEqual($A, $B)
}

$dexHead = [byte[]](0x64, 0x65, 0x78, 0x0a, 0x30, 0x33, 0x35, 0x00)
$utf8 = [Text.UTF8Encoding]::new($false)
$primary = New-Bytes 3001 1 $dexHead
$second = New-Bytes 5003 2 $dexHead
$third = New-Bytes 7001 3 $dexHead
$store = New-Bytes 4099 4
$library = New-Bytes 20000 5
$table = New-Bytes 999 6
$layout = $utf8.GetBytes('<LinearLayout/>' * 40)
$services = $utf8.GetBytes("kotlinx.coroutines.android.AndroidExceptionPreHandler`n")
$dexManifest = $utf8.GetBytes("Lsecondary/dex01/Canary;,ordinal=0`nLsecondary/dex02/Canary;,ordinal=1`n")

function New-Metadata {
    # secondary-1.dex holds $third and secondary-2.dex holds $second, so the order comes from metadata.txt, not the bytes.
    param([string[]]$Head = @('.superpack_files 1', '.superpack_extension spo'), [string]$FirstName = 'secondary-1.dex')
    $lines = @($Head) + @(
        "$FirstName $(Get-Sha1 $third) secondary.dex01.Canary",
        "secondary-2.dex $(Get-Sha1 $second) secondary.dex02.Canary"
    )
    return $utf8.GetBytes(($lines -join "`n") + "`n")
}

function New-Apk {
    param([string]$Path, [byte[]]$Metadata = (New-Metadata), [switch]$NoStore)
    $entries = @(
        , @('META-INF/MANIFEST.MF', $utf8.GetBytes("Manifest-Version: 1.0`n"), $false)
        , @('META-INF/CERT.SF', $utf8.GetBytes("Signature-Version: 1.0`n"), $false)
        , @('META-INF/CERT.RSA', (New-Bytes 300 7), $false)
        , @('META-INF/services/kotlinx.coroutines.CoroutineExceptionHandler', $services, $false)
        , @('AndroidManifest.xml', (New-Bytes 501 8), $false)
        , @('classes.dex', $primary, $true)
        , @('resources.arsc', $table, $true)
        , @('res/layout/main.xml', $layout, $false)
        , @('lib/arm64-v8a/libsuperpack-jni.so', $library, $true)
        , @('assets/secondary-program-dex-jars/dex_manifest.txt', $dexManifest, $false)
        , @('assets/secondary-program-dex-jars/metadata.txt', $Metadata, $false)
    )
    if (-not $NoStore) { $entries += , @('assets/secondary-program-dex-jars/store-0.dex.spo', $store, $true) }
    New-Zip -Path $Path -Entries $entries
}

$decoded = Join-Path $work 'decoded'
New-Item -ItemType Directory -Path $decoded -Force | Out-Null
[IO.File]::WriteAllBytes((Join-Path $decoded 'secondary-1.dex'), $third)
[IO.File]::WriteAllBytes((Join-Path $decoded 'secondary-2.dex'), $second)

try {
    # The rewrite.
    $apk = Join-Path $work 'facebook-android9.apk'
    New-Apk -Path $apk
    $out = Join-Path $work 'storeless.apk'
    $run = Invoke-Storeless $apk $decoded $out
    Assert-True ($run.Code -eq 0) "StorelessApk refused a well-formed Android 9+ APK: $($run.Text)"
    Assert-True ($run.Text -match '2 DEX files from store-0\.dex\.spo \(4099 bytes\) now classes2\.dex to classes3\.dex') "StorelessApk didn't report the move: $($run.Text)"
    $rewritten = Read-Zip $out
    Assert-True (Test-Equal $rewritten['classes2.dex'].Bytes $third) 'classes2.dex is not the first DEX metadata.txt lists.'
    Assert-True (Test-Equal $rewritten['classes3.dex'].Bytes $second) 'classes3.dex is not the second DEX metadata.txt lists.'
    Assert-True ($rewritten['classes2.dex'].Stored -and $rewritten['classes3.dex'].Stored) 'The moved DEX files are compressed; the Android 11+ build stores them.'
    $expected = ".root_relative`nclasses2.dex $(Get-Sha1 $third) secondary.dex01.Canary`nclasses3.dex $(Get-Sha1 $second) secondary.dex02.Canary`n"
    $actual = $utf8.GetString($rewritten['assets/secondary-program-dex-jars/metadata.txt'].Bytes)
    Assert-True ($actual -ceq $expected) "metadata.txt isn't in the Android 11+ form:`n$actual"
    foreach ($gone in @('assets/secondary-program-dex-jars/store-0.dex.spo', 'META-INF/MANIFEST.MF', 'META-INF/CERT.SF', 'META-INF/CERT.RSA')) {
        Assert-True (-not $rewritten.Contains($gone)) "$gone is still in the rewritten APK."
    }
    $kept = @{
        'classes.dex' = $primary; 'resources.arsc' = $table; 'res/layout/main.xml' = $layout
        'lib/arm64-v8a/libsuperpack-jni.so' = $library; 'AndroidManifest.xml' = $null
        'assets/secondary-program-dex-jars/dex_manifest.txt' = $dexManifest
        'META-INF/services/kotlinx.coroutines.CoroutineExceptionHandler' = $services
    }
    foreach ($name in $kept.Keys) {
        Assert-True $rewritten.Contains($name) "$name went missing in the rewrite."
        if ($kept[$name]) { Assert-True (Test-Equal $rewritten[$name].Bytes $kept[$name]) "$name changed in the rewrite." }
    }
    Assert-True (-not $rewritten['res/layout/main.xml'].Stored) 'A compressed entry came out uncompressed.'
    foreach ($name in $rewritten.Keys) {
        $entry = $rewritten[$name]
        if (-not $entry.Stored) { continue }
        $boundary = if ($name -like 'lib/*.so') { 16384 } else { 4 }
        Assert-True ($entry.DataOffset % $boundary -eq 0) "$name starts at $($entry.DataOffset), off its $boundary byte boundary."
    }
    $names = @($rewritten.Keys)
    Assert-True ([array]::IndexOf($names, 'classes2.dex') -eq [array]::IndexOf($names, 'classes.dex') + 1) 'The moved DEX files are not right after classes.dex.'

    # A bundle: base.apk rewritten, the other APKs copied, the bundle's own signature dropped.
    $split = New-Bytes 2500 9
    $bundle = Join-Path $work 'facebook-android9.apkm'
    New-Zip -Path $bundle -Entries @(
        , @('META-INF/APKMIRRO.SF', $utf8.GetBytes("Signature-Version: 1.0`n"), $false)
        , @('info.json', $utf8.GetBytes('{"pname":"com.facebook.katana"}'), $false)
        , @('base.apk', [IO.File]::ReadAllBytes($apk), $false)
        , @('split_config.xxhdpi.apk', $split, $true)
    )
    $bundleOut = Join-Path $work 'storeless.apkm'
    $run = Invoke-Storeless $bundle $decoded $bundleOut
    Assert-True ($run.Code -eq 0) "StorelessApk refused a well-formed bundle: $($run.Text)"
    $bundleEntries = Read-Zip $bundleOut
    Assert-True (-not $bundleEntries.Contains('META-INF/APKMIRRO.SF')) "The bundle's old signature is still there."
    Assert-True (Test-Equal $bundleEntries['split_config.xxhdpi.apk'].Bytes $split) 'The density split changed.'
    Assert-True ($bundleEntries['split_config.xxhdpi.apk'].Stored) 'The stored split came out compressed.'
    $innerPath = Join-Path $work 'inner-base.apk'
    [IO.File]::WriteAllBytes($innerPath, $bundleEntries['base.apk'].Bytes)
    $inner = Read-Zip $innerPath
    Assert-True ($inner.Contains('classes3.dex') -and -not $inner.Contains('assets/secondary-program-dex-jars/store-0.dex.spo')) "The bundle's base.apk wasn't rewritten."
    Assert-True (@(Get-ChildItem -LiteralPath $work -Filter 'storeless-base*').Count -eq 0) 'The extracted base.apk was left behind.'

    # Refusals, each leaving nothing at the output path.
    function Assert-Refused {
        param([string]$InputPath, [string]$Decoded, [string]$Pattern, [string]$What)
        $target = Join-Path $work ('refused-' + [guid]::NewGuid().ToString('N').Substring(0, 6) + '.apk')
        $result = Invoke-Storeless $InputPath $Decoded $target
        Assert-True ($result.Code -eq 1) "StorelessApk accepted $What (exit $($result.Code)): $($result.Text)"
        Assert-True ($result.Text -match $Pattern) "StorelessApk refused $What without saying why: $($result.Text)"
        Assert-True (-not (Test-Path -LiteralPath $target)) "StorelessApk left an output behind after refusing $What."
        Assert-True (-not (Test-Path -LiteralPath "$target.part")) "StorelessApk left a partial output behind after refusing $What."
    }

    $tampered = Join-Path $work 'tampered'
    New-Item -ItemType Directory -Path $tampered -Force | Out-Null
    [IO.File]::WriteAllBytes((Join-Path $tampered 'secondary-1.dex'), $third)
    $changed = [byte[]]$second.Clone()
    $changed[4000] = $changed[4000] -bxor 1
    [IO.File]::WriteAllBytes((Join-Path $tampered 'secondary-2.dex'), $changed)
    Assert-Refused $apk $tampered 'secondary-2\.dex has SHA-1 [0-9a-f]{40}, metadata\.txt lists' 'a DEX with the wrong SHA-1'

    $partial = Join-Path $work 'partial'
    New-Item -ItemType Directory -Path $partial -Force | Out-Null
    [IO.File]::WriteAllBytes((Join-Path $partial 'secondary-1.dex'), $third)
    Assert-Refused $apk $partial 'no secondary-2\.dex' 'a decoded directory missing a DEX'

    $already = Join-Path $work 'android11.apk'
    New-Apk -Path $already -Metadata ($utf8.GetBytes(".root_relative`nclasses2.dex $(Get-Sha1 $second) secondary.dex01.Canary`n")) -NoStore
    Assert-Refused $already $decoded 'already lists its DEX files in the APK root' 'an Android 11+ APK'

    $twoStores = Join-Path $work 'two-stores.apk'
    New-Apk -Path $twoStores -Metadata (New-Metadata -Head @('.superpack_files 2', '.superpack_extension spo'))
    Assert-Refused $twoStores $decoded 'names 2 Superpack stores' 'two stores'

    $unknown = Join-Path $work 'unknown-line.apk'
    New-Apk -Path $unknown -Metadata (New-Metadata -Head @('.superpack_files 1', '.superpack_extension spo', '.requires something'))
    Assert-Refused $unknown $decoded "a line this rewrite doesn't know: \.requires something" 'a metadata line it does not know'

    $outOfOrder = Join-Path $work 'out-of-order.apk'
    New-Apk -Path $outOfOrder -Metadata (New-Metadata -FirstName 'secondary-3.dex')
    Assert-Refused $outOfOrder $decoded 'lists secondary-3\.dex where secondary-1\.dex belongs' 'an out-of-order DEX number'

    $noStore = Join-Path $work 'no-store.apk'
    New-Apk -Path $noStore -NoStore
    Assert-Refused $noStore $decoded "names a store at assets/secondary-program-dex-jars/store-0\.dex\.spo that the APK doesn't have" 'an APK without its store'

    $same = Invoke-Storeless $apk $decoded $apk
    Assert-True ($same.Code -eq 1 -and $same.Text -match 'would overwrite the input') "StorelessApk didn't refuse to overwrite its input: $($same.Text)"
    Assert-True ((Read-Zip $apk).Contains('assets/secondary-program-dex-jars/store-0.dex.spo')) 'The refused overwrite changed the input.'

    Write-Host '[storeless] StorelessApk fixture tests passed'
} finally {
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
}
