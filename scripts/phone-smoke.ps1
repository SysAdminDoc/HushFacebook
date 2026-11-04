<#
.SYNOPSIS
    Walk a patched Facebook through a few screens on a phone and read its diagnostic report.

.DESCRIPTION
    Starts Facebook, opens the feed, Watch, Reels, notifications and Marketplace with a few
    swipes on each, then saves a full diagnostic report from Hushfacebook settings, pulls it and
    reads the [HOOK STATUS] table. It fails when any family reports something missing, ambiguous
    or a hook that threw, when a family named in -ExpectInvoked never ran, or when Facebook's
    process crashed during the walk. The report is the evidence, so the screen is read only to
    find the two rows the export needs, and every tap comes from a UI dump's bounds.

    The phone has to be unlocked and signed in. Nothing here puts it to sleep, uninstalls or
    clears anything. -Apk installs over what is there with adb install -r, so it only works for
    a build signed with the key Facebook on that phone already has.

    Dot-source it to get Read-SmokeReport alone, which the tests do.

.EXAMPLE
    scripts/phone-smoke.ps1 -Serial emulator-5554 -Apk out/hushfacebook-signed.apk

.EXAMPLE
    pwsh -Command "& scripts/phone-smoke.ps1 -ExpectInvoked 'Hide sponsored posts','Tap to play'"

    With HUSHFACEBOOK_DEVICE_SERIAL set. A list needs -Command, since -File passes it as one word.
#>
# Names only: a stray word must not land in -Apk and get installed.
[CmdletBinding(PositionalBinding = $false)]
param(
    [string]$Serial = $env:HUSHFACEBOOK_DEVICE_SERIAL,
    [string]$Apk,
    [string]$OutDir,
    [string[]]$Visit = @('fb://feed', 'fb://watch', 'fb://fb_shorts/viewer', 'fb://notifications', 'fb://marketplace'),
    [int]$Swipes = 3,
    [int]$SettleSeconds = 4,
    # Families whose hooks must have run at least once on the walk.
    [string[]]$ExpectInvoked = @(),
    # The two rows' labels, for a phone whose Hushfacebook settings aren't in English.
    [string]$ExportLabel = 'Export diagnostic report',
    [string]$SaveLabel = 'Save full report'
)

$Package = 'com.facebook.katana'
$LauncherClass = 'com.facebook.katana.LoginActivity'
# Resolved from the phone before anything starts: an alternate app icon turns .LoginActivity off.
$Entry = $null
# Reports go next to the crash files; builds before that saved them in Download/Morphe.
$ReportGlob = '/sdcard/Download/Hushfacebook/morphe-diagnostics-*.txt /sdcard/Download/Morphe/morphe-diagnostics-*.txt'

