<#
.SYNOPSIS
    Audit a patched Facebook on a phone or emulator: every switch on, a walk through the app, the
    hook report checked against a baseline, and every settings page captured in dark and light.

.DESCRIPTION
    The run, in order:

    1. Optionally installs -Apk over what is there (adb install -r -d, same signing key only).
    2. Creates Hushfacebook's audit folder (Android/data/com.facebook.katana/files/hushfacebook-audit),
       which only adb can make, and pushes a settings profile into it: every switch on except the
       ones that stop a walk (AuditMode.ALL_ON_EXCLUDED), plus -On and less -Off (by default the
       two switches that would take the reel hold away from Hold a reel for 2x). Facebook applies
       it at its next start and keeps the switches it had in before.json.
    3. Restarts Facebook twice, so every hook starts with the profile's values.
    4. Walks the screens in -Visit with -Swipes swipes each. It opens links, swipes, taps a few
       navigation rows and searches for one fixed word, so nothing is liked, followed or sent.
    5. Asks for the full diagnostic report over an intent and pulls it, then reads [HOOK STATUS]
       with phone-smoke's Read-SmokeReport and compares it to -Expectations: a family that ran in
       the baseline and didn't run now fails, as does anything missing, ambiguous or a crash.
       A -MayIdle family (or one the baseline lists under mayIdle) waits on what Facebook serves,
       so it gets a note either way and is never written into the baseline's ranOnWalk.
    6. Expands every settings category named in SettingsNavigation.java and Support, scrolls to the end and
       screenshots each screenful, in dark and light, plus one dark pass per -Locales entry.
       Each section's text and native expansion metadata go next to its screenshots, and index.html shows them all.
    7. Puts the switches, the night mode and the app language back, and removes the audit folder.
    8. Writes summary.txt and walk-receipt.json (walk-receipt.ps1): the result, whether the whole
       audit ran, the device, and the patch build identity the app reported. A release publishes
       only with a receipt that passed in full for the bundle it uploads.

    The phone has to be unlocked and signed in. -WriteBaseline records which families ran, for
    the next run to be held to: run it once on a build you trust after Facebook's version changes.
    -MinimumTapY keeps navigation taps below a caller's reserved area by scrolling rows down.
    Locale checks follow the app's ordered language list, including its English fallback.
    The requested application locale and section identity have to match. Each targeted section
    needs schema 3 proof that it is expanded and has visible child rows.

.EXAMPLE
    scripts/device-audit.ps1 -Serial emulator-5554 -ExpectedDevice Hushfacebook_API36 -Apk out/hushfacebook.apk

.EXAMPLE
    scripts/device-audit.ps1 -Serial emulator-5554 -ExpectedDevice Hushfacebook_API36 -WriteBaseline
#>
[CmdletBinding(PositionalBinding = $false)]
param(
    [string]$Serial = $env:HUSHFACEBOOK_DEVICE_SERIAL,
    # The leased emulator's AVD name, or the physical device's ro.product.model.
    [string]$ExpectedDevice,
    # Existing whole-device lease. This runner borrows it and never releases it.
    [string]$LeaseTokenFile = $env:HUSHFACEBOOK_DEVICE_LEASE_TOKEN_FILE,
    [string]$LeaseDirectory = $env:HUSHFACEBOOK_DEVICE_LEASE_DIR,
    [string]$Apk,
    [string]$OutDir,
    # The walk, step by step. A link is opened and swiped -Swipes times. "tap:<label>" taps the
    # node whose text or description is exactly <label>, from a fresh dump, scrolling back up to
    # find it, and only labels that open or expand something belong here: never Like, Follow,
    # Add friend, Send or a person. "longpress" holds the screen's center line 3/8 of the way down,
    # "back" goes back.
    # "query" types $AuditQuery into the search field fb://search just opened and submits it.
    # No fb://groups or fb://friends: all-on hides those tabs, and their links then land nowhere
    # or on Android's app chooser.
    [string[]]$Visit = @('fb://feed', 'tap:Comment', 'back', 'fb://watch', 'fb://fb_shorts/viewer', 'longpress',
        'fb://notifications', 'fb://marketplace', 'fb://profile', 'fb://search', 'query',
        'fb://bookmarks', 'tap:Settings and privacy', 'fb://bookmarks', 'tap:Feeds', 'fb://feed'),
    # Only these explicitly optional steps may be absent without failing the requested scope.
    [string[]]$OptionalVisit = @('tap:Comment', 'tap:Feeds'),
    [int]$Swipes = 4,
    [int]$SettleSeconds = 4,
    # Setting keys to turn on or off on top of the all-on profile, besides the few the walk itself
    # needs off (see Get-AuditOff).
    [string[]]$On = @(),
    [string[]]$Off = @(),
    [string]$Expectations = (Join-Path $PSScriptRoot 'device-audit-expectations.json'),
    # Families that run only when Facebook serves something the walk can't make appear: a
    # sponsored post on the profile, a new-reels badge on the Reels tab.
    [string[]]$MayIdle = @('Hide sponsored profile posts', 'Hide the Reels tab dot'),
    [switch]$WriteBaseline,
    [switch]$SkipWalk,
    [switch]$SkipPages,
    # App languages for an extra dark pass over the pages each, like 'de' or 'pt-BR'.
    [string[]]$Locales = @(),
    [int]$MaxScrolls = 14,
    # A caller can keep taps below an area reserved for system overlays.
    [ValidateRange(0, 10000)]
    [int]$MinimumTapY = 0,
    # Leaves the profile's switches on and the audit folder in place, for poking at by hand.
    [switch]$KeepSettings
)

$ErrorActionPreference = 'Stop'
# Read-SmokeReport and Read-CrashBuffer alone: dot-sourcing all of phone-smoke would bind its parameters over these.
$smokeAst = [System.Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'phone-smoke.ps1'), [ref]$null, [ref]$null)
foreach ($smokeFunction in @('Read-SmokeReport', 'Read-CrashBuffer')) {
    $smokeReader = $smokeAst.Find({ param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $smokeFunction }, $true)
    . ([scriptblock]::Create($smokeReader.Extent.Text))
}
# Use-Utf8ConsoleOutput alone, the same way: adb writes UTF-8, and Invoke-Adb reads it through this.
$commonAst = [System.Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'common.ps1'), [ref]$null, [ref]$null)
$utf8Reader = $commonAst.Find({ param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Use-Utf8ConsoleOutput' }, $true)
. ([scriptblock]::Create($utf8Reader.Extent.Text))
. (Join-Path $PSScriptRoot 'device-install.ps1')
. (Join-Path $PSScriptRoot 'walk-receipt.ps1')

$Package = 'com.facebook.katana'
# The launcher entry that's on, resolved from the phone by Get-AuditLauncher before any change.
# Picking an alternate app icon turns .LoginActivity off, and Android starts nothing for it then.
$LauncherClass = 'com.facebook.katana.LoginActivity'
$Entry = $null
$Extra = 'app.morphe.extension.facebook'
$AuditDir = "/sdcard/Android/data/$Package/files/hushfacebook-audit"
# These links open a playing reel, and uiautomator can't read a screen while a video plays
# ("could not get idle state", about 11 seconds a try). Their destination and their input are
# proved from the fragment tree instead (Get-AuditReelProof), and a reel never gets a tap.
$ReelRoutes = @('fb://watch', 'fb://fb_shorts/viewer')
# The only text the walk types. The search hooks run on a typeahead and a results page, which an
# empty search field never shows, and a common word brings both.
$AuditQuery = 'news'
$translationTables = Join-Path $PSScriptRoot '..\extensions\shared\library\src\main\l10n'

if (-not $Serial) { throw 'Pass -Serial or set HUSHFACEBOOK_DEVICE_SERIAL.' }
if ($Serial -cnotmatch '\A[A-Za-z0-9_.:-]+\z') { throw 'Invalid device serial.' }
if ([string]::IsNullOrWhiteSpace($ExpectedDevice)) { throw 'Pass -ExpectedDevice with the AVD name or device model.' }
if (-not $LeaseDirectory -or -not $LeaseTokenFile) { throw 'Pass -LeaseDirectory and -LeaseTokenFile, or set the HUSHFACEBOOK_DEVICE_LEASE_DIR and HUSHFACEBOOK_DEVICE_LEASE_TOKEN_FILE variables.' }
$identityVerified = $false
if (-not $OutDir) {
    $OutDir = Join-Path ([IO.Path]::GetTempPath()) ("hushfacebook-audit-" + (Get-Date -Format 'yyyyMMdd-HHmmss'))
}
$OutDir = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutDir)
$LeaseDirectory = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($LeaseDirectory)
$LeaseTokenFile = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($LeaseTokenFile)
$Expectations = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Expectations)
if ((Test-Path -LiteralPath $OutDir) -and
        (-not (Test-Path -LiteralPath $OutDir -PathType Container) -or @(Get-ChildItem -LiteralPath $OutDir -Force).Count -gt 0)) {
    throw 'Choose a new or empty -OutDir. Existing audit backups must not be reused.'
}
New-Item -ItemType Directory -Force $OutDir | Out-Null

$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
foreach ($root in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk'))) {
    if ($adb -or -not $root) { continue }
    $candidate = Join-Path $root 'platform-tools\adb.exe'
    if (Test-Path -LiteralPath $candidate) { $adb = $candidate }
}
if (-not $adb) { throw 'No adb found. Put it on the PATH or set ANDROID_HOME.' }

