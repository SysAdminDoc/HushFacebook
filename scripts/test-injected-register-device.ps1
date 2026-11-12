<#
.SYNOPSIS
    Exercise ADB failure cleanup for injected-register verification.

.DESCRIPTION
    Every stage of a device verify can fail, and each failure has to be named and still remove
    what was pushed. Two of them are about evidence rather than errors: Meta's build raises no
    verifier message, so a tally only counts once dex2oat has read a file of the pushed size, and
    dex2oat exits 0 while logging that the file it was given doesn't exist. The devices are shared,
    so the log is never cleared: the fake refuses a clear, puts another run's verifier and
    missing-file lines ahead of this run's mark and another run's mark after it, and can lose the
    mark to a rotated buffer that keeps only someone else's.
#>
[CmdletBinding()]
param([string]$Root)

$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
. (Join-Path $PSScriptRoot 'injected-register-device.ps1')
. (Join-Path $PSScriptRoot 'script-wiring.ps1')

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

# The helper reads the local file's size, so the fixture is a real file.
$fixture = Join-Path ([System.IO.Path]::GetTempPath()) ("hushfacebook-device-fixture-" + [guid]::NewGuid().ToString('N') + '.apk')
[System.IO.File]::WriteAllBytes($fixture, [byte[]](1..200 | ForEach-Object { $_ % 256 }))
$fixtureSize = (Get-Item -LiteralPath $fixture).Length
try {
    function New-FakeAdb {
        param(
            [string]$FailureStage,
            [int]$CleanupFailureNumber = 0,
            [int]$CleanupThrowNumber = 0,
            # A parameter, so GetNewClosure captures it: the closure sees this function's own
            # variables, not the script's, once the suite runs nested inside the push gate.
            [long]$Size = $fixtureSize
        )

        $state = [pscustomobject]@{
            Calls = [System.Collections.Generic.List[string]]::new()
            Mark = $null
            RemotePaths = [System.Collections.Generic.HashSet[string]]::new(
                [System.StringComparer]::Ordinal)
            CleanupCalls = 0
        }
        $invoker = {
            param([string]$Executable, [string[]]$Arguments)
            $line = $Arguments -join ' '
            [void]$state.Calls.Add($line)
            $exitCode = 0
            $output = @()
            $operation = $Arguments[2]

            if ($operation -eq 'push') {
                [void]$state.RemotePaths.Add($Arguments[4])
                if ($FailureStage -eq 'push') { $exitCode = 11 }
            } elseif ($operation -eq 'shell' -and $Arguments[3] -like '*&& mkdir -p*') {
                $directory = ($Arguments[3] -split 'mkdir -p ', 2)[1]
                [void]$state.RemotePaths.Add($directory)
                if ($FailureStage -eq 'setup') { $exitCode = 12 }
            } elseif ($operation -eq 'logcat') {
                throw "Host-side logcat can wait for a disconnected device: $line"
            } elseif ($operation -eq 'shell' -and $Arguments[3] -like 'log -t HushfacebookVerify *') {
                $state.Mark = $Arguments[3].Substring('log -t HushfacebookVerify '.Length)
                if ($FailureStage -eq 'mark') { $exitCode = 13 }
            } elseif ($operation -eq 'shell' -and $Arguments[3] -like 'dex2oat64*') {
                $read = if ($FailureStage -eq 'short-read') { 5 } else { $Size }
                $output = if ($FailureStage -eq 'dex2oat') { @('exit=14', "size=$read") } else { @('exit=0', "size=$read") }
            } elseif ($operation -eq 'shell' -and $Arguments[3] -ceq 'logcat') {
                if ($Arguments.Count -ne 5 -or $Arguments[4] -cne '-d') {
                    throw "The verifier must read exactly shell logcat -d: $line"
                }
                # Another run on the same device: its mark, a verifier line and a missing file, all
                # before this run's mark. A tally that counts them is wrong, and one that reads the
                # missing file refuses a good run.
                $older = @(
                    '10-02 12:00:00.000  4000  4000 I HushfacebookVerify: case-0123456789abcdef0123456789abcdef',
                    'I dex2oat64: Verification error in Lfixture/Stale;',
                    'I dex2oat64: Verification error in Lfixture/Host;',
                    "W dex2oat64: Skipping non-existent dex file '/data/local/tmp/hushfacebook-verify-other.apk'"
                )
                $marked = "10-02 12:00:01.000  4001  4001 I HushfacebookVerify: $($state.Mark)"
                # A run that started while this one was still going. Only its mark is this run's
                # business: a helper that takes it for this run's own loses this run's lines.
                $foreign = '10-02 12:00:02.000  4002  4002 I HushfacebookVerify: case-fedcba9876543210fedcba9876543210'
                if ($FailureStage -eq 'log-read-throw') {
                    throw 'fake ADB threw while reading logcat'
                } elseif ($FailureStage -eq 'log-read') {
                    $exitCode = 15
                } elseif ($FailureStage -eq 'offline-log') {
                    $exitCode = 1
                    $output = @('error: device offline')
                } elseif ($FailureStage -eq 'unread') {
                    $output = $older + $marked +
                        @("W dex2oat64: Skipping non-existent dex file '/data/local/tmp/hushfacebook-verify-case.apk'")
                } elseif ($FailureStage -eq 'rotated') {
                    # The buffer rolled over: this run's lines are there, its mark isn't, and a later
                    # run's mark is.
                    $output = @('I dex2oat64: Verification error in Lfixture/Host;', $foreign)
                } else {
                    # The mark twice, with an older line between: only what follows the last copy counts,
                    # and another run's mark among those lines doesn't end them.
                    $output = $older + $marked + @('I dex2oat64: Verification error in Lfixture/Stale;') + $marked + @(
                        'I dex2oat64: Verification error in Lfixture/Host;',
                        'I dex2oat64: Verification error in Lfixture/Host;',
                        $foreign,
                        'W dex2oat: VerifyError in Lfixture/Other;'
                    )
                }
            } elseif ($operation -eq 'shell' -and $Arguments[3] -like 'rm -rf *') {
                $state.CleanupCalls++
                $path = $Arguments[3].Substring('rm -rf '.Length)
                if ($FailureStage -eq 'offline-log') {
                    $exitCode = 1
                    $output = @('error: device offline')
                } elseif ($CleanupThrowNumber -eq $state.CleanupCalls) {
                    throw "fake ADB threw while removing $path"
                } elseif ($CleanupFailureNumber -eq $state.CleanupCalls) {
                    $exitCode = 91
                } else {
                    [void]$state.RemotePaths.Remove($path)
                }
            } else {
                throw "Unexpected fake ADB call through ${Executable}: $line"
            }
            [pscustomobject]@{ ExitCode = $exitCode; Output = $output }
        }.GetNewClosure()
        [pscustomobject]@{ State = $state; Invoker = $invoker }
    }

    function Assert-BothCleanupCalls {
        param($State, [string]$Context)
        $expected = @(
            '-s SERIAL shell rm -rf /data/local/tmp/hushfacebook-verify-case',
            '-s SERIAL shell rm -rf /data/local/tmp/hushfacebook-verify-case.apk'
        )
        foreach ($call in $expected) {
            Assert-True (@($State.Calls | Where-Object { $_ -eq $call }).Count -eq 1) `
                "$Context did not attempt cleanup exactly once: $call"
        }
    }

    $failures = [ordered]@{
        push = 'Could not push case to SERIAL (ADB exit 11).'
        setup = 'Could not prepare the verifier output directory for case on SERIAL (ADB exit 12).'
        mark = 'Could not mark the log on SERIAL before verifying case (ADB exit 13).'
        dex2oat = 'dex2oat on case exited 14.'
        'log-read' = 'Could not read logcat on SERIAL after verifying case (ADB exit 15).'
        'short-read' = "dex2oat on case read a file of 5 bytes on SERIAL, not the $fixtureSize bytes pushed."
        unread = "dex2oat did not read case on SERIAL: Skipping non-existent dex file '/data/local/tmp/hushfacebook-verify-case.apk'"
        rotated = "The log mark for case on SERIAL was gone from the log by the time it was read, so this run's lines cannot be told apart from older ones."
    }
    foreach ($stage in $failures.Keys) {
        $fake = New-FakeAdb -FailureStage $stage
        $caught = $null
        try {
            [void](Invoke-AndroidVerifierTally -Adb 'fake-adb' -Serial 'SERIAL' `
                -Local $fixture -Label 'case' -AdbInvoker $fake.Invoker)
        } catch {
            $caught = $_
        }
        Assert-True ($null -ne $caught) "$stage failure was accepted."
        Assert-True ($caught.Exception.Message -eq $failures[$stage]) `
            "$stage returned a different failure: $($caught.Exception.Message)"
        Assert-BothCleanupCalls -State $fake.State -Context $stage
        Assert-True ($fake.State.RemotePaths.Count -eq 0) "$stage left fake remote files behind."
    }

    # A host logcat read can wait for a disconnected transport instead of returning an error.
    # The shell read returns promptly; cleanup is attempted even while the device stays offline.
    $offline = New-FakeAdb -FailureStage 'offline-log'
    $warnings = @()
    $caught = $null
    try {
        [void](Invoke-AndroidVerifierTally -Adb 'fake-adb' -Serial 'SERIAL' `
            -Local $fixture -Label 'case' -AdbInvoker $offline.Invoker -WarningVariable warnings)
    } catch {
        $caught = $_
    }
    Assert-True ($caught.Exception.Message -eq 'Could not read logcat on SERIAL after verifying case (ADB exit 1). Output: error: device offline') `
        'A disconnected log read did not preserve its transport error.'
    Assert-True (@($offline.State.Calls | Where-Object { $_ -ceq '-s SERIAL shell logcat -d' }).Count -eq 1) `
        'The offline log read did not use the exact serial-scoped shell command.'
    Assert-BothCleanupCalls -State $offline.State -Context 'offline log retrieval'
    Assert-True ($offline.State.RemotePaths.Count -eq 2 -and
        (($warnings | ForEach-Object { "$_" }) -join "`n") -match 'cleanup also failed') `
        'Offline cleanup failures were hidden or claimed to remove the remote files.'

    $thrownFailure = New-FakeAdb -FailureStage 'log-read-throw'
    $caught = $null
    try {
        [void](Invoke-AndroidVerifierTally -Adb 'fake-adb' -Serial 'SERIAL' `
            -Local $fixture -Label 'case' -AdbInvoker $thrownFailure.Invoker)
    } catch {
        $caught = $_
    }
    Assert-True ($caught.Exception.Message -eq 'fake ADB threw while reading logcat') `
        'A thrown ADB error was replaced or accepted.'
    Assert-BothCleanupCalls -State $thrownFailure.State -Context 'thrown log retrieval failure'
    Assert-True ($thrownFailure.State.RemotePaths.Count -eq 0) `
        'A thrown ADB error left fake remote files behind.'

    $primaryWithCleanupFailure = New-FakeAdb -FailureStage 'mark' -CleanupThrowNumber 1
    $warnings = @()
    $caught = $null
    try {
        [void](Invoke-AndroidVerifierTally -Adb 'fake-adb' -Serial 'SERIAL' `
            -Local $fixture -Label 'case' -AdbInvoker $primaryWithCleanupFailure.Invoker `
            -WarningVariable warnings)
    } catch {
        $caught = $_
    }
    Assert-True ($caught.Exception.Message -eq $failures.mark) `
        'A cleanup failure replaced the original verification failure.'
    Assert-BothCleanupCalls -State $primaryWithCleanupFailure.State -Context 'secondary cleanup failure'
    Assert-True (($warnings | ForEach-Object { "$_" }) -join "`n" -match 'cleanup also failed') `
        'A cleanup failure beside a primary failure produced no secondary diagnostic.'

    $success = New-FakeAdb
    $tally = Invoke-AndroidVerifierTally -Adb 'fake-adb' -Serial 'SERIAL' `
        -Local $fixture -Label 'case' -AdbInvoker $success.Invoker
    Assert-True ($tally.Count -eq 2 -and $tally['Verification error in Lfixture/Host;'] -eq 2 -and
        $tally['VerifyError in Lfixture/Other;'] -eq 1) 'The successful fake verifier tally was wrong.'
    Assert-BothCleanupCalls -State $success.State -Context 'success'
    Assert-True ($success.State.RemotePaths.Count -eq 0) 'The successful run left fake remote files behind.'
    Assert-True ($success.State.Mark -match '^case-[0-9a-f]{32}$') "The run's mark doesn't name it: $($success.State.Mark)"
    $markCall = $success.State.Calls.IndexOf("-s SERIAL shell log -t HushfacebookVerify $($success.State.Mark)")
    $dexCall = @($success.State.Calls | ForEach-Object { $_ }).IndexOf(
        @($success.State.Calls | Where-Object { $_ -like '-s SERIAL shell dex2oat64*' })[0])
    Assert-True ($markCall -ge 0 -and $markCall -lt $dexCall) 'The log was not marked before dex2oat ran.'
    $second = New-FakeAdb
    [void](Invoke-AndroidVerifierTally -Adb 'fake-adb' -Serial 'SERIAL' -Local $fixture -Label 'case' -AdbInvoker $second.Invoker)
    Assert-True ($second.State.Mark -ne $success.State.Mark) 'Two runs used the same log mark.'

    # The helper's own text, with the run's lines taken back to the whole log, or cut at any
    # run's mark rather than this one's: the other runs' lines above have to fail both. A copy
    # that keeps the scoping has to pass.
    $helperText = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot 'injected-register-device.ps1'))
    $scoping = 'Select-VerifierRunLines -Lines $logResult.Output -Mark $mark'
    Assert-True ($helperText.Contains($scoping)) 'The helper no longer takes the run''s lines from the log.'
    $ownMark = ' -and $Lines[$i].Contains($Mark)'
    Assert-True ($helperText.Contains($ownMark)) 'The helper no longer looks for the run''s own mark.'
    $helperCopy = Join-Path ([System.IO.Path]::GetTempPath()) `
        ("hushfacebook-device-helper-" + [guid]::NewGuid().ToString('N') + '.ps1')
    try {
        foreach ($copy in @(@{ Name = 'the helper unchanged'; Text = $helperText; Passes = $true },
                @{ Name = 'the helper counting the whole log'; Text = $helperText.Replace($scoping, ', @($logResult.Output)'); Passes = $false },
                @{ Name = 'the helper taking any run''s mark'; Text = $helperText.Replace($ownMark, ''); Passes = $false })) {
            [System.IO.File]::WriteAllText($helperCopy, $copy.Text)
            $mutant = New-FakeAdb
            $passed = & {
                . $helperCopy
                try {
                    $copyTally = Invoke-AndroidVerifierTally -Adb 'fake-adb' -Serial 'SERIAL' `
                        -Local $fixture -Label 'case' -AdbInvoker $mutant.Invoker
                    $copyTally.Count -eq 2 -and $copyTally['Verification error in Lfixture/Host;'] -eq 2 -and
                        $copyTally['VerifyError in Lfixture/Other;'] -eq 1
                } catch { $false }
            }
            Assert-True ($passed -eq $copy.Passes) "$($copy.Name): the suite said $passed"
        }
    } finally {
        [System.IO.File]::Delete($helperCopy)
    }

    $successWithCleanupFailure = New-FakeAdb -CleanupFailureNumber 1
    $caught = $null
    try {
        [void](Invoke-AndroidVerifierTally -Adb 'fake-adb' -Serial 'SERIAL' `
            -Local $fixture -Label 'case' -AdbInvoker $successWithCleanupFailure.Invoker)
    } catch {
        $caught = $_
    }
    Assert-True ($null -ne $caught -and $caught.Exception.Message -like 'Verifier cleanup failed.*') `
        'A cleanup failure after successful verification was accepted.'
    Assert-BothCleanupCalls -State $successWithCleanupFailure.State -Context 'success cleanup failure'

    # As the verifier runs them (script-wiring.ps1), so help text, log lines, functions nothing
    # calls and dead branches can't stand in for the calls: the helper dot-sourced, and a tally of
    # the clean APK and one of the patched APK, compared with each other.
    $verifier = Join-Path $PSScriptRoot 'verify-injected-registers.ps1'
    $prePush = Join-Path $PSScriptRoot 'pre-push.ps1'
    Assert-True ((Test-DotSourcesFile $verifier 'injected-register-device.ps1') -and (Test-TalliesBothSides $verifier)) `
        'The device verifier does not use the cleanup helper for both sides.'
    # The line that adds the suite to the run, not the path list that only decides when to run it.
    Assert-True (Test-PushGateRunsSuite $prePush 'scripts/test-injected-register-device.ps1') `
        'The push gate does not run the verifier cleanup fixtures.'

    # The checks themselves, on copies with the wiring taken out in ways that leave its text
    # behind. Each copy has to fail the check it was made for, and an untouched copy has to pass.
    $wiringCopy = Join-Path ([System.IO.Path]::GetTempPath()) `
        ("hushfacebook-device-wiring-" + [guid]::NewGuid().ToString('N') + '.ps1')
    try {
        $verifierText = [System.IO.File]::ReadAllText($verifier)
        $prePushSource = [System.IO.File]::ReadAllText($prePush)
        $dotSourcesDevice = { param($Path) Test-DotSourcesFile $Path 'injected-register-device.ps1' }
        $talliesBothSides = { param($Path) Test-TalliesBothSides $Path }
        $gateRunsSuite = { param($Path) Test-PushGateRunsSuite $Path 'scripts/test-injected-register-device.ps1' }
        $deviceDotSource = { param($Node)
            $Node -is [System.Management.Automation.Language.CommandAst] -and
            $Node.InvocationOperator -eq [System.Management.Automation.Language.TokenKind]::Dot -and
            $Node.Extent.Text -like '*injected-register-device.ps1*' }
        # The device half's first statement, which the copies below put a statement in front of.
        $deviceHalfStart = { param($Node)
            $Node -is [System.Management.Automation.Language.CommandAst] -and
            $Node.Extent.Text -like '*running the device verifier on*' }
        $beforeDeviceHalf = { param([string]$Statement)
            Edit-ScriptNode $verifierText $deviceHalfStart { param($Text) "$Statement`n    $Text" }.GetNewClosure() }
        # And the other places the tally copies put a statement: before the compare, and after
        # the helpers are dot-sourced.
        $compareStatement = { param($Node)
            $Node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
            $Node.Extent.Text -like '$comparison = Compare-VerifierTallies*' }
        $beforeCompare = { param([string]$Statement)
            Edit-ScriptNode $verifierText $compareStatement { param($Text) "$Statement`n    $Text" }.GetNewClosure() }
        $afterHelpers = { param([string]$Statement)
            Edit-ScriptNode $verifierText $deviceDotSource { param($Text) "$Text`n$Statement" }.GetNewClosure() }
        $approveAll = '[pscustomobject]@{ Valid = $true; CleanTotal = 0; PatchedTotal = 0; Deltas = @() }'
        $wiringCases = @(
            @{ Name = 'the untouched verifier'; Check = $dotSourcesDevice; Expect = $true; Text = $verifierText }
            @{ Name = 'the untouched verifier'; Check = $talliesBothSides; Expect = $true; Text = $verifierText }
            @{ Name = 'the untouched pre-push.ps1'; Check = $gateRunsSuite; Expect = $true; Text = $prePushSource }
            @{ Name = 'both tallies taken of the patched APK'; Check = $talliesBothSides
                Text = Edit-ScriptNode $verifierText { param($Node)
                    $Node -is [System.Management.Automation.Language.CommandAst] -and
                    $Node.GetCommandName() -eq 'Invoke-AndroidVerifierTally' -and $Node.Extent.Text -like '*$cleanBase*'
                } { param($Text) $Text.Replace('$cleanBase', '$PatchedApk') } }
            @{ Name = 'both tallies taken into one variable'; Check = $talliesBothSides
                Text = Edit-ScriptNode $verifierText { param($Node)
                    $Node -is [System.Management.Automation.Language.IfStatementAst] -and
                    $Node.Clauses[0].Item1.Extent.Text -eq '$Serial' } { param($Text)
                    $Text.Replace('$cleanTally', '$tally').Replace('$patchedTally', '$tally') } }
            @{ Name = 'the clean tally written over with the patched one before the compare'; Check = $talliesBothSides
                Text = Edit-ScriptNode $verifierText { param($Node)
                    $Node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
                    $Node.Extent.Text -like '$comparison = Compare-VerifierTallies*' } { param($Text)
                    "`$cleanTally = `$patchedTally`n    $Text" } }
            @{ Name = 'Compare-VerifierTallies defined in the verifier itself'; Check = $talliesBothSides
                Text = Edit-ScriptNode $verifierText $deviceDotSource { param($Text)
                    "$Text`nfunction Compare-VerifierTallies { param(`$Clean, `$Patched)`n" +
                    "    [pscustomobject]@{ Valid = `$true; CleanTotal = 0; PatchedTotal = 0; Deltas = @() } }" } }
            @{ Name = 'Invoke-AndroidVerifierTally defined in the verifier itself'; Check = $talliesBothSides
                Text = Edit-ScriptNode $verifierText $deviceDotSource { param($Text)
                    "$Text`nfunction Invoke-AndroidVerifierTally { @{} }" } }
            @{ Name = 'the device half in a function nothing calls'; Check = $talliesBothSides
                Text = Edit-ScriptNode $verifierText { param($Node)
                    $Node -is [System.Management.Automation.Language.IfStatementAst] -and
                    $Node.Clauses[0].Item1.Extent.Text -eq '$Serial' } { param($Text) "function Invoke-DeviceHalf {`n$Text`n}" } }
            @{ Name = 'the device helper dot-sourced in a dead branch'; Check = $dotSourcesDevice
                Text = Edit-ScriptNode $verifierText $deviceDotSource { param($Text) "if (`$false) { $Text }" } }
            # Statements that end their block with the exit nested inside them. First in the device
            # half, each leaves every -Serial run passing with no tally taken. The copies expected
            # to pass keep those rules off statements that can still carry on: a catch that
            # finishes, a clause that matches and finishes, a break out of the do, and a return
            # out of the script block.
            @{ Name = 'the device half after try { exit 0 } finally { }'; Check = $talliesBothSides
                Text = & $beforeDeviceHalf 'try { exit 0 } finally { }' }
            @{ Name = 'the device half after try { exit 0 } catch { }'; Check = $talliesBothSides
                Text = & $beforeDeviceHalf 'try { exit 0 } catch { }' }
            @{ Name = "the device half after try { throw 'skip' } catch { exit 0 }"; Check = $talliesBothSides
                Text = & $beforeDeviceHalf "try { throw 'skip' } catch { exit 0 }" }
            @{ Name = 'the device half after try { } finally { exit 0 }'; Check = $talliesBothSides
                Text = & $beforeDeviceHalf 'try { } finally { exit 0 }' }
            @{ Name = "the device half after try { throw 'skip' } catch { }"; Check = $talliesBothSides; Expect = $true
                Text = & $beforeDeviceHalf "try { throw 'skip' } catch { }" }
            @{ Name = 'the device half after switch (1) { default { exit 0 } }'; Check = $talliesBothSides
                Text = & $beforeDeviceHalf 'switch (1) { default { exit 0 } }' }
            @{ Name = 'the device half after switch (1) { 1 { exit 0 } }'; Check = $talliesBothSides
                Text = & $beforeDeviceHalf 'switch (1) { 1 { exit 0 } }' }
            @{ Name = "the device half after switch (2) { 1 { 'one' } default { exit 0 } }"; Check = $talliesBothSides
                Text = & $beforeDeviceHalf "switch (2) { 1 { 'one' } default { exit 0 } }" }
            @{ Name = "the device half after switch (1) { 1 { 'one' } default { exit 0 } }"; Check = $talliesBothSides; Expect = $true
                Text = & $beforeDeviceHalf "switch (1) { 1 { 'one' } default { exit 0 } }" }
            @{ Name = 'the device half after a switch with no default'; Check = $talliesBothSides; Expect = $true
                Text = & $beforeDeviceHalf 'switch (1) { 2 { exit 0 } }' }
            @{ Name = 'the device half after a switch a break can leave'; Check = $talliesBothSides; Expect = $true
                Text = & $beforeDeviceHalf 'switch (1) { default { if ($Serial) { break }; exit 0 } }' }
            @{ Name = 'the device half after a switch over @()'; Check = $talliesBothSides; Expect = $true
                Text = & $beforeDeviceHalf 'switch (@()) { default { exit 0 } }' }
            @{ Name = 'the device half after do { exit 0 } while ($false)'; Check = $talliesBothSides
                Text = & $beforeDeviceHalf 'do { exit 0 } while ($false)' }
            @{ Name = 'the device half after do { exit 0 } until ($true)'; Check = $talliesBothSides
                Text = & $beforeDeviceHalf 'do { exit 0 } until ($true)' }
            @{ Name = 'the device half after a do a break can leave'; Check = $talliesBothSides; Expect = $true
                Text = & $beforeDeviceHalf 'do { if ($Serial) { break }; exit 0 } while ($false)' }
            @{ Name = 'the device half after & { exit 0 }'; Check = $talliesBothSides
                Text = & $beforeDeviceHalf '& { exit 0 }' }
            @{ Name = "the device half after . { throw 'skip' }"; Check = $talliesBothSides
                Text = & $beforeDeviceHalf ". { throw 'skip' }" }
            @{ Name = 'the device half after & { begin { exit 0 } }'; Check = $talliesBothSides
                Text = & $beforeDeviceHalf '& { begin { exit 0 } }' }
            @{ Name = 'the device half after & { process { exit 0 } }'; Check = $talliesBothSides
                Text = & $beforeDeviceHalf '& { process { exit 0 } }' }
            @{ Name = 'the device half after @() | & { process { exit 0 } }, which runs no process block'
                Check = $talliesBothSides; Expect = $true
                Text = & $beforeDeviceHalf '@() | & { process { exit 0 } }' }
            @{ Name = 'the device half after a script block a return can leave'; Check = $talliesBothSides; Expect = $true
                Text = & $beforeDeviceHalf '& { if ($Serial) { return }; exit 0 }' }
            # A tally input or a tally written in a way that isn't an assignment to the tally
            # variable, or a helper replaced without a function statement. Each leaves every
            # -Serial run comparing a build with itself, or not comparing at all. The copies
            # expected to pass write other names the same ways.
            @{ Name = 'the clean input set to the patched APK in the device half'; Check = $talliesBothSides
                Text = & $beforeDeviceHalf '$cleanBase = $PatchedApk' }
            @{ Name = 'the patched input set to the clean one in the device half'; Check = $talliesBothSides
                Text = & $beforeDeviceHalf '$PatchedApk = $cleanBase' }
            @{ Name = 'the clean input taken from the patched APK instead of Get-BaseApk'; Check = $talliesBothSides
                Text = Edit-ScriptNode $verifierText { param($Node)
                    $Node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
                    $Node.Extent.Text -like '$cleanBase = Get-BaseApk*' } { param($Text) '$cleanBase = $PatchedApk' } }
            @{ Name = 'the clean input taken again, from the patched APK, before the DexDiff run'; Check = $talliesBothSides
                Text = Edit-ScriptNode $verifierText { param($Node)
                    $Node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
                    $Node.Extent.Text -eq '$diff = Invoke-DexDiff' } { param($Text)
                    "`$cleanBase = Get-BaseApk -Apk `$PatchedApk -Destination (Join-Path `$work 'again.apk')`n$Text" } }
            @{ Name = 'the clean input taken from a command other than Get-BaseApk'; Check = $talliesBothSides
                Text = Edit-ScriptNode $verifierText { param($Node)
                    $Node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
                    $Node.Extent.Text -like '$cleanBase = Get-BaseApk*' } { param($Text) '$cleanBase = Write-Output $PatchedApk' } }
            @{ Name = 'the patched input set to the clean one by a function the device half calls'; Check = $talliesBothSides
                Text = Edit-ScriptNode (& $afterHelpers 'function Reset-Input { $script:PatchedApk = $script:cleanBase }') `
                    $deviceHalfStart { param($Text) "Reset-Input`n    $Text" } }
            @{ Name = 'the clean input written with -OutVariable in the device half'; Check = $talliesBothSides
                Text = & $beforeDeviceHalf 'Write-Output $PatchedApk -OutVariable cleanBase | Out-Null' }
            @{ Name = 'the clean tally written over with Set-Variable'; Check = $talliesBothSides
                Text = & $beforeCompare 'Set-Variable -Name cleanTally -Value $patchedTally' }
            @{ Name = 'the clean tally written over with sv'; Check = $talliesBothSides
                Text = & $beforeCompare 'sv cleanTally $patchedTally' }
            @{ Name = 'the clean tally written over with Microsoft.PowerShell.Utility\Set-Variable'; Check = $talliesBothSides
                Text = & $beforeCompare 'Microsoft.PowerShell.Utility\Set-Variable -Name cleanTally -Value $patchedTally' }
            @{ Name = 'the clean tally written over with New-Variable -Force'; Check = $talliesBothSides
                Text = & $beforeCompare 'New-Variable -Name cleanTally -Value $patchedTally -Force' }
            @{ Name = 'the clean tally emptied with Clear-Variable'; Check = $talliesBothSides
                Text = & $beforeCompare 'Clear-Variable -Name cleanTally' }
            @{ Name = 'the clean tally written over through the variable: drive'; Check = $talliesBothSides
                Text = & $beforeCompare 'Set-Item -Path variable:cleanTally -Value $patchedTally' }
            @{ Name = 'the clean tally written over by a foreach over the patched one'; Check = $talliesBothSides
                Text = & $beforeCompare 'foreach ($cleanTally in @($patchedTally)) { }' }
            @{ Name = 'a tally written over with Set-Variable of a name built at run time'; Check = $talliesBothSides
                Text = & $beforeCompare "Set-Variable -Name ('clean' + 'Tally') -Value `$patchedTally" }
            @{ Name = 'Compare-VerifierTallies replaced through ${function:}'; Check = $talliesBothSides
                Text = & $afterHelpers "`${function:Compare-VerifierTallies} = { param(`$Clean, `$Patched) $approveAll }" }
            @{ Name = 'Compare-VerifierTallies replaced with Set-Item function:'; Check = $talliesBothSides
                Text = & $afterHelpers "Set-Item -Path function:Compare-VerifierTallies -Value { param(`$Clean, `$Patched) $approveAll }" }
            @{ Name = 'Invoke-AndroidVerifierTally replaced with New-Item function:'; Check = $talliesBothSides
                Text = & $afterHelpers 'New-Item -Path function: -Name Invoke-AndroidVerifierTally -Value { @{} } -Force | Out-Null' }
            @{ Name = 'Compare-VerifierTallies defined in the verifier as script:Compare-VerifierTallies'; Check = $talliesBothSides
                Text = & $afterHelpers "function script:Compare-VerifierTallies { param(`$Clean, `$Patched) $approveAll }" }
            @{ Name = 'the clean tally written over through $script:cleanTally'; Check = $talliesBothSides
                Text = & $beforeCompare '$script:cleanTally = $patchedTally' }
            @{ Name = 'Compare-VerifierTallies made an alias of a function of the script'; Check = $talliesBothSides
                Text = & $afterHelpers "function Approve-Tallies { param(`$Clean, `$Patched) $approveAll }`nSet-Alias -Name Compare-VerifierTallies -Value Approve-Tallies" }
            @{ Name = 'another variable set with Set-Variable before the compare'; Check = $talliesBothSides; Expect = $true
                Text = & $beforeCompare 'Set-Variable -Name note -Value $patchedTally' }
            @{ Name = 'another function set up through the function: drive'; Check = $talliesBothSides; Expect = $true
                Text = & $afterHelpers "Set-Item -Path function:Show-Note -Value { 'note' }" }
            @{ Name = 'an alias of another name'; Check = $talliesBothSides; Expect = $true
                Text = & $afterHelpers 'Set-Alias -Name Show-Note -Value Write-Host' }
            @{ Name = 'the suite line in a block comment'; Check = $gateRunsSuite
                Text = Edit-ScriptNode $prePushSource { param($Node)
                    $Node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
                    $Node.Left.Extent.Text -eq '$suites' -and
                    $Node.Extent.Text -like "*'scripts/test-injected-register-device.ps1'*" } { param($Text) "<#`n$Text`n#>" } }
        )
        $wiringFailures = @()
        foreach ($case in $wiringCases) {
            [System.IO.File]::WriteAllText($wiringCopy, $case.Text)
            $expect = $case.ContainsKey('Expect') -and $case.Expect
            if ((& $case.Check $wiringCopy) -ne $expect) {
                $wiringFailures += "$($case.Name): the check said $(-not $expect)"
            }
        }
        if ($wiringFailures.Count -ne 0) { throw ("The wiring checks misjudged:`n" + ($wiringFailures -join "`n")) }
    } finally {
        [System.IO.File]::Delete($wiringCopy)
    }

    # The verifier receipt names the bundle by the identity in the patched APK's own dex: the one
    # string getPatchesBuildIdentity returns, in whichever classes*.dex it sits. One outside a dex,
    # or cut short, isn't the APK's, and two different ones can't be told apart.
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $identityCase = Join-Path ([System.IO.Path]::GetTempPath()) ("hushfacebook-identity-" + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $identityCase | Out-Null
    $one = 'sha256=' + ('a' * 64) + '; source=clean:' + ('b' * 40) + '; tree=' + ('c' * 40) + '; inputs=' + ('d' * 64)
    $other = 'sha256=' + ('e' * 64) + '; source=unknown; inputs=' + ('f' * 64)
    try {
        function New-IdentityApk([string]$Name, [System.Collections.IDictionary]$Entries) {
            $path = Join-Path $identityCase $Name
            $archive = [System.IO.Compression.ZipFile]::Open($path, [System.IO.Compression.ZipArchiveMode]::Create)
            try {
                foreach ($entryName in $Entries.Keys) {
                    $stream = $archive.CreateEntry($entryName).Open()
                    try {
                        # A dex string among other bytes: its length, its bytes and a zero.
                        $bytes = [byte[]](@(0x64, 0x65, 0x78, 0x0a, 0x00, 0xf6, 0x01) +
                            [System.Text.Encoding]::ASCII.GetBytes($Entries[$entryName]) + @(0x00, 0xff, 0x10))
                        $stream.Write($bytes, 0, $bytes.Length)
                    } finally { $stream.Dispose() }
                }
            } finally { $archive.Dispose() }
            return $path
        }
        $carries = New-IdentityApk 'carries.apk' ([ordered]@{ 'classes.dex' = 'nothing here'; 'classes7.dex' = "x$one"; 'resources.arsc' = $other })
        Assert-True ((Get-ApkPatchBuild -Apk $carries) -ceq $one) "The identity wasn't read out of classes7.dex: $(Get-ApkPatchBuild -Apk $carries)"
        $twice = New-IdentityApk 'twice.apk' ([ordered]@{ 'classes.dex' = $one; 'classes2.dex' = $one })
        Assert-True ((Get-ApkPatchBuild -Apk $twice) -ceq $one) 'The same identity in two dex files read as two.'
        $none = New-IdentityApk 'none.apk' ([ordered]@{ 'classes.dex' = 'sha256=' + ('a' * 63) + '; source=unknown; inputs=' + ('d' * 64)
            'lib/classes.dex' = $one; 'assets/identity.txt' = $one })
        Assert-True ($null -eq (Get-ApkPatchBuild -Apk $none)) 'An identity outside the APK''s own dex, or a cut one, was read as its own.'
        $two = New-IdentityApk 'two.apk' ([ordered]@{ 'classes.dex' = $one; 'classes2.dex' = $other })
        $named = $null
        try { Get-ApkPatchBuild -Apk $two | Out-Null } catch { $named = $_.Exception.Message }
        Assert-True ($named -like '*2 patch build identities*') "An APK with two bundles' identities was read as one of them: $named"
    } finally {
        Remove-Item -LiteralPath $identityCase -Recurse -Force -ErrorAction SilentlyContinue
    }

    # The receipt is a PASS only when both halves passed and no message differs, and it keeps each
    # differing message word for word, through JSON and back.
    $same = [pscustomobject]@{ Valid = $true; CleanTotal = 2; PatchedTotal = 2; Deltas = @() }
    $passing = New-VerifierReceipt -Serial 'TESTPHONE01' -Device 'SM-S908U1' -Facebook '587.0.0.39.55' -PatchBuild $one `
        -PatchedApkSha256 ('0' * 64) -StaticPassed $true -Comparison $same -Finished '2026-10-22T05:00:00Z'
    $passingRead = $passing | ConvertTo-Json -Depth 5 | ConvertFrom-Json
    Assert-True ($passingRead.result -ceq 'PASS' -and $passingRead.static -ceq 'PASS' -and $passingRead.emulator -eq $false -and
        $passingRead.patchBuild -ceq $one -and $passingRead.cleanMessages -eq 2 -and $passingRead.patchedMessages -eq 2 -and
        $passingRead.deltas -is [array] -and $passingRead.deltas.Count -eq 0 -and $passing.finished -ceq '2026-10-22T05:00:00Z') `
        "A clean run's receipt didn't read back as a PASS: $($passing | ConvertTo-Json -Depth 5 -Compress)"
    $crash = 'Verification error in X.BZ3 X.BZ1.A0G'
    $extra = [pscustomobject]@{ Valid = $false; CleanTotal = 0; PatchedTotal = 1
        Deltas = @([pscustomobject]@{ Kind = 'extra'; Message = $crash; Clean = 0; Patched = 1; Difference = 1 }) }
    $failing = New-VerifierReceipt -Serial 'emulator-5554' -Device 'sdk_gphone64_x86_64' -PatchBuild $one -StaticPassed $true -Comparison $extra
    $failingRead = $failing | ConvertTo-Json -Depth 5 | ConvertFrom-Json
    Assert-True ($failingRead.result -ceq 'FAIL' -and $failingRead.emulator -eq $true -and $failingRead.deltas -is [array] -and
        $failingRead.deltas.Count -eq 1 -and $failingRead.deltas[0].kind -ceq 'extra' -and $failingRead.deltas[0].message -ceq $crash -and
        $failingRead.deltas[0].clean -eq 0 -and $failingRead.deltas[0].patched -eq 1) `
        "A run with an extra verifier message wasn't written as a FAIL naming it: $($failing | ConvertTo-Json -Depth 5 -Compress)"
    $staticFailed = New-VerifierReceipt -Serial 'TESTPHONE01' -Device 'SM-S908U1' -PatchBuild $one -StaticPassed $false -Comparison $same
    Assert-True ($staticFailed.result -ceq 'FAIL' -and $staticFailed.static -ceq 'FAIL') 'A run whose static half failed was written as a PASS.'
    # A comparison that calls the tallies different without listing a difference isn't a PASS either.
    $unlisted = [pscustomobject]@{ Valid = $false; CleanTotal = 0; PatchedTotal = 0; Deltas = @() }
    Assert-True ((New-VerifierReceipt -Serial 'TESTPHONE01' -Device 'SM-S908U1' -PatchBuild $one -StaticPassed $true `
        -Comparison $unlisted).result -ceq 'FAIL') 'A comparison that failed with no difference listed was written as a PASS.'

} finally {
    [System.IO.File]::Delete($fixture)
}

$global:LASTEXITCODE = 0
Write-Host '[scripts] injected-register device cleanup contracts passed'