<#
    Reads a report's text into what the walk is judged on. Returns an object with Running (the
    report says hushfacebook: running), Families (one entry per [HOOK STATUS] line with Name,
    Invoked, Found, Ambiguous, Missing, Detail), Unread (lines in the table no pattern matched,
    which happens when the phone isn't in English) and Problems (one sentence per failure).
#>
function Read-SmokeReport {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Text,
        [string[]]$ExpectInvoked = @(),
        # When the walk started, in UTC. A crash the report keeps from before it doesn't count.
        [DateTime]$Since = [DateTime]::MinValue
    )
    $lines = $Text -split "`r?`n"
    $problems = New-Object System.Collections.Generic.List[string]
    $families = New-Object System.Collections.Generic.List[object]
    $unread = New-Object System.Collections.Generic.List[string]

    if ($lines.Count -eq 0 -or $lines[0] -ne 'MORPHE DIAGNOSTIC REPORT') {
        $problems.Add('The file is not a Morphe diagnostic report.')
    }
    $running = [bool]($lines | Where-Object { $_ -eq 'hushfacebook: running' })
    if (-not $running) {
        $paused = $lines | Where-Object { $_ -like 'hushfacebook: paused*' } | Select-Object -First 1
        if ($paused) { $problems.Add("Hushfacebook is paused ($paused), so no hook took its own path.") }
        else { $problems.Add('The report has no hushfacebook: line.') }
    }

    $inHooks = $false
    foreach ($line in $lines) {
        if ($line -eq '[HOOK STATUS]') { $inHooks = $true; continue }
        if (-not $inHooks) { continue }
        if ($line -eq '' -or $line -match '^\[.+\]$') { break }
        $pattern = '^(?<name>.+?): invoked (?<invoked>\d+), (?<found>\d+) found, (?:(?<ambiguous>\d+) ambiguous, )?' +
            '(?<missing>\d+) missing(?<rest>.*)$'
        if ($line -notmatch $pattern) { $unread.Add($line); continue }
        $ambiguous = 0
        if ($Matches['ambiguous']) { $ambiguous = [int]$Matches['ambiguous'] }
        $family = [pscustomobject]@{
            Name      = $Matches['name']
            Invoked   = [long]$Matches['invoked']
            Found     = [int]$Matches['found']
            Ambiguous = $ambiguous
            Missing   = [int]$Matches['missing']
            Detail    = $Matches['rest'].TrimStart('.', ' ')
        }
        $families.Add($family)
        if ($family.Missing -gt 0 -or $family.Ambiguous -gt 0 -or $family.Detail -like '*and more it stopped counting*') {
            $problems.Add("$($family.Name): $($family.Missing) missing, $($family.Ambiguous) ambiguous. $($family.Detail)".TrimEnd())
        }
    }
    if (-not $inHooks) { $problems.Add('The report has no [HOOK STATUS] table. Is Patch errors left out of Included diagnostics?') }
    if ($unread.Count -gt 0) {
        $problems.Add("$($unread.Count) hook status line(s) could not be read, the first: $($unread[0])")
    }
    foreach ($name in $ExpectInvoked) {
        $seen = $families | Where-Object { $_.Name -eq $name } | Select-Object -First 1
        if (-not $seen) { $problems.Add("$name is not in the hook status table.") }
        elseif ($seen.Invoked -eq 0) { $problems.Add("$name never ran on the walk.") }
    }
    # The report keeps the latest crash however old it is, so only one from the walk counts.
    if ($lines -contains '[LATEST JAVA CRASH]') {
        $at = [array]::IndexOf($lines, '[LATEST JAVA CRASH]')
        $stamp = $null
        $exception = ''
        for ($i = $at + 1; $i -lt $lines.Count -and $lines[$i] -notmatch '^\[.+\]$'; $i++) {
            if ($lines[$i] -match '^timestamp_utc: (.+)$') {
                $parsed = [DateTime]::MinValue
                if ([DateTime]::TryParse($Matches[1], [Globalization.CultureInfo]::InvariantCulture,
                        [Globalization.DateTimeStyles]'AdjustToUniversal, AssumeUniversal', [ref]$parsed)) { $stamp = $parsed }
            }
            if ($lines[$i] -match '^exception: (.+)$') { $exception = $Matches[1] }
        }
        if ($null -eq $stamp -or $stamp -ge $Since) {
            $problems.Add("Facebook crashed with $exception at $stamp, during the walk or at a time the report doesn't give.")
        }
    }

    [pscustomobject]@{
        Running  = $running
        Families = $families.ToArray()
        Unread   = $unread.ToArray()
        Problems = $problems.ToArray()
    }
}

<#
    Reads crash buffer lines (logcat -b crash -v epoch, already cut to the walk) into $Package's
    crashes. Fatal holds one line per crash: Java's "Process:" line, or for a native crash, which
    leaves no Java line, its time, signal and top frame from debuggerd's tombstone. A native crash
    whose top frame is in libndk_translation, an emulator's arm translation layer, goes to
    EmulatorNotes instead: emulator-5590 logged an fdsan abort there on the 585 walks, and the walk
    read clean because only Java crashes were counted.
