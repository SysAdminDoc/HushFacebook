<# Tests audit control flow without connecting to a device. #>
$ErrorActionPreference = 'Stop'
$testScriptRoot = $PSScriptRoot
$auditPath = Join-Path $PSScriptRoot 'device-audit.ps1'
$parseErrors = $null
$auditAst = [System.Management.Automation.Language.Parser]::ParseFile($auditPath, [ref]$null, [ref]$parseErrors)
if ($parseErrors.Count) { throw ($parseErrors -join "`n") }
$testFunctions = @('Assert-AuditLease', 'Assert-AuditSteps', 'Assert-AuditForeground', 'Invoke-Adb',
    'Get-AuditLocales', 'Get-AuditNightMode', 'Restore-AuditState', 'Format-Shell',
    'New-AuditBaseline', 'Get-AuditOff', 'Compare-AuditWalk', 'Test-AuditBaseline', 'Publish-AuditBaseline', 'Save-SettingsPages', 'Get-SettingsPages', 'Test-AuditSectionShown', 'Test-SettingsScreen', 'Get-AuditLocaleTable', 'Write-Step', 'Test-AuditDestination', 'Test-AuditNodeHolds', 'Test-AuditTapCovered', 'Select-AuditTapNode', 'Select-AuditQueryField', 'Find-AuditTapNode', 'Get-UiNodes', 'Send-Profile', 'Test-AuditLocaleList', 'Get-AuditLauncher', 'Get-Report',
    'Test-AuditReelViewer', 'Get-AuditReelProof', 'Close-AuditHoldCover', 'Open-AuditRoute', 'Test-AuditKeyboard', 'Test-AuditSettingsRestored', 'ConvertFrom-AuditJsonTable', 'Resolve-AuditStateBefore', 'Get-AuditCrashes')