function Assert-AuditLease {
    if (-not (Test-Path -LiteralPath $LeaseTokenFile -PathType Leaf)) { throw 'A caller-owned lease token file is required.' }
    $token = [IO.File]::ReadAllText([IO.Path]::GetFullPath($LeaseTokenFile)).Trim()
    if ([string]::IsNullOrWhiteSpace($token)) { throw 'The lease token is empty.' }
    $leasePath = Join-Path $LeaseDirectory "$Serial.json"
    # Keep ownership validation and renewal under the same exclusive file handle. Another session
    # waiting for the phone may be reading the lease right now, so a sharing violation is retried
    # for a few seconds. A missing lease or a lock that outlasts the retries still stops the audit.
    $stream = $null
    foreach ($delay in @(100, 250, 500, 1000, 2000, 0)) {
        try {
            $stream = [IO.File]::Open($leasePath, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
            break
        } catch [IO.IOException] {
            if (($_.Exception.GetBaseException().HResult -band 0xFFFF) -notin 32, 33 -or $delay -eq 0) { throw }
            Start-Sleep -Milliseconds $delay
        }
    }
    try {
        $reader = [IO.StreamReader]::new($stream, [Text.Encoding]::UTF8, $true, 1024, $true)
        try { $lease = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
        if (($lease.schemaVersion -isnot [int] -and $lease.schemaVersion -isnot [long]) -or $lease.schemaVersion -ne 1 -or
                $lease.serial -isnot [string] -or $lease.project -isnot [string] -or
                $lease.chatIdentity -isnot [string] -or $lease.ownershipToken -isnot [string] -or
                $lease.serial -cne $Serial -or $lease.project -ine 'Hushfacebook' -or
                [string]::IsNullOrWhiteSpace($lease.chatIdentity) -or $lease.ownershipToken -cne $token) {
            throw 'The device lease belongs to another caller or has invalid identity.'
        }
        # ConvertFrom-Json may already have parsed an ISO timestamp. Do not stringify a DateTime
        # and lose its UTC offset before checking expiry.
        if ($lease.expiresUtc -isnot [DateTime] -and $lease.expiresUtc -isnot [DateTimeOffset] -and
                $lease.expiresUtc -isnot [string]) { throw 'The lease expiry is not a scalar timestamp.' }
        $expires = if ($lease.expiresUtc -is [DateTime]) { [DateTimeOffset]$lease.expiresUtc }
            else { [DateTimeOffset]::Parse([string]$lease.expiresUtc, [Globalization.CultureInfo]::InvariantCulture) }
        $now = [DateTimeOffset]::UtcNow
        if ($expires -le $now) { throw 'The device lease expired. Confirm the previous test stopped before renewing it.' }
        if ($expires -lt $now.AddMinutes(10)) {
            $lease.expiresUtc = $now.AddMinutes(25).ToString('o')
            $bytes = [Text.Encoding]::UTF8.GetBytes(($lease | ConvertTo-Json -Depth 5))
            $stream.Position = 0
            $stream.Write($bytes, 0, $bytes.Length)
            $stream.SetLength($bytes.Length)
            $stream.Flush($true)
        }
    } finally { $stream.Dispose() }
}

function Assert-AuditSteps {
    $allowed = @('fb://feed', 'fb://watch', 'fb://fb_shorts/viewer', 'fb://notifications',
        'fb://marketplace', 'fb://profile', 'fb://search', 'fb://bookmarks',
        'tap:Comment', 'tap:Settings and privacy', 'tap:Feeds', 'back', 'longpress', 'query')
    foreach ($step in $Visit) {
        if ($allowed -cnotcontains $step) { throw "Not a permitted audit navigation step: $step" }
    }
    foreach ($optional in $OptionalVisit) {
        if ($allowed -cnotcontains $optional) { throw "Not a permitted optional navigation step: $optional" }
    }
}

function Assert-AuditForeground {
    $activity = (Invoke-Adb shell dumpsys activity activities) -join "`n"
    $resumed = @($activity -split "`n" | Where-Object { $_ -match '(?:mResumedActivity|topResumedActivity)[:=]' } |
        ForEach-Object { if ($_ -match '\s([\w.]+/[\w.$]+)(?:\s|\})') { $Matches[1] } } | Sort-Object -Unique)
    if ($resumed.Count -ne 1 -or -not $resumed[0].StartsWith("$Package/", [StringComparison]::Ordinal)) {
        throw 'Facebook is not the single foreground activity. No input was sent.'
    }
    $windows = (Invoke-Adb shell dumpsys window) -join "`n"
    $focused = @($windows -split "`n" | Where-Object { $_ -match '^\s*mCurrentFocus=' } |
        ForEach-Object { if ($_ -match '\s([\w.]+/[\w.$]+)(?:\s|\})') { $Matches[1] } } | Sort-Object -Unique)
    if ($focused.Count -ne 1 -or -not $focused[0].StartsWith("$Package/", [StringComparison]::Ordinal)) {
        # Every focus line, as the first alone can be Facebook's when another display holds a second.
        $focusLines = @([regex]::Matches($windows, '(?m)^\s*mCurrentFocus=.*$') | ForEach-Object { $_.Value.Trim() })
        $focusText = if ($focusLines.Count -gt 0) { $focusLines -join '; ' } else { 'no mCurrentFocus line' }
        throw "Facebook does not own the focused input window ($focusText). No input was sent."
    }
    $windowId = [regex]::Match($windows, '(?m)^\s*mCurrentFocus=Window\{([A-Za-z0-9]+)\s')
    if (-not $windowId.Success) { throw 'The focused window has no verifiable identity. No input was sent.' }
    $script:auditWindowIdentity = $resumed[0] + '|' + $windowId.Groups[1].Value
}

function Invoke-Adb {
    $arguments = $args
    Assert-AuditLease
    if ($arguments.Count -gt 2 -and $arguments[0] -eq 'shell' -and $arguments[1] -eq 'input') {
        if (-not $identityVerified) { throw 'Device identity has not been verified. No input was sent.' }
        if (-not ($arguments.Count -eq 4 -and $arguments[2] -eq 'keyevent' -and $arguments[3] -eq 'KEYCODE_WAKEUP')) {
            if ($arguments[2] -cnotin @('tap', 'swipe', 'keyevent', 'text') -or
                    ($arguments[2] -eq 'keyevent' -and ($arguments.Count -ne 4 -or $arguments[3] -cnotin @('KEYCODE_BACK', 'KEYCODE_ENTER')))) {
                throw 'Not a permitted input. No input was sent.'
            }
            $typing = $arguments[2] -eq 'text' -or $arguments[3] -ceq 'KEYCODE_ENTER'
            if ($typing -and (-not $inputQuery -or
                    ($arguments[2] -eq 'text' -and ($arguments.Count -ne 4 -or $arguments[3] -cne $AuditQuery)))) {
                throw 'Only the audit query is typed, and only at the query step. Nothing was typed.'
            }
            Assert-AuditForeground
            if ($inputReel) {
                # A playing reel can't be dumped, so its fragments prove the screen. A tap soon after
                # another one likes a reel, so it never gets one.
                if ($arguments[2] -eq 'tap') { throw 'A reel never gets a tap. No tap was sent.' }
                if (-not (Get-AuditReelProof)) { throw 'The reel viewer is not on screen. No input was sent.' }
            } else {
                $fresh = @(Get-UiNodes)
                if ($fresh.Count -eq 0) { throw 'No fresh Facebook UI nodes. No input was sent.' }
            }
            $nodeWindow = $script:auditWindowIdentity
            $queryTap = $inputQuery -and $arguments[2] -eq 'tap'
            if ($queryTap) {
                # One tap per query step, and only inside the one search field holding the query.
                if ($script:auditQueryTaps -ge 1) { throw 'The search field already got its tap. No tap was sent.' }
                $field = Select-AuditQueryField $fresh
                if ($arguments.Count -ne 5 -or $arguments[3] -notmatch '^\d+$' -or $arguments[4] -notmatch '^\d+$' -or
                        $null -eq $field -or $field.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$' -or
                        [int]$arguments[3] -lt [int]$Matches[1] -or [int]$arguments[3] -ge [int]$Matches[3] -or
                        [int]$arguments[4] -lt [int]$Matches[2] -or [int]$arguments[4] -ge [int]$Matches[4]) {
                    throw 'The tap is not inside the one search field holding the query. No tap was sent.'
                }
            } elseif ($arguments[2] -eq 'tap') {
                if ($inputNodeLabel -cnotin @('Comment', 'Settings and privacy', 'Feeds')) {
                    throw 'No permitted navigation node was selected. No tap was sent.'
                }
                # The label must still name one target on the fresh screen, and the tap must land in it.
                $target = Select-AuditTapNode $inputNodeLabel $fresh
                if ($null -eq $target -or $target.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$' -or
                        [int]$arguments[3] -lt [int]$Matches[1] -or [int]$arguments[3] -ge [int]$Matches[3] -or
                        [int]$arguments[4] -lt [int]$Matches[2] -or [int]$arguments[4] -ge [int]$Matches[4]) {
                    throw 'The navigation node moved or became ambiguous. No tap was sent.'
                }
            }
            # A swipe or tap across an open keyboard types into the focused field (stray characters
            # in Facebook search on the S22, 2026-10-18), and the query needs the field focused.
            $keyboard = $arguments[2] -ne 'keyevent' -and (Test-AuditKeyboard)
            if ($typing -and $arguments[2] -eq 'text' -and -not $keyboard) { throw 'No keyboard is open for the query. Nothing was typed.' }
            if (-not $typing -and -not $queryTap -and $keyboard) { throw 'The keyboard is open. No swipe or tap was sent.' }
            if ($queryTap -and -not $keyboard) { throw 'No keyboard is open for the search field tap. No tap was sent.' }
            # A dump can take seconds. An overlay may have taken input while it was running.
            Assert-AuditForeground
            if ($nodeWindow -cne $script:auditWindowIdentity) {
                throw 'The window changed after collecting navigation nodes. No input was sent.'
            }
            if ($inputWindow -and $inputWindow -cne $script:auditWindowIdentity) {
                throw 'The verified destination window changed. No input was sent.'
            }
            if ($queryTap) { $script:auditQueryTaps++ }
        }
    }
    $preference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        # Read as UTF-8: under the console's code page 437 (a pwsh started from Git Bash) the curly
        # apostrophe in a notification row's settings button came back as three other characters, so no label with one
        # ever matched (the S22 walks' notifications step, 2026-10-27).
        $output = @(Use-Utf8ConsoleOutput { & $adb -s $Serial @arguments 2>&1 | ForEach-Object { [string]$_ } })
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $preference
    }
    if ($code -ne 0) { throw "adb $($arguments -join ' ') exited with ${code}: $($output -join ' ')" }
    return $output
}

function Write-Step([string]$Text) { Write-Host "[audit] $Text" }

# One argument for the phone's shell, quoted so spaces and apostrophes survive it.
function Format-Shell([string]$Text) { "'" + $Text.Replace("'", "'\''") + "'" }

function Get-AuditCrashes([long]$SinceEpoch) {
    # Host-side logcat waits for a missing device. Shell reports a disconnected transport as an error.
    $crashes = @(Invoke-Adb shell logcat -b crash -d -v epoch) | Where-Object {
        $_ -match '^\s*(\d+)\.\d+\s' -and [long]$Matches[1] -ge $SinceEpoch
    }
    # Java crashes and native ones (a tombstone's signal and top frame), minus emulator translation aborts.
    $read = Read-CrashBuffer -Lines @($crashes) -Package $Package
    return [pscustomobject]@{ Lines = @($crashes); Fatal = @($read.Fatal); EmulatorNotes = @($read.EmulatorNotes) }
}

# Whether the keyboard is on screen. A dump that doesn't say is no answer either way.
function Test-AuditKeyboard {
    $state = (Invoke-Adb shell dumpsys input_method) -join "`n"
    $shown = @([regex]::Matches($state, '\bmInputShown=(true|false)\b') | ForEach-Object { $_.Groups[1].Value })
    if ($shown.Count -eq 0) { throw 'The input method dump does not say whether the keyboard is open. No input was sent.' }
    return $shown -ccontains 'true'
}

function Get-UiNodes([int]$Tries = 10) {
    Assert-AuditForeground
    $windowBefore = $script:auditWindowIdentity
    # The settings title's gradient moves every frame, and on an emulator uiautomator then loses the
    # window's root ("null root node returned") up to two dumps in five, so a screen gets ten tries.
    for ($try = 1; $try -le $Tries; $try++) {
        $dump = (Invoke-Adb shell 'rm -f /sdcard/hushfacebook-audit.xml; uiautomator dump /sdcard/hushfacebook-audit.xml >/dev/null 2>&1; cat /sdcard/hushfacebook-audit.xml; rm -f /sdcard/hushfacebook-audit.xml') -join "`n"
        $start = $dump.IndexOf('<?xml')
        if ($start -ge 0) {
            try {
                $nodes = @(([xml]$dump.Substring($start)).SelectNodes('//node') | Where-Object { $_.package -ceq $Package })
                Assert-AuditForeground
                if ($script:auditWindowIdentity -cne $windowBefore) { throw 'The window changed during its UI dump.' }
                return $nodes
            } catch { }
        }
        Start-Sleep -Milliseconds 800
    }
    return @()
}

function Get-ScreenText([int]$Tries = 10) {
    $lines = foreach ($node in Get-UiNodes -Tries $Tries) {
        foreach ($value in @($node.text, $node.'content-desc')) { if ($value) { $value } }
    }
    return @($lines)
}

function Test-AuditDestination([string]$Route, [object[]]$Nodes) {
    $own = @($Nodes | Where-Object { $_.package -ceq $Package })
    $labels = @($own | ForEach-Object { $_.text; $_.'content-desc' } | Where-Object { $_ })
    # Known English accessibility markers on the supported Facebook build. An unknown language
    # or layout fails closed instead of counting a successful intent dispatch as a visit.
    switch -CaseSensitive ($Route) {
        'fb://feed' {
            return ($labels -ccontains "What's on your mind?" -and $labels -ccontains 'Go to profile') -or
                @($own | Where-Object { $_.'content-desc' -ceq 'Facebook logo' }).Count -gt 0
        }
        'fb://notifications' {
            # The link opens a page whose title isn't a node and which a warm app restores scrolled
            # down, so a row's own settings button counts too.
            return @($own | Where-Object { $_.text -ceq 'Notifications' -and $_.'content-desc' -ceq 'Notifications' }).Count -gt 0 -or
                $labels -ccontains "Manage the notification$([char]0x2019)s settings"
        }
        'fb://marketplace' { return $labels -ccontains 'Marketplace' -and $labels -ccontains 'Search Marketplace' }
        # An account with no profile picture offers to add one instead.
        'fb://profile' {
            return $labels -ccontains 'Edit profile' -and
                ($labels -ccontains 'Edit profile picture' -or $labels -ccontains 'Add profile picture')
        }
        'fb://search' {
            return @($own | Where-Object { $_.class -ceq 'android.widget.EditText' -and
                ($_.text -cmatch '^Search(?:\s|$)' -or $_.'content-desc' -cmatch '^Search(?:\s|$)') }).Count -gt 0
        }
        # The link opens the menu as a page with a Settings button and no Menu title.
        'fb://bookmarks' {
            return $labels -ccontains 'Settings and privacy' -and ($labels -ccontains 'Menu' -or $labels -ccontains 'Settings')
        }
    }
    return $false
}

# Whether $Activity's section of an activity client dump is resumed and shows a reel viewer: a
# WatchFeedOrWarionFragment that's shown and visible to the user, inside fragments that are too.
# A fragment's fields are the lines indented under its header, up to its child fragments. Lines
# naming a fragment after "#n:" or "mParent=" only list it. Anything ambiguous is no proof.
function Test-AuditReelViewer([string[]]$Top, [string]$Activity) {
    $lines = @(($Top -join "`n") -split "\r?\n")
    $heads = @(for ($i = 0; $i -lt $lines.Count; $i++) { if ($lines[$i] -match '^(?:TASK |\s*ACTIVITY )') { $i } })
    $mine = @($heads | Where-Object { $lines[$_] -match '^\s*ACTIVITY (\S+)\s' -and $Matches[1] -ceq $Activity })
    $section = $null
    foreach ($head in $mine) {
        $next = @(@($heads | Where-Object { $_ -gt $head }) + $lines.Count)[0]
        $candidate = if ($next -gt $head + 1) { $lines[($head + 1)..($next - 1)] } else { @() }
        $text = $candidate -join "`n"
        $local = [regex]::Matches($text, '(?m)^([ \t]*)Local Activity \S+ State:$')
        if ($lines[$head] -cmatch '\spid=\(not running\)(?:\s|$)') {
            if ($local.Count -ne 0) { return $false }
            continue
        }
        if ($local.Count -ne 1) { return $false }
        $stateIndent = $local[0].Groups[1].Value.Length + 2
        $states = [regex]::Matches($text, "(?m)^[ \t]{$stateIndent}mResumed=(true|false)\b")
        if ($states.Count -ne 1) { return $false }
        if ($states[0].Groups[1].Value -ceq 'true') {
            if ($null -ne $section) { return $false }
            $section = $candidate
        }
    }
    if ($null -eq $section) { return $false }
    $fields = @{}
    $name = $null
    $indent = 0
    foreach ($line in $section) {
        $depth = $line.Length - $line.TrimStart().Length
        if ($line -match '^\s+([\w$.]+\{[0-9a-f]+\}) \(') {
            if ($fields.ContainsKey($Matches[1])) { return $false }
            $name = $Matches[1]
            $indent = $depth
            $fields[$name] = [Collections.Generic.List[string]]::new()
        } elseif ($null -ne $name -and ($depth -le $indent -or $line -match '^\s+Child FragmentManager')) {
            $name = $null
        } elseif ($null -ne $name) {
            $fields[$name].Add($line)
        }
    }
    foreach ($reel in @($fields.Keys | Where-Object { $_.StartsWith('WatchFeedOrWarionFragment{', [StringComparison]::Ordinal) })) {
        $at = $reel
        for ($level = 0; $null -ne $at -and $level -lt 12; $level++) {
            if (-not $fields.ContainsKey($at)) { break }
            $text = $fields[$at] -join "`n"
            if ($text -cnotmatch '\bmHidden=false\b' -or $text -cnotmatch '\bmDetached=false\b' -or
                    $text -cnotmatch '\bmMenuVisible=true\b' -or $text -cnotmatch '\bmUserVisibleHint=true\b') { break }
            $parents = @([regex]::Matches($text, '(?m)^\s*mParentFragment=([\w$.]+\{[0-9a-f]+\})') | ForEach-Object { $_.Groups[1].Value })
            if ($parents.Count -gt 1) { break }
            $at = if ($parents.Count) { $parents[0] } else { $null }
        }
        if ($null -eq $at) { return $true }
    }
    return $false
}

# Proves the reel viewer is in front from the resumed Facebook screen's fragments, which can be
# read while a video plays. A proof that fails keeps what it read in the results folder, the windows
# before and after and the fragment dump, since the S22's all-on walk on 2026-11-08 failed a hold
# on a reel that was on screen and left nothing to show why.
function Get-AuditReelProof {
    Assert-AuditForeground
    $windowBefore = $script:auditWindowIdentity
    $activity = $windowBefore.Substring(0, $windowBefore.IndexOf('|'))
    $top = @(Invoke-Adb shell dumpsys activity (Format-Shell $activity))
    Assert-AuditForeground
    $proved = $script:auditWindowIdentity -ceq $windowBefore -and (Test-AuditReelViewer $top $activity)
    if (-not $proved -and -not [string]::IsNullOrEmpty($OutDir) -and (Test-Path -LiteralPath $OutDir -PathType Container)) {
        $script:auditReelMisses++
        $evidence = @("window before: $windowBefore", "window after: $($script:auditWindowIdentity)", '') + $top
        Set-Content -LiteralPath (Join-Path $OutDir ('reel-proof-failed-{0:00}.txt' -f $script:auditReelMisses)) -Value $evidence -Encoding utf8
    }
    return [bool]$proved
}

# What a hold on a reel opened (the comment sheet Open comments with a gesture opens, or the share
# sheet Facebook's own hold opens) stays over the reel, and the next link's page opens under it in the
# same activity, where a Back pops that page instead of the sheet: the fourth S22 walk, 2026-10-27,
# failed the notifications step after two Backs that way. So the hold step closes it there and then,
# a Back at a time, up to three (a focused composer takes one), until the reel's own controls are on
# screen again. An empty dump is a playing reel, which can't be dumped, with nothing over it. A screen
# that reads as it did before the hold ($Before, its texts and descriptions) has nothing over it
# either: on the S22's all-on walk, 2026-11-08, a reel viewer stopped on a page that never loaded
# read the same before and after the hold, showed no video controls, and the Back closed the viewer.
# Every read counts, repeats and case included, so a cover holding only a second spinner still differs.
function Close-AuditHoldCover([string[]]$Before = @()) {
    $unchanged = (@($Before | Sort-Object -CaseSensitive) -join "`n")
    $backs = 0
    while ($backs -lt 3) {
        $nodes = @(Get-UiNodes)
        if ($nodes.Count -eq 0 -or @($nodes | Where-Object {
                    $_.'content-desc' -ceq 'Tap to show video controls' -or $_.text -ceq 'Tap to show video controls' }).Count -gt 0) { break }
        $reads = @(foreach ($node in $nodes) { foreach ($value in @($node.text, $node.'content-desc')) { if ($value) { $value } } })
        if ($Before.Count -gt 0 -and (@($reads | Sort-Object -CaseSensitive) -join "`n") -ceq $unchanged) { break }
        Invoke-Adb shell input keyevent KEYCODE_BACK | Out-Null
        Start-Sleep -Seconds 1
        $backs++
    }
    return $backs
}

# Opens a walk route's link and proves the page it reached. A sheet an earlier step left open can
# cover the page the link opened (the comment sheet the hold on a reel left on the S22, 2026-10-27,
# failing the notifications step on two walks in a row), so a page that isn't proved gets a Back,
# which closes what covers it, and the link goes again. That comment sheet holds the composer's
# focus, so its first Back only drops the focus and a second one closes it (third S22 walk,
# 2026-10-27): up to two Backs, each followed by the link. A reel's proof reads its activity, which
# a sheet can't hide, so a reel route gets no second try.
function Open-AuditRoute([string]$Route, [bool]$Reel) {
    $script:auditRouteBacks = 0
    for ($attempt = 1; ; $attempt++) {
        $launch = (Invoke-Adb shell am start -W -a android.intent.action.VIEW -d $Route -p $Package) -join "`n"
        Start-Sleep -Seconds $SettleSeconds
        $proved = $launch -cmatch '(?m)^Status: ok\r?$' -and
            $(if ($Reel) { Get-AuditReelProof } else { Test-AuditDestination $Route @(Get-UiNodes) })
        if ($proved -or $Reel -or $attempt -ge 3) { return [bool]$proved }
        Invoke-Adb shell input keyevent KEYCODE_BACK | Out-Null
        Start-Sleep -Seconds 1
        $script:auditRouteBacks++
    }
}

# True when $Inner sits somewhere inside $Outer in the dump's tree. Only dumped nodes carry a parent.
function Test-AuditNodeHolds($Outer, $Inner) {
    if ($Inner -isnot [Xml.XmlNode]) { return $false }
    for ($parent = $Inner.ParentNode; $null -ne $parent; $parent = $parent.ParentNode) {
        if ([object]::ReferenceEquals($parent, $Outer)) { return $true }
    }
    return $false
}

# True when a clickable Facebook node other than the target, its rows or its parts holds the
# target's middle, where a tap on it would land.
function Test-AuditTapCovered($Target, [object[]]$Nodes) {
    if ($Target.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { return $true }
    $x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
    $y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    foreach ($other in $Nodes) {
        if ([object]::ReferenceEquals($other, $Target) -or $other.clickable -cne 'true' -or $other.package -cne $Package -or
                $other.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { continue }
        if ($x -lt [int]$Matches[1] -or $x -ge [int]$Matches[3] -or $y -lt [int]$Matches[2] -or $y -ge [int]$Matches[4]) { continue }
        if (-not (Test-AuditNodeHolds $other $Target) -and -not (Test-AuditNodeHolds $Target $other)) { return $true }
    }
    return $false
}

# The one node a "tap:<label>" step may tap, or $null. A row can repeat its label on a child that
# isn't clickable (the menu's Settings and privacy row on the S22), so several matches still
# name one target when exactly one is clickable and every other match sits inside it.
# Comment sits under every post, so two short posts put two on one screen, and any of them opens a
# comment sheet: the topmost clickable one a tap may reach is taken. A Comment scrolled under the
# top bar, the header row or the tab bar can't be reached, since the tap lands on what covers its
# middle (the all-on feed's drawer button on the S22, 2026-11-05, opened the drawer). Every other
# label must name one target.
function Select-AuditTapNode([string]$Label, [object[]]$Nodes, [string[]]$Repeated = @('Comment')) {
    $boundsPattern = '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$'
    $found = @($Nodes | Where-Object { $_.text -ceq $Label -or $_.'content-desc' -ceq $Label } |
        Where-Object { $_.package -ceq $Package -and $_.bounds -match $boundsPattern })
    if ($found.Count -eq 1 -and -not ($Repeated -ccontains $Label -and (Test-AuditTapCovered $found[0] $Nodes))) { return $found[0] }
    if ($found.Count -gt 1 -and $Repeated -ccontains $Label) {
        $reachable = @($found | Where-Object { $_.clickable -ceq 'true' } | ForEach-Object {
            $null = $_.bounds -match $boundsPattern
            $box = @([int]$Matches[1], [int]$Matches[2], [int]$Matches[3], [int]$Matches[4])
            if ($box[2] -gt $box[0] -and $box[3] -gt $box[1] -and [int](($box[1] + $box[3]) / 2) -ge $MinimumTapY -and
                    -not (Test-AuditTapCovered $_ $Nodes)) {
                [pscustomobject]@{ Node = $_; Top = $box[1] }
            }
        })
        if ($reachable.Count -eq 0) { return $null }
        return @($reachable | Sort-Object Top)[0].Node
    }
    $rows = @($found | Where-Object { $_.clickable -ceq 'true' })
    if ($found.Count -lt 2 -or $rows.Count -ne 1) { return $null }
    $null = $rows[0].bounds -match $boundsPattern
    $outer = @([int]$Matches[1], [int]$Matches[2], [int]$Matches[3], [int]$Matches[4])
    foreach ($other in $found) {
        if ([object]::ReferenceEquals($other, $rows[0])) { continue }
        $null = $other.bounds -match $boundsPattern
        if ([int]$Matches[1] -lt $outer[0] -or [int]$Matches[2] -lt $outer[1] -or
                [int]$Matches[3] -gt $outer[2] -or [int]$Matches[4] -gt $outer[3]) { return $null }
    }
    return $rows[0]
}

# The search field once the query is typed: Facebook's one EditText on screen, holding exactly the
# query. A typeahead row repeating the query, a second field or other text is no target.
function Select-AuditQueryField([object[]]$Nodes) {
    $fields = @($Nodes | Where-Object { $_.package -ceq $Package -and $_.class -ceq 'android.widget.EditText' })
    if ($fields.Count -ne 1 -or $fields[0].text -cne $AuditQuery -or
            $fields[0].bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$' -or
            [int]$Matches[3] -le [int]$Matches[1] -or [int]$Matches[4] -le [int]$Matches[2]) { return $null }
    return $fields[0]
}

function Find-AuditTapNode([string]$Label) {
    for ($look = 0; $look -le 5; $look++) {
        if ($look -gt 0) {
            Invoke-Adb shell input swipe $swipeX $swipeTo $swipeX $swipeFrom 300 | Out-Null
            Start-Sleep -Milliseconds 1200
        }
        $node = Select-AuditTapNode $Label @(Get-UiNodes)
        if ($node -and $node.bounds -match '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$' -and
                [int](([int]$Matches[2] + [int]$Matches[4]) / 2) -ge $MinimumTapY) { return $node }
    }
    return $null
}

function Save-Screenshot([string]$Path) {
    Invoke-Adb shell screencap -p /sdcard/hushfacebook-audit.png | Out-Null
    Invoke-Adb pull /sdcard/hushfacebook-audit.png $Path | Out-Null
    Invoke-Adb shell rm -f /sdcard/hushfacebook-audit.png | Out-Null
}

# The one MAIN/LAUNCHER entry the phone lists for Facebook: the default LoginActivity, or the
# alias an alternate icon turned on (Android lists only enabled components). Zero or several
# fail the run before anything changes. Component states are read, never set.
function Get-AuditLauncher {
    $lines = @(Invoke-Adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $Package)
    return Select-AndroidLauncher -Lines $lines -PackageName $Package -LauncherClass $LauncherClass
}

function Start-Facebook {
    if (-not $Entry) { throw 'The launcher entry was not resolved. No app start was sent.' }
    Invoke-Adb shell am force-stop $Package | Out-Null
    Invoke-Adb shell am start -W -n $Entry | Out-Null
    Start-Sleep -Seconds ($SettleSeconds * 2)
}

function Send-Profile([string]$Json) {
    $local = Join-Path $OutDir 'settings.json'
    [IO.File]::WriteAllText($local, $Json)
    Invoke-Adb shell am force-stop $Package | Out-Null
    Invoke-Adb shell rm -f "$AuditDir/import.txt" | Out-Null
    Invoke-Adb shell rm -f "$AuditDir/applied.json" | Out-Null
    Invoke-Adb push $local "$AuditDir/settings.json" | Out-Null
    Start-Facebook
    $import = ''
    for ($wait = 0; $wait -lt 30 -and -not $import; $wait++) {
        $import = ((Invoke-Adb shell "cat $AuditDir/import.txt 2>/dev/null; true") -join "`n").Trim()
        if (-not $import) { Start-Sleep -Seconds 1 }
    }
    if (-not $import) { throw 'Facebook never read the settings file. Is this a build with the audit folder in it?' }
    return $import
}

# The keys to turn off: [WalkKeys] that -On didn't ask for, then every -Off key, each once. The
# walk's hold on a reel reaches Hold a reel for 2x only with Long press opens
# comments and Only on the right edge off: either one sends it to the comment sheet or Facebook's
# menu instead. With the reel waiting for Tap to play, that hold is the only thing on the walk that
# sets a reel's speed (585 sets none for a reel that hasn't started), so Hold a reel for 2x and
# Keep the reel speed run on it alone. Hiding the Comment button under posts leaves tap:Comment
# nothing to open, and the comment sheet is the only way the walk reaches Open comments at full
# height (585 hides it; 584 never sent the row's type list on this account, so the baseline didn't
# see it). A key in both -On and -Off ends off, as the extension applies off after on.
function Get-AuditOff([string[]]$OnKeys, [string[]]$OffKeys,
    [string[]]$WalkKeys = @('hushfacebook_hold_reel_right_edge', 'hushfacebook_long_press_opens_comments',
        'hushfacebook_hide_post_comment_button')) {
    $keys = [System.Collections.Generic.List[string]]::new()
    foreach ($key in @(@($WalkKeys | Where-Object { $OnKeys -cnotcontains $_ }) + @($OffKeys))) {
        if ($key -and -not $keys.Contains($key)) { $keys.Add($key) }
    }
    return @($keys)
}

function New-AuditBaseline([string]$Report, [string[]]$Ran, [string[]]$Idle) {
    $identity = [regex]::Match($Report, '(?m)^patch_build: (sha256=[a-f0-9]{64}; source=(?:clean|dirty|unknown):[^\r\n]+)\r?$')
    if (-not $identity.Success) { throw 'The installed build has no verifiable patch identity.' }
    $app = [regex]::Match($Report, '(?m)^app: ' + [regex]::Escape($Package) + ' ([^ \r\n]+) \(([0-9]+)\)\r?$')
    if (-not $app.Success -or $app.Groups[1].Value -cne $version) { throw 'The report does not identify the installed Facebook version.' }
    return [ordered]@{
        schema = 2; facebook = $version; written = (Get-Date -Format 'yyyy-MM-dd')
        patchBuild = $identity.Groups[1].Value; installedApkSha256 = $installedHash
        abi = $installedAbi; sdk = $sdk; device = $deviceIdentity
        settingsSha256 = (Get-FileHash -LiteralPath (Join-Path $OutDir 'applied.json') -Algorithm SHA256).Hash.ToLowerInvariant()
        profile = [ordered]@{ name = 'all-on'; on = @($On | Sort-Object -Unique); off = @($Off | Sort-Object -Unique)
            visit = @($Visit); optionalVisit = @($OptionalVisit | Sort-Object -Unique); swipes = $Swipes
            locales = @($Locales); maxScrolls = $MaxScrolls }
        visit = @($Visit); ranOnWalk = @($Ran | Where-Object { @($MayIdle) -notcontains $_ } | Sort-Object)
        notOnWalk = @($Idle | Sort-Object); mayIdle = @($MayIdle | Where-Object { $_ } | Sort-Object -Unique)
    }
}

function Compare-AuditWalk($Expected, [string[]]$Ran) {
    $waits = @(@($MayIdle) + @($Expected.mayIdle) | Where-Object { $_ } | Sort-Object -Unique)
    $failures = @(@($Expected.ranOnWalk) | Where-Object { $Ran -notcontains $_ -and $waits -notcontains $_ } |
        ForEach-Object { "$_ ran on the baseline's walk ($($Expected.facebook)) and didn't now." })
    $notes = [Collections.Generic.List[string]]::new()
    $new = @($Ran | Where-Object { @($Expected.ranOnWalk) -notcontains $_ -and $waits -notcontains $_ })
    if ($new) { $notes.Add("Ran now and not in the baseline: $($new -join ', ')") }
    if ($waits) {
        $notes.Add('Wait on what Facebook serves: ' + (@($waits | ForEach-Object {
            if ($Ran -contains $_) { "$_ ran" } else { "$_ didn't run" } }) -join ', '))
    }
    return [pscustomobject]@{ Failures = $failures; Notes = @($notes) }
}

function Test-AuditBaseline($Expected, $Actual) {
    if (($Expected.schema -isnot [int] -and $Expected.schema -isnot [long]) -or $Expected.schema -ne 2 -or
            ($Expected.sdk -isnot [int] -and $Expected.sdk -isnot [long]) -or $Expected.sdk -ne $Actual.sdk) { return $false }
    foreach ($field in @('facebook', 'patchBuild', 'installedApkSha256', 'abi', 'device', 'settingsSha256')) {
        if ($Expected.$field -isnot [string] -or $Expected.$field -cne $Actual.$field) { return $false }
    }
    if (($Expected.profile -isnot [Collections.IDictionary] -and $Expected.profile -isnot [pscustomobject]) -or
            $Expected.ranOnWalk -isnot [Array] -or $Expected.ranOnWalk.Count -eq 0 -or
            @($Expected.ranOnWalk | Where-Object { $_ -isnot [string] -or [string]::IsNullOrWhiteSpace($_) }).Count -gt 0) { return $false }
    if ($null -ne $Expected.mayIdle -and ($Expected.mayIdle -isnot [Array] -or @($Expected.mayIdle | Where-Object {
            $_ -isnot [string] -or [string]::IsNullOrWhiteSpace($_) -or @($Expected.ranOnWalk) -contains $_ }).Count -gt 0)) { return $false }
    return (ConvertTo-Json -InputObject $Expected.profile -Depth 5 -Compress) -ceq
        (ConvertTo-Json -InputObject $Actual.profile -Depth 5 -Compress)
}

function Publish-AuditBaseline($Candidate) {
    if (-not $WriteBaseline -or $problems.Count -ne 0 -or -not $recovered -or $SkipWalk -or $SkipPages -or $KeepSettings) { return $false }
    if (-not (Test-AuditBaseline $Candidate $Candidate)) { return $false }
    $destination = [IO.Path]::GetFullPath($Expectations)
    $temporary = $destination + '.' + [Guid]::NewGuid().ToString('N') + '.candidate'
    try {
        # The baselines are committed as LF, and ConvertTo-Json writes CRLF on Windows.
        [IO.File]::WriteAllText($temporary, (($Candidate | ConvertTo-Json -Depth 6) -replace "`r`n", "`n") + "`n")
        if (Test-Path -LiteralPath $destination) { [IO.File]::Replace($temporary, $destination, [NullString]::Value) }
        else { [IO.File]::Move($temporary, $destination) }
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
    }
    return $true
}

function Get-AuditNightMode {
    $answer = ((Invoke-Adb shell cmd uimode night) -join '').Trim()
    if ($answer -notmatch '\ANight mode:\s*(yes|no|auto|custom)\s*\z') { throw 'Could not read the original night mode.' }
    return $Matches[1]
}

function Test-AuditLocaleList($Value) {
    if ($Value -isnot [string]) { return $false }
    if ($Value -ceq '') { return $true }
    # RFC 5646 section 2.1. Preserve private use and extensions, but refuse truncated tags that
    # Android would silently shorten. Variant and extension-singleton repeats are not well formed.
    $pattern = '\A(?:(?:[a-z]{2,3}(?:-[a-z]{3}){0,3}|[a-z]{4}|[a-z]{5,8})' +
        '(?:-[a-z]{4})?(?:-(?:[a-z]{2}|[0-9]{3}))?(?:-(?<variant>[a-z0-9]{5,8}|[0-9][a-z0-9]{3}))*' +
        '(?:-(?<singleton>[0-9a-wy-z])(?:-[a-z0-9]{2,8})+)*(?:-x(?:-[a-z0-9]{1,8})+)?' +
        '|x(?:-[a-z0-9]{1,8})+|en-gb-oed|i-(?:ami|bnn|default|enochian|hak|klingon|lux|mingo|navajo|pwn|tao|tay|tsu)' +
        '|sgn-(?:be-fr|be-nl|ch-de))\z'
    foreach ($tag in $Value.Split(',')) {
        if ($tag -cnotmatch '\A[A-Za-z0-9-]+\z') { return $false }
        $parsed = [regex]::Match($tag.ToLowerInvariant(), $pattern)
        if (-not $parsed.Success) { return $false }
        foreach ($group in @('variant', 'singleton')) {
            $parts = @($parsed.Groups[$group].Captures | ForEach-Object { $_.Value })
            if (@($parts | Sort-Object -Unique).Count -ne $parts.Count) { return $false }
        }
    }
    return $true
}

function Get-AuditLocales {
    $answer = ((Invoke-Adb shell cmd locale get-app-locales $Package) -join '').Trim()
    if ($answer -notmatch ('\ALocales for ' + [regex]::Escape($Package) + '.*\[([A-Za-z0-9,-]*)\]\s*\z')) {
        throw 'Could not read the original app locales.'
    }
    $locales = $Matches[1]
    if (-not (Test-AuditLocaleList $locales)) {
        throw 'The original app locale list is malformed.'
    }
    return $locales
}

# JSON objects as hashtables in both editions. Windows PowerShell has no -AsHashtable, and the
# pre-push hook falls back to it wherever git's PATH has no pwsh, so every restore check there
# threw "A parameter cannot be found that matches parameter name 'AsHashtable'".
function ConvertFrom-AuditJsonTable([string]$Json) {
    if ((Get-Command ConvertFrom-Json).Parameters.ContainsKey('AsHashtable')) {
        return , ($Json | ConvertFrom-Json -AsHashtable)
    }
    $toTable = {
        param($value)
        if ($value -is [System.Management.Automation.PSCustomObject]) {
            $table = @{}
            foreach ($property in $value.PSObject.Properties) { $table[$property.Name] = & $toTable $property.Value }
            return , $table
        }
        if ($value -is [array]) { return , @(foreach ($item in $value) { & $toTable $item }) }
        return $value
    }
    return , (& $toTable ($Json | ConvertFrom-Json))
}

# Whether $Restored puts back every switch $Original holds, and holds each switch $Original has
# no entry for (one added after the build that wrote it) at its value in $Defaults.
function Test-AuditSettingsRestored([string]$Original, [string]$Restored, [string]$Defaults) {
    try {
        $before = ConvertFrom-AuditJsonTable $Original
        $after = ConvertFrom-AuditJsonTable $Restored
        $reset = ConvertFrom-AuditJsonTable $Defaults
    } catch { return $false }
    foreach ($snapshot in @($before, $after, $reset)) {
        if ($snapshot -isnot [hashtable] -or $snapshot.settings -isnot [hashtable]) { return $false }
    }
    foreach ($field in @(@($before.Keys) + @($after.Keys) | Sort-Object -Unique | Where-Object { $_ -cne 'settings' })) {
        if ((ConvertTo-Json -InputObject $before[$field] -Compress) -cne (ConvertTo-Json -InputObject $after[$field] -Compress)) { return $false }
    }
    foreach ($key in $before.settings.Keys) {
        if (-not $after.settings.ContainsKey($key)) { return $false }
        if ((ConvertTo-Json -InputObject $before.settings[$key] -Compress) -cne (ConvertTo-Json -InputObject $after.settings[$key] -Compress)) { return $false }
    }
    foreach ($key in $after.settings.Keys) {
        if ($before.settings.ContainsKey($key)) { continue }
        if (-not $reset.settings.ContainsKey($key)) { return $false }
        if ((ConvertTo-Json -InputObject $reset.settings[$key] -Compress) -cne (ConvertTo-Json -InputObject $after.settings[$key] -Compress)) { return $false }
    }
    return $true
}

function Resolve-AuditStateBefore($Current) {
    <# The locale and theme the audit puts back at the end, given what the device shows now. #>
    # A previous unfinished run owns its original snapshot. Do not overwrite it with audit settings.
    $previousState = ((Invoke-Adb shell "cat $AuditDir/state-before.json 2>/dev/null; true") -join "`n").Trim()
    $previousBackup = ((Invoke-Adb shell "ls $AuditDir/before.json 2>/dev/null; true") -join '').Trim()
    if ($previousState) {
        $savedState = $previousState | ConvertFrom-Json
        if (($savedState.schema -isnot [int] -and $savedState.schema -isnot [long]) -or $savedState.schema -ne 1 -or
                $savedState.serial -isnot [string] -or $savedState.device -isnot [string] -or $savedState.package -isnot [string] -or
                $savedState.night -isnot [string] -or $savedState.serial -cne $Serial -or $savedState.device -cne $deviceIdentity -or
                $savedState.package -cne $Package -or $savedState.night -cnotmatch '\A(yes|no|auto|custom)\z' -or
                ($sdk -ge 33 -and $savedState.locales -isnot [string]) -or
                ($null -ne $savedState.locales -and -not (Test-AuditLocaleList $savedState.locales))) {
            throw 'Invalid recovery snapshot. Existing device backups were left untouched.'
        }
        return $savedState
    }
    if (-not $previousBackup) { return $Current }
    # A before.json with no snapshot beside it was left by an older audit build or the settings
    # tool, and twice on 2026-10-22 it stopped a walk until the snapshot was written by hand. Its
    # switches still come back at the end, as for a run that never finished, and the locale and
    # theme the device shows now stand in for the ones nothing recorded. A good restore clears the
    # folder, so a dated copy comes off the phone first, and the audit goes no further without it.
    $copy = Join-Path $OutDir ('orphaned-audit-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    New-Item -ItemType Directory -Path $copy -Force | Out-Null
    Invoke-Adb pull $AuditDir $copy | Out-Null
    $copied = Join-Path (Join-Path $copy ($AuditDir -split '/')[-1]) 'before.json'
    $copiedText = if (Test-Path -LiteralPath $copied -PathType Leaf) { [IO.File]::ReadAllText($copied).Trim() } else { '' }
    $readable = $false
    if ($copiedText) { try { $null = $copiedText | ConvertFrom-Json; $readable = $true } catch { $readable = $false } }
    if (-not $readable) {
        throw "An older audit backup has no locale/theme snapshot, and its folder didn't copy to $copy whole. It was left untouched."
    }
    Write-Step ('an older audit backup had no locale/theme snapshot. Its switches come back at the end, the ' +
        "current locale and theme stand in, and a copy of its folder is in $copy")
    return $Current
}

function Restore-AuditState {
    $restored = $true
    # Each part must run even if another fails. Keep both backups until all parts are verified.
    foreach ($part in @('settings', 'locale', 'night')) {
        try {
            switch ($part) {
                'settings' {
                    if ($KeepSettings) { break }
                    $backup = Join-Path $OutDir 'before.json'
                    if (-not (Test-Path -LiteralPath $backup)) {
                        Invoke-Adb pull "$AuditDir/before.json" $backup | Out-Null
                    }
                    $original = [IO.File]::ReadAllText($backup)
                    $restore = Send-Profile $original
                    if ($restore -cnotmatch '\Aapplied [0-9]+\z') { throw "Settings restore was not applied: $restore" }
                    $confirmed = Join-Path $OutDir 'restored.json'
                    Invoke-Adb pull "$AuditDir/applied.json" $confirmed | Out-Null
                    if ([IO.File]::ReadAllText($confirmed).Trim() -ceq $original.Trim()) { break }
                    # A backup an older build wrote has no entry for a switch added since, which then
                    # keeps its all-on value. Put every switch at its default, then the backup over it.
                    $reset = Send-Profile '{"format":"hushfacebook-audit","profile":"defaults"}'
                    if ($reset -cnotmatch '\Aapplied [0-9]+\z') { throw "The defaults profile was not applied: $reset" }
                    $defaults = Join-Path $OutDir 'defaults.json'
                    Invoke-Adb pull "$AuditDir/applied.json" $defaults | Out-Null
                    $restore = Send-Profile $original
                    if ($restore -cnotmatch '\Aapplied [0-9]+\z') { throw "Settings restore was not applied: $restore" }
                    Invoke-Adb pull "$AuditDir/applied.json" $confirmed | Out-Null
                    if (-not (Test-AuditSettingsRestored $original ([IO.File]::ReadAllText($confirmed)) ([IO.File]::ReadAllText($defaults)))) {
                        throw 'The applied settings snapshot does not match the original backup.'
                    }
                }
                'locale' {
                    if ($null -eq $stateBefore.locales) { break }
                    Invoke-Adb shell "cmd locale set-app-locales $Package --locales $(Format-Shell $stateBefore.locales)" | Out-Null
                    if ((Get-AuditLocales) -cne $stateBefore.locales) { throw 'App locales did not return to their original value.' }
                }
                'night' {
                    Invoke-Adb shell cmd uimode night $stateBefore.night | Out-Null
                    if ((Get-AuditNightMode) -cne $stateBefore.night) { throw 'Night mode did not return to its original value.' }
                }
            }
        } catch {
            $problems.Add("Could not restore ${part}: $($_.Exception.Message)")
            $restored = $false
        }
    }
    if ($restored -and -not $KeepSettings) {
        try {
            Start-Facebook
            Invoke-Adb shell rm -rf $AuditDir | Out-Null
        } catch {
            $problems.Add("Could not finish recovery; backups retained: $($_.Exception.Message)")
            $restored = $false
        }
    }
    return $restored
}

function Get-Report([string]$Path) {
    if (-not $Entry) { throw 'The launcher entry was not resolved. No report request was sent.' }
    Invoke-Adb shell rm -f "$AuditDir/report.txt" | Out-Null
    # The request matches the task's root intent (extras don't count), so with another screen on
    # top Android only brings the task forward and drops it. Clearing to the launcher delivers it.
    Invoke-Adb shell am start -W --activity-clear-top -n $Entry --ez "$Extra.AUDIT_REPORT" true | Out-Null
    for ($wait = 0; $wait -lt 20; $wait++) {
        Start-Sleep -Seconds 1
        $there = (Invoke-Adb shell "ls $AuditDir/report.txt 2>/dev/null; true") -join ''
        if ($there -like '*report.txt') {
            Invoke-Adb pull "$AuditDir/report.txt" $Path | Out-Null
            return [IO.File]::ReadAllText($Path)
        }
    }
    throw 'Facebook wrote no report.txt.'
}

# The accordion's category routes, followed by its Support section.
function Get-SettingsPages {
    $source = Join-Path $PSScriptRoot '..\extensions\facebook\src\main\java\app\morphe\extension\facebook\settings\SettingsNavigation.java'
    $pages = [regex]::Matches([IO.File]::ReadAllText($source), 'section\("([^"]+)"') | ForEach-Object { $_.Groups[1].Value }
    if (-not $pages) { throw "No settings pages found in $source" }
    # "" captures the main list before opening each category in place.
    return @('') + @($pages) + @('support')
}

# The Hush settings screen by its own words. The Hushfacebook name scrolls away with the header, so
# a page opened at its section, or scrolled to the bottom, shows only the fixed toolbar's list
# toggle, under either of its labels and in the page's language.
function Test-SettingsScreen([string[]]$Text, [object]$Rows) {
    foreach ($label in @('Hushfacebook', 'Show all settings as a list', 'Show settings by category')) {
        $words = $label
        if ($Rows -is [Collections.IDictionary] -and $Rows.Contains($label) -and -not [string]::IsNullOrEmpty([string]$Rows[$label])) {
            $words = [string]$Rows[$label]
        }
        if ($Text -ccontains $words) { return $true }
    }
    return $false
}

# Back until no Hush settings screen is left. Back first takes a page back from its section jump,
# then closes the screen.
function Close-Settings {
    for ($i = 0; $i -lt 4; $i++) {
        $text = @(Get-ScreenText)
        $record = ((Invoke-Adb shell "cat $AuditDir/page.json 2>/dev/null; true") -join "`n").Trim()
        $shown = if ($record) { $record | ConvertFrom-Json } else { $null }
        $table = if ($shown) { Get-AuditLocaleTable ([string]$shown.locale) $shown.localeTags } else { $null }
        if (-not ((Test-SettingsScreen $text $(if ($table) { $table.Rows } else { $null })) -or $text -contains 'Search settings' -or
                ($shown -and $shown.schema -in @(2, 3) -and $text -contains $shown.title))) { return }
        Invoke-Adb shell input keyevent KEYCODE_BACK | Out-Null
        Start-Sleep -Milliseconds 900
    }
}

function Get-AuditLocaleTable([string]$ActualLocale, [object]$LocaleTags) {
    if ([string]::IsNullOrEmpty($ActualLocale) -or $LocaleTags -isnot [Collections.IList] -or $LocaleTags.Count -eq 0) { return $null }
    foreach ($tag in $LocaleTags) {
        if ($tag -isnot [string] -or $tag -cnotmatch '^[a-z0-9]+(?:-[a-z0-9]+)*$') { return $null }
    }
    $actualLanguage = $ActualLocale.ToLowerInvariant().Split('-')[0] -replace '^id$', 'in' -replace '^he$', 'iw'
    $firstLanguage = $LocaleTags[0].Split('-')[0] -replace '^id$', 'in' -replace '^he$', 'iw'
    if ($actualLanguage -cne $firstLanguage) { return $null }
    if ($LocaleTags[0] -cin @('en-rxa', 'ar-rxb')) { return $null }
    foreach ($tag in $LocaleTags) {
        if ($tag -cmatch '^en(?:-r|$)') { break }
        $files = @(Get-ChildItem -LiteralPath $translationTables -Filter '*.tsv' | Where-Object { $_.BaseName.ToLowerInvariant() -ceq $tag })
        if ($files.Count -gt 1) { return $null }
        if ($files.Count -eq 1) {
            $rows = @{}
            foreach ($line in [IO.File]::ReadAllLines($files[0].FullName)) {
                if ($line.StartsWith('#') -or -not $line.Contains("`t")) { continue }
                $parts = $line.Split("`t", 2)
                $rows[$parts[0]] = $parts[1]
            }
            return [pscustomobject]@{ Tag = $tag; Rows = $rows }
        }
    }
    return [pscustomobject]@{ Tag = 'en'; Rows = @{} }
}

function Test-AuditSectionShown([object]$Shown, [string]$Page) {
    if ($null -eq $Shown -or ($Shown.schema -isnot [int] -and $Shown.schema -isnot [long]) -or $Shown.schema -ne 3 -or
            $Shown.route -isnot [string] -or $Shown.route -cne $Page -or
            $Shown.title -isnot [string] -or [string]::IsNullOrWhiteSpace($Shown.title) -or
            $Shown.expandedSections -isnot [Collections.IList] -or
            ($Shown.sectionRows -isnot [int] -and $Shown.sectionRows -isnot [long]) -or $Shown.sectionRows -lt 0) { return $false }
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($section in $Shown.expandedSections) {
        if ($section -isnot [string] -or $section -cnotmatch "^[A-Za-z0-9 ,']{1,64}$" -or -not $seen.Add($section)) { return $false }
    }
    $targetExpanded = $Shown.expandedSections -ccontains $Page
    if ($Shown.targetExpanded -isnot [bool] -or $Shown.targetExpanded -ne $targetExpanded) { return $false }
    if (-not $Page) { return $Shown.sectionRows -eq 0 -and $Shown.title -ceq 'Hushfacebook' }
    return $targetExpanded -and $Shown.sectionRows -gt 0
}

function Save-SettingsPages([string]$Pass, [string]$RequestedLocale = '') {
    $dir = Join-Path $OutDir "pages-$Pass"
    New-Item -ItemType Directory -Force $dir | Out-Null
    if (-not $Entry) { throw 'The launcher entry was not resolved. No settings page was opened.' }
    $results = New-Object System.Collections.Generic.List[object]
    $announcedTable = $null
    foreach ($page in Get-SettingsPages) {
        $name = if ($page) { ($page -replace "[^A-Za-z0-9]+", '-').Trim('-').ToLowerInvariant() } else { 'home' }
        Close-Settings
        Invoke-Adb shell rm -f "$AuditDir/page.json" | Out-Null
        $command = "am start -W --activity-clear-top -n $Entry --ez $Extra.OPEN_SETTINGS true"
        if ($page) { $command += " --es $Extra.AUDIT_PAGE $(Format-Shell $page)" }
        Invoke-Adb shell $command | Out-Null
        Start-Sleep -Seconds $SettleSeconds
        $pageRecord = ((Invoke-Adb shell "cat $AuditDir/page.json 2>/dev/null; true") -join "`n").Trim()
        $shown = if ($pageRecord) { $pageRecord | ConvertFrom-Json } else { $null }
        if ($pageRecord) { [IO.File]::WriteAllText((Join-Path $dir "$name.json"), $pageRecord) }
        $allText = New-Object System.Collections.Generic.List[string]
        $last = $null
        $shots = 0
        for ($scroll = 0; $scroll -le $MaxScrolls; $scroll++) {
            $text = @(Get-ScreenText)
            if ($text.Count -eq 0) { throw "No fresh Facebook UI nodes on $Pass/$name." }
            $signature = $text -join "`n"
            if ($signature -eq $last) { break }
            $last = $signature
            foreach ($line in $text) { if (-not $allText.Contains($line)) { $allText.Add($line) } }
            Save-Screenshot (Join-Path $dir ('{0}-{1:00}.png' -f $name, $scroll))
            $shots++
            Invoke-Adb shell input swipe $swipeX $swipeFrom $swipeX $swipeTo 500 | Out-Null
            Start-Sleep -Milliseconds 900
        }
        [IO.File]::WriteAllLines((Join-Path $dir "$name.txt"), [string[]]$allText)
        $localeMatches = $true
        $table = $null
        if ($RequestedLocale) {
            $requestedTag = $RequestedLocale.ToLowerInvariant() -replace '^in(?=-|$)', 'id'
            $actualTag = ([string]$shown.locale).ToLowerInvariant() -replace '^in(?=-|$)', 'id'
            $table = Get-AuditLocaleTable ([string]$shown.locale) $shown.localeTags
            $localeMatches = $null -ne $table -and ($actualTag -ceq $requestedTag -or $actualTag.StartsWith("$requestedTag-", [StringComparison]::Ordinal))
            $translations = if ($table) { $table.Rows } else { @{} }
            $titleKey = if (-not $page) { 'Hushfacebook' } elseif ($page -ceq 'support') { 'Support' } else { $page }
            $expectedTitle = if ($translations.ContainsKey($titleKey) -and -not [string]::IsNullOrEmpty($translations[$titleKey])) {
                $translations[$titleKey]
            } else { $titleKey }
            $localeMatches = $localeMatches -and $shown.title -ceq $expectedTitle
            if ($table -and $announcedTable -cne $table.Tag) {
                Write-Step "Text for $RequestedLocale resolves to $($table.Tag)."
                $announcedTable = $table.Tag
            }
        }
        $opened = (Test-AuditSectionShown $shown $page) -and $localeMatches -and
            (Test-SettingsScreen @($allText) $(if ($table) { $table.Rows } else { $null })) -and $allText.Contains($shown.title)
        $results.Add([pscustomobject]@{ Page = $(if ($page) { $page } else { 'Home' }); Name = $name; Shots = $shots
            Opened = $opened; Locale = $shown.locale; TextLocale = $(if ($table) { $table.Tag } else { $null })
            ExpandedSections = $shown.expandedSections; SectionRows = $shown.sectionRows; TargetExpanded = $shown.targetExpanded })
        Write-Step ("{0} {1}: {2} screen(s){3}" -f $Pass, $(if ($page) { $page } else { 'Home' }), $shots, $(if ($opened) { '' } else { ', NOT OPENED' }))
    }
    Close-Settings
    return $results
}

# --- Checks before anything changes ---------------------------------------------------------

Assert-AuditSteps
Assert-AuditLease
foreach ($locale in $Locales) {
    if (-not $locale -or $locale.Contains(',') -or -not (Test-AuditLocaleList $locale)) { throw "Invalid app locale: $locale" }
}
if ($WriteBaseline -and ($SkipWalk -or $SkipPages -or $KeepSettings)) { throw 'Baseline promotion requires the full walk, all pages and verified restoration.' }
if (((Invoke-Adb get-state) -join '') -ne 'device') { throw "$Serial is not ready." }
$deviceIdentity = if ($Serial.StartsWith('emulator-', [StringComparison]::Ordinal)) {
    @((Invoke-Adb emu avd name) | Where-Object { $_ -and $_ -cne 'OK' }) -join ''
} else { ((Invoke-Adb shell getprop ro.product.model) -join '').Trim() }
if ($deviceIdentity -cne $ExpectedDevice) { throw "Device identity mismatch on $Serial. No device changes were made." }
$identityVerified = $true
if (@(Get-AndroidPackagePaths -Adb $adb -Serial $Serial -PackageName $Package).Count -eq 0) { throw "$Package is not installed on $Serial." }
Invoke-Adb shell input keyevent KEYCODE_WAKEUP | Out-Null
$keyguard = (Invoke-Adb shell dumpsys window) -join "`n"
if ($keyguard -match 'mDreamingLockscreen=true|isKeyguardShowing=true|mShowingLockscreen=true') {
    throw "$Serial is locked. Unlock it and run this again."
}
$size = (Invoke-Adb shell wm size) -join ' '
if ($size -notmatch '(\d+)x(\d+)\s*$') { throw "Could not read the screen size: $size" }
$width = [int]$Matches[1]; $height = [int]$Matches[2]
if ($MinimumTapY -ge $height) { throw 'MinimumTapY must be below the bottom of the screen.' }
$swipeX = [int]($width / 2); $swipeFrom = [int]($height * 0.75); $swipeTo = [int]($height * 0.3)
# A reel waiting for Tap to play has Facebook's play button at the middle of the screen, which takes
# a hold there as a press of its own: the reel stays paused and Hold a reel for 2x never hears it.
# 3/8 of the way down is still the reel and clear of that button.
$holdY = [int]($height * 0.375)
$sdk = [int](((Invoke-Adb shell getprop ro.build.version.sdk) -join '').Trim())
if ($Locales.Count -gt 0 -and $sdk -lt 33) { throw 'App-locale passes require Android 13 or newer.' }
$stateBefore = [ordered]@{ schema = 1; serial = $Serial; device = $deviceIdentity; package = $Package
    night = Get-AuditNightMode; locales = $(if ($sdk -ge 33) { Get-AuditLocales } else { $null }) }
$stateBefore = Resolve-AuditStateBefore $stateBefore
$statePath = Join-Path $OutDir 'state-before.json'
[IO.File]::WriteAllText($statePath, ($stateBefore | ConvertTo-Json))

if ($Apk) {
    Write-Step "installing $(Split-Path -Leaf $Apk)"
    $install = (Invoke-Adb install -r -d -g $Apk) -join ' '
    if ($install -notmatch 'Success') { throw "adb install failed: $install" }
}
$packageInfo = @(Invoke-Adb shell dumpsys package $Package)
$version = ($packageInfo | Where-Object { $_ -match '^\s*versionName=' } | Select-Object -First 1) -replace '^\s*versionName=', ''
$installedAbi = (($packageInfo | Where-Object { $_ -match '^\s*primaryCpuAbi=' } | Select-Object -First 1) -replace '^\s*primaryCpuAbi=', '').Trim()
if ($installedAbi -notin @('arm64-v8a', 'armeabi-v7a')) { throw 'The installed package ABI is unavailable or unsupported.' }
$basePath = @((Get-AndroidPackagePaths -Adb $adb -Serial $Serial -PackageName $Package) | Where-Object { $_.EndsWith('/base.apk') })
if ($basePath.Count -ne 1) { throw 'Could not identify one installed base APK.' }
$apkHash = ((Invoke-Adb shell "sha256sum $(Format-Shell $basePath[0].Substring(8))") -join '').Trim()
if ($apkHash -cnotmatch '\A([a-f0-9]{64})\s') { throw 'Could not hash the installed APK.' }
$installedHash = $Matches[1]
$Entry = "$Package/$(Get-AuditLauncher)"
Write-Step "Facebook $version on $Serial, launching $Entry, results in $OutDir"

$problems = New-Object System.Collections.Generic.List[string]
$summary = New-Object System.Collections.Generic.List[string]
$summary.Add("Facebook $version on $Serial, $(Get-Date -Format 'yyyy-MM-dd HH:mm')")
# What the walk receipt reports, filled in as the walk gets that far.
$auditStarted = [DateTimeOffset]::UtcNow
$walkBuild = ''
$walkRan = 0
$walkFamilies = 0
$walkCrashes = 0
$startedEpoch = 0
$Off = @(Get-AuditOff -OnKeys $On -OffKeys $Off)

try {
    # --- Every switch on ----------------------------------------------------------------------
    Invoke-Adb shell mkdir -p $AuditDir | Out-Null
    Invoke-Adb push $statePath "$AuditDir/state-before.json" | Out-Null
    # A before.json already there is from a run that never put things back, and still holds the
    # switches from before it, so it stays.
    Invoke-Adb shell rm -f "$AuditDir/report.txt" | Out-Null
    $allOn = [ordered]@{ format = 'hushfacebook-audit'; profile = 'all-on'; on = @($On); off = @($Off) }
    $import = Send-Profile ($allOn | ConvertTo-Json -Compress)
    Write-Step "profile: $import"
    $summary.Add("Profile all-on: $import")
    Invoke-Adb pull "$AuditDir/before.json" (Join-Path $OutDir 'before.json') | Out-Null
    if ($import -cnotmatch '\Aapplied [0-9]+\z') { throw "The profile was not applied: $import" }
    Invoke-Adb pull "$AuditDir/applied.json" (Join-Path $OutDir 'applied.json') | Out-Null
    $startedEpoch = [long]((Invoke-Adb shell date +%s) -join '').Trim()
    Start-Facebook

    # --- The walk -----------------------------------------------------------------------------
    if (-not $SkipWalk) {
        $stepNumber = 0
        $routeVerified = $false
        $routeReel = $false
        $routeStep = $null
        $skipBack = $false
        foreach ($step in $Visit) {
            $stepNumber++
            Write-Step $step
            if (-not $step.StartsWith('fb://', [StringComparison]::Ordinal) -and -not $routeVerified) {
                $summary.Add("Walk: no verified destination for step $stepNumber ($step). No input sent.")
                if ($OptionalVisit -cnotcontains $step) { $problems.Add("Required step $stepNumber has no verified destination: $step") }
                continue
            }
            if ($step -like 'tap:*') {
                if ($routeReel) {
                    $skipBack = $true
                    $summary.Add("Walk: step $stepNumber ($step) would tap a reel. No tap sent.")
                    if ($OptionalVisit -cnotcontains $step) { $problems.Add("Required step $stepNumber would tap a reel: $step") }
                    continue
                }
                $skipBack = $false
                $label = $step.Substring(4)
                $node = Find-AuditTapNode $label
                if ($node -and $node.bounds -match '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') {
                    if ([int]$Matches[1] -lt 0 -or [int]$Matches[2] -lt 0 -or
                            [int]$Matches[3] -gt $width -or [int]$Matches[4] -gt $height -or
                            [int]$Matches[3] -le [int]$Matches[1] -or [int]$Matches[4] -le [int]$Matches[2]) {
                        throw "The $label node has invalid screen bounds. No tap was sent."
                    }
                    $inputNodeLabel = $label
                    try {
                        Invoke-Adb shell input tap ([int](([int]$Matches[1] + [int]$Matches[3]) / 2)) ([int](([int]$Matches[2] + [int]$Matches[4]) / 2)) | Out-Null
                    } finally { $inputNodeLabel = $null }
                    $routeStep = $null
                    Start-Sleep -Seconds $SettleSeconds
                } else {
                    $skipBack = $true
                    $summary.Add("Walk: no '$label' to tap at step $stepNumber.")
                    if ($OptionalVisit -cnotcontains $step) { $problems.Add("Required step $stepNumber was not reached: $step") }
                }
            } elseif ($step -eq 'longpress') {
                # One dump only: a playing reel can't be read, and ten tries there cost about two minutes.
                $holdBefore = @(Get-ScreenText -Tries 1)
                $inputReel = $routeReel
                $inputWindow = if ($routeReel) { $routeWindow }
                try { Invoke-Adb shell input swipe $swipeX $holdY $swipeX $holdY 2500 | Out-Null }
                finally { $inputReel = $false; $inputWindow = $null }
                Start-Sleep -Seconds 1
                $inputReel = $routeReel
                try { $holdBacks = Close-AuditHoldCover $holdBefore }
                finally { $inputReel = $false }
                if ($holdBacks) { $summary.Add("Walk: the hold at step $stepNumber opened something over the reel, closed with $holdBacks Back(s).") }
            } elseif ($step -eq 'back') {
                if ($skipBack) { $skipBack = $false; continue }
                $inputReel = $routeReel
                try { Invoke-Adb shell input keyevent KEYCODE_BACK | Out-Null }
                finally { $inputReel = $false }
                $routeReel = $false
                $routeStep = $null
                Start-Sleep -Seconds 1
            } elseif ($step -eq 'query') {
                if ($routeStep -cne 'fb://search' -or -not (Test-AuditKeyboard)) {
                    $summary.Add("Walk: no open search field to type in at step $stepNumber. Nothing typed.")
                    if ($OptionalVisit -cnotcontains $step) { $problems.Add("Required step $stepNumber had no open search field: $step") }
                    continue
                }
                $inputWindow = $routeWindow
                $inputQuery = $true
                $script:auditQueryTaps = 0
                try {
                    Invoke-Adb shell input text $AuditQuery | Out-Null
                    Start-Sleep -Seconds 2
                    # Enter straight after typing went to the first typeahead row about 2 times in 10
                    # (emulator-5590, 0.21.0) and searched nothing. A tap in the field first, then
                    # Enter, submitted every time.
                    $field = Select-AuditQueryField @(Get-UiNodes)
                    if ($field -and $field.bounds -match '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$' -and
                            [int]$Matches[1] -ge 0 -and [int]$Matches[2] -ge 0 -and [int]$Matches[3] -le $width -and [int]$Matches[4] -le $height) {
                        $fieldX = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
                        $fieldY = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
                        # The field can change before Invoke-Adb's own dump. A refused tap leaves Enter,
                        # which checks the screen again before it goes.
                        try {
                            Invoke-Adb shell input tap $fieldX $fieldY | Out-Null
                            Start-Sleep -Milliseconds 800
                        } catch {
                            $summary.Add("Walk: the search field tap at step $stepNumber was refused ($($_.Exception.Message)). Enter sent without it.")
                        }
                    } else {
                        $summary.Add("Walk: no single search field held the query at step $stepNumber. Enter sent without the tap.")
                    }
                    Invoke-Adb shell input keyevent KEYCODE_ENTER | Out-Null
                } finally { $inputWindow = $null; $inputQuery = $false }
                $routeStep = $null
                Start-Sleep -Seconds $SettleSeconds
            } else {
                $routeVerified = $false
                $routeReel = $false
                $routeStep = $null
                $reel = $ReelRoutes -ccontains $step
                $routeVerified = Open-AuditRoute $step $reel
                if ($script:auditRouteBacks) {
                    $summary.Add("Walk: step $stepNumber ($step) $(if ($routeVerified) { 'was proved' } else { 'was still not proved' }) after $($script:auditRouteBacks) Back(s), each followed by the link again.")
                }
                if (-not $routeVerified) {
                    $summary.Add("Walk: destination not proved at step $stepNumber ($step). No input sent.")
                    if ($OptionalVisit -cnotcontains $step) { $problems.Add("Required destination not reached at step ${stepNumber}: $step") }
                    continue
                }
                $routeWindow = $script:auditWindowIdentity
                $routeReel = $reel
                $routeStep = $step
                # Before the swipes too, while a tab bar that slides away is still on screen.
                Save-Screenshot (Join-Path $OutDir ('walk-{0:00}-{1}-top.png' -f $stepNumber, ($step -replace '[^A-Za-z0-9]+', '-').Trim('-')))
                for ($i = 0; $i -lt $Swipes; $i++) {
                    # Search opens with its field focused and the keyboard up.
                    if (Test-AuditKeyboard) {
                        $summary.Add("Walk: the keyboard is open at step $stepNumber ($step). No swipe sent.")
                        break
                    }
                    $inputWindow = $routeWindow
                    $inputReel = $routeReel
                    try { Invoke-Adb shell input swipe $swipeX $swipeFrom $swipeX $swipeTo 300 | Out-Null }
                    finally { $inputWindow = $null; $inputReel = $false }
                    Start-Sleep -Milliseconds 1500
                }
            }
            $shot = ($step -replace '[^A-Za-z0-9]+', '-').Trim('-')
            Save-Screenshot (Join-Path $OutDir ('walk-{0:00}-{1}.png' -f $stepNumber, $shot))
        }

        $reportPath = Join-Path $OutDir 'report.txt'
        $text = Get-Report $reportPath
        $walkStarted = [DateTime]::SpecifyKind([DateTime]'1970-01-01', 'Utc').AddSeconds($startedEpoch)
        $result = Read-SmokeReport -Text $text -Since $walkStarted
        foreach ($problem in $result.Problems) { $problems.Add($problem) }
        $ran = @($result.Families | Where-Object { $_.Invoked -gt 0 } | ForEach-Object { $_.Name })
        # The report lists only families that recorded something, so the rest come from FamilyNames.
        $namesSource = Join-Path $PSScriptRoot '..\extensions\facebook\src\main\java\app\morphe\extension\facebook\settings\FamilyNames.java'
        $allFamilies = @([regex]::Matches([IO.File]::ReadAllText($namesSource), 'public static final String \w+ = "([^"]+)"') |
            ForEach-Object { $_.Groups[1].Value })
        $idle = @($allFamilies + @($result.Families | ForEach-Object { $_.Name }) | Sort-Object -Unique |
            Where-Object { $ran -notcontains $_ })
        $summary.Add("$($allFamilies.Count) hook families, $($ran.Count) ran on the walk")
        $walkRan = $ran.Count
        $walkFamilies = $allFamilies.Count
        $baseline = New-AuditBaseline -Report $text -Ran $ran -Idle $idle
        $walkBuild = $baseline.patchBuild
        [IO.File]::WriteAllText((Join-Path $OutDir 'baseline-candidate.json'), ($baseline | ConvertTo-Json -Depth 6))

        if (Test-Path -LiteralPath $Expectations) {
            $expected = Get-Content -Raw -LiteralPath $Expectations | ConvertFrom-Json
            if (-not $WriteBaseline -and -not (Test-AuditBaseline $expected $baseline)) {
                $problems.Add('The baseline identifies a different build, device or audit profile. A reviewed full baseline run is required.')
            }
            $walk = Compare-AuditWalk $expected $ran
            foreach ($failure in $walk.Failures) { $problems.Add($failure) }
            foreach ($note in $walk.Notes) { $summary.Add($note) }
        } elseif (-not $WriteBaseline) {
            $problems.Add("No baseline at $Expectations. Run once with -WriteBaseline on a build you trust.")
        }
        $summary.Add('Never ran on the walk: ' + $(if ($idle) { $idle -join ', ' } else { 'none' }))

    }

    # --- Every settings page ------------------------------------------------------------------
    if (-not $SkipPages) {
        $passes = New-Object System.Collections.Generic.List[object]
        foreach ($mode in @('yes', 'no')) {
            Invoke-Adb shell cmd uimode night $mode | Out-Null
            Start-Facebook
            $pass = if ($mode -eq 'yes') { 'dark' } else { 'light' }
            $passes.Add([pscustomobject]@{ Name = $pass; Pages = Save-SettingsPages $pass })
        }
        Invoke-Adb shell cmd uimode night yes | Out-Null
        foreach ($locale in $Locales) {
            Invoke-Adb shell cmd locale set-app-locales $Package --locales $locale | Out-Null
            if ((Get-AuditLocales) -ine $locale) { throw "Android did not apply the requested app locale: $locale" }
            Start-Facebook
            $passes.Add([pscustomobject]@{ Name = "dark-$locale"; Pages = Save-SettingsPages "dark-$locale" $locale })
        }

        foreach ($pass in $passes) {
            foreach ($page in $pass.Pages) {
                if (-not $page.Opened) { $problems.Add("$($pass.Name): the $($page.Page) page did not open.") }
            }
        }
        $html = New-Object System.Text.StringBuilder
        [void]$html.Append('<!doctype html><meta charset="utf-8"><title>Hushfacebook audit</title>')
        [void]$html.Append('<style>body{background:#111;color:#ddd;font:14px sans-serif}img{width:240px;margin:2px;border:1px solid #333}h2{margin-top:24px}</style>')
        $walkShots = @(Get-ChildItem -LiteralPath $OutDir -Filter 'walk-*.png' | Sort-Object Name)
        if ($walkShots) {
            [void]$html.Append('<h2>Walk</h2>')
            foreach ($shot in $walkShots) { [void]$html.Append("<img src=`"$($shot.Name)`" title=`"$($shot.Name)`">") }
        }
        foreach ($pass in $passes) {
            foreach ($page in $pass.Pages) {
                [void]$html.Append("<h2>$($pass.Name): $([Net.WebUtility]::HtmlEncode($page.Page))</h2>")
                for ($i = 0; $i -lt $page.Shots; $i++) {
                    [void]$html.Append(('<img src="pages-{0}/{1}-{2:00}.png">' -f $pass.Name, $page.Name, $i))
                }
            }
        }
        [IO.File]::WriteAllText((Join-Path $OutDir 'index.html'), $html.ToString())
        $summary.Add("Settings pages: $(($passes | ForEach-Object { "$($_.Name) $($_.Pages.Count)" }) -join ', '), see index.html")
    }
} catch {
    $problems.Add("Audit failed: $($_.Exception.Message)")
} finally {
    # --- Put things back ----------------------------------------------------------------------
    $recovered = Restore-AuditState
}

if ($startedEpoch -gt 0) {
    try {
        # Settings, locale changes and restoration run after the feed walk. Their crashes count too.
        $finalCrashes = Get-AuditCrashes -SinceEpoch $startedEpoch
        if ($finalCrashes.Lines.Count -gt 0) {
            [IO.File]::WriteAllLines((Join-Path $OutDir 'crash-buffer.txt'), [string[]]$finalCrashes.Lines)
        }
        $walkCrashes = $finalCrashes.Fatal.Count
        if ($walkCrashes -gt 0) { $problems.Add("Facebook crashed during the audit: $($finalCrashes.Fatal[0].Trim())") }
        foreach ($note in $finalCrashes.EmulatorNotes) { $summary.Add("Emulator note, not Facebook's crash: $note") }
    } catch { $problems.Add("Could not read the final crash buffer: $($_.Exception.Message)") }
}

if ($problems.Count -eq 0 -and $WriteBaseline) {
    try {
        if (-not (Publish-AuditBaseline $baseline)) { throw 'The baseline candidate did not meet promotion requirements.' }
        Write-Step "baseline written to $Expectations"
    } catch { $problems.Add("Could not promote the baseline: $($_.Exception.Message)") }
}
if ($problems.Count -eq 0) {
    $summary.Add($(if ($SkipWalk -or $SkipPages -or $KeepSettings) { 'PARTIAL: requested scope passed; full audit not established' } else { 'PASS' }))
}
else {
    $summary.Add("FAIL ($($problems.Count))")
    foreach ($problem in $problems) { $summary.Add("  $problem") }
}
[IO.File]::WriteAllLines((Join-Path $OutDir 'summary.txt'), [string[]]$summary)
$fullAudit = -not ($SkipWalk -or $SkipPages -or $KeepSettings)
$walkReceipt = New-WalkReceipt -Result $(if ($problems.Count -gt 0) { 'FAIL' } elseif ($fullAudit) { 'PASS' } else { 'PARTIAL' }) `
    -Full $fullAudit -Serial $Serial -Device $deviceIdentity -Facebook $version -PatchBuild $walkBuild `
    -InstalledApkSha256 $installedHash -Started $auditStarted -Finished ([DateTimeOffset]::UtcNow) `
    -FamiliesRan $walkRan -Families $walkFamilies -Crashes $walkCrashes -Problems ([string[]]$problems)
[IO.File]::WriteAllText((Join-Path $OutDir 'walk-receipt.json'), ($walkReceipt | ConvertTo-Json -Depth 4) + "`n")
$summary | ForEach-Object { Write-Step $_ }
Write-Step "walk receipt: $(Join-Path $OutDir 'walk-receipt.json')"
if ($problems.Count -gt 0) { exit 1 }