#>
function Read-CrashBuffer {
    param(
        [AllowEmptyCollection()][string[]]$Lines = @(),
        [Parameter(Mandatory = $true)][string]$Package
    )
    $fatal = New-Object System.Collections.Generic.List[string]
    $notes = New-Object System.Collections.Generic.List[string]
    $name = [regex]::Escape($Package)
    $natives = New-Object System.Collections.Generic.List[object]
    # libc's "Fatal signal 6 (SIGABRT) ... pid 1 (package)" comes first, then the tombstone:
    # "pid: 1, tid: 2, name: x  >>> package <<<", "signal 6 (SIGABRT)" and the backtrace's
    # "#00 pc 0005e1fc  /path/lib.so (symbol+164)" lines. The top frame is the first outside libc,
    # which only aborts for whoever called it. A header naming the crash's own pid again is the same
    # crash, so one crash counts once whichever lines it left.
    $native = $null
    foreach ($line in @($Lines)) {
        if ($line -match "Process: $name(?=[:,\s]|$)") { $fatal.Add($line.Trim()); continue }
        $epoch = if ($line -match '^\s*(\d+\.\d+)\s') { $Matches[1] } else { '' }
        if ($line -match "\bFatal signal \d+ \((?<signal>SIG[A-Z0-9]+)\).*\(\s*$name(?::[^)]*)?\)\s*$") {
            $signal = $Matches['signal']
            $crashPid = if ($line -match '\bpid (\d+) \(') { $Matches[1] } else { '' }
            $native = [pscustomobject]@{ Epoch = $epoch; Pid = $crashPid; Signal = $signal; Tombstone = $false; Top = ''; Frame = '' }
            $natives.Add($native)
            continue
        }
        if ($line -match '>>> (?<owner>\S+) <<<') {
            $owner = $Matches['owner']
            if ($owner -notmatch "^$name(?::|$)") { $native = $null; continue }
            $headerPid = if ($line -match '\bpid: (\d+),') { $Matches[1] } else { '' }
            $same = if ($null -eq $native) { $false }
                elseif ($native.Pid -and $headerPid) { $native.Pid -eq $headerPid }
                else { -not $native.Tombstone }
            if (-not $same) {
                $native = [pscustomobject]@{ Epoch = $epoch; Pid = $headerPid; Signal = ''; Tombstone = $true; Top = ''; Frame = '' }
                $natives.Add($native)
            }
            $native.Tombstone = $true
            if (-not $native.Pid) { $native.Pid = $headerPid }
            continue
        }
        if ($null -eq $native -or -not $native.Tombstone) { continue }
        if (-not $native.Signal -and $line -match '\bsignal \d+ \((?<signal>SIG[A-Z0-9]+)\)') {
            $native.Signal = $Matches['signal']
            continue
        }
        if ($line -match '#\d+ pc [0-9a-fA-F]+\s+(?<path>\S+)(?:\s+\((?<symbol>[^)]*)\))?') {
            $where = $Matches['path']
            $symbol = $Matches['symbol']
            $outsideLibc = $where -notmatch '/libc\.so$'
            if ($symbol -and $symbol -notlike 'BuildId:*') { $where += " ($symbol)" }
            if (-not $native.Top) { $native.Top = $where }
            if (-not $native.Frame -and $outsideLibc) { $native.Frame = $where }
        }
    }
    foreach ($crash in $natives) {
        $frame = if ($crash.Frame) { $crash.Frame } elseif ($crash.Top) { $crash.Top } else { 'no backtrace' }
        $signal = if ($crash.Signal) { $crash.Signal } else { 'unknown signal' }
        $text = "$($crash.Epoch) native crash, $signal in $frame".Trim()
        if ($frame -like '*libndk_translation*') { $notes.Add($text) } else { $fatal.Add($text) }
    }
    [pscustomobject]@{
        Fatal         = $fatal.ToArray()
        EmulatorNotes = $notes.ToArray()
    }
}

if ($MyInvocation.InvocationName -eq '.') { return }

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'device-install.ps1')
if (-not $Serial) { throw 'Pass -Serial or set HUSHFACEBOOK_DEVICE_SERIAL.' }
if (-not $OutDir) {
    $OutDir = Join-Path ([IO.Path]::GetTempPath()) ("hushfacebook-smoke-" + (Get-Date -Format 'yyyyMMdd-HHmmss'))
}
New-Item -ItemType Directory -Force $OutDir | Out-Null