foreach ($functionName in $testFunctions) {
    $definition = $auditAst.Find({ param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $functionName
    }, $true)
    if (-not $definition) { throw "Missing audit function $functionName" }
    . ([scriptblock]::Create($definition.Extent.Text.Replace('$PSScriptRoot', '$testScriptRoot')))
}
$commonAst = [System.Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'common.ps1'), [ref]$null, [ref]$null)
. ([scriptblock]::Create($commonAst.Find({ param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Use-Utf8ConsoleOutput' }, $true).Extent.Text))
# Get-AuditCrashes reads the buffer through phone-smoke's Read-CrashBuffer, loaded the way device-audit loads it.
$smokeAst = [System.Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'phone-smoke.ps1'), [ref]$null, [ref]$null)
. ([scriptblock]::Create($smokeAst.Find({ param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Read-CrashBuffer' }, $true).Extent.Text))

. (Join-Path $PSScriptRoot 'device-install.ps1')
$failures = [Collections.Generic.List[string]]::new()
$checks = 0
function Assert-That([bool]$Condition, [string]$Message) {
    $script:checks++
    if (-not $Condition) { $script:failures.Add($Message) }
}
function Assert-Throws([scriptblock]$Action, [string]$Message) {
    $threw = $false
    try { & $Action } catch { $threw = $true }
    Assert-That $threw $Message
}

$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('hushfacebook-audit-test-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
$Serial = 'emulator-5554'
$Package = 'com.facebook.katana'
$LeaseDirectory = $testRoot
$LeaseTokenFile = Join-Path $testRoot 'owner.txt'
$leasePath = Join-Path $testRoot "$Serial.json"
$testToken = [Guid]::NewGuid().ToString()
$identityVerified = $true
$adb = 'Invoke-FakeAdb'
$script:adbCalls = [Collections.Generic.List[string]]::new()
$script:foreground = "topResumedActivity=ActivityRecord{1 u0 $Package/.Main t1}"
$script:focusedWindow = "mCurrentFocus=Window{1 u0 $Package/$Package.Main}"
$script:uiDump = "<?xml version=`"1.0`"?><hierarchy><node package=`"$Package`" text=`"Comment`" content-desc=`"Comment`" bounds=`"[0,0][20,20]`" /></hierarchy>"
$script:imeState = '  mInputShown=false'
$AuditQuery = 'news'
$inputNodeLabel = 'Comment'
function Invoke-FakeAdb {
    $command = $args -join ' '
    $script:adbCalls.Add($command)
    $global:LASTEXITCODE = 0
    if ($script:disconnected) { throw 'Mock disconnect' }
    if ($command -like '*logcat -b crash -d -v epoch') {
        if ($script:offlineCrashRead) {
            # Host logcat waits for a missing transport. Model its wait response without hanging the suite.
            if ($args[2] -ceq 'logcat') { return '- waiting for device -' }
            $global:LASTEXITCODE = 1
            return 'error: device offline'
        }
        if ($script:failCrashRead) { throw 'Mock crash-buffer failure' }
        return $script:crashBuffer
    }
    if ($command -like '* pull */applied.json *') {
        $snapshot = if ($script:wrongSnapshot) { '{"wrong":true}' }
            elseif ($null -ne $script:deviceSwitches) { New-TestBackup $script:deviceSwitches }
            else { '{}' }
        [IO.File]::WriteAllText([string]$args[-1], $snapshot)
    }
    if ($command -like '*cat */page.json*') { return $script:pageAck }
    if ($command -like '*cat */state-before.json*') { return $script:deviceState }
    if ($command -like '*ls */before.json*') { return $script:deviceBackupListing }
    # The audit folder pulled into an existing folder lands under its own name, as adb does it.
    if ($command -like '* pull */hushfacebook-audit *' -and $script:orphanBackup -and -not $script:failFolderPull) {
        $pulled = Join-Path ([string]$args[-1]) 'hushfacebook-audit'
        New-Item -ItemType Directory -Path $pulled -Force | Out-Null
        [IO.File]::WriteAllText((Join-Path $pulled 'before.json'), $script:orphanBackup)
    }
    if ($command -like '* pull */before.json *' -and $script:orphanBackup) { [IO.File]::WriteAllText([string]$args[-1], $script:orphanBackup) }
    if ($command -like '*cat */import.txt*') { return 'applied 4' }
    if ($command -like '*dumpsys activity activities') { return $script:foreground }
    if ($command -like '*dumpsys activity top') {
        if (-not $script:simulateGlobalTimeout) { return $script:topDump }
        return @('  ACTIVITY com.other.gallery/.ImageQuickView 30d65d pid=2364 userId=0',
            '  SERVICE activity DUMP TIMEOUT 10000ms')
    }
    if ($command -ceq "-s $Serial shell dumpsys activity '$script:topComponent'") {
        if ($script:changeWindowDuringTop) { $script:focusedWindow = "mCurrentFocus=Window{changed u0 $Package/$Package.Main}" }
        return $script:topDump
    }
    if ($command -like '*dumpsys input_method') { return $script:imeState }
    if ($command -like '*dumpsys window') {
        if ($script:changeOwnWindowAfterDump -and $null -ne $script:postDumpFocusReads) {
            $script:postDumpFocusReads++
            if ($script:postDumpFocusReads -ge 2) { $script:focusedWindow = "mCurrentFocus=Window{changed u0 $Package/$Package.Main}" }
        }
        return $script:focusedWindow
    }
    if ($command -like '*uiautomator dump*') {
        if ($script:nullRootDumps -gt 0) {
            $script:nullRootDumps--
            return 'ERROR: null root node returned by UiTestAutomationBridge.'
        }
        if ($script:takeFocusOnDump) { $script:focusedWindow = 'mCurrentFocus=Window{2 u0 NotificationShade}' }
        if ($script:changeOwnWindowAfterDump) { $script:postDumpFocusReads = 0 }
        return $script:uiDump
    }
    if ($script:uiDumpAfterDownSwipe -and $command -ceq "-s $Serial shell input swipe $swipeX $swipeTo $swipeX $swipeFrom 300") {
        $script:uiDump = $script:uiDumpAfterDownSwipe
    }
    if ($command -like '*query-activities*') { return $script:launcherQuery }
    if ($command -match "cmd locale set-app-locales .* --locales '([^']*)'$") { $script:actualLocale = $Matches[1] }
    if ($command -like '*cmd locale get-app-locales*') { return "Locales for $Package for user 0 are [$script:actualLocale]" }
    if ($script:refuseLocale -and $command -like '*cmd locale set-app-locales*') { throw 'Mock locale refusal' }
    if ($command -match 'cmd uimode night (yes|no|auto|custom)$') {
        if ($script:refuseNight) { throw 'Mock night-mode refusal' }
        $script:actualNight = $Matches[1]
    } elseif ($command -like '*cmd uimode night') { return "Night mode: $script:actualNight" }
    return 'OK'
}
function New-TestBackup([Collections.IDictionary]$Switches) {
    return ([ordered]@{ format = 'hushfacebook-settings'; schema = 1; settings = $Switches } | ConvertTo-Json -Compress)
}
function New-TestSectionAck([string]$Metadata) {
    if (-not $Metadata) { return '' }
    $record = $Metadata | ConvertFrom-Json
    $record | Add-Member -NotePropertyMembers @{ schema = 3; expandedSections = @('News feed'); sectionRows = 4; targetExpanded = $true }
    return ConvertTo-Json -InputObject $record -Compress -Depth 5
}
function Write-TestLease([string]$Owner = $testToken, [double]$Minutes = 25) {
    $body = [ordered]@{ schemaVersion = 1; serial = $Serial; project = 'Hushfacebook'; chatIdentity = 'audit-tests'
        ownershipToken = $Owner; acquiredUtc = [DateTime]::UtcNow.ToString('o')
        expiresUtc = [DateTime]::UtcNow.AddMinutes($Minutes).ToString('o') }
    [IO.File]::WriteAllText($leasePath, ($body | ConvertTo-Json))
}

try {
    # A stock install and its clone can run on one device. A crash in the clone is not a crash
    # in the package being audited, but that package's colon-named processes still belong to it.
    # Both walks pick them through phone-smoke's Read-CrashBuffer.
    $crashes = @(
        '1730000000.100 1000 1000 E AndroidRuntime: Process: com.facebook.katana, PID: 1000',
        '1730000000.200 1001 1001 E AndroidRuntime: Process: com.facebook.katana:push, PID: 1001',
        '1730000000.300 1002 1002 E AndroidRuntime: Process: com.facebook.katana.morphe, PID: 1002',
        '1730000000.400 1003 1003 E AndroidRuntime: Process: com.facebook.katanax, PID: 1003')
    $fatal = (Read-CrashBuffer -Lines $crashes -Package $Package).Fatal
    Assert-That ($fatal.Count -eq 2 -and $fatal[0] -ceq $crashes[0] -and $fatal[1] -ceq $crashes[1]) `
        "Read-CrashBuffer attributed another package's crash to Facebook."

    Assert-Throws { Invoke-Adb shell input keyevent KEYCODE_WAKEUP } 'Missing token permitted a wake.'
    [IO.File]::WriteAllText($LeaseTokenFile, $testToken)
    Assert-Throws { & $auditPath -Serial $Serial -ExpectedDevice 'Audit_AVD' -OutDir $testRoot -LeaseDirectory $LeaseDirectory -LeaseTokenFile $LeaseTokenFile } 'An occupied output folder was accepted.'
    Assert-Throws { Invoke-Adb shell input keyevent KEYCODE_WAKEUP } 'Missing lease permitted a wake.'
    Write-TestLease -Owner 'someone-else'
    Assert-Throws { Invoke-Adb install example.apk } 'Wrong owner permitted an install.'
    Write-TestLease -Minutes -1
    Assert-Throws { Invoke-Adb shell cmd uimode night yes } 'Expired lease permitted a settings change.'
    Write-TestLease
    $badLease = Get-Content -LiteralPath $leasePath -Raw | ConvertFrom-Json
    $badLease.chatIdentity = @('audit-tests')
    [IO.File]::WriteAllText($leasePath, ($badLease | ConvertTo-Json))
    Assert-Throws { Invoke-Adb shell input keyevent KEYCODE_WAKEUP } 'A nonscalar lease identity permitted input.'
    Write-TestLease
    $badLease = Get-Content -LiteralPath $leasePath -Raw | ConvertFrom-Json
    $badLease.expiresUtc = @('2099-01-01T00:00:00Z')
    [IO.File]::WriteAllText($leasePath, ($badLease | ConvertTo-Json))
    Assert-Throws { Invoke-Adb shell input keyevent KEYCODE_WAKEUP } 'A nonscalar lease expiry permitted input.'
    Assert-That ($script:adbCalls.Count -eq 0) 'An invalid lease reached ADB.'

    Write-TestLease -Minutes 1
    Assert-AuditLease
    $renewed = Get-Content -Raw $leasePath | ConvertFrom-Json
    Assert-That ([DateTimeOffset]$renewed.expiresUtc -gt [DateTimeOffset]::UtcNow.AddMinutes(20)) 'Lease was not renewed.'
    Assert-That ($renewed.ownershipToken -ceq $testToken) 'Renewal changed the owner token.'
    $held = [IO.File]::Open($leasePath, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    try { Assert-Throws { Invoke-Adb shell input tap 10 10 } 'A locked lease permitted input.' }
    finally { $held.Dispose() }
    # Another session waiting for the phone can be reading the lease at the moment a call checks it.
    $script:briefReader = [IO.File]::Open($leasePath, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    $script:adbCalls.Clear()
    $briefReadStopped = $false
    try {
        & {
            function Start-Sleep { if ($script:briefReader) { $script:briefReader.Dispose(); $script:briefReader = $null } }
            Invoke-Adb shell cmd uimode night | Out-Null
        }
    } catch { $briefReadStopped = $true }
    finally { if ($script:briefReader) { $script:briefReader.Dispose() } }
    Assert-That (-not $briefReadStopped -and $script:adbCalls.Count -eq 1) 'A lease another process read for a moment stopped the audit.'

    # adb writes UTF-8 whatever the console's code page. Under 437 (pwsh started from Git Bash) the
    # notification row's curly apostrophe came back as three other characters, and the S22 walks
    # failed the notifications step on a page that was there (2026-10-27). A real native command here.
    $rowLabel = "Manage the notification$([char]0x2019)s settings"
    $utf8Dump = Join-Path $testRoot 'utf8-dump.txt'
    [IO.File]::WriteAllText($utf8Dump, "<?xml version=`"1.0`"?><hierarchy><node package=`"$Package`" text=`"`" content-desc=`"$rowLabel`" /></hierarchy>", [Text.UTF8Encoding]::new($false))
    $nativeAdb = Join-Path $testRoot 'utf8-adb.cmd'
    [IO.File]::WriteAllText($nativeAdb, "@type `"$utf8Dump`"`r`n")
    $fakeAdb = $adb
    $consoleBefore = [Console]::OutputEncoding
    try {
        [Console]::OutputEncoding = [Text.Encoding]::GetEncoding(437)
        $adb = $nativeAdb
        $read = (Invoke-Adb shell cat /sdcard/hushfacebook-audit.xml) -join "`n"
        $nodes = @(([xml]$read.Substring($read.IndexOf('<?xml'))).SelectNodes('//node'))
        Assert-That (Test-AuditDestination 'fb://notifications' $nodes) "adb's UTF-8 was misread under code page 437: $read"
        Assert-That ([Console]::OutputEncoding.CodePage -eq 437) 'Reading adb left the console on another code page.'
    } finally {
        $adb = $fakeAdb
        [Console]::OutputEncoding = $consoleBefore
    }

    foreach ($unsafe in @('tap:Like', 'tap:Follow', 'tap:Add friend', 'tap:Send', 'tap:Alice', 'fb://friends', 'fb://messaging_thread', 'fb://feed; input tap 1 1')) {
        $Visit = @($unsafe)
        Assert-Throws { Assert-AuditSteps } "Unsafe step accepted: $unsafe"
    }
    $Visit = @('fb://feed', 'tap:Comment', 'back', 'tap:Feeds', 'tap:Settings and privacy', 'longpress', 'fb://search', 'query')
    Assert-AuditSteps
    $Visit = @('fb://search', 'query:hello')
    Assert-Throws { Assert-AuditSteps } 'A query step with its own text was accepted.'

    $identityVerified = $false
    Assert-Throws { Invoke-Adb shell input keyevent KEYCODE_WAKEUP } 'Unverified identity permitted input.'
    $identityVerified = $true
    $script:foreground = 'topResumedActivity=ActivityRecord{1 u0 com.android.settings/.Settings t1}'
    $script:adbCalls.Clear()
    Assert-Throws { Invoke-Adb shell input tap 10 10 } 'Other foreground package permitted a tap.'
    Assert-That (-not @($script:adbCalls | Where-Object { $_ -like '* input *' }).Count) 'A tap escaped the foreground guard.'
    $script:foreground = "topResumedActivity=ActivityRecord{1 u0 $Package/.Main t1}`nmResumedActivity: ActivityRecord{1 u0 $Package/.Main t1}"
    Invoke-Adb shell input tap 10 10 | Out-Null
    Assert-That (@($script:adbCalls | Where-Object { $_ -like '* input tap 10 10' }).Count -eq 1) 'Valid foreground input did not run once.'
    $script:foreground += "`nmResumedActivity: ActivityRecord{2 u0 com.android.settings/.Settings t2}"
    Assert-Throws { Invoke-Adb shell input swipe 10 20 10 5 } 'Multiple foreground packages permitted input.'
    $script:foreground = "topResumedActivity=ActivityRecord{1 u0 $Package/.Main t1}"
    foreach ($focus in @('mCurrentFocus=Window{2 u0 NotificationShade}',
            'mCurrentFocus=Window{2 u0 com.google.android.permissioncontroller/.PermissionActivity}', 'mCurrentFocus=null')) {
        $script:focusedWindow = $focus
        $script:adbCalls.Clear()
        Assert-Throws { Invoke-Adb shell input swipe 10 20 10 5 } 'An overlay permitted input while Facebook remained resumed.'
        Assert-That (-not @($script:adbCalls | Where-Object { $_ -like '* input *' }).Count) 'Input escaped the window-focus guard.'
    }
    # A second focus line (another display) is named in the refusal beside Facebook's first one.
    $script:focusedWindow = "mCurrentFocus=Window{1 u0 $Package/$Package.Main}`n  mCurrentFocus=Window{2 u0 com.android.systemui/.Shade}"
    $focusRefusal = ''
    try { Assert-AuditForeground } catch { $focusRefusal = $_.Exception.Message }
    Assert-That ($focusRefusal -clike "*$Package/$Package.Main}; mCurrentFocus=Window{2 u0 com.android.systemui/.Shade}*") `
        "The focus refusal didn't name every focus line: $focusRefusal"
    $script:focusedWindow = ''
    $focusRefusal = ''
    try { Assert-AuditForeground } catch { $focusRefusal = $_.Exception.Message }
    Assert-That ($focusRefusal -clike '*(no mCurrentFocus line)*') "The focus refusal with no focus line said: $focusRefusal"
    $script:focusedWindow = "mCurrentFocus=Window{1 u0 $Package/$Package.Main}"
    $savedDump = $script:uiDump
    $script:uiDump = '<?xml version="1.0"?><hierarchy />'
    Assert-Throws { Invoke-Adb shell input keyevent KEYCODE_BACK } 'An empty current Facebook window permitted Back.'
    $script:uiDump = $savedDump
    Assert-AuditForeground
    $inputWindow = $script:auditWindowIdentity
    Invoke-Adb shell input swipe 10 20 10 5 | Out-Null
    $script:focusedWindow = "mCurrentFocus=Window{changed u0 $Package/$Package.Main}"
    Assert-Throws { Invoke-Adb shell input swipe 10 20 10 5 } 'A different Facebook window permitted a stale route swipe.'
    $inputWindow = $null
    $script:focusedWindow = "mCurrentFocus=Window{1 u0 $Package/$Package.Main}"
    Assert-Throws { Invoke-Adb shell input tap 30 30 } 'A moved navigation node permitted a stale-coordinate tap.'
    # The S22's menu row repeats its label on an unclickable child that also holds the tap point.
    $script:uiDump = "<?xml version=`"1.0`"?><hierarchy><node package=`"$Package`" text=`"`" content-desc=`"Settings and privacy`" clickable=`"true`" bounds=`"[0,1966][1080,2091]`"><node package=`"$Package`" text=`"`" content-desc=`"Settings and privacy`" clickable=`"false`" bounds=`"[136,2001][956,2058]`" /></node></hierarchy>"
    $inputNodeLabel = 'Settings and privacy'
    $script:adbCalls.Clear()
    Invoke-Adb shell input tap 540 2028 | Out-Null
    Assert-That (@($script:adbCalls | Where-Object { $_ -like '* input tap 540 2028' }).Count -eq 1) 'A row with its label repeated inside it refused the tap.'
    Assert-Throws { Invoke-Adb shell input tap 540 1900 } 'A tap outside the selected row was permitted.'
    $script:uiDump = $savedDump
    $inputNodeLabel = 'Comment'
    $script:takeFocusOnDump = $true
    $script:adbCalls.Clear()
    Assert-Throws { Invoke-Adb shell input tap 10 10 } 'An overlay arriving during the dump permitted input.'
    Assert-That (-not @($script:adbCalls | Where-Object { $_ -like '* input *' }).Count) 'Input escaped after focus changed during the dump.'
    $script:takeFocusOnDump = $false
    $script:focusedWindow = "mCurrentFocus=Window{1 u0 $Package/$Package.Main}"
    $script:changeOwnWindowAfterDump = $true
    $script:postDumpFocusReads = $null
    $script:adbCalls.Clear()
    Assert-Throws { Invoke-Adb shell input tap 10 10 } 'A different Facebook window arriving after node collection permitted a tap.'
    Assert-That (-not @($script:adbCalls | Where-Object { $_ -like '* input *' }).Count) 'A tap used nodes from the previous Facebook window.'
    $script:changeOwnWindowAfterDump = $false
    $script:postDumpFocusReads = $null
    $script:focusedWindow = "mCurrentFocus=Window{1 u0 $Package/$Package.Main}"
    # A playing reel can't be dumped, so its fragment tree proves it (the S22's layout, 2026-10-18).
    function New-TopDump([string]$Resumed = 'true', [string]$Reel = 'true', [string]$Parent = 'true',
            [string]$ParentName = 'VideoHomeRootFragment{5a5ee79}', [string]$ReelHeader = 'WatchFeedOrWarionFragment{90bc1ee} (cc7d9f26 id=0x7f0a06ff)') {
        return @('TASK 10433:com.facebook.katana id=7721 userId=0',
            "  ACTIVITY $Package/.Main ebc6768 pid=18012 userId=0",
            '    Local Activity a1 State:',
            "      mResumed=$Resumed mStopped=false mFinished=false",
            "      mCreated=true mResumed=$Resumed mStopped=false    Active Fragments:",
            '    VideoHomeRootFragment{5a5ee79} (15d18ac3 id=0x7f0a0ccd)',
            "        mHidden=false mDetached=false mMenuVisible=$Parent mHasMenu=false",
            "        mRetainInstance=false mUserVisibleHint=$Parent",
            '        Child FragmentManager{1 in VideoHomeRootFragment{5a5ee79}}:',
            '          Active Fragments:',
            "          $ReelHeader",
            "              mHidden=false mDetached=false mMenuVisible=$Reel mHasMenu=false",
            "              mRetainInstance=false mUserVisibleHint=$Reel",
            "              mParentFragment=$ParentName (15d18ac3 id=0x7f0a0ccd)",
            '              Child FragmentManager{2 in WatchFeedOrWarionFragment{90bc1ee}}:',
            '                mParent=WatchFeedOrWarionFragment{90bc1ee} (cc7d9f26 id=0x7f0a06ff)',
            '            #0: WatchFeedOrWarionFragment{90bc1ee} (cc7d9f26 id=0x7f0a06ff)',
            'TASK 10468:com.other.app id=7401 userId=0',
            '  ACTIVITY com.other.app/.Main 30d65d pid=2364 userId=0',
            '      mResumed=false mStopped=true mFinished=false')
    }
    Assert-That (Test-AuditReelViewer (New-TopDump) "$Package/.Main") 'A visible reel viewer was not proved.'
    Assert-That (-not (Test-AuditReelViewer (New-TopDump) "$Package/.Other")) "Another screen's reel viewer passed."
    Assert-That (-not (Test-AuditReelViewer (New-TopDump -Resumed 'false') "$Package/.Main")) 'A paused screen passed.'
    Assert-That (-not (Test-AuditReelViewer (New-TopDump -Reel 'false') "$Package/.Main")) 'A reel viewer in a hidden tab passed.'
    Assert-That (-not (Test-AuditReelViewer (New-TopDump -Parent 'false') "$Package/.Main")) 'A reel viewer under a hidden fragment passed.'
    Assert-That (-not (Test-AuditReelViewer (New-TopDump -ParentName 'GoneFragment{123abc}') "$Package/.Main")) 'A reel viewer with no parent header passed.'
    Assert-That (-not (Test-AuditReelViewer (New-TopDump -ReelHeader 'OtherFragment{77} (aa id=0x1)') "$Package/.Main")) 'A reel viewer only listed passed.'
    Assert-That (-not (Test-AuditReelViewer @((New-TopDump) + (New-TopDump)) "$Package/.Main")) 'A screen listed twice passed.'
    Assert-That (Test-AuditReelViewer @((New-TopDump -Resumed 'false') + (New-TopDump)) "$Package/.Main") 'A paused instance hid the uniquely resumed reel of the same component.'
    Assert-That (-not (Test-AuditReelViewer @((New-TopDump -Resumed 'false') + (New-TopDump -ReelHeader 'OtherFragment{77} (aa id=0x1)')) "$Package/.Main")) 'A paused reel behind the resumed Menu proved the destination.'
    $nestedResume = ((New-TopDump -Resumed 'false') -join "`n").Replace('mCreated=true mResumed=false', 'mCreated=true mResumed=true')
    Assert-That (-not (Test-AuditReelViewer @($nestedResume) "$Package/.Main")) 'A fragment manager resume flag overrode the activity state.'
    $doubleState = ((New-TopDump) -join "`n").Replace('      mResumed=true', "      mResumed=true`n      mResumed=true")
    Assert-That (-not (Test-AuditReelViewer @($doubleState) "$Package/.Main")) 'Conflicting or duplicate activity state lines proved a destination.'
    $notRunning = @('TASK 10433:com.facebook.katana id=7700 userId=0', "  ACTIVITY $Package/.Main old pid=(not running)")
    Assert-That (Test-AuditReelViewer @($notRunning + (New-TopDump)) "$Package/.Main") 'A retained nonrunning record hid the resumed reel.'
    $nonrunningWithIds = @('TASK 10433:com.facebook.katana id=7700 userId=0', "  ACTIVITY $Package/.Main old pid=(not running) userId=0 uid=10588 displayId=0(type=INTERNAL)")
    Assert-That (Test-AuditReelViewer @($nonrunningWithIds + (New-TopDump)) "$Package/.Main") 'User or display IDs on a nonrunning record hid the resumed reel.'
    Assert-That (-not (Test-AuditReelViewer $notRunning "$Package/.Main")) 'A nonrunning record proved a reel.'
    Assert-That (-not (Test-AuditReelViewer @((($notRunning -join "`n").Replace('pid=(not running)', 'pid=123')) + (New-TopDump)) "$Package/.Main")) 'A running record without client state was ignored.'
    $contradictoryClient = ((New-TopDump) -join "`n").Replace('pid=18012 userId=0', 'pid=(not running)')
    Assert-That (-not (Test-AuditReelViewer @($contradictoryClient) "$Package/.Main")) 'A nonrunning record with contradictory resumed client state passed.'
    # Other apps can stall the global top dump before Facebook's client is reached.
    $foregroundBeforeTop = $script:foreground
    $windowBeforeTop = $script:focusedWindow
    try {
        $script:simulateGlobalTimeout = $true
        foreach ($component in @("$Package/.Main", "$Package/.immersiveactivity.ImmersiveActivity")) {
            $script:topComponent = $component
            $script:foreground = "topResumedActivity=ActivityRecord{1 u0 $component t1}"
            $script:focusedWindow = "mCurrentFocus=Window{1 u0 $component}"
            $script:topDump = ((New-TopDump) -join "`n").Replace("$Package/.Main", $component)
            $script:adbCalls.Clear()
            Assert-That (Get-AuditReelProof) 'The current reel was lost behind another app in an unscoped activity dump.'
            Assert-That (@($script:adbCalls | Where-Object { $_ -ceq "-s $Serial shell dumpsys activity '$component'" }).Count -eq 1) 'Reel proof did not request the exact resumed component.'
            Assert-That (@($script:adbCalls | Where-Object { $_ -like '*dumpsys activity *' -and $_ -notlike '*dumpsys activity activities' -and $_ -cne "-s $Serial shell dumpsys activity '$component'" }).Count -eq 0) 'Reel proof requested a global, package-wide or fixed-launcher client dump.'
        }
        $script:changeWindowDuringTop = $true
        Assert-That (-not (Get-AuditReelProof)) 'Reel proof survived a changed input window while its client dump ran.'
    } finally {
        $script:foreground = $foregroundBeforeTop
        $script:focusedWindow = $windowBeforeTop
        $script:changeWindowDuringTop = $false
        $script:simulateGlobalTimeout = $false
        $script:topComponent = "$Package/.Main"
    }
    $script:topDump = (New-TopDump) -join "`n"
    $inputReel = $true
    $script:adbCalls.Clear()
    Invoke-Adb shell input swipe 10 20 10 5 | Out-Null
    Invoke-Adb shell input keyevent KEYCODE_BACK | Out-Null
    Assert-That (@($script:adbCalls | Where-Object { $_ -like '* input *' }).Count -eq 2) 'A proved reel refused a swipe or Back.'
    Assert-That (-not @($script:adbCalls | Where-Object { $_ -like '*uiautomator*' }).Count) 'A reel was dumped while it played.'
    $script:adbCalls.Clear()
    Assert-Throws { Invoke-Adb shell input tap 10 10 } 'A reel got a tap.'
    $script:topDump = (New-TopDump -Reel 'false') -join "`n"
    Assert-Throws { Invoke-Adb shell input swipe 10 20 10 5 } 'A swipe went to a reel viewer that is not on screen.'
    Assert-That (-not @($script:adbCalls | Where-Object { $_ -like '* input *' }).Count) 'Input escaped the reel guard.'
    $inputReel = $false
    # A swipe across an open keyboard types into the focused field, and only the query is typed.
    $script:imeState = "  mShowRequested=true`n  mInputShown=true"
    $script:adbCalls.Clear()
    Assert-Throws { Invoke-Adb shell input swipe 10 20 10 5 } 'A swipe went across an open keyboard.'
    Assert-Throws { Invoke-Adb shell input tap 10 10 } 'A tap went across an open keyboard.'
    Assert-Throws { Invoke-Adb shell input text news } 'Text was typed outside the query step.'
    $inputQuery = $true
    Assert-Throws { Invoke-Adb shell input text hello } 'A word other than the audit query was typed.'
    Assert-Throws { Invoke-Adb shell input text news hello } 'Extra words went in with the audit query.'
    Assert-That (-not @($script:adbCalls | Where-Object { $_ -like '* input *' }).Count) 'Input escaped the keyboard guard.'
    Invoke-Adb shell input text news | Out-Null
    Invoke-Adb shell input keyevent KEYCODE_ENTER | Out-Null
    Assert-That (@($script:adbCalls | Where-Object { $_ -like '* input *' }).Count -eq 2) 'The query was not typed and submitted.'
    # Enter can land on the first typeahead row, so the query step may tap the field holding the query
    # once, with the keyboard open, and nowhere else.
    $dumpBeforeQueryTap = $script:uiDump
    function New-QueryDump([string]$Fields) {
        return "<?xml version=`"1.0`"?><hierarchy>$Fields<node package=`"$Package`" class=`"android.widget.TextView`" text=`"news`" bounds=`"[0,300][1080,420]`" clickable=`"true`" /></hierarchy>"
    }
    $queryField = "<node package=`"$Package`" class=`"android.widget.EditText`" text=`"news`" bounds=`"[120,60][900,180]`" />"
    $script:uiDump = New-QueryDump $queryField
    $script:auditQueryTaps = 0
    $script:adbCalls.Clear()
    Assert-Throws { Invoke-Adb shell input tap 540 360 } 'A query-step tap went to the typeahead row repeating the query.'
    Assert-Throws { Invoke-Adb shell input tap 900 120 } 'A query-step tap went just past the search field.'
    Assert-Throws { Invoke-Adb shell input tap 500 } 'A query-step tap with no Y was sent.'
    Assert-That (-not @($script:adbCalls | Where-Object { $_ -like '* input *' }).Count) 'A query-step tap escaped the search field guard.'
    Invoke-Adb shell input tap 510 120 | Out-Null
    Assert-That (@($script:adbCalls | Where-Object { $_ -like '* input tap 510 120' }).Count -eq 1) 'A tap in the field holding the query was refused with the keyboard open.'
    $script:adbCalls.Clear()
    Assert-Throws { Invoke-Adb shell input tap 510 120 } 'The search field got a second tap in one query step.'
    Invoke-Adb shell input keyevent KEYCODE_ENTER | Out-Null
    Assert-That (@($script:adbCalls | Where-Object { $_ -like '* input *' }).Count -eq 1) 'Enter was refused after the field tap.'
    foreach ($case in @(
            @("<node package=`"$Package`" class=`"android.widget.EditText`" text=`"new`" bounds=`"[120,60][900,180]`" />", 'a field holding other text'),
            @("<node package=`"$Package`" class=`"android.widget.EditText`" text=`"News`" bounds=`"[120,60][900,180]`" />", 'a field holding the query in another case'),
            @(($queryField + $queryField.Replace('[120,60][900,180]', '[120,200][900,280]')), 'one of two fields holding the query'),
            @("<node package=`"com.other.app`" class=`"android.widget.EditText`" text=`"news`" bounds=`"[120,60][900,180]`" />", "another app's field"),
            @("<node package=`"$Package`" class=`"android.widget.EditText`" text=`"news`" bounds=`"[900,60][120,180]`" />", 'a field with inverted bounds'),
            @('', 'a screen with no search field'))) {
        $script:uiDump = New-QueryDump $case[0]
        $script:auditQueryTaps = 0
        $script:adbCalls.Clear()
        Assert-Throws { Invoke-Adb shell input tap 510 120 } "A query-step tap went to $($case[1])."
        Assert-That (-not @($script:adbCalls | Where-Object { $_ -like '* input *' }).Count) "A tap escaped the guard on $($case[1])."
    }
    $script:uiDump = New-QueryDump $queryField
    $script:auditQueryTaps = 0
    Assert-Throws { Invoke-Adb shell input tap 510.4 120 } 'A query-step tap with a fractional X was sent.'
    Assert-Throws { Invoke-Adb shell input tap 510 -120 } 'A query-step tap with a negative Y was sent.'
    $script:auditQueryTaps = 0
    # A Comment node under the tap passes the navigation check, so only the keyboard guard is left to refuse it.
    $script:uiDump = New-QueryDump ($queryField + "<node package=`"$Package`" class=`"android.view.View`" content-desc=`"Comment`" bounds=`"[400,60][620,180]`" clickable=`"true`" />")
    $inputQuery = $false
    Assert-Throws { Invoke-Adb shell input tap 510 120 } 'The search field got a tap across the open keyboard outside the query step.'
    $inputQuery = $true
    $script:uiDump = New-QueryDump $queryField
    Assert-Throws { Invoke-Adb shell input swipe 10 20 10 5 } 'The query step sent a swipe across the open keyboard.'
    $script:imeState = '  mInputShown=false'
    Assert-Throws { Invoke-Adb shell input tap 510 120 } 'The search field got a tap with no keyboard open.'
    $script:imeState = "  mShowRequested=true`n  mInputShown=true"
    Assert-That (-not @($script:adbCalls | Where-Object { $_ -like '* input *' }).Count) 'Input escaped the query step guard.'
    $script:uiDump = $dumpBeforeQueryTap
    $script:imeState = '  mInputShown=false'
    $script:adbCalls.Clear()
    Assert-Throws { Invoke-Adb shell input text news } 'The query was typed with no keyboard open.'
    $inputQuery = $false
    Assert-Throws { Invoke-Adb shell input keyevent KEYCODE_ENTER } 'Enter was sent outside the query step.'
    Assert-Throws { Invoke-Adb shell input keyevent KEYCODE_MENU } 'An unlisted key was sent.'
    Assert-Throws { Invoke-Adb shell input draganddrop 10 20 10 5 } 'An unlisted input command was sent.'
    $script:imeState = 'mServedView=null'
    Assert-Throws { Invoke-Adb shell input swipe 10 20 10 5 } 'A dump that does not say whether the keyboard is open permitted a swipe.'
    Assert-That (-not @($script:adbCalls | Where-Object { $_ -like '* input *' }).Count) 'Input escaped the keyboard guard with no keyboard state.'
    $script:imeState = '  mInputShown=false'
    Invoke-Adb shell input swipe 10 20 10 5 | Out-Null
    Assert-That (@($script:adbCalls | Where-Object { $_ -like '* input swipe *' }).Count -eq 1) 'A swipe with the keyboard closed was refused.'
    function New-TestNode([string]$Label, [string]$Class = 'android.view.View', [string]$Owner = $Package) {
        return [pscustomobject]@{ text = $Label; 'content-desc' = $Label; package = $Owner; class = $Class }
    }
    $feedNodes = @((New-TestNode "What's on your mind?"), (New-TestNode 'Go to profile'))
    Assert-That (Test-AuditDestination 'fb://feed' $feedNodes) 'A proved feed was rejected.'
    Assert-That (Test-AuditDestination 'fb://feed' @((New-TestNode 'Facebook logo'))) 'Home with its composer hidden was rejected.'
    foreach ($route in @('fb://watch', 'fb://fb_shorts/viewer', 'fb://notifications', 'fb://marketplace', 'fb://profile', 'fb://search', 'fb://bookmarks')) {
        Assert-That (-not (Test-AuditDestination $route $feedNodes)) "Home fallback passed as $route."
        Assert-That (-not (Test-AuditDestination $route @())) "Empty UI passed as $route."
    }
    $destinations = @{
        'fb://notifications' = @((New-TestNode 'Notifications'))
        'fb://marketplace' = @((New-TestNode 'Marketplace'), (New-TestNode 'Search Marketplace'))
        'fb://profile' = @((New-TestNode 'Edit profile'), (New-TestNode 'Edit profile picture'))
        'fb://search' = @((New-TestNode 'Search with Meta AI' 'android.widget.EditText'))
        'fb://bookmarks' = @((New-TestNode 'Menu'), (New-TestNode 'Settings and privacy'))
    }
    foreach ($route in $destinations.Keys) {
        Assert-That (Test-AuditDestination $route $destinations[$route]) "Proved destination rejected: $route."
        $foreign = @($destinations[$route] | ForEach-Object { New-TestNode $_.text $_.class 'com.other.app' })
        Assert-That (-not (Test-AuditDestination $route $foreign)) "Foreign package passed as $route."
    }
    # The pages the links open on an account with no profile picture and a warm app.
    $linkedPages = @{
        'fb://notifications' = @((New-TestNode "Manage the notification$([char]0x2019)s settings" 'android.widget.Button'))
        'fb://profile' = @((New-TestNode 'Edit profile'), (New-TestNode 'Add profile picture'))
        'fb://bookmarks' = @((New-TestNode 'Settings'), (New-TestNode 'Settings and privacy'))
    }
    foreach ($route in $linkedPages.Keys) {
        Assert-That (Test-AuditDestination $route $linkedPages[$route]) "Proved linked page rejected: $route."
        if ($linkedPages[$route].Count -gt 1) {
            Assert-That (-not (Test-AuditDestination $route @($linkedPages[$route][0]))) "Half a linked page passed as $route."
        }
        $foreign = @($linkedPages[$route] | ForEach-Object { New-TestNode $_.text $_.class 'com.other.app' })
        Assert-That (-not (Test-AuditDestination $route $foreign)) "Foreign package passed as linked $route."
    }
    # The S22's menu row repeats its label on a child that isn't clickable.
    function New-TapNode([string]$Label, [string]$Bounds, [string]$Clickable, [string]$Owner = $Package) {
        return [pscustomobject]@{ text = ''; 'content-desc' = $Label; package = $Owner; bounds = $Bounds; clickable = $Clickable }
    }
    $row = New-TapNode 'Settings and privacy' '[0,1966][1080,2091]' 'true'
    $rowText = New-TapNode 'Settings and privacy' '[136,2001][956,2058]' 'false'
    $gear = New-TapNode 'Settings' '[962,87][1063,188]' 'true'
    Assert-That ([object]::ReferenceEquals((Select-AuditTapNode 'Settings and privacy' @($gear, $rowText, $row)), $row)) 'A row with its label repeated inside it was not tapped.'
    Assert-That ([object]::ReferenceEquals((Select-AuditTapNode 'Settings' @($gear, $row)), $gear)) 'A lone matching node was not tapped.'
    Assert-That ($null -eq (Select-AuditTapNode 'Settings and privacy' @($row, (New-TapNode 'Settings and privacy' '[0,100][1080,200]' 'true')))) 'Two clickable matches picked one.'
    Assert-That ($null -eq (Select-AuditTapNode 'Settings and privacy' @($row, (New-TapNode 'Settings and privacy' '[0,1900][1080,2058]' 'false')))) 'A match outside the clickable row was ignored.'
    Assert-That ($null -eq (Select-AuditTapNode 'Settings and privacy' @($rowText, (New-TapNode 'Settings and privacy' '[136,1001][956,1058]' 'false')))) 'Two unclickable matches picked one.'
    Assert-That ($null -eq (Select-AuditTapNode 'Settings and privacy' @((New-TapNode 'Settings and privacy' '[0,1966][1080,2091]' 'true' 'com.other.app')))) 'A foreign node was tapped.'
    # Two short posts put two Comment buttons on one screen (the 0.21.0 walk's feed).
    $lowerComment = New-TapNode 'Comment' '[24,866][106,948]' 'true'
    $upperComment = New-TapNode 'Comment' '[24,474][106,556]' 'true'
    Assert-That ([object]::ReferenceEquals((Select-AuditTapNode 'Comment' @($lowerComment, $upperComment)), $upperComment)) 'Two Comment buttons on one screen were not resolved to the topmost.'
    $MinimumTapY = 600
    try {
        Assert-That ([object]::ReferenceEquals((Select-AuditTapNode 'Comment' @($upperComment, $lowerComment)), $lowerComment)) 'A Comment button above the minimum tap Y was taken.'
        Assert-That ($null -eq (Select-AuditTapNode 'Comment' @($upperComment, (New-TapNode 'Comment' '[24,300][106,382]' 'true')))) 'Comment buttons all above the minimum tap Y returned one.'
    } finally { $MinimumTapY = 0 }
    Assert-That ($null -eq (Select-AuditTapNode 'Comment' @((New-TapNode 'Comment' '[24,474][106,556]' 'false'), (New-TapNode 'Comment' '[24,866][106,948]' 'false')))) 'An unclickable Comment label was tapped.'
    Assert-That ([object]::ReferenceEquals((Select-AuditTapNode 'Comment' @($lowerComment, $upperComment, (New-TapNode 'Comment' '[24,100][106,182]' 'true' 'com.other.app'))), $upperComment)) 'A foreign Comment node was taken over Facebook''s.'
    # A Comment scrolled under the top bar or the tab bar sends its tap there (the drawer button on
    # the S22, 2026-11-05). The bounds are the S22's all-on feed; the nodes are parsed the way
    # Get-UiNodes parses a dump, so rows and parts are found through the tree.
    function Get-TestTapNodes([string]$Body) {
        return @(([xml]"<?xml version=`"1.0`"?><hierarchy>$Body</hierarchy>").SelectNodes('//node') | Where-Object { $_.package -ceq $Package })
    }
    function New-XmlTapNode([string]$Label, [string]$Bounds, [string]$Inside = '') {
        return "<node package=`"$Package`" class=`"android.widget.Button`" content-desc=`"$Label`" bounds=`"$Bounds`" clickable=`"true`">$Inside</node>"
    }
    $coveredPost = "<node package=`"$Package`" content-desc=`"`" bounds=`"[0,75][1080,600]`" clickable=`"true`">" + (New-XmlTapNode 'Comment' '[124,92][248,216]') + '</node>'
    $openPost = "<node package=`"$Package`" content-desc=`"`" bounds=`"[0,1100][1080,1400]`" clickable=`"true`">" + (New-XmlTapNode 'Comment' '[124,1200][248,1324]') + '</node>'
    $topBar = "<node package=`"$Package`" content-desc=`"`" bounds=`"[0,75][1080,199]`" clickable=`"true`">" + (New-XmlTapNode 'Menu' '[24,92][200,182]') + '</node>'
    $feedNodes = Get-TestTapNodes ($coveredPost + $openPost + $topBar)
    $feedPick = Select-AuditTapNode 'Comment' $feedNodes
    Assert-That ($null -ne $feedPick -and $feedPick.bounds -ceq '[124,1200][248,1324]') 'A Comment under the top bar was taken over one in the open.'
    Assert-That ($null -eq (Select-AuditTapNode 'Comment' (Get-TestTapNodes ($coveredPost + $topBar)))) 'The one Comment on screen, under the top bar, was taken.'
    $tabBar = (New-XmlTapNode 'Comment' '[162,2131][324,2255]') + (New-XmlTapNode 'Marketplace, tab 2 of 6' '[180,2116][360,2274]')
    Assert-That ($null -eq (Select-AuditTapNode 'Comment' (Get-TestTapNodes $tabBar))) 'A Comment under the tab bar was taken.'
    $labelInside = "<node package=`"$Package`" class=`"android.view.ViewGroup`" content-desc=`"Comment`" text=`"Comment`" bounds=`"[150,120][230,190]`" clickable=`"false`" />"
    $labelledCover = $coveredPost.Replace('[248,216]" clickable="true">', '[248,216]" clickable="true">' + $labelInside)
    Assert-That ($null -eq (Select-AuditTapNode 'Comment' (Get-TestTapNodes ($labelledCover + $topBar)))) 'A covered Comment whose label repeats on a child was taken.'
    $insidePick = Select-AuditTapNode 'Comment' (Get-TestTapNodes $coveredPost)
    Assert-That ($null -ne $insidePick -and $insidePick.bounds -ceq '[124,92][248,216]') 'The clickable post row holding a Comment counted as covering it.'
    $partsPick = Select-AuditTapNode 'Comment' (Get-TestTapNodes ($openPost.Replace('[248,1324]" clickable="true">', '[248,1324]" clickable="true">' + (New-XmlTapNode 'Comment icon' '[150,1220][222,1304]'))))
    Assert-That ($null -ne $partsPick -and $partsPick.bounds -ceq '[124,1200][248,1324]') 'A clickable part inside a Comment counted as covering it.'
    $foreignBar = "<node package=`"com.other.app`" content-desc=`"`" bounds=`"[0,75][1080,199]`" clickable=`"true`" />"
    $foreignNodes = @(([xml]"<hierarchy>$coveredPost$foreignBar</hierarchy>").SelectNodes('//node'))
    Assert-That ($foreignNodes[1].'content-desc' -ceq 'Comment' -and -not (Test-AuditTapCovered $foreignNodes[1] $foreignNodes)) 'Another app''s node counted as covering a Comment.'
    Write-TestLease -Owner 'replacement'
    Assert-Throws { Invoke-Adb shell input tap 10 10 } 'Ownership loss during the walk permitted input.'

    Write-TestLease
    $OutDir = $testRoot
    $AuditDir = "/sdcard/Android/data/$Package/files/hushfacebook-audit"
    $stateBefore = @{ night = 'auto'; locales = 'de-DE,en-US' }
    $KeepSettings = $false
    $problems = [Collections.Generic.List[string]]::new()
    [IO.File]::WriteAllText((Join-Path $OutDir 'before.json'), '{}')
    function Start-Facebook { $script:restarts++ }
    $script:adbCalls.Clear()
    Assert-That ((Send-Profile '{}') -ceq 'applied 4') 'The import acknowledgement was not read.'
    $commands = @($script:adbCalls)
    $stopAt = [Array]::FindIndex($commands, [Predicate[string]]{ param($line) $line -like '*am force-stop*' })
    $clearAt = [Array]::FindIndex($commands, [Predicate[string]]{ param($line) $line -like '*rm -f */import.txt' })
    $pushAt = [Array]::FindIndex($commands, [Predicate[string]]{ param($line) $line -like '*push */settings.json' })
    Assert-That ($stopAt -ge 0 -and $clearAt -gt $stopAt -and $pushAt -gt $clearAt) 'A profile was exposed before stopping Facebook and clearing old acknowledgements.'
    function Send-Profile([string]$Json) {
        if ($script:refuseSettings) { return 'refused: fixture failure' }
        return 'applied 4'
    }
    $script:actualLocale = 'vi-VN'
    $script:actualNight = 'yes'
    $script:restarts = 0
    $script:adbCalls.Clear()
    Assert-That (Restore-AuditState) 'A successful restore failed.'
    Assert-That ($script:actualLocale -ceq 'de-DE,en-US' -and $script:actualNight -eq 'auto') 'Original locale/theme did not round-trip.'
    Assert-That (@($script:adbCalls | Where-Object { $_ -like '* rm -rf *' }).Count -eq 1) 'Successful restore did not clean up once.'

    $script:refuseSettings = $true
    $script:actualLocale = 'vi-VN'
    $script:actualNight = 'yes'
    $script:adbCalls.Clear()
    Assert-That (-not (Restore-AuditState)) 'A refused settings restore passed.'
    Assert-That ($script:actualLocale -ceq 'de-DE,en-US' -and $script:actualNight -eq 'auto') 'Settings refusal prevented independent locale/theme restore.'
    Assert-That (-not @($script:adbCalls | Where-Object { $_ -like '* rm -rf *' }).Count) 'Refused restore deleted device recovery data.'
    Assert-That (Test-Path (Join-Path $OutDir 'before.json')) 'Refused restore lost its local backup.'
    $script:refuseSettings = $false
    $script:wrongSnapshot = $true
    Assert-That (-not (Restore-AuditState)) 'An applied but different settings snapshot passed.'
    $script:wrongSnapshot = $false
    $script:refuseNight = $true
    Assert-That (-not (Restore-AuditState)) 'A failed night restore passed.'
    $script:refuseNight = $false
    $script:disconnected = $true
    Assert-That (-not (Restore-AuditState)) 'A disconnected restore passed.'
    $script:disconnected = $false
    $script:refuseLocale = $true
    $script:actualLocale = 'vi-VN'
    $script:actualNight = 'yes'
    $script:adbCalls.Clear()
    $problems.Clear()
    Assert-That (-not (Restore-AuditState)) 'A failed locale restore passed.'
    Assert-That ($script:actualNight -eq 'auto') 'A locale restore failure prevented the theme restore.'
    Assert-That (-not @($script:adbCalls | Where-Object { $_ -like '* rm -rf *' }).Count) 'A failed locale restore deleted device recovery data.'
    Assert-That (Test-Path (Join-Path $OutDir 'before.json')) 'A failed locale restore lost its local backup.'
    Assert-That (@($problems | Where-Object { $_ -like 'Could not restore locale*' }).Count -ge 1) 'A failed locale restore was not reported.'
    $script:refuseLocale = $false
    $stateBefore.locales = ''
    $script:actualLocale = 'vi-VN'
    $KeepSettings = $true
    $script:refuseSettings = $true
    $script:adbCalls.Clear()
    Assert-That (Restore-AuditState) 'KeepSettings unexpectedly tried importing settings.'
    Assert-That ($script:actualLocale -ceq '') 'KeepSettings failed to restore an originally empty locale.'
    Assert-That (-not @($script:adbCalls | Where-Object { $_ -like '* rm -rf *' }).Count) 'KeepSettings deleted retained recovery data.'

    # A backup an older build wrote, restored on a build with a switch it never knew.
    $KeepSettings = $false
    $script:refuseSettings = $false
    $script:dropOnImport = $null
    $script:deviceSwitches = [ordered]@{ hide_ads = $true; hide_reels = $true; added_later = $true }
    function Send-Profile([string]$Json) {
        $imported = ConvertFrom-AuditJsonTable $Json
        if ($imported.format -ceq 'hushfacebook-audit') {
            foreach ($key in @($script:deviceSwitches.Keys)) { $script:deviceSwitches[$key] = $false }
        } else {
            foreach ($key in $imported.settings.Keys) {
                if ($key -cne $script:dropOnImport) { $script:deviceSwitches[$key] = $imported.settings[$key] }
            }
        }
        return 'applied 3'
    }
    $olderBackup = New-TestBackup ([ordered]@{ hide_ads = $true; hide_reels = $false })
    [IO.File]::WriteAllText((Join-Path $OutDir 'before.json'), $olderBackup)
    $problems.Clear()
    $script:adbCalls.Clear()
    Assert-That (Restore-AuditState) "An older build's backup could not be restored: $($problems -join '; ')"
    Assert-That ($script:deviceSwitches.hide_ads -and -not $script:deviceSwitches.hide_reels) "An older build's backup was not put back."
    Assert-That (-not $script:deviceSwitches.added_later) 'A switch the backup never knew kept its audit value.'
    Assert-That (@($script:adbCalls | Where-Object { $_ -like '* rm -rf *' }).Count -eq 1) "A restored older backup wasn't cleaned up."
    $script:deviceSwitches = [ordered]@{ hide_ads = $true; hide_reels = $true; added_later = $true }
    $script:dropOnImport = 'hide_ads'
    $problems.Clear()
    $script:adbCalls.Clear()
    Assert-That (-not (Restore-AuditState)) "An older build's backup that didn't fully apply passed."
    Assert-That (-not @($script:adbCalls | Where-Object { $_ -like '* rm -rf *' }).Count) "A failed older-backup restore deleted device recovery data."
    $script:dropOnImport = $null
    $script:deviceSwitches = $null
    $defaultsShape = New-TestBackup ([ordered]@{ hide_ads = $false; hide_reels = $false; added_later = $false })
    Assert-That (-not (Test-AuditSettingsRestored $olderBackup (New-TestBackup ([ordered]@{ hide_ads = $true; hide_reels = $false; added_later = $true })) $defaultsShape)) 'A new switch away from its default passed.'
    Assert-That (-not (Test-AuditSettingsRestored $olderBackup (New-TestBackup ([ordered]@{ hide_ads = $true; added_later = $false })) $defaultsShape)) 'A switch missing after the restore passed.'
    Assert-That (-not (Test-AuditSettingsRestored $olderBackup (New-TestBackup ([ordered]@{ hide_ads = $true; hide_reels = $false; unknown = $false })) $defaultsShape)) 'A switch the defaults never listed passed.'
    Assert-That (-not (Test-AuditSettingsRestored '{}' '{}' $defaultsShape)) 'A snapshot without settings passed.'
    Assert-That (Test-AuditSettingsRestored $olderBackup (New-TestBackup ([ordered]@{ hide_ads = $true; hide_reels = $false; added_later = $false })) $defaultsShape) 'A correct older-backup restore failed.'

    # A before.json with no locale/theme snapshot beside it, as an older audit build or the settings
    # tool leaves one, is adopted: a dated copy of the folder first, the current locale and theme,
    # and its switches back at the end.
    $deviceIdentity = 'Audit_AVD'
    $sdk = 36
    $current = [ordered]@{ schema = 1; serial = $Serial; device = $deviceIdentity; package = $Package; night = 'no'; locales = 'en-US' }
    $script:deviceState = $null
    $script:deviceBackupListing = $null
    $script:adbCalls.Clear()
    Assert-That ((Resolve-AuditStateBefore $current).night -ceq 'no' -and -not @($script:adbCalls | Where-Object { $_ -like '* pull *' }).Count) `
        'An empty audit folder did not start from the current locale and theme.'
    $script:deviceBackupListing = "$AuditDir/before.json"
    $script:orphanBackup = New-TestBackup ([ordered]@{ hide_ads = $true; hide_reels = $false })
    $script:failFolderPull = $true
    $script:adbCalls.Clear()
    Assert-Throws { Resolve-AuditStateBefore $current } 'An orphaned backup was adopted though its folder did not copy.'
    Assert-That (-not @($script:adbCalls | Where-Object { $_ -like '* rm *' -or $_ -like '* push *' }).Count) 'A failed copy of an orphaned backup changed the device.'
    $script:failFolderPull = $false
    $orphanText = $script:orphanBackup
    $script:orphanBackup = ' '
    Assert-Throws { Resolve-AuditStateBefore $current } 'An orphaned backup was adopted though its copy came off the phone empty.'
    $script:orphanBackup = $orphanText
    Get-ChildItem -LiteralPath $OutDir -Directory -Filter 'orphaned-audit-*' | Remove-Item -Recurse -Force
    $adoptLog = @(& { $script:adopted = Resolve-AuditStateBefore $current } 6>&1 | ForEach-Object { [string]$_ })
    Assert-That ($script:adopted.night -ceq 'no' -and $script:adopted.locales -ceq 'en-US') 'An adopted orphan did not start from the current locale and theme.'
    $copies = @(Get-ChildItem -LiteralPath $OutDir -Directory -Filter 'orphaned-audit-*')
    Assert-That ($copies.Count -eq 1 -and [IO.File]::ReadAllText((Join-Path $copies[0].FullName 'hushfacebook-audit/before.json')) -ceq $script:orphanBackup) `
        'An adopted orphan left no copy of its folder.'
    Assert-That (@($adoptLog | Where-Object { $_ -like '*older audit backup had no locale/theme snapshot*' }).Count -eq 1) 'Adopting an orphaned backup was not logged.'
    Remove-Item -LiteralPath (Join-Path $OutDir 'before.json') -Force -ErrorAction SilentlyContinue
    $script:deviceSwitches = [ordered]@{ hide_ads = $false; hide_reels = $true }
    $stateBefore = $script:adopted
    $problems.Clear()
    Assert-That (Restore-AuditState) "An adopted orphan's switches could not be restored: $($problems -join '; ')"
    Assert-That ($script:deviceSwitches.hide_ads -and -not $script:deviceSwitches.hide_reels -and
        $script:actualNight -ceq 'no' -and $script:actualLocale -ceq 'en-US') "An adopted orphan's switches, locale or theme did not come back."
    # A snapshot left beside it is still the one that wins.
    $script:deviceState = ([ordered]@{ schema = 1; serial = $Serial; device = $deviceIdentity; package = $Package; night = 'yes'; locales = 'de-DE' } | ConvertTo-Json)
    Assert-That ((Resolve-AuditStateBefore $current).night -ceq 'yes') "An unfinished run's own snapshot lost to the current locale and theme."
    $script:deviceState = $null
    $script:deviceBackupListing = $null
    $script:orphanBackup = $null
    $script:deviceSwitches = $null
    Get-ChildItem -LiteralPath $OutDir -Directory -Filter 'orphaned-audit-*' | Remove-Item -Recurse -Force

    $version = '585.0.0.47.54'
    $installedHash = 'a' * 64
    $installedAbi = 'arm64-v8a'
    $sdk = 36
    $deviceIdentity = 'Audit_AVD'
    $recoveryGuard = $auditAst.Find({ param($node)
        $node -is [System.Management.Automation.Language.IfStatementAst] -and
            $node.Extent.Text.StartsWith('if (($savedState.schema')
    }, $true)
    Assert-That ($null -ne $recoveryGuard) 'Recovery snapshot validation is missing.'
    $validateRecovery = [scriptblock]::Create($recoveryGuard.Extent.Text)
    foreach ($brokenLocale in @($null, 1, @('de'), 'de;input tap 1 1', '---', 'de,,en', '123', 'en-a', 'en-a-bbb-a-ccc', 'de-1901-1901')) {
        $savedState = [pscustomobject]@{ schema = 1; serial = $Serial; device = $deviceIdentity
            package = $Package; night = 'auto'; locales = $brokenLocale }
        Assert-Throws { & $validateRecovery } 'An invalid original locale passed the recovery snapshot gate.'
    }
    $savedState.locales = ''
    & $validateRecovery
    foreach ($field in @('schema', 'serial', 'device', 'package', 'night')) {
        $malformedState = $savedState | ConvertTo-Json | ConvertFrom-Json
        $malformedState.$field = @($malformedState.$field, $malformedState.$field)
        $savedState = $malformedState
        Assert-Throws { & $validateRecovery } "A nonscalar recovery $field passed validation."
        $savedState = [pscustomobject]@{ schema = 1; serial = $Serial; device = $deviceIdentity
            package = $Package; night = 'auto'; locales = '' }
    }
    foreach ($validLocale in @('', 'de-DE,en-US', 'en-US-u-ca-gregory', 'en-x-a', 'x-private', 'zh-Hant-TW', 'en-GB-oed', 'i-klingon', 'de-CH-1901')) {
        Assert-That (Test-AuditLocaleList $validLocale) "A well-formed locale list was rejected: $validLocale"
    }
    $kept = @(Get-AuditOff -OnKeys @('hold_edge') -OffKeys @('long_press') -WalkKeys @('hold_edge', 'comment'))
    Assert-That ($kept -join ',' -ceq 'comment,long_press') "A walk key asked for with -On stayed off, or -Off's key went missing: $($kept -join ',')"
    Assert-That (@(Get-AuditOff -OnKeys @() -OffKeys @('a', 'b') -WalkKeys @('w')) -join ',' -ceq 'w,a,b') 'An -Off key replaced the walk keys.'
    Assert-That (@(Get-AuditOff -OnKeys @('k') -OffKeys @('k') -WalkKeys @()) -join ',' -ceq 'k') 'A key in both -On and -Off ended on.'
    Assert-That (@(Get-AuditOff -OnKeys @('Long_Press') -OffKeys @() -WalkKeys @('long_press')) -join ',' -ceq 'long_press') 'An -On key in another case took a walk key out.'
    Assert-That (@(Get-AuditOff -OnKeys @() -OffKeys @('w') -WalkKeys @('w')) -join ',' -ceq 'w') 'A key in both -Off and the walk keys came back twice.'
    Assert-That (@(Get-AuditOff -OnKeys @('a') -OffKeys @() -WalkKeys @('a')).Count -eq 0) 'A walk key asked for with -On came back.'
    # The walk's own keys, which must stay real settings for the walk to reach its families.
    $walkOff = @(Get-AuditOff -OnKeys @() -OffKeys @())
    $settingsJava = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot '..\extensions\facebook\src\main\java\app\morphe\extension\facebook\settings\Settings.java')
    foreach ($walkKey in @('hushfacebook_hold_reel_right_edge', 'hushfacebook_long_press_opens_comments', 'hushfacebook_hide_post_comment_button')) {
        Assert-That ($walkOff -ccontains $walkKey) "The walk's keys lost $walkKey."
        Assert-That ($settingsJava.Contains("new BooleanSetting(`"$walkKey`"")) "$walkKey isn't a setting any more."
    }
    $On = @('one')
    $Off = @('two')
    $OptionalVisit = @('tap:Comment')
    $Locales = @('de')
    $Swipes = 4
    $MaxScrolls = 0
    [IO.File]::WriteAllText((Join-Path $OutDir 'applied.json'), '{}')
    $report = "app: $Package $version (2147483647)`npatch_build: sha256=$('b' * 64); source=clean:$('c' * 40); tree=$('d' * 40); inputs=$('e' * 64)`n"
    $candidate = New-AuditBaseline $report @('family') @('other')
    $expected = $candidate | ConvertTo-Json -Depth 6 | ConvertFrom-Json
    Assert-That (Test-AuditBaseline $expected $candidate) 'Matching baseline was rejected.'
    foreach ($field in @('schema', 'facebook', 'patchBuild', 'installedApkSha256', 'abi', 'sdk', 'device', 'settingsSha256')) {
        $different = $candidate | ConvertTo-Json -Depth 6 | ConvertFrom-Json
        $different.$field = $(if ($field -in @('sdk','schema')) { 999 } else { 'different' })
        Assert-That (-not (Test-AuditBaseline $different $candidate)) "Different baseline $field passed."
        $different = $candidate | ConvertTo-Json -Depth 6 | ConvertFrom-Json
        $different.$field = @($different.$field, $different.$field)
        Assert-That (-not (Test-AuditBaseline $different $candidate)) "Nonscalar baseline $field passed."
    }
    $different = $candidate | ConvertTo-Json -Depth 6 | ConvertFrom-Json
    $different.profile.on = @('other')
    Assert-That (-not (Test-AuditBaseline $different $candidate)) 'Different profile switches passed.'
    $different = $candidate | ConvertTo-Json -Depth 6 | ConvertFrom-Json
    $different.profile = @($different.profile)
    Assert-That (-not (Test-AuditBaseline $different $candidate)) 'A wrapped profile object passed.'
    foreach ($badFamilies in @($null, 'family', @(), @(1))) {
        $different = $candidate | ConvertTo-Json -Depth 6 | ConvertFrom-Json
        $different.ranOnWalk = $badFamilies
        Assert-That (-not (Test-AuditBaseline $different $candidate)) 'An absent or malformed baseline family list passed.'
    }
    Assert-Throws { New-AuditBaseline ($report -replace 'sha256=', 'unknown=') @() @() } 'Unknown patch build was accepted.'

    $MayIdle = @('waits', 'waits')
    $served = New-AuditBaseline $report @('family', 'waits') @('other')
    Assert-That (@($served.ranOnWalk) -join ',' -ceq 'family') 'A family that waits on served content went into ranOnWalk.'
    Assert-That (@($served.mayIdle) -join ',' -ceq 'waits') 'The baseline did not record its mayIdle families once each.'
    $expected = $served | ConvertTo-Json -Depth 6 | ConvertFrom-Json
    Assert-That (Test-AuditBaseline $expected $served) 'A baseline with mayIdle families was rejected.'
    foreach ($badIdle in @('waits', @(1), @(''), @('family'))) {
        $different = $served | ConvertTo-Json -Depth 6 | ConvertFrom-Json
        $different.mayIdle = $badIdle
        Assert-That (-not (Test-AuditBaseline $different $served)) 'A malformed mayIdle list, or one naming a ranOnWalk family, passed.'
    }
    $different = $served | ConvertTo-Json -Depth 6 | ConvertFrom-Json
    $different.PSObject.Properties.Remove('mayIdle')
    Assert-That (Test-AuditBaseline $different $served) 'A baseline from before mayIdle was rejected.'
    $walk = Compare-AuditWalk $expected @()
    Assert-That (@($walk.Failures).Count -eq 1 -and $walk.Failures[0] -like 'family ran on*') 'An idle family outside mayIdle did not fail, or a mayIdle one did.'
    Assert-That (@($walk.Notes) -contains "Wait on what Facebook serves: waits didn't run") 'An idle mayIdle family got no note.'
    $walk = Compare-AuditWalk $expected @('family', 'waits', 'fresh')
    Assert-That (@($walk.Failures).Count -eq 0) 'A walk that ran every baseline family failed.'
    Assert-That (@($walk.Notes) -contains 'Wait on what Facebook serves: waits ran') 'A mayIdle family that ran got no note.'
    Assert-That (@($walk.Notes) -contains 'Ran now and not in the baseline: fresh') 'A new family was not reported, or a mayIdle one was reported as new.'
    $older = $candidate | ConvertTo-Json -Depth 6 | ConvertFrom-Json
    $older.ranOnWalk = @('family', 'waits')
    Assert-That (@((Compare-AuditWalk $older @('family')).Failures).Count -eq 0) 'A baseline from before -MayIdle failed a family that waits on served content.'
    $MayIdle = @()
    Assert-That (@((Compare-AuditWalk $expected @('family')).Failures).Count -eq 0) "The baseline's own mayIdle list was ignored."
    Assert-That (@((Compare-AuditWalk $older @('family')).Failures).Count -eq 1) 'A ranOnWalk family idled without failing while nothing marks it as waiting.'

    $Expectations = Join-Path $testRoot 'baseline.json'
    $original = '{"original":true}'
    [IO.File]::WriteAllText($Expectations, $original)
    $WriteBaseline = $true
    $SkipWalk = $false
    $SkipPages = $false
    $KeepSettings = $false
    $recovered = $true
    foreach ($cause in @('crash', 'wrong page', 'import refusal', 'restore refusal')) {
        $problems.Clear()
        $problems.Add($cause)
        Assert-That (-not (Publish-AuditBaseline $candidate)) "Baseline promoted after $cause."
        Assert-That ([IO.File]::ReadAllText($Expectations) -ceq $original) "Trusted baseline changed after $cause."
    }
    $problems.Clear()
    $recovered = $false
    Assert-That (-not (Publish-AuditBaseline $candidate)) 'Baseline promoted without recovery.'
    $recovered = $true
    $SkipPages = $true
    Assert-That (-not (Publish-AuditBaseline $candidate)) 'Baseline promoted after skipping pages.'
    $SkipPages = $false
    Assert-That (Publish-AuditBaseline $candidate) 'Successful full audit did not promote its baseline.'
    Assert-That ((Get-Content -Raw $Expectations | ConvertFrom-Json).schema -eq 2) 'Promoted baseline was not schema 2.'
    # The baselines are committed as LF. ConvertTo-Json writes CRLF on Windows.
    Assert-That (-not [IO.File]::ReadAllText($Expectations).Contains("`r")) 'The promoted baseline has CR line endings.'

    $declaredPages = @(Get-SettingsPages)
    Assert-That ($declaredPages.Count -eq 23 -and $declaredPages[0] -ceq '') 'The settings sweep did not include the home list, all 21 categories and Support.'
    Assert-That (@($declaredPages | Select-Object -Unique).Count -eq 23) 'The settings sweep included a route more than once.'
    Assert-That ($declaredPages -cnotcontains 'more' -and $declaredPages[-1] -ceq 'support') 'The settings sweep still uses More or omits Support.'
    function Start-Sleep { param([int]$Seconds, [int]$Milliseconds) }
    # The settings title moves every frame, and an emulator's uiautomator then loses the window's
    # root now and then: a run of misses still reads the screen, and a screen that never dumps doesn't.
    $savedDump = $script:uiDump
    $script:uiDump = "<?xml version=`"1.0`"?><hierarchy><node package=`"$Package`" text=`"Comment`" content-desc=`"Comment`" bounds=`"[0,0][20,20]`" /></hierarchy>"
    $script:nullRootDumps = 6
    Assert-That (@(Get-UiNodes).Count -eq 1) 'Six null-root dumps in a row lost the screen.'
    $script:nullRootDumps = 50
    Assert-That (@(Get-UiNodes).Count -eq 0) 'A screen that never dumped returned nodes.'
    $script:nullRootDumps = 0
    $script:uiDump = $savedDump
    $script:settingsPage = 'News feed'
    function Get-SettingsPages { return $script:settingsPage }
    function Close-Settings { }
    function Get-ScreenText { return $script:screenText }
    function Save-Screenshot([string]$Path) {
        if ($script:crashOnScreenshot) {
            $script:crashBuffer += '1730000001.100 1000 1000 E AndroidRuntime: Process: com.facebook.katana, PID: 1000'
            $script:crashOnScreenshot = $false
        }
    }
    $SettleSeconds = 0
    $translationTables = Join-Path $PSScriptRoot '..\extensions\shared\library\src\main\l10n'
    $Entry = "$Package/.LoginActivity"
    $Extra = 'app.morphe.extension.facebook'
    $swipeX = 10; $swipeFrom = 20; $swipeTo = 5
    # A route swipe can leave Menu's row above the phone's permitted tap area.
    function New-TapDump([string[]]$Bounds) {
        $nodes = foreach ($box in $Bounds) {
            "<node package=`"$Package`" content-desc=`"Settings and privacy`" clickable=`"true`" bounds=`"$box`" />"
        }
        return "<?xml version=`"1.0`"?><hierarchy>$($nodes -join '')</hierarchy>"
    }
    $tapDumpBefore = $script:uiDump
    $inputLabelBefore = $inputNodeLabel
    $MinimumTapY = 600
    $lowBounds = '[0,360][1080,488]'
    $safeBounds = '[0,1546][1080,1674]'
    $downSwipe = "-s $Serial shell input swipe $swipeX $swipeTo $swipeX $swipeFrom 300"
    try {
        $script:uiDump = New-TapDump @($lowBounds)
        $script:uiDumpAfterDownSwipe = New-TapDump @($safeBounds)
        $script:adbCalls.Clear()
        $found = Find-AuditTapNode 'Settings and privacy'
        Assert-That ($null -ne $found -and $found.bounds -ceq $safeBounds) 'The navigation row was not freshly selected below the minimum tap Y after moving down.'
        $commands = @($script:adbCalls)
        $moveAt = [Array]::IndexOf($commands, $downSwipe)
        Assert-That (@($commands | Where-Object { $_ -like '* input *' }).Count -eq 1 -and $moveAt -ge 0) 'Moving an unsafe row did not use exactly one permitted downward swipe.'
        Assert-That ($moveAt -ge 0 -and @($commands | Select-Object -Skip ($moveAt + 1) | Where-Object { $_ -like '*uiautomator dump*' }).Count -ge 1) 'Moving the row did not collect a fresh UI dump before returning it.'
        if ($null -ne $found -and $found.bounds -match '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') {
            $inputNodeLabel = 'Settings and privacy'
            Invoke-Adb shell input tap ([int](([int]$Matches[1] + [int]$Matches[3]) / 2)) ([int](([int]$Matches[2] + [int]$Matches[4]) / 2)) | Out-Null
        }
        Assert-That (@($script:adbCalls | Where-Object { $_ -like '* input tap *' }).Count -eq 1 -and
            @($script:adbCalls | Where-Object { $_ -like '* input tap 540 1610' }).Count -eq 1) 'The caller tapped the old unsafe row instead of the newly selected row.'

        $script:uiDumpAfterDownSwipe = $null
        $script:uiDump = New-TapDump @($lowBounds)
        $script:adbCalls.Clear()
        $found = Find-AuditTapNode 'Settings and privacy'
        Assert-That ($null -eq $found) 'A row that remained above the minimum tap Y was returned to the tap caller.'
        Assert-That (@($script:adbCalls | Where-Object { $_ -ceq $downSwipe }).Count -eq 5 -and
            @($script:adbCalls | Where-Object { $_ -like '* input *' }).Count -eq 5) 'An immovable unsafe row did not stop after five downward swipes without a tap.'

        foreach ($allowed in @(@{ Minimum = 0; Bounds = $lowBounds }, @{ Minimum = 600; Bounds = '[0,540][1080,660]' })) {
            $MinimumTapY = $allowed.Minimum
            $script:uiDump = New-TapDump @($allowed.Bounds)
            $script:adbCalls.Clear()
            $found = Find-AuditTapNode 'Settings and privacy'
            Assert-That ($null -ne $found -and $found.bounds -ceq $allowed.Bounds) "A row meeting the minimum tap Y $MinimumTapY was refused."
            Assert-That (@($script:adbCalls | Where-Object { $_ -like '* input *' }).Count -eq 0 -and
                @($script:adbCalls | Where-Object { $_ -like '*uiautomator dump*' }).Count -eq 1) "A row meeting the minimum tap Y $MinimumTapY caused an unnecessary swipe."
        }

        $MinimumTapY = 600
        foreach ($badBounds in @(@('invalid'), @($safeBounds, '[0,1000][1080,1128]'))) {
            $script:uiDump = New-TapDump @($lowBounds)
            $script:uiDumpAfterDownSwipe = New-TapDump $badBounds
            $script:adbCalls.Clear()
            $found = Find-AuditTapNode 'Settings and privacy'
            Assert-That ($null -eq $found) 'An invalid or ambiguous replacement row was accepted after repositioning.'
            Assert-That (@($script:adbCalls | Where-Object { $_ -ceq $downSwipe }).Count -eq 5 -and
                @($script:adbCalls | Where-Object { $_ -like '* input *' }).Count -eq 5) 'Invalid or ambiguous replacement rows did not remain bounded and untapped.'
        }
    } finally {
        $MinimumTapY = 0
        $script:uiDumpAfterDownSwipe = $null
        $script:uiDump = $tapDumpBefore
        $inputNodeLabel = $inputLabelBefore
    }
    # The launcher entry: default, alternate icon, none and several.
    $realStart = ($auditAst.Find({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Start-Facebook' }, $true)).Extent.Text.Replace('function Start-Facebook', 'function Start-FacebookReal')
    . ([scriptblock]::Create($realStart))
    $LauncherClass = 'com.facebook.katana.LoginActivity'
    $alias = 'com.facebook.katana.LoginActivity.fab_ic'
    $script:launcherQuery = @('1 activities found:', "  $Package/.LoginActivity")
    Assert-That ((Get-AuditLauncher) -ceq $LauncherClass) 'The default launcher was not resolved.'
    $script:launcherQuery = @('1 activities found:', "  $Package/$alias")
    Assert-That ((Get-AuditLauncher) -ceq $alias) 'The enabled alternate launcher alias was not resolved.'
    $script:launcherQuery = @('1 activities found:', "  $Package/.LoginActivity.fab_ic")
    Assert-That ((Get-AuditLauncher) -ceq $alias) 'A short alias name was not expanded.'
    $script:launcherQuery = @('1 activities found:', "  $Package/com.facebook.katana.OtherActivity", "  com.other.app/.Main")
    Assert-Throws { Get-AuditLauncher } 'Only unrelated activities passed as a launcher.'
    $script:launcherQuery = @('No activities found')
    Assert-Throws { Get-AuditLauncher } 'A missing launcher passed.'
    $script:launcherQuery = @('0 activities found:', '')
    Assert-Throws { Get-AuditLauncher } 'An empty launcher query passed.'
    $script:launcherQuery = @('2 activities found:', "  $Package/.LoginActivity", "  $Package/$alias")
    Assert-Throws { Get-AuditLauncher } 'Two enabled launchers passed as one.'
    $script:launcherQuery = @("  $Package/.LoginActivity", "  $Package/.LoginActivity")
    Assert-That ((Get-AuditLauncher) -ceq $LauncherClass) 'One launcher listed twice was treated as ambiguous.'
    foreach ($launcher in @($LauncherClass, $alias)) {
        $Entry = "$Package/$launcher"
        $script:adbCalls.Clear()
        Start-FacebookReal
        try { Get-Report (Join-Path $OutDir 'report-launcher.txt') | Out-Null } catch { }
        $script:pageAck = New-TestSectionAck '{"route":"News feed","title":"Newsfeed","locale":"de-DE","localeTags":["de-rde","de"]}'
        $script:screenText = @('Hushfacebook', 'Newsfeed')
        Save-SettingsPages 'dark' | Out-Null
        $starts = @($script:adbCalls | Where-Object { $_ -like '*am start*' -and $_ -notlike '*android.intent.action.VIEW*' })
        Assert-That ($starts.Count -ge 3) "Launch commands were not issued for $launcher."
        Assert-That (@($starts | Where-Object { $_ -notlike "* -n $Package/$launcher*" }).Count -eq 0) "A launch or extra missed the active launcher $launcher."
        Assert-That (@($starts | Where-Object { $_ -like '*OPEN_SETTINGS true*' -and $_ -like "* -n $Package/$launcher *" }).Count -ge 1) "OPEN_SETTINGS did not go to $launcher."
        Assert-That (@($starts | Where-Object { $_ -like '*AUDIT_REPORT true*' -and $_ -like "* -n $Package/$launcher *" }).Count -ge 1) "AUDIT_REPORT did not go to $launcher."
        # Without it, an intent matching the task's root is dropped whenever another screen sits on top.
        Assert-That (@($starts | Where-Object { ($_ -like '*AUDIT_REPORT true*' -or $_ -like '*OPEN_SETTINGS true*') -and $_ -notlike '*--activity-clear-top*' }).Count -eq 0) "A report or settings request to $launcher could be dropped under another screen."
        Assert-That (-not @($script:adbCalls | Where-Object { $_ -match 'pm (enable|disable)|setEnabledSetting|cmd package (enable|disable)' }).Count) 'The audit changed a component state.'
    }
    $Entry = $null
    $script:adbCalls.Clear()
    Assert-Throws { Start-FacebookReal } 'An unresolved launcher still started Facebook.'
    Assert-Throws { Get-Report (Join-Path $OutDir 'report-none.txt') } 'An unresolved launcher still requested a report.'
    Assert-Throws { Save-SettingsPages 'dark' } 'An unresolved launcher still opened settings.'
    Assert-That (-not @($script:adbCalls | Where-Object { $_ -like '*am *' }).Count) 'An unresolved launcher reached ADB.'
    $Entry = "$Package/.LoginActivity"
    $script:foreground = "topResumedActivity=ActivityRecord{1 u0 $Package/.Main t1}"
    $script:screenText = @('Hushfacebook', 'Newsfeed')
    $script:pageAck = New-TestSectionAck '{"route":"News feed","title":"Newsfeed","locale":"de-DE","localeTags":["de-rde","de"]}'
    $script:crashBuffer = @('1729999999.100 1000 1000 E AndroidRuntime: Process: com.facebook.katana, PID: 1000')
    Assert-That ((Get-AuditCrashes -SinceEpoch 1730000000).Fatal.Count -eq 0) 'An old crash entered the current audit.'
    $script:crashOnScreenshot = $true
    Assert-That (@(Save-SettingsPages 'dark-de' 'de')[0].Opened) 'Proved translated page was rejected.'
    $savedAck = Get-Content -LiteralPath (Join-Path $OutDir 'pages-dark-de/news-feed.json') -Raw | ConvertFrom-Json
    Assert-That ($savedAck.schema -eq 3 -and $savedAck.expandedSections -ccontains 'News feed' -and $savedAck.sectionRows -eq 4) `
        'The native expansion and row proof was not kept beside the section screenshots.'
    $lateCrashes = Get-AuditCrashes -SinceEpoch 1730000000
    Assert-That ($lateCrashes.Fatal.Count -eq 1 -and $lateCrashes.Fatal[0] -like '1730000001.*') `
        'A crash recorded during the settings pass was missing from the final collection.'
    $script:failCrashRead = $true
    try { Assert-Throws { Get-AuditCrashes -SinceEpoch 1730000000 } 'A failed final crash-buffer read returned an empty successful result.' }
    finally { $script:failCrashRead = $false }
    $script:offlineCrashRead = $true
    try {
        foreach ($scriptPath in @($auditPath, (Join-Path $PSScriptRoot 'phone-smoke.ps1'))) {
            $sourceAst = [System.Management.Automation.Language.Parser]::ParseFile($scriptPath, [ref]$null, [ref]$null)
            $readCrashes = $sourceAst.Find({ param($node)
                $node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
                    $node.Left -is [System.Management.Automation.Language.VariableExpressionAst] -and
                    $node.Left.VariablePath.UserPath -ceq 'crashes'
            }, $true)
            if (-not $readCrashes) { throw "No crash-buffer read in $scriptPath" }
            $offlineError = ''
            try { . ([scriptblock]::Create($readCrashes.Extent.Text)) }
            catch { $offlineError = $_.Exception.Message }
            Assert-That ($offlineError -like '*device offline*') `
                "$(Split-Path -Leaf $scriptPath) didn't fail promptly on a disconnected crash-read transport."
        }
    } finally { $script:offlineCrashRead = $false }
    # Every category header is visible while collapsed, so a title alone cannot prove its content opened.
    $expandedAck = New-TestSectionAck '{"route":"News feed","title":"Newsfeed","locale":"de-DE","localeTags":["de-rde","de"]}'
    foreach ($case in @(
        @{ Name = 'legacy-schema'; Change = { param($record) $record.schema = 2 } },
        @{ Name = 'string-schema'; Change = { param($record) $record.schema = '3' } },
        @{ Name = 'fractional-schema'; Change = { param($record) $record.schema = 3.1 } },
        @{ Name = 'collapsed'; Change = { param($record) $record.expandedSections = @('Playback'); $record.targetExpanded = $false; $record.sectionRows = 0 } },
        @{ Name = 'wrong-expanded-target'; Change = { param($record) $record.expandedSections = @('Playback') } },
        @{ Name = 'empty-expanded'; Change = { param($record) $record.expandedSections = @(); $record.targetExpanded = $false } },
        @{ Name = 'scalar-expanded'; Change = { param($record) $record.expandedSections = 'News feed' } },
        @{ Name = 'null-expanded'; Change = { param($record) $record.expandedSections = $null } },
        @{ Name = 'object-expanded'; Change = { param($record) $record.expandedSections = @('News feed', @{ route = 'Playback' }) } },
        @{ Name = 'empty-id'; Change = { param($record) $record.expandedSections = @('News feed', '') } },
        @{ Name = 'duplicate-id'; Change = { param($record) $record.expandedSections = @('News feed', 'News feed') } },
        @{ Name = 'case-changed-id'; Change = { param($record) $record.expandedSections = @('News Feed') } },
        @{ Name = 'empty-section'; Change = { param($record) $record.sectionRows = 0 } },
        @{ Name = 'missing-row-count'; Change = { param($record) $record.PSObject.Properties.Remove('sectionRows') } },
        @{ Name = 'negative-row-count'; Change = { param($record) $record.sectionRows = -1 } },
        @{ Name = 'string-row-count'; Change = { param($record) $record.sectionRows = '4' } },
        @{ Name = 'fractional-row-count'; Change = { param($record) $record.sectionRows = 4.5 } },
        @{ Name = 'boolean-row-count'; Change = { param($record) $record.sectionRows = $true } },
        @{ Name = 'missing-expanded-flag'; Change = { param($record) $record.PSObject.Properties.Remove('targetExpanded') } },
        @{ Name = 'string-expanded-flag'; Change = { param($record) $record.targetExpanded = 'true' } },
        @{ Name = 'false-expanded-flag'; Change = { param($record) $record.targetExpanded = $false } }
    )) {
        $record = $expandedAck | ConvertFrom-Json
        & $case.Change $record
        $script:pageAck = ConvertTo-Json -InputObject $record -Compress -Depth 5
        Assert-That (-not @(Save-SettingsPages "dark-de-$($case.Name)" 'de')[0].Opened) `
            "The visible header passed invalid accordion proof for $($case.Name)."
    }
    $record = $expandedAck | ConvertFrom-Json
    $record.expandedSections = @('Playback', 'News feed', 'support')
    $script:pageAck = ConvertTo-Json -InputObject $record -Compress -Depth 5
    $severalOpen = @(Save-SettingsPages 'dark-de-several-open' 'de')[0]
    Assert-That ($severalOpen.Opened -and $severalOpen.ExpandedSections.Count -eq 3 -and $severalOpen.SectionRows -eq 4 -and $severalOpen.TargetExpanded) `
        'Independent open sections were rejected or their proof was missing from the result.'
    $script:screenText = @('Newsfeed')
    Assert-That (-not @(Save-SettingsPages 'dark-de-no-toolbar' 'de')[0].Opened) 'Section proof passed without the Hushfacebook toolbar.'
    # A page opened at its section has scrolled the Hushfacebook header away; the list toggle stays.
    $script:screenText = @('Alle Einstellungen als Liste zeigen', 'Newsfeed')
    Assert-That (@(Save-SettingsPages 'dark-de-toggle' 'de')[0].Opened) 'A German page under its list toggle alone was rejected.'
    $script:screenText = @('Show all settings as a list', 'Newsfeed')
    Assert-That (-not @(Save-SettingsPages 'dark-de-english-toggle' 'de')[0].Opened) 'An English toggle passed a German page.'
    $script:screenText = @('Hushfacebook', 'Newsfeed')
    $script:pageAck = New-TestSectionAck '{"route":"Playback","title":"Newsfeed","locale":"de-DE","localeTags":["de-rde","de"]}'
    Assert-That (-not @(Save-SettingsPages 'dark-de' 'de')[0].Opened) 'Wrong localized page was accepted.'
    $script:screenText = @('Hushfacebook', 'News feed')
    $script:pageAck = New-TestSectionAck '{"route":"News feed","title":"News feed","locale":"en-US","localeTags":["en-rus","en"]}'
    Assert-That (-not @(Save-SettingsPages 'dark-de' 'de')[0].Opened) 'An English page passed a requested German pass.'
    $script:pageAck = New-TestSectionAck '{"route":"News feed","title":"News feed","locale":"de-DE","localeTags":["de-rde","de"]}'
    Assert-That (-not @(Save-SettingsPages 'dark-de' 'de')[0].Opened) 'An English title under German configuration passed.'
    $script:pageAck = New-TestSectionAck '{"route":"News feed","title":"News feed","localeTags":["de-rde","de"]}'
    Assert-That (-not @(Save-SettingsPages 'dark-de' 'de')[0].Opened) 'A missing effective locale passed a localized pass.'
    $script:pageAck = ''
    Assert-That (-not @(Save-SettingsPages 'dark-de' 'de')[0].Opened) 'Missing page acknowledgement passed.'
    try {
        $script:settingsPage = ''
        $script:screenText = @('Hushfacebook', 'Newsfeed', 'Hilfe')
        $script:pageAck = '{"schema":3,"route":"","title":"Hushfacebook","locale":"de-DE","localeTags":["de-rde","de"],"expandedSections":["Playback"],"sectionRows":0,"targetExpanded":false}'
        Assert-That (@(Save-SettingsPages 'dark-de-home' 'de')[0].Opened) 'The main list with another independently expanded section was refused.'
        $record = $script:pageAck | ConvertFrom-Json
        $record.sectionRows = 4
        $script:pageAck = ConvertTo-Json -InputObject $record -Compress -Depth 5
        Assert-That (-not @(Save-SettingsPages 'dark-de-home-rows' 'de')[0].Opened) 'The main list claimed children for an absent target.'

        $script:settingsPage = 'support'
        $script:pageAck = '{"schema":3,"route":"support","title":"Hilfe","locale":"de-DE","localeTags":["de-rde","de"],"expandedSections":["News feed","support"],"sectionRows":4,"targetExpanded":true}'
        $supportPage = @(Save-SettingsPages 'dark-de-support' 'de')[0]
        Assert-That ($supportPage.Opened -and $supportPage.Name -ceq 'support') 'The translated Support section was omitted or used its lowercase route as the translation key.'
        $record = $script:pageAck | ConvertFrom-Json
        $record.expandedSections = @('News feed')
        $record.targetExpanded = $false
        $record.sectionRows = 0
        $script:pageAck = ConvertTo-Json -InputObject $record -Compress -Depth 5
        Assert-That (-not @(Save-SettingsPages 'dark-de-support-collapsed' 'de')[0].Opened) 'The collapsed Support header passed without its content.'
    } finally {
        $script:settingsPage = 'News feed'
    }
    # The configuration's primary locale and the runtime's translation lookup order are independent.
    $actualTranslationTables = $translationTables
    $translationTables = Join-Path $testRoot 'fallback-translations'
    New-Item -ItemType Directory -Path $translationTables | Out-Null
    [IO.File]::WriteAllText((Join-Path $translationTables 'de.tsv'), "News feed`tNewsfeed`n")
    try {
        foreach ($case in @(
            @{ Name = 'base'; Requested = 'ar'; Ack = '{"route":"News feed","title":"News feed","locale":"ar","localeTags":["ar"]}'; Text = 'News feed'; Opened = $true },
            @{ Name = 'regional'; Requested = 'ar-SA'; Ack = '{"route":"News feed","title":"News feed","locale":"ar-SA","localeTags":["ar-rsa","ar"]}'; Text = 'News feed'; Opened = $true },
            @{ Name = 'german-secondary'; Requested = 'ar'; Ack = '{"route":"News feed","title":"Newsfeed","locale":"ar","localeTags":["ar","de-rde","de"]}'; Text = 'Newsfeed'; Opened = $true },
            @{ Name = 'english-before-german'; Requested = 'ar'; Ack = '{"route":"News feed","title":"News feed","locale":"ar","localeTags":["ar","en-rus","en","de"]}'; Text = 'News feed'; Opened = $true },
            @{ Name = 'unknown-secondary'; Requested = 'ar'; Ack = '{"route":"News feed","title":"News feed","locale":"ar","localeTags":["ar","zz-rzz","zz"]}'; Text = 'News feed'; Opened = $true },
            @{ Name = 'wrong-secondary-title'; Requested = 'ar'; Ack = '{"route":"News feed","title":"News feed","locale":"ar","localeTags":["ar","de-rde","de"]}'; Text = 'News feed'; Opened = $false },
            @{ Name = 'wrong-english-priority'; Requested = 'ar'; Ack = '{"route":"News feed","title":"Newsfeed","locale":"ar","localeTags":["ar","en-rus","en","de"]}'; Text = 'Newsfeed'; Opened = $false },
            @{ Name = 'missing-tags'; Requested = 'ar'; Ack = '{"route":"News feed","title":"News feed","locale":"ar"}'; Text = 'News feed'; Opened = $false },
            @{ Name = 'null-tags'; Requested = 'ar'; Ack = '{"route":"News feed","title":"News feed","locale":"ar","localeTags":null}'; Text = 'News feed'; Opened = $false },
            @{ Name = 'scalar-tags'; Requested = 'ar'; Ack = '{"route":"News feed","title":"News feed","locale":"ar","localeTags":"ar"}'; Text = 'News feed'; Opened = $false },
            @{ Name = 'empty-tags'; Requested = 'ar'; Ack = '{"route":"News feed","title":"News feed","locale":"ar","localeTags":[]}'; Text = 'News feed'; Opened = $false },
            @{ Name = 'nonscalar-tags'; Requested = 'ar'; Ack = '{"route":"News feed","title":"News feed","locale":"ar","localeTags":["ar",{"tag":"de"}]}'; Text = 'News feed'; Opened = $false },
            @{ Name = 'malformed-tags'; Requested = 'ar'; Ack = '{"route":"News feed","title":"News feed","locale":"ar","localeTags":["ar","not a tag"]}'; Text = 'News feed'; Opened = $false },
            @{ Name = 'foreign-first'; Requested = 'ar'; Ack = '{"route":"News feed","title":"Newsfeed","locale":"ar","localeTags":["de-rde","de","ar"]}'; Text = 'Newsfeed'; Opened = $false },
            @{ Name = 'wrong-locale'; Requested = 'ar'; Ack = '{"route":"News feed","title":"News feed","locale":"en-US","localeTags":["en-rus","en"]}'; Text = 'News feed'; Opened = $false },
            @{ Name = 'missing-locale'; Requested = 'ar'; Ack = '{"route":"News feed","title":"News feed","localeTags":["ar"]}'; Text = 'News feed'; Opened = $false },
            @{ Name = 'wrong-route'; Requested = 'ar'; Ack = '{"route":"Playback","title":"News feed","locale":"ar","localeTags":["ar"]}'; Text = 'News feed'; Opened = $false },
            @{ Name = 'wrong-title'; Requested = 'ar'; Ack = '{"route":"News feed","title":"Playback","locale":"ar","localeTags":["ar"]}'; Text = 'Playback'; Opened = $false },
            @{ Name = 'wrong-visible-title'; Requested = 'ar'; Ack = '{"route":"News feed","title":"News feed","locale":"ar","localeTags":["ar"]}'; Text = 'Playback'; Opened = $false },
            @{ Name = 'missing-ack'; Requested = 'ar'; Ack = ''; Text = 'News feed'; Opened = $false }
        )) {
            $script:screenText = @('Hushfacebook', $case.Text)
            $script:pageAck = New-TestSectionAck $case.Ack
            $fallbackPages = @()
            $fallbackError = $null
            try { $fallbackPages = @(Save-SettingsPages "dark-ar-$($case.Name)" $case.Requested) }
            catch { $fallbackError = $_.Exception.Message }
            Assert-That ($null -eq $fallbackError -and $fallbackPages.Count -eq 1 -and $fallbackPages[0].Opened -eq $case.Opened) `
                "Arabic runtime-locale case $($case.Name) did not validate the actual page (error: $fallbackError)."
        }
        [IO.File]::WriteAllText((Join-Path $translationTables 'ar.tsv'), "News feed`t`n")
        $script:screenText = @('Hushfacebook', 'News feed')
        $script:pageAck = New-TestSectionAck '{"route":"News feed","title":"News feed","locale":"ar","localeTags":["ar","de-rde","de"]}'
        Assert-That (@(Save-SettingsPages 'dark-ar-empty-row' 'ar')[0].Opened) 'An empty translation row refused the English fallback title under Arabic configuration.'
    } finally {
        $translationTables = $actualTranslationTables
        $script:pageAck = ''
    }
    $script:screenText = @()
    Assert-Throws { Save-SettingsPages 'dark-de' } 'Empty UI nodes passed.'

    # A sheet an earlier step left over the page a link opened (the S22 walks, 2026-10-27) gets up to
    # two Backs, each followed by the link, since the comment sheet's first Back only drops the
    # composer's focus. A reel route, proved by its activity, never gets the Back.
    & {
        $SettleSeconds = 0
        function Start-Sleep { param([int]$Seconds, [int]$Milliseconds) }
        function Invoke-Adb { $script:routeCalls.Add($args -join ' '); if ($args -ccontains 'start') { 'Status: ok' } }
        function Get-UiNodes { return @('node') }
        function Test-AuditDestination([string]$Route, [object[]]$Nodes) { $script:pageAnswers.Dequeue() }
        function Get-AuditReelProof { $script:pageAnswers.Dequeue() }
        $cases = @(
            @{ Reel = $false; Answers = @($true); Proved = $true; Backs = 0; Starts = 1; Name = 'a page proved at once' },
            @{ Reel = $false; Answers = @($false, $true); Proved = $true; Backs = 1; Starts = 2; Name = 'a covered page' },
            @{ Reel = $false; Answers = @($false, $false, $true); Proved = $true; Backs = 2; Starts = 3; Name = 'a focused comment sheet' },
            @{ Reel = $false; Answers = @($false, $false, $false); Proved = $false; Backs = 2; Starts = 3; Name = 'a page never reached' },
            @{ Reel = $true; Answers = @($false); Proved = $false; Backs = 0; Starts = 1; Name = 'a reel not proved' })
        foreach ($case in $cases) {
            $script:routeCalls = [Collections.Generic.List[string]]::new()
            $script:pageAnswers = [Collections.Generic.Queue[bool]]::new([bool[]]$case.Answers)
            $proved = Open-AuditRoute 'fb://notifications' $case.Reel
            $backs = @($script:routeCalls | Where-Object { $_ -ceq 'shell input keyevent KEYCODE_BACK' }).Count
            $starts = @($script:routeCalls | Where-Object { $_ -like 'shell am start *-d fb://notifications *' }).Count
            Assert-That ($proved -eq $case.Proved) "Open-AuditRoute gave the wrong answer for $($case.Name)."
            Assert-That ($backs -eq $case.Backs -and $starts -eq $case.Starts) "Open-AuditRoute sent $backs Back and $starts links for $($case.Name)."
            Assert-That ($script:auditRouteBacks -eq $case.Backs) "Open-AuditRoute reported $($script:auditRouteBacks) Backs for $($case.Name)."
            Assert-That ($script:pageAnswers.Count -eq 0) "Open-AuditRoute skipped a proof for $($case.Name)."
        }
    }

    # What a hold on a reel opened is closed at the hold, a Back at a time, until the reel's controls
    # show again, and never more than three Backs. An empty dump is a playing reel left alone.
    & {
        function Start-Sleep { param([int]$Seconds, [int]$Milliseconds) }
        function Invoke-Adb { $script:holdCalls.Add($args -join ' ') }
        function Get-UiNodes { $script:holdScreens.Dequeue() }
        $reel = [pscustomobject]@{ text = ''; 'content-desc' = 'Tap to show video controls' }
        $sheet = [pscustomobject]@{ text = 'Newest'; 'content-desc' = '' }
        $cases = @(
            @{ Screens = @(, @($reel)); Backs = 0; Name = 'a hold that opened nothing' },
            @{ Screens = @(, @()); Backs = 0; Name = 'a playing reel' },
            @{ Screens = @(@($sheet), @($reel)); Backs = 1; Name = 'the share sheet' },
            @{ Screens = @(@($sheet), @($sheet), @($reel)); Backs = 2; Name = 'a comment sheet with a focused composer' },
            @{ Screens = @(@($sheet), @($sheet), @($sheet)); Backs = 3; Name = 'a sheet that never closes' })
        foreach ($case in $cases) {
            $script:holdCalls = [Collections.Generic.List[string]]::new()
            $script:holdScreens = [Collections.Generic.Queue[object]]::new()
            foreach ($screen in $case.Screens) { $script:holdScreens.Enqueue($screen) }
            $backs = Close-AuditHoldCover
            $sent = @($script:holdCalls | Where-Object { $_ -ceq 'shell input keyevent KEYCODE_BACK' }).Count
            Assert-That ($backs -eq $case.Backs -and $sent -eq $case.Backs) "Close-AuditHoldCover sent $sent Back(s) and said $backs for $($case.Name)."
            Assert-That ($script:holdCalls.Count -eq $sent) "Close-AuditHoldCover sent something other than Back for $($case.Name)."
            Assert-That ($script:holdScreens.Count -eq 0) "Close-AuditHoldCover skipped a look for $($case.Name)."
        }
    }
} finally {
    # Only the exact uniquely-created test directory is removed.
    if ([IO.Path]::GetDirectoryName($testRoot.TrimEnd('\')) -ne [IO.Path]::GetTempPath().TrimEnd('\')) {
        throw 'Refusing cleanup outside the test temp directory.'
    }
    Remove-Item -LiteralPath $testRoot -Recurse -Force
}
if ($failures.Count) { throw ($failures -join "`n") }
Write-Host "[device-audit] $checks checks passed"