$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb -and $env:ANDROID_HOME) {
    $candidate = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
    if (Test-Path -LiteralPath $candidate) { $adb = $candidate }
}
if (-not $adb) { throw 'No adb found. Put it on the PATH or set ANDROID_HOME.' }

# A plain function on purpose: $args takes am's -W, -n and --ez as they are, where a param()
# block would try to bind them.
function Invoke-Adb {
    $arguments = $args
    $preference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& $adb -s $Serial @arguments 2>&1 | ForEach-Object { [string]$_ })
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $preference
    }
    if ($code -ne 0) { throw "adb $($arguments -join ' ') exited with ${code}: $($output -join ' ')" }
    return $output
}

function Get-UiNodes {
    # uiautomator can't dump while the screen is still animating, so it gets a few tries.
    for ($try = 1; $try -le 4; $try++) {
        $dump = (Invoke-Adb shell 'uiautomator dump /sdcard/hushfacebook-smoke.xml >/dev/null 2>&1; cat /sdcard/hushfacebook-smoke.xml; rm -f /sdcard/hushfacebook-smoke.xml') -join "`n"
        $start = $dump.IndexOf('<?xml')
        if ($start -ge 0) {
            try { return ([xml]$dump.Substring($start)).SelectNodes('//node') } catch { }
        }
        Start-Sleep -Milliseconds 800
    }
    throw 'uiautomator gave no readable dump.'
}

function Invoke-TapLabel {
    param([Parameter(Mandatory = $true)][string]$Label)
    for ($try = 1; $try -le 3; $try++) {
        $node = Get-UiNodes | Where-Object { $_.text -eq $Label } | Select-Object -First 1
        if ($node -and $node.bounds -match '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') {
            $x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
            $y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
            Invoke-Adb shell input tap $x $y | Out-Null
            return
        }
        Start-Sleep -Seconds 1
    }
    throw "No '$Label' on the screen. Is the phone unlocked, with Facebook in front?"
}

if (((Invoke-Adb get-state) -join '') -ne 'device') { throw "$Serial is not ready." }
if (@(Get-AndroidPackagePaths -Adb $adb -Serial $Serial -PackageName $Package).Count -eq 0) { throw "$Package is not installed on $Serial." }

if ($Apk) {
    Write-Host "[smoke] installing $(Split-Path -Leaf $Apk)"
    $install = (Invoke-Adb install -r -g $Apk) -join ' '
    if ($install -notmatch 'Success') { throw "adb install failed: $install" }
}

$Entry = "$Package/$(Select-AndroidLauncher -Lines @(Invoke-Adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $Package) -PackageName $Package -LauncherClass $LauncherClass)"

Invoke-Adb shell input keyevent KEYCODE_WAKEUP | Out-Null
$keyguard = (Invoke-Adb shell dumpsys window) -join "`n"
if ($keyguard -match 'mDreamingLockscreen=true|isKeyguardShowing=true|mShowingLockscreen=true') {
    throw "$Serial is locked. Unlock it and run this again."
}

$size = (Invoke-Adb shell wm size) -join ' '
if ($size -notmatch '(\d+)x(\d+)\s*$') { throw "Could not read the screen size: $size" }
$width = [int]$Matches[1]; $height = [int]$Matches[2]
$swipeX = [int]($width / 2); $swipeFrom = [int]($height * 0.75); $swipeTo = [int]($height * 0.3)

$before = @(Invoke-Adb shell "ls $ReportGlob 2>/dev/null; true")
$startedEpoch = [long]((Invoke-Adb shell date +%s) -join '').Trim()

Write-Host "[smoke] starting Facebook on $Serial"
Invoke-Adb shell am force-stop $Package | Out-Null
Invoke-Adb shell am start -W -n $Entry | Out-Null
Start-Sleep -Seconds ($SettleSeconds * 2)

foreach ($uri in $Visit) {
    Write-Host "[smoke] $uri"
    Invoke-Adb shell am start -W -a android.intent.action.VIEW -d $uri -p $Package | Out-Null
    Start-Sleep -Seconds $SettleSeconds
    for ($i = 0; $i -lt $Swipes; $i++) {
        Invoke-Adb shell input swipe $swipeX $swipeFrom $swipeX $swipeTo 300 | Out-Null
        Start-Sleep -Milliseconds 1500
    }
}

Write-Host '[smoke] saving the diagnostic report'
# Clear to the launcher: with the walk's last page on top, Android would drop an intent that
# matches the task's root and differs only by extras.
Invoke-Adb shell am start -W --activity-clear-top -n $Entry --ez app.morphe.extension.facebook.OPEN_SETTINGS true `
    --es app.morphe.extension.facebook.SHOW_SETTING action_export_diagnostic_report | Out-Null
Start-Sleep -Seconds $SettleSeconds
Invoke-TapLabel $ExportLabel
Start-Sleep -Seconds 1
Invoke-TapLabel $SaveLabel

$saved = $null
for ($wait = 0; $wait -lt 15 -and -not $saved; $wait++) {
    Start-Sleep -Seconds 1
    $saved = @(Invoke-Adb shell "ls -t $ReportGlob 2>/dev/null; true") |
        Where-Object { $_ -like '*/morphe-diagnostics-*.txt' -and $before -notcontains $_ } | Select-Object -First 1
}
if (-not $saved) { throw 'No new report appeared in Download/Hushfacebook or Download/Morphe.' }
$local = Join-Path $OutDir ($saved -split '/')[-1]
Invoke-Adb pull $saved $local | Out-Null
Write-Host "[smoke] report: $local"

$walkStarted = [DateTime]::SpecifyKind([DateTime]'1970-01-01', 'Utc').AddSeconds($startedEpoch)
$result = Read-SmokeReport -Text ([IO.File]::ReadAllText($local)) -ExpectInvoked $ExpectInvoked -Since $walkStarted
$problems = New-Object System.Collections.Generic.List[string]
foreach ($problem in $result.Problems) { $problems.Add($problem) }

# Facebook's own crashes during the walk, from the crash buffer, which outlives the report's
# single latest-crash slot.
$crashes = @(Invoke-Adb shell logcat -b crash -d -v epoch) | Where-Object {
    $_ -match '^\s*(\d+)\.\d+\s' -and [long]$Matches[1] -ge $startedEpoch
}
$crashRead = Read-CrashBuffer -Lines @($crashes) -Package $Package
if ($crashRead.Fatal.Count -gt 0) { $problems.Add("Facebook crashed during the walk: $($crashRead.Fatal[0])") }
if (@($crashes).Count -gt 0) { [IO.File]::WriteAllLines((Join-Path $OutDir 'crash-buffer.txt'), [string[]]$crashes) }

$summary = New-Object System.Collections.Generic.List[string]
foreach ($note in $crashRead.EmulatorNotes) { $summary.Add("Emulator note, not Facebook's crash: $note") }
$summary.Add("$($result.Families.Count) hook families, $(@($result.Families | Where-Object { $_.Invoked -gt 0 }).Count) ran on the walk")
foreach ($family in $result.Families) {
    $summary.Add(('  {0}: invoked {1}, {2} found, {3} missing{4}' -f $family.Name, $family.Invoked, $family.Found,
        $family.Missing, $(if ($family.Detail) { ". $($family.Detail)" } else { '' })))
}
if ($problems.Count -eq 0) { $summary.Add('PASS') }
else {
    $summary.Add("FAIL ($($problems.Count))")
    foreach ($problem in $problems) { $summary.Add("  $problem") }
}
[IO.File]::WriteAllLines((Join-Path $OutDir 'summary.txt'), [string[]]$summary)
$summary | ForEach-Object { Write-Host "[smoke] $_" }
if ($problems.Count -gt 0) { exit 1 }
