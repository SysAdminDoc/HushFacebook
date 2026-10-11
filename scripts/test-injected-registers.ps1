<#
.SYNOPSIS
    Exercise DexDiff.java against dex files that break one check each.

.DESCRIPTION
    BadDexFixture.java writes a clean host, a patched build of it that passes every check, and one
    patched build per check that breaks that check and nothing else: a branch into an instruction,
    a branch to itself, a switch case into an instruction, a switch pointed at the other kind of
    table, a branch or switch case onto a payload (a packed or sparse switch's, or
    fill-array-data's) or a move-result, flow that falls into a payload, starts at one or runs off
    the end of the code, an invoke with too few registers, a wide argument split across two
    registers, the static off-by-one, the upper half of a wide parameter read as an object, a
    narrow constant read as a long and the reverse (the AMOLED sweep's bug on 580), either half of
    a live long overwritten and the other half still read, a broken pair or a narrow constant on
    one arm of a branch or on a loop's back edge, either half of a pair broken on one arm moved
    where the arms meet, a zero on one arm read as a long, a conflict (an object on one arm, an
    int on the other) read by each instruction and in each register that takes a value, or read
    through a copy, a wide move of a conflict, a long and a lone upper half tested against zero,
    an int and an object tested for equality in either order, a move-result the patch separated
    from its invoke or left behind when Morphe's inline compiler dropped a plain invoke of a
    parameter past v15 (Hide tab badges in an 18-register writer), bad try ranges and handlers (a handler at a switch or array payload among
    them), a move-exception the method's entry reaches, the one feed guard doubled, moved or
    missing, the reels hook deleted from the pre-EOF injector or put after a branch, the showcase
    stub left unfilled, calling another class, or calling a class that isn't the only one
    answering its type name, Clean up Reels' hook deleted from Facebook's Follow check or put
    after a branch there, Use the phone's emoji's hooks deleted from Facebook's emoji typeface
    provider and from its maker of emoji picture addresses, or put after a branch there, Open
    Messenger from the top bar's hooks deleted from the Messenger icon's tap and from its button
    handler, or put after a branch there, Turn off double tap to like's hooks deleted from the reel
    like helper's like and from the feed attachment's onDoubleTap, or put after a branch there, Keep
    the reel speed's hook deleted from the Reels menu's speed toast, or put after a branch there, and
    the GenAI reel stub left unfilled, filled with a call that stays in the extension, or calling
    Facebook's finder only after it has returned. Each start-call hook is also put first in a method
    holding part of what its rule picks by (onPause, another method naming both surfaces, a method
    holding the emoji provider's log tag alone, an instance method holding the emoji pictures' base
    address, a method of the tap's shape holding one entry point, a method of another shape holding
    "long_press", a static method holding the like's trace, a static method holding "translationY",
    an instance method holding the toast's selector name, another method of the jewel count's
    shape), and one rule is given two methods to choose from; all eleven fail naming the method the
    rule picks. The Stories tray's count call is left out of one tray class, made twice in the
    other's count, or made in another of its methods, and each fails its count-call rule saying
    which. The Follow hook is also put first in that other method as
    well as in the check. A register out of range fails as its own finding:
    named by a helper added to a host class, as the upper half of a long read from the last
    register, as a long an extension method writes there, and in the feed guard. Each of the five
    ShortcutManager calls the settings patch sends to the
    extension is left in Facebook's code by a build of its own, which has to fail that call's no-call
    rule and no other, and the contract file may hold no no-call rule without such a build. The
    call that gives the Facebook logo its touch listener is left as Facebook makes it, the stand-in
    is sent in place of the container's call instead, made on the container's register, sent
    twice, sent in another top bar method holding the logo's first trace section with the builder
    left alone, or sent there as well as in the builder, and a second builder answers the rule, a
    build each, and each has to fail the logo's next-call rule for its own reason; the contract
    file may hold no other next-call rule. The call that hands the Reels viewer's batch of watched
    reels to its executor is left as Facebook makes it, the watch-history stand-in is sent in
    another batcher method holding the mutation's name with the flush left alone, sent there as
    well as in the flush, sent twice, sent with Facebook's call left beside it, or handed the two
    registers the wrong way round, and a second flush answers the rule, a build each, each failing
    the sole-call rule for its own reason; a clean build whose flush makes no such call fails the
    good build's stand-in for want of one to stand in for, and the contract file may hold no other
    sole-call rule. The feed guard's call in the runnable that swaps an edge into the feed is left
    out, sent to another method of the runnable holding the first size of its log line with run()
    left alone, sent there as well as in run(), sent twice, and a second run() answers the rule, a
    build each, each failing the once-call rule for its own reason. Each of the tab links patch's
    three calls is left out, a build each, failing its own once-call rule, and so is Hide read
    receipts' call in Mailbox's mark-read, which is also sent twice; the contract file may hold no
    other once-call rules. Each of the sources filter's four story stubs is left unfilled, and Hide
    typing indicator's call is left out of Mailbox's typing setter or put after a branch there, a
    build each.
    The good build carries the joins, copies and reads ART accepts, a zero tested against
    an object among them, so a check made stricter still has to pass them. Each bad build has to
    fail with findings of its own category only, so a check that fires for the wrong reason fails
    here too. Removed methods and DEX entries, the removal allowlist, and the device tally
    comparison are held to what they did before.
    verify-injected-registers.ps1 also runs end to end with stand-in tools, and its wiring is read
    through the parser (script-wiring.ps1), with the wiring checks themselves tried on copies that
    drop the calls but keep their text, at least one for each rule of what a script can't reach.
#>
[CmdletBinding()]
param(
    [string]$Root,
    [string]$Java,
    [string]$DesktopJar
)

$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
. (Join-Path $PSScriptRoot 'Resolve-Java.ps1')
. (Join-Path $PSScriptRoot 'injected-register-contracts.ps1')
. (Join-Path $PSScriptRoot 'common.ps1')
. (Join-Path $PSScriptRoot 'script-wiring.ps1')

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

# Native calls run with Continue: Windows PowerShell 5.1, which the push hook uses when pwsh isn't
# on git's PATH, turns any line a program writes to stderr into a terminating error under Stop,
# and several cases here are meant to make a program fail. The exit code is what gets judged.
function Invoke-Checked {
    param([string]$Program, [string[]]$Arguments, [string]$Description)
    $ErrorActionPreference = 'Continue'
    $output = @(& $Program @Arguments 2>&1 | ForEach-Object { "$_" })
    if ($LASTEXITCODE -ne 0) {
        throw "$Description exited $LASTEXITCODE.`n$($output -join "`n")"
    }
}

function New-DexApk {
    param([string]$Name, [System.Collections.IDictionary]$Entries)
    $path = Join-Path $caseRoot "$Name.apk"
    $archive = [System.IO.Compression.ZipFile]::Open(
        $path, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($entry in $Entries.GetEnumerator()) {
            [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                $archive, $entry.Value, $entry.Key,
                [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
        }
    } finally {
        $archive.Dispose()
    }
    return $path
}

function Invoke-DexDiff {
    param([string]$Clean, [string]$Patched, [string]$Allowlist, [string]$Name, [string]$Contracts, [string]$Base)
    $report = Join-Path $caseRoot "$Name-report.txt"
    $arguments = @('-Xmx1g', '-cp', $classPath, 'DexDiff', $Clean, $Patched, $report, $Allowlist)
    if ($Contracts) { $arguments += $Contracts }
    if ($Base) { $arguments += $Base }
    $ErrorActionPreference = 'Continue'
    $global:LASTEXITCODE = 0
    $output = @(& $Java @arguments 2>&1 | ForEach-Object { "$_" })
    [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = $output; Report = $report }
}

# The category of every finding line, and every FAIL line at all, so a fixture that fails for a
# second reason is caught as well as one that fails for none.
function Get-Findings {
    param($Result)
    $fails = @($Result.Output | Where-Object { $_ -like '`[diff`] FAIL*' })
    $categories = @($fails | ForEach-Object {
        if ($_ -match '^\[diff\] FAIL: (register|branch|invoke|parameter|width|try|result|contract): ') { $Matches[1] } else { '?' }
    })
    [pscustomobject]@{ Fails = $fails; Categories = $categories }
}

$equal = Compare-VerifierTallies -Clean @{ lock = 2; class = 1 } -Patched @{ class = 1; lock = 2 }
Assert-True $equal.Valid 'Equal verifier message multisets were rejected.'
$extra = Compare-VerifierTallies -Clean @{ lock = 2 } -Patched @{ lock = 2; verify = 1 }
Assert-True (-not $extra.Valid -and $extra.Deltas.Count -eq 1 -and `
    $extra.Deltas[0].Kind -eq 'extra') 'An extra verifier message was accepted.'
$missing = Compare-VerifierTallies -Clean @{ lock = 2; verify = 1 } -Patched @{ lock = 2 }
Assert-True (-not $missing.Valid -and $missing.Deltas.Count -eq 1 -and `
    $missing.Deltas[0].Kind -eq 'missing') 'A missing verifier message was accepted.'
$reduced = Compare-VerifierTallies -Clean @{ lock = 3 } -Patched @{ lock = 1 }
Assert-True (-not $reduced.Valid -and $reduced.Deltas[0].Difference -eq 2 -and `
    $reduced.Deltas[0].Kind -eq 'missing') 'A reduced verifier message count was accepted.'
$caseChanged = Compare-VerifierTallies -Clean @{ VerifyError = 1 } -Patched @{ verifyerror = 1 }
Assert-True (-not $caseChanged.Valid -and $caseChanged.Deltas.Count -eq 2) `
    'A case-changed verifier message was treated as the same message.'
# Meta's 580 raises no verifier message on the emulator, and a patched build that adds none is the
# pass. The device helper proves each run read its file, so the tallies don't have to.
$bothEmpty = Compare-VerifierTallies -Clean @{} -Patched @{}
Assert-True ($bothEmpty.Valid -and $bothEmpty.Deltas.Count -eq 0) 'Two empty verifier tallies were rejected.'
$newError = Compare-VerifierTallies -Clean @{} -Patched @{ 'Verification error in void X.jMJ.<clinit>()' = 1 }
Assert-True (-not $newError.Valid -and $newError.Deltas[0].Kind -eq 'extra') `
    'A verifier error the clean build does not raise was accepted.'

# The calls themselves, as the scripts run them (script-wiring.ps1). Help text, comments, log
# lines, functions nothing calls and dead branches can all name these files too.
$verifier = Join-Path $PSScriptRoot 'verify-injected-registers.ps1'
$allPatches = Join-Path $PSScriptRoot 'verify-all-patches.ps1'
$prePush = Join-Path $PSScriptRoot 'pre-push.ps1'
Assert-True ((Test-DotSourcesFile $verifier 'injected-register-contracts.ps1') -and (Test-TalliesBothSides $verifier)) `
    'The device verifier does not use the exact tally comparison contract.'
Assert-True (Test-RunsDexDiffWithContracts $verifier) 'The verifier does not hold the patched APK to the mutation contracts.'
Assert-True (Test-VerifiesWhatItPatched $allPatches) `
    'verify-all-patches.ps1 does not run the structural checks on the APK it patched.'
Assert-True (Test-PushGateRunsSuite $prePush 'scripts/test-injected-registers.ps1') `
    'The push gate does not run the injected-register fixture test.'
Assert-True (Test-PushGatePatchesFixtures $prePush) `
    'The push gate does not apply the bundle to the Facebook fixtures and check what it injected.'
Assert-True ((Get-Content -LiteralPath $prePush -Raw) -notmatch 'so it has nothing to run there') `
    'The push gate still skips a suite it expects when the file is missing.'

# The checks themselves, on copies of those scripts with the wiring taken out in the ways that
# leave its text behind. Each copy has to fail the check it was made for, and an untouched copy
# has to pass it, so a check can't pass here by failing everything.
$wiringCopies = [System.IO.Path]::GetFullPath((Join-Path ([System.IO.Path]::GetTempPath()) `
    ("hushfacebook-wiring-" + [guid]::NewGuid().ToString('N'))))
try {
    New-Item -ItemType Directory -Path $wiringCopies | Out-Null
    $verifierText = [System.IO.File]::ReadAllText($verifier)
    $allPatchesText = [System.IO.File]::ReadAllText($allPatches)
    $prePushSource = [System.IO.File]::ReadAllText($prePush)
    $runsDexDiff = { param($Path) Test-RunsDexDiffWithContracts $Path }
    $dotSourcesContracts = { param($Path) Test-DotSourcesFile $Path 'injected-register-contracts.ps1' }
    $talliesBothSides = { param($Path) Test-TalliesBothSides $Path }
    $verifiesPatched = { param($Path) Test-VerifiesWhatItPatched $Path }
    $gateRunsSuite = { param($Path) Test-PushGateRunsSuite $Path 'scripts/test-injected-registers.ps1' }
    $gatePatchesFixtures = { param($Path) Test-PushGatePatchesFixtures $Path }
    # The pre-push call that runs verify-all-patches.ps1 on a fixture, and the one call to the
    # function holding it.
    $fixtureRun = { param($Node)
        $Node -is [System.Management.Automation.Language.CommandAst] -and
        $Node.GetCommandName() -eq 'Invoke-CommitScript' -and $Node.Extent.Text -like '*verify-all-patches.ps1*' }
    $fixtureCheckCall = { param($Node)
        $Node -is [System.Management.Automation.Language.CommandAst] -and
        $Node.GetCommandName() -eq 'Invoke-PatchedFixtureCheck' }
    # The nodes the copies take the wiring out around: the & $Java DexDiff call in Invoke-DexDiff,
    # the call to Invoke-DexDiff, the contracts helper's dot-source, verify-all-patches' verifier
    # call, and the pre-push line that runs this suite.
    $dexDiffCall = { param($Node)
        $Node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
        $Node.Extent.Text -like '*DexDiff.java*' }
    $dexDiffRun = { param($Node)
        $Node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
        $Node.Extent.Text -eq '$diff = Invoke-DexDiff' }
    $contractsDotSource = { param($Node)
        $Node -is [System.Management.Automation.Language.CommandAst] -and
        $Node.InvocationOperator -eq [System.Management.Automation.Language.TokenKind]::Dot -and
        $Node.Extent.Text -like '*injected-register-contracts.ps1*' }
    $verifierCall = { param($Node)
        $Node -is [System.Management.Automation.Language.CommandAst] -and
        $Node.Extent.Text -like '*verify-injected-registers.ps1*' }
    $suiteLine = { param($Node)
        $Node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
        $Node.Left.Extent.Text -eq '$suites' -and
        $Node.Extent.Text -like "*'scripts/test-injected-registers.ps1'*" }
    $dexDiffFunction = { param($Node)
        $Node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $Node.Name -eq 'Invoke-DexDiff' }
    $contractsArgument = "(Join-Path `$PSScriptRoot 'injected-mutation-contracts.txt')"
    # The DexDiff call without the contracts, and the old call kept after it between -Open and
    # -Close: code that can't run for the copies expected to fail, code that can for the rest.
    $parkOldCall = { param([string]$Open, [string]$Close)
        # A local copy, so GetNewClosure takes it along with the two parameters.
        $contracts = $contractsArgument
        Edit-ScriptNode $verifierText $dexDiffCall { param($Text)
            $Text.Replace($contracts, '') + "`n    $Open`n    $Text`n    $Close" }.GetNewClosure() }
    $wiringCases = @(
        @{ Name = 'the untouched verifier'; Check = $runsDexDiff; Expect = $true; Text = $verifierText }
        @{ Name = 'the untouched verifier'; Check = $dotSourcesContracts; Expect = $true; Text = $verifierText }
        @{ Name = 'the untouched verifier'; Check = $talliesBothSides; Expect = $true; Text = $verifierText }
        @{ Name = 'the untouched verify-all-patches.ps1'; Check = $verifiesPatched; Expect = $true; Text = $allPatchesText }
        @{ Name = 'the untouched pre-push.ps1'; Check = $gateRunsSuite; Expect = $true; Text = $prePushSource }
        @{ Name = 'the contracts dropped from the DexDiff call and named in a log line'; Check = $runsDexDiff
            Text = (Edit-ScriptNode $verifierText { param($Node)
                $Node -is [System.Management.Automation.Language.ParenExpressionAst] -and
                $Node.Extent.Text -like '*injected-mutation-contracts.txt*' } { param($Text) '' }) +
                "`nWrite-Host '[registers] DexDiff.java runs without injected-mutation-contracts.txt now'`n" }
        @{ Name = 'the DexDiff function left uncalled'; Check = $runsDexDiff
            Text = Edit-ScriptNode $verifierText { param($Node)
                $Node -is [System.Management.Automation.Language.CommandAst] -and $Node.GetCommandName() -eq 'Invoke-DexDiff'
            } { param($Text) '[pscustomobject]@{ ExitCode = 0; Output = @() }' } }
        @{ Name = 'the DexDiff call behind a return'; Check = $runsDexDiff
            Text = Edit-ScriptNode $verifierText $dexDiffCall { param($Text) "return`n    $Text" } }
        @{ Name = 'the contracts helper dot-sourced in a dead branch'; Check = $dotSourcesContracts
            Text = Edit-ScriptNode $verifierText $contractsDotSource { param($Text) "if (`$false) { $Text }" } }
        @{ Name = 'the patched tally compared with itself'; Check = $talliesBothSides
            Text = Edit-ScriptNode $verifierText { param($Node)
                $Node -is [System.Management.Automation.Language.CommandAst] -and
                $Node.GetCommandName() -eq 'Compare-VerifierTallies'
            } { param($Text) 'Compare-VerifierTallies -Clean $patchedTally -Patched $patchedTally' } }
        @{ Name = 'the verifier call replaced by a log line naming it'; Check = $verifiesPatched
            Text = Edit-ScriptNode $allPatchesText $verifierCall { param($Text)
                "Write-Host '[verify] skipped verify-injected-registers.ps1 -PatchedApk `$out'" } }
        @{ Name = 'the verifier run on the stock APK'; Check = $verifiesPatched
            Text = Edit-ScriptNode $allPatchesText $verifierCall { param($Text)
                $Text.Replace('-PatchedApk $out', '-PatchedApk $stockApk') } }
        @{ Name = 'the suite line in a block comment'; Check = $gateRunsSuite
            Text = Edit-ScriptNode $prePushSource $suiteLine { param($Text) "<#`n$Text`n#>" } }
        @{ Name = 'the untouched pre-push.ps1'; Check = $gatePatchesFixtures; Expect = $true; Text = $prePushSource }
        @{ Name = 'the fixture run behind if ($false)'; Check = $gatePatchesFixtures
            Text = Edit-ScriptNode $prePushSource $fixtureRun { param($Text) "if (`$false) { $Text }" } }
        @{ Name = 'the fixture check never called'; Check = $gatePatchesFixtures
            Text = Edit-ScriptNode $prePushSource $fixtureCheckCall { param($Text) '$null = $gateRoot' } }
        # One copy for each rule of what a script can't reach, each taking the wiring out by that
        # rule alone, so none of them can be dropped without a copy here passing its check.
        @{ Name = 'the contracts helper dot-sourced in a while ($false) body'; Check = $dotSourcesContracts
            Text = Edit-ScriptNode $verifierText $contractsDotSource { param($Text) "while (`$false) { $Text }" } }
        @{ Name = 'the contracts helper dot-sourced in the else of if ($true)'; Check = $dotSourcesContracts
            Text = Edit-ScriptNode $verifierText $contractsDotSource { param($Text) "if (`$true) { } else { $Text }" } }
        @{ Name = 'the contracts helper dot-sourced behind if (-not $true)'; Check = $dotSourcesContracts
            Text = Edit-ScriptNode $verifierText $contractsDotSource { param($Text) "if (-not `$true) { $Text }" } }
        @{ Name = 'the contracts helper dot-sourced behind if (0)'; Check = $dotSourcesContracts
            Text = Edit-ScriptNode $verifierText $contractsDotSource { param($Text) "if (0) { $Text }" } }
        @{ Name = 'the contracts helper dot-sourced inside a function the script calls'; Check = $dotSourcesContracts
            Text = Edit-ScriptNode $verifierText $contractsDotSource { param($Text)
                "function Import-Contracts { $Text }`nImport-Contracts" } }
        # A script block run with & has a scope of its own too, even around a . { } block, while
        # . { } and ForEach-Object run in the script's.
        @{ Name = 'the contracts helper dot-sourced inside & { }'; Check = $dotSourcesContracts
            Text = Edit-ScriptNode $verifierText $contractsDotSource { param($Text) "& { $Text }" } }
        @{ Name = 'the contracts helper dot-sourced inside . { } inside & { }'; Check = $dotSourcesContracts
            Text = Edit-ScriptNode $verifierText $contractsDotSource { param($Text) "& { . { $Text } }" } }
        @{ Name = 'the contracts helper dot-sourced inside & { } inside . { }'; Check = $dotSourcesContracts
            Text = Edit-ScriptNode $verifierText $contractsDotSource { param($Text) ". { & { $Text } }" } }
        @{ Name = 'the contracts helper dot-sourced behind if ($false) inside . { }'; Check = $dotSourcesContracts
            Text = Edit-ScriptNode $verifierText $contractsDotSource { param($Text) ". { if (`$false) { $Text } }" } }
        @{ Name = 'the contracts helper dot-sourced inside . { }'; Check = $dotSourcesContracts; Expect = $true
            Text = Edit-ScriptNode $verifierText $contractsDotSource { param($Text) ". { $Text }" } }
        @{ Name = 'the contracts helper dot-sourced inside ForEach-Object { }'; Check = $dotSourcesContracts; Expect = $true
            Text = Edit-ScriptNode $verifierText $contractsDotSource { param($Text) "1 | ForEach-Object { $Text }" } }
        @{ Name = 'the DexDiff call behind exit'; Check = $runsDexDiff
            Text = Edit-ScriptNode $verifierText $dexDiffCall { param($Text) "exit 0`n    $Text" } }
        @{ Name = 'the DexDiff call behind throw'; Check = $runsDexDiff
            Text = Edit-ScriptNode $verifierText $dexDiffCall { param($Text) "throw 'stopped'`n    $Text" } }
        @{ Name = 'the DexDiff call behind break'; Check = $runsDexDiff
            Text = Edit-ScriptNode $verifierText $dexDiffCall { param($Text) "break`n    $Text" } }
        @{ Name = 'the DexDiff call behind continue'; Check = $runsDexDiff
            Text = Edit-ScriptNode $verifierText $dexDiffCall { param($Text) "continue`n    $Text" } }
        @{ Name = 'the DexDiff function called only in a dead branch'; Check = $runsDexDiff
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text)
                "if (`$false) { $Text }`n`$diff = [pscustomobject]@{ ExitCode = 0; Output = @() }" } }
        @{ Name = 'the patched APK moved off its place in the DexDiff call'; Check = $runsDexDiff
            Text = Edit-ScriptNode $verifierText $dexDiffCall { param($Text)
                $Text.Replace('$CleanMerged $PatchedApk', '$PatchedApk $CleanMerged') } }
        @{ Name = 'the verifier run on the stock APK, which follows -o only in a dead branch'; Check = $verifiesPatched
            Text = Edit-ScriptNode (Edit-ScriptNode $allPatchesText $verifierCall { param($Text)
                $Text.Replace('-PatchedApk $out', '-PatchedApk $stockApk') }) { param($Node)
                $Node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
                $Node.Extent.Text -like "`$arguments = @('patch'*" } { param($Text)
                "$Text`nif (`$false) { `$arguments = @('-o', `$stockApk) }" } }
        @{ Name = 'the suite line behind if ($false)'; Check = $gateRunsSuite
            Text = Edit-ScriptNode $prePushSource $suiteLine { param($Text) "if (`$false) { $Text }" } }
        @{ Name = 'the suite line in an elseif after if ($true)'; Check = $gateRunsSuite
            Text = Edit-ScriptNode $prePushSource { param($Node)
                $Node -is [System.Management.Automation.Language.IfStatementAst] -and
                $Node.Clauses[0].Item1.Extent.Text -eq '$touchesInjectedRegisterVerifier' } { param($Text)
                "if (`$true) { } else$Text" } }
        # Code that never runs though no constant or return right before it says so: a script block
        # nothing runs, an exit in a function that never returns or in an if or a loop that always
        # reaches it, and a condition that compares two constants. The copies expected to pass keep
        # those rules off code that does run: a block run by & or by ForEach-Object, a function
        # that can return, and a loop a break leaves.
        @{ Name = 'the DexDiff call without the contracts, and the old one kept in a script block nothing runs'
            Check = $runsDexDiff
            Text = Edit-ScriptNode $verifierText $dexDiffCall { param($Text)
                $Text.Replace("(Join-Path `$PSScriptRoot 'injected-mutation-contracts.txt')", '') +
                "`n    `$previous = { $Text }" } }
        @{ Name = 'the DexDiff function run through & { }'; Check = $runsDexDiff; Expect = $true
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text) '$diff = & { Invoke-DexDiff }' } }
        @{ Name = 'the DexDiff function run through ForEach-Object'; Check = $runsDexDiff; Expect = $true
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text) '$diff = @(1) | ForEach-Object { Invoke-DexDiff }' } }
        # common.ps1's Invoke-HeavyJob runs its -ScriptBlock once, in a slot of the build queue or
        # straight away, and nothing else it's handed: a block given to another command, or to
        # Invoke-HeavyJob under another parameter, is still a value.
        @{ Name = 'the DexDiff function run through Invoke-HeavyJob -ScriptBlock'; Check = $runsDexDiff; Expect = $true
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text) "Invoke-HeavyJob -Label 'diff' -ScriptBlock { $Text }" } }
        @{ Name = 'the DexDiff function in a block handed to another command'; Check = $runsDexDiff
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text)
                "Register-HeavyJob -Label 'diff' -ScriptBlock { $Text }`n`$diff = [pscustomobject]@{ ExitCode = 0; Output = @() }" } }
        @{ Name = 'the DexDiff function in a block handed to Invoke-HeavyJob as its -Label'; Check = $runsDexDiff
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text)
                "Invoke-HeavyJob -Label { $Text } -ScriptBlock { }`n`$diff = [pscustomobject]@{ ExitCode = 0; Output = @() }" } }
        @{ Name = 'the contracts helper dot-sourced inside Invoke-HeavyJob -ScriptBlock { }'; Check = $dotSourcesContracts
            Text = Edit-ScriptNode $verifierText $contractsDotSource { param($Text) "Invoke-HeavyJob -Label 'load' -ScriptBlock { $Text }" } }
        @{ Name = 'the verify run handed to another command than Invoke-HeavyJob'; Check = $verifiesPatched
            Text = Edit-ScriptNode $allPatchesText { param($Node)
                $Node -is [System.Management.Automation.Language.CommandAst] -and $Node.GetCommandName() -eq 'Invoke-HeavyJob'
            } { param($Text) $Text.Replace('Invoke-HeavyJob', 'Register-HeavyJob') } }
        @{ Name = 'the DexDiff call after a function that ends in one that exits'; Check = $runsDexDiff
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text)
                "function Stop-Early { Stop-Now }`nfunction Stop-Now { exit 0 }`n`$stopped = Stop-Early`n$Text" } }
        @{ Name = 'the DexDiff call after a function that can return before its exit'; Check = $runsDexDiff; Expect = $true
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text)
                "function Stop-Early { if (`$Serial) { return }`n    exit 0 }`nStop-Early`n$Text" } }
        @{ Name = 'the DexDiff call after an exit behind if ($true)'; Check = $runsDexDiff
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text) "if (`$true) { exit 0 }`n$Text" } }
        @{ Name = 'the DexDiff call after an exit on every arm that can run'; Check = $runsDexDiff
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text)
                "if (`$false) { } elseif (`$Serial) { exit 1 } else { exit 0 }`n$Text" } }
        @{ Name = 'the DexDiff call after while ($true) { exit 0 }'; Check = $runsDexDiff
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text) "while (`$true) { exit 0 }`n$Text" } }
        @{ Name = 'the DexDiff call after a while ($true) a break leaves'; Check = $runsDexDiff; Expect = $true
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text) "while (`$true) { break }`n$Text" } }
        @{ Name = 'the contracts helper dot-sourced behind if (1 -gt 2)'; Check = $dotSourcesContracts
            Text = Edit-ScriptNode $verifierText $contractsDotSource { param($Text) "if (1 -gt 2) { $Text }" } }
        @{ Name = 'the suite line behind if (0 -eq 1)'; Check = $gateRunsSuite
            Text = Edit-ScriptNode $prePushSource $suiteLine { param($Text) "if (0 -eq 1) { $Text }" } }
        # The old call kept where a constant settles that it can't run: an -and or -or one side
        # settles, the right of an -and or -or the left side settles, a switch clause that can't
        # match or a default a clause always takes, a foreach over nothing, a catch of a try that
        # can't throw, and a function defined a second time, whose first body never runs.
        @{ Name = 'the old DexDiff call kept behind if ($false -and $Serial)'; Check = $runsDexDiff
            Text = & $parkOldCall 'if ($false -and $Serial) {' '}' }
        @{ Name = 'the old DexDiff call kept behind if ($Serial -and $false)'; Check = $runsDexDiff
            Text = & $parkOldCall 'if ($Serial -and $false) {' '}' }
        @{ Name = 'the old DexDiff call kept behind if (-not ($Serial -or $true))'; Check = $runsDexDiff
            Text = & $parkOldCall 'if (-not ($Serial -or $true)) {' '}' }
        @{ Name = 'the old DexDiff call kept behind if ($true -xor $true)'; Check = $runsDexDiff
            Text = & $parkOldCall 'if ($true -xor $true) {' '}' }
        @{ Name = 'the old DexDiff call kept behind if (-not ($true -and $true))'; Check = $runsDexDiff
            Text = & $parkOldCall 'if (-not ($true -and $true)) {' '}' }
        @{ Name = 'the old DexDiff call kept behind if ($false -or $false)'; Check = $runsDexDiff
            Text = & $parkOldCall 'if ($false -or $false) {' '}' }
        @{ Name = 'the old DexDiff call kept on the right of $false -and'; Check = $runsDexDiff
            Text = & $parkOldCall '$null = $false -and $(' ')' }
        @{ Name = 'the old DexDiff call kept on the right of $true -or'; Check = $runsDexDiff
            Text = & $parkOldCall '$null = $true -or $(' ')' }
        @{ Name = "the old DexDiff call kept in switch ('current') { 'previous' { } }"; Check = $runsDexDiff
            Text = & $parkOldCall "switch ('current') { 'previous' {" '} }' }
        @{ Name = 'the old DexDiff call kept in the default of switch (1) { 1 { } }'; Check = $runsDexDiff
            Text = & $parkOldCall 'switch (1) { 1 { } default {' '} }' }
        @{ Name = 'the old DexDiff call kept in the default of a switch over @()'; Check = $runsDexDiff
            Text = & $parkOldCall 'switch (@()) { default {' '} }' }
        @{ Name = 'the old DexDiff call kept in a foreach over @()'; Check = $runsDexDiff
            Text = & $parkOldCall 'foreach ($unused in @()) {' '}' }
        @{ Name = 'the old DexDiff call kept in a foreach over $null'; Check = $runsDexDiff
            Text = & $parkOldCall 'foreach ($unused in $null) {' '}' }
        @{ Name = 'the old DexDiff call kept in the catch of an empty try'; Check = $runsDexDiff
            Text = & $parkOldCall 'try { } catch {' '}' }
        @{ Name = 'the old DexDiff call kept in the catch of try { return }'; Check = $runsDexDiff
            Text = & $parkOldCall 'try { return } catch {' '}' }
        @{ Name = 'Invoke-DexDiff defined again below it, without the contracts'; Check = $runsDexDiff
            Text = Edit-ScriptNode $verifierText $dexDiffFunction { param($Text) "$Text`n`n" + $Text.Replace($contractsArgument, '') } }
        @{ Name = 'Invoke-DexDiff defined again below it as script:Invoke-DexDiff, without the contracts'; Check = $runsDexDiff
            Text = Edit-ScriptNode $verifierText $dexDiffFunction { param($Text)
                "$Text`n`n" + $Text.Replace($contractsArgument, '').Replace('function Invoke-DexDiff', 'function script:Invoke-DexDiff') } }
        @{ Name = 'Invoke-DexDiff set again through ${function:}, without the contracts'; Check = $runsDexDiff
            Text = Edit-ScriptNode $verifierText $dexDiffFunction { param($Text)
                "$Text`n`n" + $Text.Replace($contractsArgument, '').Replace('function Invoke-DexDiff {', '${function:Invoke-DexDiff} = {') } }
        @{ Name = 'the DexDiff call after a call to function script:Stop-Now { exit 0 }'; Check = $runsDexDiff
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text) "function script:Stop-Now { exit 0 }`nStop-Now`n$Text" } }
        # And where it still runs, so none of those rules reaches code that does.
        @{ Name = 'the old DexDiff call kept behind if ($true -and $Serial)'; Check = $runsDexDiff; Expect = $true
            Text = & $parkOldCall 'if ($true -and $Serial) {' '}' }
        @{ Name = 'the old DexDiff call kept behind if ($false -or $Serial)'; Check = $runsDexDiff; Expect = $true
            Text = & $parkOldCall 'if ($false -or $Serial) {' '}' }
        @{ Name = 'the old DexDiff call kept on the right of $Serial -and'; Check = $runsDexDiff; Expect = $true
            Text = & $parkOldCall '$null = $Serial -and $(' ')' }
        @{ Name = "the old DexDiff call kept in switch ('current') { 'current' { } }"; Check = $runsDexDiff; Expect = $true
            Text = & $parkOldCall "switch ('current') { 'current' {" '} }' }
        @{ Name = 'the old DexDiff call kept in the default of switch (1) { 2 { } }'; Check = $runsDexDiff; Expect = $true
            Text = & $parkOldCall 'switch (1) { 2 { } default {' '} }' }
        @{ Name = "the old DexDiff call kept in switch (`$Serial) { 'previous' { } }"; Check = $runsDexDiff; Expect = $true
            Text = & $parkOldCall "switch (`$Serial) { 'previous' {" '} }' }
        @{ Name = 'the old DexDiff call kept in a foreach over @(1)'; Check = $runsDexDiff; Expect = $true
            Text = & $parkOldCall 'foreach ($once in @(1)) {' '}' }
        @{ Name = 'the old DexDiff call kept in the catch of a try that can throw'; Check = $runsDexDiff; Expect = $true
            Text = & $parkOldCall 'try { Get-Item -LiteralPath $PatchedApk | Out-Null } catch {' '}' }
        @{ Name = 'Invoke-DexDiff defined once, as script:Invoke-DexDiff'; Check = $runsDexDiff; Expect = $true
            Text = Edit-ScriptNode $verifierText $dexDiffFunction { param($Text)
                $Text.Replace('function Invoke-DexDiff', 'function script:Invoke-DexDiff') } }
        # The rules the copies above leave untried: every comparison the parser folds, $null as a
        # constant, an ending call past the first element of a pipeline, a for with no condition
        # at all, and the other ways ForEach-Object and Where-Object get written. The copies
        # expected to pass keep a loop and an if to ending only when they certainly do.
        foreach ($condition in '1 -ne 1', '0 -ge 1', '2 -lt 1', '1 -le 0', "'a' -ceq 'A'", "'a' -cne 'a'",
                "'a' -cgt 'b'", "'a' -cge 'b'", "'b' -clt 'a'", "'b' -cle 'a'", '$null') {
            @{ Name = "the contracts helper dot-sourced behind if ($condition)"; Check = $dotSourcesContracts
                Text = Edit-ScriptNode $verifierText $contractsDotSource { param($Text) "if ($condition) { $Text }" } }
        }
        @{ Name = 'the DexDiff call after $null | Stop-Now, a function that exits'; Check = $runsDexDiff
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text) "function Stop-Now { exit 0 }`n`$null | Stop-Now`n$Text" } }
        @{ Name = 'the DexDiff call after for (;;) { exit 0 }'; Check = $runsDexDiff
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text) "for (;;) { exit 0 }`n$Text" } }
        # ForEach-Object and Where-Object run only the blocks they're given to run, and only for
        # an item: a block handed to -ArgumentList is a value, and @() gives them nothing.
        @{ Name = 'the old DexDiff call kept in a block handed to ForEach-Object -ArgumentList'; Check = $runsDexDiff
            Text = & $parkOldCall '$null = @(1) | ForEach-Object -ArgumentList {' '} -Process { }' }
        @{ Name = 'the old DexDiff call kept in ForEach-Object over @()'; Check = $runsDexDiff
            Text = & $parkOldCall '$null = @() | ForEach-Object {' '}' }
        @{ Name = 'the old DexDiff call kept in Where-Object over @()'; Check = $runsDexDiff
            Text = & $parkOldCall '$null = @() | Where-Object {' '}' }
        @{ Name = 'the DexDiff function run through ForEach-Object -Process { }'; Check = $runsDexDiff; Expect = $true
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text) '$diff = @(1) | ForEach-Object -Process { Invoke-DexDiff }' } }
        @{ Name = 'the DexDiff function run through ForEach-Object -Begin { }'; Check = $runsDexDiff; Expect = $true
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text) '$diff = @(1) | ForEach-Object -Begin { Invoke-DexDiff } -Process { }' } }
        @{ Name = 'the DexDiff function run through ForEach-Object -End { }'; Check = $runsDexDiff; Expect = $true
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text) '$diff = @(1) | ForEach-Object -Process { } -End { Invoke-DexDiff }' } }
        @{ Name = 'the DexDiff function run through Where-Object -FilterScript { }'; Check = $runsDexDiff; Expect = $true
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text) '$diff = @(1) | Where-Object -FilterScript { Invoke-DexDiff }' } }
        @{ Name = 'the DexDiff function run through ForEach-Object over $null, which is one item'; Check = $runsDexDiff; Expect = $true
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text) '$diff = $null | ForEach-Object { Invoke-DexDiff }' } }
        @{ Name = 'the DexDiff function run through ForEach-Object -Process:{ }'; Check = $runsDexDiff; Expect = $true
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text) '$diff = @(1) | ForEach-Object -Process:{ Invoke-DexDiff }' } }
        @{ Name = 'the DexDiff function run through Where-Object'; Check = $runsDexDiff; Expect = $true
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text) '$diff = @(1) | Where-Object { Invoke-DexDiff }' } }
        @{ Name = 'the DexDiff function run through %'; Check = $runsDexDiff; Expect = $true
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text) '$diff = @(1) | % { Invoke-DexDiff }' } }
        @{ Name = 'the DexDiff call after while ($Serial) { exit 0 }'; Check = $runsDexDiff; Expect = $true
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text) "while (`$Serial) { exit 0 }`n$Text" } }
        @{ Name = "the DexDiff call after if (`$Serial) { 'x' } else { exit 0 }"; Check = $runsDexDiff; Expect = $true
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text) "if (`$Serial) { 'x' } else { exit 0 }`n$Text" } }
    )
    $wiringFailures = @()
    $copy = Join-Path $wiringCopies 'copy.ps1'
    foreach ($case in $wiringCases) {
        [System.IO.File]::WriteAllText($copy, $case.Text)
        $expect = $case.ContainsKey('Expect') -and $case.Expect
        if ((& $case.Check $copy) -ne $expect) {
            $wiringFailures += "$($case.Name): the check said $(-not $expect)"
        }
    }
    if ($wiringFailures.Count -ne 0) { throw ("The wiring checks misjudged:`n" + ($wiringFailures -join "`n")) }
} finally {
    Remove-Item -LiteralPath $wiringCopies -Recurse -Force -ErrorAction SilentlyContinue
}

# The verifier run end to end, with stand-ins for the tools it starts: a java that answers the
# version probe and plays DexDiff with the given exit code, an aapt2 that describes Facebook 580,
# and an apksigner that reports Meta's signer. With -JavaGone the apksigner also deletes that java,
# which leaves it unable to start by the time DexDiff runs, as a JDK replaced mid-run would. A
# Continue preference around the DexDiff call once turned exactly that into '[registers] success.'.
$standIns = [System.IO.Path]::GetFullPath((Join-Path ([System.IO.Path]::GetTempPath()) `
    ("hushfacebook-verifier-standins-" + [guid]::NewGuid().ToString('N'))))
$metaSigner = @((Get-Content -LiteralPath (Join-Path (Split-Path -Parent $PSScriptRoot) 'patches-list.json') -Raw |
    ConvertFrom-Json).patches | ForEach-Object { $_.compatibility } |
    Where-Object { $_.packageName -eq 'com.facebook.katana' } | ForEach-Object { $_.signatures } |
    Sort-Object -Unique | Select-Object -First 1)
Assert-True ($metaSigner.Count -eq 1) 'patches-list.json declares no Facebook signer for the stand-in apksigner.'

function Write-StandIn {
    param([string]$Path, [string]$Text)
    [System.IO.File]::WriteAllText($Path, (($Text -replace "`r`n", "`n") -replace "`n", "`r`n"),
        [System.Text.Encoding]::ASCII)
}

function Invoke-VerifierWithStandIns {
    param([string]$Name, [int]$DexDiffExit, [switch]$JavaGone)
    $case = Join-Path $standIns $Name
    New-Item -ItemType Directory -Path $case -Force | Out-Null
    $javaStandIn = Join-Path $case 'java.cmd'
    Write-StandIn $javaStandIn @"
@echo off
if "%~1"=="-version" (
  echo openjdk version "21.0.0"
  exit /b 0
)
echo [diff] structural findings: 0
exit /b $DexDiffExit
"@
    Write-StandIn (Join-Path $case 'aapt2.cmd') @'
@echo off
echo   E: manifest (line=2)
echo     A: http://schemas.android.com/apk/res/android:versionCode(0x0101021b)=475019344
echo     A: http://schemas.android.com/apk/res/android:versionName(0x0101021c)="580.0.0.51.74" (Raw: "580.0.0.51.74")
echo     A: package="com.facebook.katana" (Raw: "com.facebook.katana")
exit /b 0
'@
    $delete = if ($JavaGone) { "del /f /q `"$javaStandIn`"" } else { 'rem' }
    Write-StandIn (Join-Path $case 'apksigner.bat') @"
@echo off
$delete
echo Signer #1 certificate SHA-256 digest: $($metaSigner[0])
exit /b 0
"@
    foreach ($file in 'clean.apk', 'patched.apk', 'desktop.jar') {
        [System.IO.File]::WriteAllText((Join-Path $case $file), 'stand-in')
    }
    $ErrorActionPreference = 'Continue'
    $global:LASTEXITCODE = 0
    $output = @(& (Get-Process -Id $PID).Path -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $verifier `
        -CleanApk (Join-Path $case 'clean.apk') -PatchedApk (Join-Path $case 'patched.apk') `
        -ReportPath (Join-Path $case 'report.txt') -Java $javaStandIn -DesktopJar (Join-Path $case 'desktop.jar') `
        -Aapt2 (Join-Path $case 'aapt2.cmd') 2>&1 | ForEach-Object { "$_" })
    [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = $output; Text = $output -join "`n" }
}

try {
    New-Item -ItemType Directory -Path $standIns | Out-Null
    # Each run has to reach the DexDiff call, or a stand-in that broke early would pass the checks below.
    $reached = '(?m)^\[registers\] patched '
    $passed = Invoke-VerifierWithStandIns -Name 'passed' -DexDiffExit 0
    Assert-True ($passed.ExitCode -eq 0 -and $passed.Text -match $reached -and
        $passed.Output -contains '[registers] success.') `
        "The verifier did not pass a comparison DexDiff passed.`n$($passed.Text)"
    $refused = Invoke-VerifierWithStandIns -Name 'refused' -DexDiffExit 1
    Assert-True ($refused.ExitCode -eq 1 -and $refused.Text -match $reached -and
        $refused.Text -match 'FAIL: the dex comparison exited 1' -and
        $refused.Output -notcontains '[registers] success.') `
        "The verifier did not fail a comparison DexDiff failed.`n$($refused.Text)"
    $gone = Invoke-VerifierWithStandIns -Name 'java-gone' -DexDiffExit 0 -JavaGone
    Assert-True ($gone.ExitCode -ne 0 -and $gone.Text -match $reached -and
        $gone.Output -notcontains '[registers] success.' -and $gone.Text -notmatch '\[registers\] static: ') `
        "The verifier passed a run whose java could not start for DexDiff (exit $($gone.ExitCode)).`n$($gone.Text)"
} finally {
    Remove-Item -LiteralPath $standIns -Recurse -Force -ErrorAction SilentlyContinue
}

$Java = Resolve-Java -Explicit $Java
$DesktopJar = Resolve-DesktopCli -Explicit $DesktopJar -Root $Root -Required
$javac = Join-Path (Split-Path -Parent $Java) 'javac.exe'
if (-not (Test-Path -LiteralPath $javac -PathType Leaf)) { throw "Required tool not found: $javac" }
$contracts = Join-Path $PSScriptRoot 'injected-mutation-contracts.txt'

$tempBase = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$caseRoot = [System.IO.Path]::GetFullPath((Join-Path $tempBase `
    ("hushfacebook-register-test-" + [guid]::NewGuid().ToString('N'))))
$requiredPrefix = $tempBase.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + `
    [System.IO.Path]::DirectorySeparatorChar
if (-not $caseRoot.StartsWith($requiredPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to create test files outside the temporary directory: $caseRoot"
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
try {
    New-Item -ItemType Directory -Path $caseRoot | Out-Null
    # Compiled once rather than launched from source per case: the same code, a fraction of the time.
    $classes = Join-Path $caseRoot 'classes'
    Invoke-Checked -Program $javac -Arguments @('-encoding', 'UTF-8', '-cp', $DesktopJar, '-d', $classes,
        (Join-Path $PSScriptRoot 'DexDiff.java'), (Join-Path $PSScriptRoot 'BadDexFixture.java')) `
        -Description 'javac for DexDiff and its fixtures'
    $classPath = $DesktopJar + [System.IO.Path]::PathSeparator + $classes
    $dexDir = Join-Path $caseRoot 'dex'
    Invoke-Checked -Program $Java -Arguments @('-cp', $classPath, 'BadDexFixture', $dexDir) `
        -Description 'BadDexFixture'
    function Get-Dex([string]$Name) { Join-Path $dexDir "$Name.dex" }

    $emptyAllowlist = Join-Path $caseRoot 'empty-allowlist.txt'
    [System.IO.File]::WriteAllText($emptyAllowlist, "# nothing reviewed`n")
    $cleanApk = New-DexApk -Name 'clean' -Entries ([ordered]@{ 'classes.dex' = (Get-Dex 'clean') })

    $good = Invoke-DexDiff -Clean $cleanApk -Patched (New-DexApk -Name 'good' -Entries ([ordered]@{
        'classes.dex' = (Get-Dex 'good') })) -Allowlist $emptyAllowlist -Name 'good' -Contracts $contracts
    Assert-True ($good.ExitCode -eq 0) "The patched build that breaks nothing failed.`n$($good.Output -join "`n")"
    Assert-True ((Get-Findings $good).Fails.Count -eq 0) "The good build printed a FAIL line.`n$($good.Output -join "`n")"
    Assert-True (($good.Output -join "`n") -match [regex]::Escape(
        'FeedFilter;->hideEdge(Ljava/lang/Object;Ljava/lang/Object;)Z: 1 call site, in Lfixture/Feed;->addNewEdgeToCollection(')) `
        "The good build's guard was not reported at its one call site.`n$($good.Output -join "`n")"
    Assert-True (($good.Output -join "`n") -match 'structural findings: 0') `
        "The good build did not report its structural count.`n$($good.Output -join "`n")"
    # The badge writer's count handed over as a range from v17 is a changed method the good build
    # passes with, so the dropped-call control below is refused for the lone move-result alone.
    Assert-True ((Get-Content -LiteralPath $good.Report -Raw) -match
        '(?m)^==== Lfixture/IconBadger;->write\(Landroid/content/Context;I\)V\r?$') `
        "The good build's badge writer, hooked with a range call, was not among its changed methods.`n$(Get-Content -LiteralPath $good.Report -Raw)"
    foreach ($stub in 'GenAiLabel;->detectedInfo', 'GenAiLabel;->selfDisclosureInfo',
            'RecommendationLabel;->recommendationContext', 'PostText;->message', 'PostText;->attachedStory',
            'PostSources;->actors', 'PostSources;->attachments') {
        Assert-True (($good.Output -join "`n") -match ([regex]::Escape("$stub(Ljava/lang/Object;)Ljava/lang/Object;: calls " +
            'Lcom/facebook/graphql/model/GraphQLStory;->A0X()Lfixture/Model; before its first return'))) `
            "The good build's $stub was not reported calling the story's accessor.`n$($good.Output -join "`n")"
    }
    # The tray count rule finds one call in each counted class's count.
    Assert-True (($good.Output -join "`n") -match [regex]::Escape(
        'storiesTrayCount(Ljava/lang/Object;II)I in getItemCount 3: 3 call sites, in ' +
        'Lfixture/ClassicTray;->getItemCount()I, Lfixture/ComposerRow;->getItemCount()I, Lfixture/UnifiedTray;->getItemCount()I')) `
        "The good build's tray counts were not reported, one in each counted class.`n$($good.Output -join "`n")"
    # Each start-call rule finds its one method among others holding part of what it names (the
    # refresh controller's onPause, two other methods naming both surfaces), and the hook first there.
    Assert-True (($good.Output -join "`n") -match [regex]::Escape(
        'hidePreEofReels()Z holding PreEofIfuSectionAdapter: first in Lfixture/PreEof;->injectPreEofIfuEdge$fixture(')) `
        "The good build's reels hook was not reported first in the pre-EOF injector.`n$($good.Output -join "`n")"
    Assert-True (($good.Output -join "`n") -match [regex]::Escape(
        'ShowcaseType;->storyType(Ljava/lang/Object;)Ljava/lang/Object; on-type-named ShowcaseFeedUnit: calls ' +
        'Lfixture/Showcase;->A01()Lfixture/StoryType; before its first return')) `
        "The good build's showcase stub was not reported calling the showcase unit's accessor.`n$($good.Output -join "`n")"
    Assert-True (($good.Output -join "`n") -match [regex]::Escape(
        'ReturnRefresh;->skip()Z holding FeedRefreshTriggerController onRefresh: first in Lfixture/ReturnController;->resumeAfterBackground(')) `
        "The good build's background-return guard was not first in the resume callback.`n$($good.Output -join "`n")"
    Assert-True (($good.Output -join "`n") -match [regex]::Escape(
        ('ReelDeclutter;->hideFollowButton()Z in static (Lcom/facebook/auth/usersession/FbUserSession;*)Z holding ' +
            'friendly_feed friends_tab_ifu: first in Lfixture/FollowCheck;->offersFollow('))) `
        "The good build's Follow hook was not first in Facebook's Follow check.`n$($good.Output -join "`n")"
    Assert-True (($good.Output -join "`n") -match [regex]::Escape(
        ('SystemEmoji;->typeface()Landroid/graphics/Typeface; in instance ()Landroid/graphics/Typeface; holding ' +
            'fb.e2e.force_system_emoji_font FacebookEmojiTypefaceProviderImpl: first in Lfixture/EmojiProvider;->emojiTypeface('))) `
        "The good build's emoji hook was not first in Facebook's emoji typeface provider.`n$($good.Output -join "`n")"
    Assert-True (($good.Output -join "`n") -match [regex]::Escape(
        ('SystemEmoji;->skipRemoteEmoji()Z in static (Ljava/lang/String;*)Ljava/lang/String; holding ' +
            'https://www.facebook.com/images/mobileemoji: first in Lfixture/EmojiPictures;->makeUrl('))) `
        "The good build's emoji picture hook was not first in Facebook's maker of emoji picture addresses.`n$($good.Output -join "`n")"
    Assert-True (($good.Output -join "`n") -match [regex]::Escape(
        ('MessengerIcon;->open(Landroid/content/Context;Z)Z in static (Landroid/content/Context;Lcom/facebook/auth/usersession/FbUserSession;*)V ' +
            'holding entry_point_navbar_global_icon_ entry_point_navbar_global_icon_reels_tab: first in Lfixture/MessengerBar;->tap('))) `
        "The good build's Messenger icon hook was not first in the icon's tap.`n$($good.Output -join "`n")"
    Assert-True (($good.Output -join "`n") -match [regex]::Escape(
        ('MessengerIcon;->open(Landroid/content/Context;Z)Z in static (Landroid/content/Context;Lcom/facebook/auth/usersession/FbUserSession;' +
            'Ljava/lang/String;ZZ)V holding long_press: first in Lfixture/MessengerBar;->button('))) `
        "The good build's Messenger icon hook was not first in the Messenger button handler.`n$($good.Output -join "`n")"
    Assert-True (($good.Output -join "`n") -match [regex]::Escape(
        ('TypingIndicator;->chatTyping(Z)Z holding setTypingIndicatorForThreadWithThreadIdentifier: ' +
            'first in Lfixture/Mailbox;->setTyping(Z)V'))) `
        "The good build's typing hook was not first in Mailbox's typing setter.`n$($good.Output -join "`n")"
    Assert-True (($good.Output -join "`n") -match [regex]::Escape(
        ('DoubleTapLike;->holdBackLike(Ljava/lang/String;)Z in instance (Lcom/facebook/auth/usersession/FbUserSession;*)V ' +
            'holding FbShortsMutationUtil.mutateViewerLikeReaction: first in Lfixture/ReelLikeHelper;->like('))) `
        "The good build's double tap like hook was not first in the reel like helper's like.`n$($good.Output -join "`n")"
    Assert-True (($good.Output -join "`n") -match [regex]::Escape(
        ('DoubleTapLike;->holdBackTap()Z in instance (Landroid/view/MotionEvent;)Z holding translationY: ' +
            'first in Lfixture/AttachmentTap;->onDoubleTap('))) `
        "The good build's double tap hook was not first in the feed attachment's onDoubleTap.`n$($good.Output -join "`n")"
    Assert-True (($good.Output -join "`n") -match [regex]::Escape(
        ('ReelSpeed;->picked(F)V in static (Landroid/content/Context;F)V holding InlinePlaybackSpeedAttributeSelector: ' +
            'first in Lfixture/SpeedToast;->show('))) `
        "The good build's reel speed hook was not first in the Reels menu's speed toast.`n$($good.Output -join "`n")"
    Assert-True (($good.Output -join "`n") -match [regex]::Escape(
        ('ReelsTabDot;->clear(Ljava/lang/Object;)Z in static (Lcom/facebook/auth/usersession/FbUserSession;*' +
            'Lcom/facebook/navigation/tabbar/state/model/TabTag;I)I holding ' +
            'FbMainTabActivityJewelController.getTrackedCountWithLogging: first in Lfixture/JewelController;->count('))) `
        "The good build's Reels tab dot hook was not first in the tab bar's jewel count.`n$($good.Output -join "`n")"
    Assert-True (($good.Output -join "`n") -match [regex]::Escape(
        'GenAiReelFilter;->transparencyAttribution(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object; outside ' +
        'Lapp/morphe/extension/: calls Lfixture/Attributions;->A02(Lfixture/ReelModel;Ljava/lang/String;)Lfixture/Model; ' +
        'before its first return')) `
        "The good build's GenAI reel stub was not reported calling Facebook's attribution finder.`n$($good.Output -join "`n")"
    Assert-True (($good.Output -join "`n") -match [regex]::Escape(
        'ReelMidCards;->midCardType(Ljava/lang/Object;)Ljava/lang/Object; outside ' +
        'Lapp/morphe/extension/: calls Lfixture/MidCardItem;->A0F()Ljava/lang/Object; before its first return')) `
        "The good build's Threads card stub was not reported calling the mid-card item's read.`n$($good.Output -join "`n")"
    # The settings patch sends each of these ShortcutManager calls to SettingsEntry, and the fixture's
    # publisher makes each one from a method of its own (Caller). Every no-call rule in the contract
    # file has to be one of them, or a rule with no bad build below would pass on "0 call sites".
    $shortcutCalls = @(
        [pscustomobject]@{ Case = 'push'; Call = 'pushDynamicShortcut'; Takes = 'Landroid/content/pm/ShortcutInfo;'; Answers = 'V'; Caller = 'push' }
        [pscustomobject]@{ Case = 'add'; Call = 'addDynamicShortcuts'; Takes = 'Ljava/util/List;'; Answers = 'Z'; Caller = 'add' }
        [pscustomobject]@{ Case = 'set'; Call = 'setDynamicShortcuts'; Takes = 'Ljava/util/List;'; Answers = 'Z'; Caller = 'set' }
        [pscustomobject]@{ Case = 'update'; Call = 'updateShortcuts'; Takes = 'Ljava/util/List;'; Answers = 'Z'; Caller = 'update' }
        [pscustomobject]@{ Case = 'remove-all'; Call = 'removeAllDynamicShortcuts'; Takes = ''; Answers = 'V'; Caller = 'removeAll' }
    )
    foreach ($shortcut in $shortcutCalls) {
        $shortcut | Add-Member -NotePropertyName Callee -NotePropertyValue (
            "Landroid/content/pm/ShortcutManager;->$($shortcut.Call)($($shortcut.Takes))$($shortcut.Answers)")
        $shortcut | Add-Member -NotePropertyName Site -NotePropertyValue (
            "Lfixture/Shortcuts;->$($shortcut.Caller)(Landroid/content/pm/ShortcutManager;$($shortcut.Takes))$($shortcut.Answers)")
    }
    $noCallRules = @(Get-Content -LiteralPath $contracts | Where-Object { $_ -match '^\s*no-call\s' } |
        ForEach-Object { ($_.Trim() -split '\s+')[1] } | Sort-Object)
    $shortcutCallees = @($shortcutCalls | ForEach-Object { $_.Callee } | Sort-Object)
    Assert-True (($noCallRules -join "`n") -ceq ($shortcutCallees -join "`n")) `
        ("The contract file's no-call rules and this suite's shortcut builds name different calls.`n" +
        "Contract file:`n$($noCallRules -join "`n")`nThis suite:`n$($shortcutCallees -join "`n")")
    foreach ($shortcut in $shortcutCalls) {
        Assert-True (($good.Output -join "`n") -match [regex]::Escape(
            "no-call $($shortcut.Callee) outside Lapp/morphe/extension/: 0 call sites")) `
            ("The good build's $($shortcut.Call), sent to the stand-in whose own call is inside the extension, " +
            "was not reported clean.`n$($good.Output -join "`n")")
    }
    # The settings patch sends the call that gives the Facebook logo its touch listener to a stand-in,
    # right after the logo gets its tap. The contract file's one next-call rule is that hook, so a
    # rule this suite builds no bad fixtures for can't pass on a count nobody checks.
    $logoHook = 'Lapp/morphe/extension/facebook/settings/SettingsEntry;->setLogoTouchListener(Landroid/view/View;Landroid/view/View$OnTouchListener;)V'
    $logoTap = 'Landroid/view/View;->setOnClickListener(Landroid/view/View$OnClickListener;)V'
    $logoShape = '(Landroid/content/Context;Lcom/facebook/navigation/navbar/legacy/search/WordmarkNavigationBar;)V'
    $logoBuilder = "Lfixture/TopBar;->buildLogo$logoShape"
    $logoHeld = '"WordmarkNavigationBar#createWordmarkView" and "WordmarkNavigationBar.initContents" with the shape ' +
        "static $logoShape"
    $logoRule = "next-call $logoHook after $logoTap in static $logoShape holding " +
        'WordmarkNavigationBar#createWordmarkView WordmarkNavigationBar.initContents'
    $nextCallRules = @(Get-Content -LiteralPath $contracts | Where-Object { $_ -match '^\s*next-call\s' } |
        ForEach-Object { ($_.Trim() -split '\s+') -join ' ' })
    Assert-True ($nextCallRules.Count -eq 1 -and $nextCallRules[0] -ceq $logoRule) `
        "The contract file's next-call rules are not the logo hook this suite builds bad fixtures for:`n$($nextCallRules -join "`n")"
    Assert-True (($good.Output -join "`n") -match [regex]::Escape(
        "contract $logoRule`: right after it on v1 in $logoBuilder")) `
        "The good build's logo hook was not reported right after the logo's tap.`n$($good.Output -join "`n")"
    # Don't send reel watch history sends the batcher's one hand-over of watched reels to a
    # stand-in on the same registers. The contract file's one sole-call rule is that hook, so a rule
    # this suite builds no bad fixtures for can't pass on a count nobody checks.
    $watchSend = 'Lapp/morphe/extension/facebook/reels/ReelWatchHistory;->send(Ljava/util/concurrent/Executor;Ljava/lang/Runnable;)V'
    $watchExecute = 'Ljava/util/concurrent/Executor;->execute(Ljava/lang/Runnable;)V'
    $watchFlush = 'Lfixture/SeenStateBatcher;->flush()V'
    $watchHeld = '"FbShortsSeenStateMutation" and "video_ids" with the shape instance ()V'
    $watchRule = "sole-call $watchSend replacing $watchExecute in instance ()V holding FbShortsSeenStateMutation video_ids"
    $soleCallRules = @(Get-Content -LiteralPath $contracts | Where-Object { $_ -match '^\s*sole-call\s' } |
        ForEach-Object { ($_.Trim() -split '\s+') -join ' ' })
    Assert-True ($soleCallRules.Count -eq 1 -and $soleCallRules[0] -ceq $watchRule) `
        "The contract file's sole-call rules are not the watch-history hook this suite builds bad fixtures for:`n$($soleCallRules -join "`n")"
    Assert-True (($good.Output -join "`n") -match [regex]::Escape(
        "contract $watchRule`: in place of it on v2, v1 in $watchFlush")) `
        "The good build's watch-history hook was not reported in place of the executor call.`n$($good.Output -join "`n")"
    # The feed guard asks the extension once in the runnable that swaps an edge into the feed, Hide
    # read receipts once in Mailbox's mark-read, and the tab links patch once in each of the three
    # places Facebook looks a link's tab up in its configured tabs. The contract file's once-call
    # rules are those five, so a rule this suite builds no bad fixtures for can't pass on a count
    # nobody checks.
    $swapHook = 'Lapp/morphe/extension/facebook/feed/FeedFilter;->hideSwappedEdge(Ljava/lang/Object;Ljava/lang/Object;)Z'
    $swapRun = 'Lfixture/EdgeSwap;->run()V'
    $swapHeld = '"sizeBefore" and "sizeAfter" with the shape instance ()V'
    $swapRule = "once-call $swapHook in instance ()V holding sizeBefore sizeAfter"
    $readHook = 'Lapp/morphe/extension/facebook/chats/ReadReceipts;->holdsChatRead()Z'
    $readSite = 'Lfixture/Mailbox;->markRead()V'
    $readRule = "once-call $readHook holding markAsReadThreadWithThreadIdentifier"
    $tabFilter = 'Lapp/morphe/extension/facebook/navigation/TabBarFilter;'
    $tabTag = 'Lcom/facebook/navigation/tabbar/state/model/TabTag;'
    $session = 'Lcom/facebook/auth/usersession/FbUserSession;'
    $linkShape = '(Landroid/content/Context;Landroid/content/Intent;*)Landroid/content/Intent;'
    $linkSite = "(Landroid/content/Context;Landroid/content/Intent;$session)Landroid/content/Intent;"
    $tabLinkRules = [ordered]@{
        ("once-call $tabFilter->launchedTab(Ljava/lang/Object;)Ljava/lang/Object; in instance " +
            "(Landroid/content/Intent;$session)$tabTag holding extra_launch_uri") =
            "Lfixture/TabLinks;->launchTab(Landroid/content/Intent;$session)$tabTag"
        "once-call $tabFilter->friendsTab(Ljava/lang/Object;)Ljava/lang/Object; in instance $linkShape holding DEEPLINK" =
            "Lfixture/TabLinks;->friendsLink$linkSite"
        "once-call $tabFilter->configuresTab(ZLjava/lang/Object;)Z in instance $linkShape holding key_uri /menu/bookmarks" =
            "Lfixture/TabLinks;->targetTabLink$linkSite"
    }
    $onceCallRules = @(Get-Content -LiteralPath $contracts | Where-Object { $_ -match '^\s*once-call\s' } |
        ForEach-Object { ($_.Trim() -split '\s+') -join ' ' })
    $expectedOnceCalls = @($swapRule, $readRule) + @($tabLinkRules.Keys)
    Assert-True (($onceCallRules -join "`n") -ceq ($expectedOnceCalls -join "`n")) `
        "The contract file's once-call rules are not the swap guard, read receipts and tab links this suite builds bad fixtures for:`n$($onceCallRules -join "`n")"
    Assert-True (($good.Output -join "`n") -match [regex]::Escape("contract $swapRule`: once in $swapRun")) `
        "The good build's swap guard was not reported once in the swap runnable.`n$($good.Output -join "`n")"
    Assert-True (($good.Output -join "`n") -match [regex]::Escape("contract $readRule`: once in $readSite")) `
        "The good build's read receipts hook was not reported once in Mailbox's mark-read.`n$($good.Output -join "`n")"
    foreach ($rule in $tabLinkRules.Keys) {
        Assert-True (($good.Output -join "`n") -match [regex]::Escape("contract $rule`: once in $($tabLinkRules[$rule])")) `
            "The good build's tab link was not reported once in its method: $rule`n$($good.Output -join "`n")"
    }

    $bad = [ordered]@{
        'bad-branch' = 'branch'
        'bad-branch-self' = 'branch'
        'bad-switch-case' = 'branch'
        'bad-switch-to-payload' = 'branch'
        'bad-switch-to-result' = 'branch'
        'bad-goto-to-payload' = 'branch'
        'bad-goto-to-array-payload' = 'branch'
        'bad-switch-to-array-payload' = 'branch'
        'bad-sparse-case-to-payload' = 'branch'
        'bad-packed-switch-sparse-table' = 'branch'
        'bad-sparse-switch-packed-table' = 'branch'
        'bad-fallthrough-into-payload' = 'branch'
        'bad-payload-at-entry' = 'branch'
        'bad-branch-to-result' = 'branch'
        'bad-walk-off-end' = 'branch'
        'bad-goto-to-handler' = 'branch'
        'bad-fallthrough-handler' = 'try'
        'bad-move-exception-entry' = 'try'
        'bad-wide-high-clobber' = 'width'
        'bad-wide-high-clobber-branch' = 'width'
        'bad-wide-high-clobber-loop' = 'width'
        'bad-wide-low-clobber' = 'width'
        'bad-wide-below-pair' = 'width'
        'bad-invoke-count' = 'invoke'
        'bad-wide-split' = 'invoke'
        'bad-static-parameter' = 'parameter'
        'bad-wide-parameter' = 'parameter'
        'bad-narrow-for-wide' = 'width'
        'bad-narrow-for-wide-branch' = 'width'
        'bad-narrow-shift' = 'width'
        'bad-wide-for-narrow' = 'width'
        'bad-conflict-if-eqz' = 'width'
        'bad-conflict-if-ne' = 'width'
        'bad-conflict-if-lt' = 'width'
        'bad-conflict-switch' = 'width'
        'bad-conflict-monitor-enter' = 'width'
        'bad-conflict-throw' = 'width'
        'bad-conflict-check-cast' = 'width'
        'bad-conflict-instance-of' = 'width'
        'bad-conflict-array-length' = 'width'
        'bad-conflict-new-array' = 'width'
        'bad-conflict-filled-new-array' = 'width'
        'bad-conflict-fill-array-data' = 'width'
        'bad-conflict-if-nez' = 'width'
        'bad-conflict-if-eq' = 'width'
        'bad-conflict-if-eq-second' = 'width'
        'bad-conflict-if-gez' = 'width'
        'bad-conflict-if-lt-second' = 'width'
        'bad-conflict-sparse-switch' = 'width'
        'bad-conflict-monitor-exit' = 'width'
        'bad-conflict-filled-new-array-range' = 'width'
        'bad-broken-high-if-eqz' = 'width'
        'bad-if-eq-int-object' = 'width'
        'bad-if-ne-object-int' = 'width'
        'bad-wide-if-eqz' = 'width'
        'bad-broken-low-move' = 'width'
        'bad-broken-high-move' = 'width'
        'bad-conflict-object-copy' = 'width'
        'bad-conflict-plain-copy' = 'width'
        'bad-zero-for-wide-branch' = 'width'
        'bad-move-wide-conflict' = 'width'
        'bad-move-result' = 'result'
        'bad-dropped-invoke' = 'result'
        'bad-try-range' = 'try'
        'bad-try-handler' = 'try'
        'bad-try-handler-result' = 'try'
        'bad-try-handler-payload' = 'try'
        'bad-try-handler-array-payload' = 'try'
        'bad-double-guard' = 'contract'
        'bad-guard-elsewhere' = 'contract'
        'bad-no-guard' = 'contract'
        'bad-stub-not-filled' = 'contract'
        'bad-self-label-stub-not-filled' = 'contract'
        'bad-stub-other-class' = 'contract'
        'bad-stub-call-after-return' = 'contract'
        'bad-tray-hook-missing' = 'contract'
        'bad-tray-count-twice' = 'contract'
        'bad-preeof-hook-missing' = 'contract'
        'bad-preeof-hook-late' = 'contract'
        'bad-showcase-stub-not-filled' = 'contract'
        'bad-showcase-stub-other-class' = 'contract'
        'bad-showcase-two-classes' = 'contract'
        'bad-return-refresh-hook-missing' = 'contract'
        'bad-return-refresh-hook-late' = 'contract'
        'bad-follow-hook-missing' = 'contract'
        'bad-follow-hook-late' = 'contract'
        'bad-emoji-hook-missing' = 'contract'
        'bad-emoji-hook-late' = 'contract'
        'bad-emoji-pictures-hook-missing' = 'contract'
        'bad-emoji-pictures-hook-late' = 'contract'
        'bad-messenger-tap-hook-missing' = 'contract'
        'bad-messenger-tap-hook-late' = 'contract'
        'bad-messenger-button-hook-missing' = 'contract'
        'bad-messenger-button-hook-late' = 'contract'
        'bad-double-tap-like-hook-missing' = 'contract'
        'bad-double-tap-like-hook-late' = 'contract'
        'bad-double-tap-tap-hook-missing' = 'contract'
        'bad-double-tap-tap-hook-late' = 'contract'
        'bad-reel-speed-hook-missing' = 'contract'
        'bad-reel-speed-hook-late' = 'contract'
        'bad-reels-tab-dot-hook-missing' = 'contract'
        'bad-reels-tab-dot-hook-late' = 'contract'
        'bad-tab-links-launch-hook-missing' = 'contract'
        'bad-tab-links-friends-hook-missing' = 'contract'
        'bad-tab-links-check-hook-missing' = 'contract'
        'bad-post-message-stub-not-filled' = 'contract'
        'bad-post-attached-stub-not-filled' = 'contract'
        'bad-post-actors-stub-not-filled' = 'contract'
        'bad-post-attachments-stub-not-filled' = 'contract'
        'bad-typing-hook-missing' = 'contract'
        'bad-typing-hook-late' = 'contract'
        'bad-read-receipts-hook-missing' = 'contract'
        'bad-read-receipts-hook-twice' = 'contract'
        'bad-logo-hook-missing' = 'contract'
        'bad-logo-hook-other-call' = 'contract'
        'bad-logo-hook-other-view' = 'contract'
        'bad-logo-hook-twice' = 'contract'
        'bad-logo-hook-decoy' = 'contract'
        'bad-logo-hook-also-elsewhere' = 'contract'
        'bad-logo-two-builders' = 'contract'
        'bad-watch-hook-missing' = 'contract'
        'bad-watch-hook-decoy' = 'contract'
        'bad-watch-hook-also-elsewhere' = 'contract'
        'bad-watch-hook-twice' = 'contract'
        'bad-watch-execute-left' = 'contract'
        'bad-watch-hook-other-registers' = 'contract'
        'bad-watch-two-flushes' = 'contract'
        'bad-swap-hook-missing' = 'contract'
        'bad-swap-hook-decoy' = 'contract'
        'bad-swap-hook-also-elsewhere' = 'contract'
        'bad-swap-hook-twice' = 'contract'
        'bad-swap-two-runs' = 'contract'
        'bad-finder-stub-not-filled' = 'contract'
        'bad-finder-stub-extension-call' = 'contract'
        'bad-finder-stub-call-after-return' = 'contract'
        'bad-tray-hook-wrong-method' = 'contract'
        'bad-return-refresh-hook-wrong-method' = 'contract'
        'bad-return-refresh-two-callbacks' = 'contract'
        'bad-follow-hook-wrong-method' = 'contract'
        'bad-emoji-hook-wrong-method' = 'contract'
        'bad-emoji-pictures-hook-wrong-method' = 'contract'
        'bad-messenger-tap-hook-wrong-method' = 'contract'
        'bad-messenger-button-hook-wrong-method' = 'contract'
        'bad-double-tap-like-hook-wrong-method' = 'contract'
        'bad-double-tap-tap-hook-wrong-method' = 'contract'
        'bad-reel-speed-hook-wrong-method' = 'contract'
        'bad-reels-tab-dot-hook-wrong-method' = 'contract'
        'bad-follow-hook-also-elsewhere' = 'contract'
        'bad-register-added-helper' = 'register'
        'bad-register-wide-source' = 'register'
        'bad-register-own-wide' = 'register'
        'bad-register-changed' = 'register'
    }
    foreach ($shortcut in $shortcutCalls) { $bad["bad-shortcut-$($shortcut.Case)-left"] = 'contract' }
    $failures = @()
    $badResults = @{}
    foreach ($case in $bad.GetEnumerator()) {
        $apk = New-DexApk -Name $case.Key -Entries ([ordered]@{ 'classes.dex' = (Get-Dex $case.Key) })
        $result = Invoke-DexDiff -Clean $cleanApk -Patched $apk -Allowlist $emptyAllowlist `
            -Name $case.Key -Contracts $contracts
        $badResults[$case.Key] = $result
        $findings = Get-Findings $result
        $others = @($findings.Categories | Where-Object { $_ -ne $case.Value })
        if ($result.ExitCode -eq 0) {
            $failures += "$($case.Key) was accepted"
        } elseif ($findings.Categories.Count -eq 0) {
            $failures += "$($case.Key) failed without a $($case.Value) finding: $($result.Output -join ' | ')"
        } elseif ($others.Count -ne 0) {
            $failures += "$($case.Key) failed for more than $($case.Value): $($findings.Fails -join ' | ')"
        } elseif ((Get-Content -LiteralPath $result.Report -Raw) -notmatch "FAIL  $($case.Value): ") {
            $failures += "$($case.Key)'s report did not list its finding"
        }
    }
    if ($failures.Count -ne 0) { throw ($failures -join "`n") }

    # Each shortcut build fails on its own call's no-call rule alone, naming the one method that still
    # makes the call, so a rule that fires for another call, or for every call, fails here too.
    foreach ($shortcut in $shortcutCalls) {
        $name = "bad-shortcut-$($shortcut.Case)-left"
        $fails = @((Get-Findings $badResults[$name]).Fails)
        $expected = "[diff] FAIL: contract: $($shortcut.Callee) is still called outside Lapp/morphe/extension/, in $($shortcut.Site)"
        Assert-True ($fails.Count -eq 1 -and $fails[0] -ceq $expected) `
            "$name did not fail with its own no-call finding alone.`nExpected: $expected`nGot:`n$($fails -join "`n")"
        foreach ($other in $shortcutCalls) {
            $count = if ($other.Call -eq $shortcut.Call) { '1 call site, in ' } else { '0 call sites' }
            Assert-True (($badResults[$name].Output -join "`n") -match [regex]::Escape(
                "no-call $($other.Callee) outside Lapp/morphe/extension/: $count")) `
                "$name reported $($other.Call) wrong: expected '$count'.`n$($badResults[$name].Output -join "`n")"
        }
    }

    # Each logo build fails on the logo rule alone, for its own reason: no stand-in, a stand-in not
    # right after the logo's tap, one made on another view, two, one in another method holding the
    # first trace section, one there as well as in the builder, or a second builder.
    $logoSearch = "Lfixture/TopBar;->buildSearch$logoShape"
    $logoFails = [ordered]@{
        'bad-logo-hook-missing' = "[diff] FAIL: contract: $logoHook is not called in $logoBuilder, the one method holding $logoHeld"
        'bad-logo-hook-other-call' = "[diff] FAIL: contract: $logoHook is called in $logoBuilder, but not right after $logoTap"
        'bad-logo-hook-other-view' = "[diff] FAIL: contract: $logoHook is called in $logoBuilder on v2, not on v1, " +
            "the register $logoTap is made on"
        'bad-logo-hook-twice' = "[diff] FAIL: contract: $logoHook has 2 call sites in $logoBuilder, and must have exactly one"
        'bad-logo-hook-decoy' = "[diff] FAIL: contract: $logoHook is not called in $logoBuilder, the one method holding " +
            "$logoHeld; the host methods that call it: $logoSearch"
        'bad-logo-hook-also-elsewhere' = "[diff] FAIL: contract: $logoHook is called in $logoSearch as well as in " +
            "$logoBuilder, the one method holding $logoHeld"
        'bad-logo-two-builders' = "[diff] FAIL: contract: 2 methods hold $logoHeld, and exactly one must, so the rule " +
            "can't say which one calls ${logoHook}: $logoBuilder, Lfixture/TopBar;->buildLogoAgain$logoShape"
    }
    foreach ($case in $logoFails.GetEnumerator()) {
        $fails = @((Get-Findings $badResults[$case.Key]).Fails)
        Assert-True ($fails.Count -eq 1 -and $fails[0] -ceq $case.Value) `
            "$($case.Key) did not fail with its own logo finding alone.`nExpected: $($case.Value)`nGot:`n$($fails -join "`n")"
    }

    # Each watch-history build fails on the sole-call rule alone, for its own reason: no stand-in,
    # one in another method holding the mutation's name, one there as well as in the flush, two,
    # Facebook's call left beside it, one on the registers the wrong way round, or a second flush.
    $watchDescribe = 'Lfixture/SeenStateBatcher;->describe()V'
    $watchFails = [ordered]@{
        'bad-watch-hook-missing' = "[diff] FAIL: contract: $watchSend is not called in $watchFlush, the one method holding $watchHeld"
        'bad-watch-hook-decoy' = "[diff] FAIL: contract: $watchSend is not called in $watchFlush, the one method holding " +
            "$watchHeld; the host methods that call it: $watchDescribe"
        'bad-watch-hook-also-elsewhere' = "[diff] FAIL: contract: $watchSend is called in $watchDescribe as well as in " +
            "$watchFlush, the one method holding $watchHeld"
        'bad-watch-hook-twice' = "[diff] FAIL: contract: $watchSend has 2 call sites in $watchFlush, and must have exactly one"
        'bad-watch-execute-left' = "[diff] FAIL: contract: $watchFlush still calls $watchExecute, which $watchSend stands in for"
        'bad-watch-hook-other-registers' = "[diff] FAIL: contract: $watchSend is called in $watchFlush on v1, v2, but the " +
            "clean build calls $watchExecute there on v2, v1"
        'bad-watch-two-flushes' = "[diff] FAIL: contract: 2 methods hold $watchHeld, and exactly one must, so the rule " +
            "can't say which one calls ${watchSend}: $watchFlush, Lfixture/SeenStateBatcher;->flushAgain()V"
    }
    foreach ($case in $watchFails.GetEnumerator()) {
        $fails = @((Get-Findings $badResults[$case.Key]).Fails)
        Assert-True ($fails.Count -eq 1 -and $fails[0] -ceq $case.Value) `
            "$($case.Key) did not fail with its own watch-history finding alone.`nExpected: $($case.Value)`nGot:`n$($fails -join "`n")"
    }
    # Each swap build fails on the once-call rule alone, for its own reason: no guard, one in another
    # method of the runnable holding the first size, one there as well as in run(), two, or a
    # second run().
    $swapDescribe = 'Lfixture/EdgeSwap;->describe()V'
    $swapFails = [ordered]@{
        'bad-swap-hook-missing' = "[diff] FAIL: contract: $swapHook is not called in $swapRun, the one method holding $swapHeld"
        'bad-swap-hook-decoy' = "[diff] FAIL: contract: $swapHook is not called in $swapRun, the one method holding " +
            "$swapHeld; the host methods that call it: $swapDescribe"
        'bad-swap-hook-also-elsewhere' = "[diff] FAIL: contract: $swapHook is called in $swapDescribe as well as in " +
            "$swapRun, the one method holding $swapHeld"
        'bad-swap-hook-twice' = "[diff] FAIL: contract: $swapHook has 2 call sites in $swapRun, and must have exactly one"
        'bad-swap-two-runs' = "[diff] FAIL: contract: 2 methods hold $swapHeld, and exactly one must, so the rule " +
            "can't say which one calls ${swapHook}: $swapRun, Lfixture/EdgeSwap;->runAgain()V"
    }
    foreach ($case in $swapFails.GetEnumerator()) {
        $fails = @((Get-Findings $badResults[$case.Key]).Fails)
        Assert-True ($fails.Count -eq 1 -and $fails[0] -ceq $case.Value) `
            "$($case.Key) did not fail with its own swap finding alone.`nExpected: $($case.Value)`nGot:`n$($fails -join "`n")"
    }
    # Each chat build fails on its own rule alone: the typing hook missing or after a branch, the
    # read receipts hook missing or there twice.
    $typingHook = 'Lapp/morphe/extension/facebook/chats/TypingIndicator;->chatTyping(Z)Z'
    $typingSite = 'Lfixture/Mailbox;->setTyping(Z)V'
    $chatFails = [ordered]@{
        'bad-typing-hook-missing' = "[diff] FAIL: contract: $typingHook is not called in $typingSite, the one method holding " +
            '"setTypingIndicatorForThreadWithThreadIdentifier"'
        'bad-typing-hook-late' = "[diff] FAIL: contract: $typingHook is called in $typingSite, but after a call, branch, " +
            'switch, return or throw, not first'
        'bad-read-receipts-hook-missing' = "[diff] FAIL: contract: $readHook is not called in $readSite, the one method holding " +
            '"markAsReadThreadWithThreadIdentifier"'
        'bad-read-receipts-hook-twice' = "[diff] FAIL: contract: $readHook has 2 call sites in $readSite, and must have exactly one"
    }
    foreach ($case in $chatFails.GetEnumerator()) {
        $fails = @((Get-Findings $badResults[$case.Key]).Fails)
        Assert-True ($fails.Count -eq 1 -and $fails[0] -ceq $case.Value) `
            "$($case.Key) did not fail with its own chat finding alone.`nExpected: $($case.Value)`nGot:`n$($fails -join "`n")"
    }
    # And against a clean build whose flush makes no executor call, the good build's stand-in has
    # nothing it took the place of.
    $noHandOverClean = New-DexApk -Name 'clean-no-hand-over' -Entries ([ordered]@{ 'classes.dex' = (Get-Dex 'clean-no-hand-over') })
    $noHandOver = Invoke-DexDiff -Clean $noHandOverClean -Patched (Join-Path $caseRoot 'good.apk') `
        -Allowlist $emptyAllowlist -Name 'no-hand-over' -Contracts $contracts
    $noHandOverFails = @((Get-Findings $noHandOver).Fails)
    $noHandOverExpected = "[diff] FAIL: contract: $watchSend is called in $watchFlush on v2, v1, but the clean build " +
        "calls $watchExecute there 0 times, not once"
    Assert-True ($noHandOver.ExitCode -ne 0 -and $noHandOverFails.Count -eq 1 -and $noHandOverFails[0] -ceq $noHandOverExpected) `
        "A stand-in for a call the clean build never made was accepted.`nExpected: $noHandOverExpected`nGot:`n$($noHandOver.Output -join "`n")"

    # The GenAI reel stub's three builds fail on that stub's own rule alone: a call that stays in the
    # extension, or Facebook's finder reached only after a return, is no fill.
    $finderStub = 'Lapp/morphe/extension/facebook/feed/GenAiReelFilter;->transparencyAttribution(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;'
    foreach ($name in 'bad-finder-stub-not-filled', 'bad-finder-stub-extension-call', 'bad-finder-stub-call-after-return') {
        $fails = @((Get-Findings $badResults[$name]).Fails)
        $expected = "[diff] FAIL: contract: $finderStub returns before it calls a method outside Lapp/morphe/extension/, " +
            "so the patch didn't fill it"
        Assert-True ($fails.Count -eq 1 -and $fails[0] -ceq $expected) `
            "$name did not fail on the GenAI reel stub's rule alone.`nExpected: $expected`nGot:`n$($fails -join "`n")"
    }

    # A hook in the wrong method names the one method its rule picks and where the hook went; a rule
    # two methods answer names both; a register out of range names the instruction, the register it
    # reaches and the count, in the method it sits in. Each FAIL line has to be one of these.
    $wrongPlace = [ordered]@{
        'bad-tray-hook-missing' = @(('*contract: Lapp/morphe/extension/facebook/feed/FeedFilter;->storiesTrayCount(Ljava/lang/Object;II)I ' +
            'has 2 call sites, and must have exactly 3, each in getItemCount of a class of its own: Lfixture/ClassicTray;->getItemCount()I, Lfixture/ComposerRow;->getItemCount()I'))
        'bad-tray-count-twice' = @(('*contract: Lapp/morphe/extension/facebook/feed/FeedFilter;->storiesTrayCount(Ljava/lang/Object;II)I ' +
            'is called more than once in one class''s getItemCount: Lfixture/ClassicTray;->getItemCount()I, Lfixture/ClassicTray;->getItemCount()I, Lfixture/ComposerRow;->getItemCount()I'))
        'bad-tray-hook-wrong-method' = @(('*contract: Lapp/morphe/extension/facebook/feed/FeedFilter;->storiesTrayCount(Ljava/lang/Object;II)I ' +
            'is called from Lfixture/UnifiedTray;->countRows()I, not from getItemCount'))
        'bad-return-refresh-hook-wrong-method' = @(('*contract: Lapp/morphe/extension/facebook/feed/ReturnRefresh;->skip()Z is not ' +
            'called in Lfixture/ReturnController;->resumeAfterBackground(Ljava/lang/Object;)V, the one method holding ' +
            '"FeedRefreshTriggerController" and "onRefresh"; the host methods that call it: Lfixture/ReturnController;->onPause()V'))
        'bad-follow-hook-wrong-method' = @(('*contract: Lapp/morphe/extension/facebook/reels/ReelDeclutter;->hideFollowButton()Z is not ' +
            'called in Lfixture/FollowCheck;->offersFollow(Lcom/facebook/auth/usersession/FbUserSession;)Z, the one method holding ' +
            '"friendly_feed" and "friends_tab_ifu" with the shape static (Lcom/facebook/auth/usersession/FbUserSession;*)Z; the host ' +
            'methods that call it: Lfixture/FollowCheck;->offersFollowHere(Lcom/facebook/auth/usersession/FbUserSession;)Z'))
        'bad-emoji-hook-wrong-method' = @(('*contract: Lapp/morphe/extension/facebook/emoji/SystemEmoji;->typeface()Landroid/graphics/Typeface; ' +
            'is not called in Lfixture/EmojiProvider;->emojiTypeface()Landroid/graphics/Typeface;, the one method holding ' +
            '"fb.e2e.force_system_emoji_font" and "FacebookEmojiTypefaceProviderImpl" with the shape instance ' +
            '()Landroid/graphics/Typeface;; the host methods that call it: Lfixture/EmojiProvider;->loggedTypeface()Landroid/graphics/Typeface;'))
        'bad-emoji-pictures-hook-wrong-method' = @(('*contract: Lapp/morphe/extension/facebook/emoji/SystemEmoji;->skipRemoteEmoji()Z ' +
            'is not called in Lfixture/EmojiPictures;->makeUrl(Ljava/lang/String;Lfixture/EmojiSize;Ljava/lang/String;I)Ljava/lang/String;, ' +
            'the one method holding "https://www.facebook.com/images/mobileemoji" with the shape static (Ljava/lang/String;*)Ljava/lang/String;; ' +
            'the host methods that call it: Lfixture/EmojiPictures;->pictureAddress()Ljava/lang/String;'))
        'bad-messenger-tap-hook-wrong-method' = @(('*contract: Lapp/morphe/extension/facebook/chats/MessengerIcon;->open(Landroid/content/Context;Z)Z ' +
            'is not called in Lfixture/MessengerBar;->tap(Landroid/content/Context;Lcom/facebook/auth/usersession/FbUserSession;Ljava/lang/String;Z)V, ' +
            'the one method holding "entry_point_navbar_global_icon_" and "entry_point_navbar_global_icon_reels_tab" with the shape ' +
            'static (Landroid/content/Context;Lcom/facebook/auth/usersession/FbUserSession;*)V; the host methods that call it: ' +
            '*Lfixture/MessengerBar;->tapEntry(Landroid/content/Context;Lcom/facebook/auth/usersession/FbUserSession;Ljava/lang/String;Z)V*'))
        'bad-messenger-button-hook-wrong-method' = @(('*contract: Lapp/morphe/extension/facebook/chats/MessengerIcon;->open(Landroid/content/Context;Z)Z ' +
            'is not called in Lfixture/MessengerBar;->button(Landroid/content/Context;Lcom/facebook/auth/usersession/FbUserSession;Ljava/lang/String;ZZ)V, ' +
            'the one method holding "long_press" with the shape static (Landroid/content/Context;Lcom/facebook/auth/usersession/FbUserSession;' +
            'Ljava/lang/String;ZZ)V; the host methods that call it: ' +
            '*Lfixture/MessengerBar;->buttonLog(Landroid/content/Context;Lcom/facebook/auth/usersession/FbUserSession;Ljava/lang/String;Z)V*'))
        'bad-double-tap-like-hook-wrong-method' = @(('*contract: Lapp/morphe/extension/facebook/reels/DoubleTapLike;->holdBackLike(Ljava/lang/String;)Z ' +
            'is not called in Lfixture/ReelLikeHelper;->like(Lcom/facebook/auth/usersession/FbUserSession;Ljava/lang/Object;Ljava/lang/String;)V, ' +
            'the one method holding "FbShortsMutationUtil.mutateViewerLikeReaction" with the shape instance ' +
            '(Lcom/facebook/auth/usersession/FbUserSession;*)V; the host methods that call it: ' +
            '*Lfixture/ReelLikeHelper;->likeStatic(Lcom/facebook/auth/usersession/FbUserSession;Ljava/lang/Object;Ljava/lang/String;)V*'))
        'bad-double-tap-tap-hook-wrong-method' = @(('*contract: Lapp/morphe/extension/facebook/reels/DoubleTapLike;->holdBackTap()Z ' +
            'is not called in Lfixture/AttachmentTap;->onDoubleTap(Landroid/view/MotionEvent;)Z, the one method holding "translationY" ' +
            'with the shape instance (Landroid/view/MotionEvent;)Z; the host methods that call it: ' +
            '*Lfixture/AttachmentTap;->animateHeart(Landroid/view/MotionEvent;)Z*'))
        'bad-reel-speed-hook-wrong-method' = @(('*contract: Lapp/morphe/extension/facebook/media/ReelSpeed;->picked(F)V ' +
            'is not called in Lfixture/SpeedToast;->show(Landroid/content/Context;F)V, the one method holding ' +
            '"InlinePlaybackSpeedAttributeSelector" with the shape static (Landroid/content/Context;F)V; the host methods ' +
            'that call it: *Lfixture/SpeedToast;->showOver(Landroid/content/Context;F)V*'))
        'bad-reels-tab-dot-hook-wrong-method' = @(('*contract: Lapp/morphe/extension/facebook/navigation/ReelsTabDot;->clear(Ljava/lang/Object;)Z ' +
            'is not called in Lfixture/JewelController;->count(Lcom/facebook/auth/usersession/FbUserSession;Lfixture/JewelController;' +
            'Lcom/facebook/navigation/tabbar/state/model/TabTag;I)I, the one method holding ' +
            '"FbMainTabActivityJewelController.getTrackedCountWithLogging" with the shape static ' +
            '(Lcom/facebook/auth/usersession/FbUserSession;*Lcom/facebook/navigation/tabbar/state/model/TabTag;I)I; ' +
            'the host methods that call it: *Lfixture/JewelController;->countOver(Lcom/facebook/auth/usersession/FbUserSession;' +
            'Lfixture/JewelController;Lcom/facebook/navigation/tabbar/state/model/TabTag;I)I*'))
        'bad-follow-hook-also-elsewhere' = @(('*contract: Lapp/morphe/extension/facebook/reels/ReelDeclutter;->hideFollowButton()Z ' +
            'is called in Lfixture/FollowCheck;->offersFollowHere(Lcom/facebook/auth/usersession/FbUserSession;)Z as well as in ' +
            'Lfixture/FollowCheck;->offersFollow(Lcom/facebook/auth/usersession/FbUserSession;)Z, the one method holding ' +
            '"friendly_feed" and "friends_tab_ifu" with the shape static (Lcom/facebook/auth/usersession/FbUserSession;*)Z'))
        'bad-return-refresh-two-callbacks' = @(('*contract: 2 methods hold "FeedRefreshTriggerController" and "onRefresh", and exactly ' +
            'one must, so the rule can''t say which one calls Lapp/morphe/extension/facebook/feed/ReturnRefresh;->skip()Z: *' +
            'Lfixture/ReturnController;->resume*(Ljava/lang/Object;)V, Lfixture/ReturnController;->resume*(Ljava/lang/Object;)V'))
        'bad-register-added-helper' = @('*register: const/4 at 0 reaches v1, and the method declares 1 register  in Lfixture/Feed;->helper()V')
        'bad-register-wide-source' = @('*register: move-wide at 0 reaches v2, and the method declares 2 registers  in Lfixture/Feed;->copyWide()V')
        'bad-register-own-wide' = @(('*register: const-wide/16 at 0 reaches v2, and the method declares 2 registers  in ' +
            'Lapp/morphe/extension/facebook/feed/Pack;->pack()V'))
        'bad-register-changed' = @(
            '*register: move-result at 3 reaches v4, and the method declares 4 registers  in Lfixture/Feed;->addNewEdgeToCollection(*',
            '*register: if-eqz at 4 reaches v4, and the method declares 4 registers  in Lfixture/Feed;->addNewEdgeToCollection(*')
        # The positive control for Morphe's inline compiler dropping invoke-static { p2 } in a method
        # of 18 registers: the lone move-result is named, with the class and method it opens.
        'bad-dropped-invoke' = @(('*result: move-result at 0 does not follow an invoke or filled-new-array; it opens the ' +
            'method  in Lfixture/IconBadger;->write(Landroid/content/Context;I)V'))
    }
    foreach ($case in $wrongPlace.GetEnumerator()) {
        $fails = @((Get-Findings $badResults[$case.Key]).Fails)
        $unmatched = @($fails | Where-Object { $line = $_; @($case.Value | Where-Object { $line -like $_ }).Count -eq 0 })
        $missing = @($case.Value | Where-Object { $pattern = $_; @($fails | Where-Object { $_ -like $pattern }).Count -eq 0 })
        Assert-True ($fails.Count -eq $case.Value.Count -and $unmatched.Count -eq 0 -and $missing.Count -eq 0) `
            "$($case.Key) did not fail with the findings expected.`nExpected:`n$($case.Value -join "`n")`nGot:`n$($fails -join "`n")"
    }
    # The added helper is read against its own count in the report too, beside the extension's
    # methods, which were the only added ones read there before.
    $helperReport = Get-Content -LiteralPath $badResults['bad-register-added-helper'].Report -Raw
    Assert-True ($helperReport -match '(?m)^==== added Lfixture/Feed;->helper\(\)V\r?$' -and
        $helperReport -match 'const/4 v1, #0 \|maxreg=1   <<< REGISTER >= registerCount') `
        "The report did not hold the helper added to a host class to its register count.`n$helperReport"
    Assert-True (($badResults['bad-register-added-helper'].Output -join "`n") -match 'injected lines naming an out-of-range register: 1') `
        "The helper added to a host class was not counted among the out-of-range lines.`n$($badResults['bad-register-added-helper'].Output -join "`n")"

    # Without a contract file the structural checks still run; only the call-site rule is off.
    $noContract = Invoke-DexDiff -Clean $cleanApk -Patched (Join-Path $caseRoot 'bad-no-guard.apk') `
        -Allowlist $emptyAllowlist -Name 'no-contract-file'
    Assert-True ($noContract.ExitCode -eq 0) "A build with no guard failed with no contract to hold it to.`n$($noContract.Output -join "`n")"
    $noContractBranch = Invoke-DexDiff -Clean $cleanApk -Patched (Join-Path $caseRoot 'bad-branch.apk') `
        -Allowlist $emptyAllowlist -Name 'no-contract-branch'
    Assert-True ($noContractBranch.ExitCode -ne 0) 'A bad branch passed when no contract file was given.'

    $badContract = Join-Path $caseRoot 'bad-contract.txt'
    [System.IO.File]::WriteAllText($badContract, "single-call hideEdge`n")
    $unreadable = Invoke-DexDiff -Clean $cleanApk -Patched (Join-Path $caseRoot 'good.apk') `
        -Allowlist $emptyAllowlist -Name 'bad-contract' -Contracts $badContract
    Assert-True ($unreadable.ExitCode -ne 0 -and ($unreadable.Output -join "`n") -match 'Invalid contract line 1') `
        "A malformed contract line was accepted.`n$($unreadable.Output -join "`n")"
    # A first-call rule needs its method, "on" and a class descriptor.
    foreach ($line in @(
            'first-call Lapp/morphe/extension/facebook/feed/GenAiLabel;->detectedInfo(Ljava/lang/Object;)Ljava/lang/Object; on GraphQLStory',
            'first-call Lapp/morphe/extension/facebook/feed/GenAiLabel;->detectedInfo(Ljava/lang/Object;)Ljava/lang/Object; in Lcom/facebook/graphql/model/GraphQLStory;',
            'first-call detectedInfo on Lcom/facebook/graphql/model/GraphQLStory;',
            'start-call Lapp/morphe/extension/facebook/feed/FeedFilter;->hideStoriesTray(I)Z in addStoriesAdapter',
            'start-call hideStoriesTray holding stories_tray_create_adapter_stop',
            'start-call Lapp/morphe/extension/facebook/feed/FeedFilter;->hideStoriesTray(I)Z holding',
            'start-call Lapp/morphe/extension/facebook/feed/FeedFilter;->hideStoriesTray(I)Z holding tofu tofu',
            'start-call Lapp/morphe/extension/facebook/feed/FeedFilter;->hideStoriesTray(I)Z in static holding tofu',
            'start-call Lapp/morphe/extension/facebook/feed/FeedFilter;->hideStoriesTray(I)Z in (I)Z',
            'start-call Lapp/morphe/extension/facebook/feed/FeedFilter;->hideStoriesTray(I)Z in static (I)Z holding',
            'start-call Lapp/morphe/extension/facebook/feed/FeedFilter;->hideStoriesTray(I)Z in sometimes (I)Z holding tofu',
            'start-call Lapp/morphe/extension/facebook/feed/FeedFilter;->hideStoriesTray(I)Z in static I)Z holding tofu',
            'start-call Lapp/morphe/extension/facebook/feed/FeedFilter;->hideStoriesTray(I)Z tofu holding tofu',
            'count-call Lapp/morphe/extension/facebook/feed/FeedFilter;->storiesTrayCount(Ljava/lang/Object;II)I in getItemCount',
            'count-call Lapp/morphe/extension/facebook/feed/FeedFilter;->storiesTrayCount(Ljava/lang/Object;II)I in getItemCount 0',
            'count-call Lapp/morphe/extension/facebook/feed/FeedFilter;->storiesTrayCount(Ljava/lang/Object;II)I in getItemCount two',
            'count-call Lapp/morphe/extension/facebook/feed/FeedFilter;->storiesTrayCount(Ljava/lang/Object;II)I on getItemCount 2',
            'count-call storiesTrayCount in getItemCount 2',
            'first-call Lapp/morphe/extension/facebook/feed/ShowcaseType;->storyType(Ljava/lang/Object;)Ljava/lang/Object; on-type-named Lfixture/Showcase;',
            'first-call Lapp/morphe/extension/facebook/feed/ShowcaseType;->storyType(Ljava/lang/Object;)Ljava/lang/Object; on-type-named',
            'first-call storyType on-type-named ShowcaseFeedUnit',
            'first-call Lapp/morphe/extension/facebook/feed/GenAiReelFilter;->transparencyAttribution(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object; outside Lapp/morphe/extension',
            'first-call transparencyAttribution outside Lapp/morphe/extension/',
            'first-call Lapp/morphe/extension/facebook/feed/GenAiReelFilter;->transparencyAttribution(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object; outside',
            'no-call Landroid/content/pm/ShortcutManager;->pushDynamicShortcut(Landroid/content/pm/ShortcutInfo;)V in Lapp/morphe/extension/',
            'no-call pushDynamicShortcut outside Lapp/morphe/extension/',
            'no-call Landroid/content/pm/ShortcutManager;->pushDynamicShortcut(Landroid/content/pm/ShortcutInfo;)V outside',
            'no-call Landroid/content/pm/ShortcutManager;->pushDynamicShortcut(Landroid/content/pm/ShortcutInfo;)V outside Lapp/morphe/extension',
            "next-call $logoHook after setOnClickListener holding WordmarkNavigationBar#createWordmarkView",
            "next-call setLogoTouchListener after $logoTap holding WordmarkNavigationBar#createWordmarkView",
            "next-call $logoHook after $logoTap in WordmarkNavigationBar#createWordmarkView",
            "next-call $logoHook before $logoTap holding WordmarkNavigationBar#createWordmarkView",
            "next-call $logoHook after $logoTap holding",
            "next-call $logoHook holding WordmarkNavigationBar#createWordmarkView",
            "next-call $logoHook after $logoTap holding tofu tofu",
            "next-call $logoHook after $logoTap in static holding tofu",
            "next-call $logoHook after $logoTap in static $logoShape",
            "next-call $logoHook after $logoTap in sometimes $logoShape holding tofu",
            "next-call $logoHook after $logoTap in static Landroid/content/Context;)V holding tofu",
            "next-call $logoHook after $logoTap tofu holding tofu",
            "sole-call $watchSend holding FbShortsSeenStateMutation video_ids",
            "sole-call $watchSend replacing execute holding FbShortsSeenStateMutation video_ids",
            "sole-call send replacing $watchExecute holding FbShortsSeenStateMutation video_ids",
            "sole-call $watchSend after $watchExecute holding FbShortsSeenStateMutation video_ids",
            "sole-call $watchSend replacing $watchExecute holding",
            "sole-call $watchSend replacing $watchExecute holding video_ids video_ids",
            "sole-call $watchSend replacing $watchExecute in instance holding video_ids",
            "sole-call $watchSend replacing $watchExecute in instance ()V",
            "once-call hideSwappedEdge holding sizeBefore sizeAfter",
            "once-call $swapHook holding",
            "once-call $swapHook holding sizeBefore sizeBefore",
            "once-call $swapHook after $watchExecute holding sizeBefore",
            "once-call $swapHook in instance ()V",
            "once-call $swapHook in sometimes ()V holding sizeBefore",
            "once-call $swapHook in instance holding sizeBefore")) {
        [System.IO.File]::WriteAllText($badContract, "# a comment line first`n$line`n")
        $unreadableFirstCall = Invoke-DexDiff -Clean $cleanApk -Patched (Join-Path $caseRoot 'good.apk') `
            -Allowlist $emptyAllowlist -Name 'bad-first-call-contract' -Contracts $badContract
        Assert-True ($unreadableFirstCall.ExitCode -ne 0 -and ($unreadableFirstCall.Output -join "`n") -match 'Invalid contract line 2') `
            "A malformed first-call line was accepted: $line`n$($unreadableFirstCall.Output -join "`n")"
    }

    $removedMethodApk = New-DexApk -Name 'removed-method' -Entries ([ordered]@{ 'classes.dex' = (Get-Dex 'removed-method') })
    $methodResult = Invoke-DexDiff -Clean $cleanApk -Patched $removedMethodApk `
        -Allowlist $emptyAllowlist -Name 'removed-method' -Contracts $contracts
    Assert-True ($methodResult.ExitCode -ne 0) 'A removed host method was accepted.'
    Assert-True (($methodResult.Output -join "`n") -match 'Lfixture/Feed;->removable\(\)V') `
        'The removed-method failure did not name the method.'

    $methodAllowlist = Join-Path $caseRoot 'method-allowlist.txt'
    [System.IO.File]::WriteAllText($methodAllowlist, "method Lfixture/Feed;->removable()V`n")
    $methodAllowed = Invoke-DexDiff -Clean $cleanApk -Patched $removedMethodApk `
        -Allowlist $methodAllowlist -Name 'allowed-method' -Contracts $contracts
    Assert-True ($methodAllowed.ExitCode -eq 0) "An exact reviewed method removal was rejected.`n$($methodAllowed.Output -join "`n")"

    $staleAllowlist = Join-Path $caseRoot 'stale-allowlist.txt'
    [System.IO.File]::WriteAllText($staleAllowlist, "method Lfixture/Feed;->notRemoved()V`n")
    $staleResult = Invoke-DexDiff -Clean $cleanApk -Patched $removedMethodApk `
        -Allowlist $staleAllowlist -Name 'stale-allowlist' -Contracts $contracts
    Assert-True ($staleResult.ExitCode -ne 0) 'A stale removal allowlist entry was accepted.'
    Assert-True (($staleResult.Output -join "`n") -match 'notRemoved') `
        'The stale allowlist failure did not name the stale entry.'
    Assert-True ((Get-Content -LiteralPath $staleResult.Report -Raw) -match 'notRemoved') `
        'The stale allowlist report did not name the stale entry.'

    $dexCleanApk = New-DexApk -Name 'dex-clean' -Entries ([ordered]@{
        'classes.dex' = (Get-Dex 'clean')
        'classes2.dex' = (Get-Dex 'secondary')
    })
    $dexPatchedApk = New-DexApk -Name 'dex-patched' -Entries ([ordered]@{
        'classes.dex' = (Get-Dex 'good-with-secondary')
    })
    $dexResult = Invoke-DexDiff -Clean $dexCleanApk -Patched $dexPatchedApk `
        -Allowlist $emptyAllowlist -Name 'removed-dex' -Contracts $contracts
    Assert-True ($dexResult.ExitCode -ne 0) 'A removed DEX was accepted.'
    Assert-True (($dexResult.Output -join "`n") -match 'classes2\.dex') `
        'The removed-DEX failure did not name the DEX.'
    $dexAllowlist = Join-Path $caseRoot 'dex-allowlist.txt'
    [System.IO.File]::WriteAllText($dexAllowlist, "dex classes2.dex`n")
    $dexAllowed = Invoke-DexDiff -Clean $dexCleanApk -Patched $dexPatchedApk `
        -Allowlist $dexAllowlist -Name 'allowed-dex' -Contracts $contracts
    Assert-True ($dexAllowed.ExitCode -eq 0) "An exact reviewed DEX removal was rejected.`n$($dexAllowed.Output -join "`n")"

    # A signature defined in two dex entries, the way Facebook 580's merged bundle defines its
    # browser's methods: the host class again in a later entry, as it ships, on both sides. The
    # patch's definition in classes.dex is the one held to the checks, so the good build passes
    # with the same host changes as on its own, and the guard's answer put out of range fails as a
    # register finding and nothing else. Read one body per signature, the copy would stand for both.
    $copyEntry = 'lib/arm64-v8a/libcopy.dex.so'
    $twiceClean = New-DexApk -Name 'twice-clean' -Entries ([ordered]@{
        'classes.dex' = (Get-Dex 'clean'); $copyEntry = (Get-Dex 'host-copy') })
    $twiceBad = Invoke-DexDiff -Clean $twiceClean -Patched (New-DexApk -Name 'twice-bad' -Entries ([ordered]@{
        'classes.dex' = (Get-Dex 'bad-register-changed'); $copyEntry = (Get-Dex 'host-copy') })) `
        -Allowlist $emptyAllowlist -Name 'twice-bad' -Contracts $contracts
    $twiceBadFindings = Get-Findings $twiceBad
    Assert-True ($twiceBad.ExitCode -ne 0 -and $twiceBadFindings.Categories.Count -ne 0 -and
        @($twiceBadFindings.Categories | Where-Object { $_ -ne 'register' }).Count -eq 0) `
        "An out-of-range register in classes.dex, with an untouched copy of its method in another entry, wasn't refused as a register finding alone.`n$($twiceBad.Output -join "`n")"
    $twiceGood = Invoke-DexDiff -Clean $twiceClean -Patched (New-DexApk -Name 'twice-good' -Entries ([ordered]@{
        'classes.dex' = (Get-Dex 'good'); $copyEntry = (Get-Dex 'host-copy') })) `
        -Allowlist $emptyAllowlist -Name 'twice-good' -Contracts $contracts -Base $cleanApk
    $twiceGoodText = $twiceGood.Output -join "`n"
    Assert-True ($twiceGood.ExitCode -eq 0 -and (Get-Findings $twiceGood).Fails.Count -eq 0) `
        "The good build with a second copy of the host class failed.`n$twiceGoodText"
    Assert-True ($twiceGoodText -match "the clean APK carries clean\.apk's classes\*\.dex byte for byte") `
        "A merge carrying its base's classes.dex and a split's dex under another name wasn't matched to the base.`n$twiceGoodText"
    Assert-True ($twiceGoodText -match 'signatures defined in more than one dex entry: clean 6, patched 6') `
        "The host class's six methods were not counted as defined twice.`n$twiceGoodText"
    $changedAlone = [regex]::Match(($good.Output -join "`n"), 'host methods changed: (\d+)').Groups[1].Value
    Assert-True ($changedAlone -and $twiceGoodText -match "host methods changed: $changedAlone\b") `
        "The good build with a second copy of the host class didn't change the $changedAlone host methods it changes alone.`n$twiceGoodText"

    # A clean side carrying the bundle's own code is a patched build, whatever else differs.
    $patchedClean = Invoke-DexDiff -Clean (Join-Path $caseRoot 'good.apk') -Patched (Join-Path $caseRoot 'bad-branch.apk') `
        -Allowlist $emptyAllowlist -Name 'patched-clean' -Contracts $contracts
    Assert-True ($patchedClean.ExitCode -ne 0 -and ($patchedClean.Output -join "`n") -match
        'the clean APK carries \d+ methods under Lapp/morphe/ .*so it is a patched build') `
        "A clean side holding the bundle's code was accepted.`n$($patchedClean.Output -join "`n")"

    # Handed the signed base.apk, DexDiff holds the merge it reads to base.apk's classes*.dex as they
    # are, and no other classes*.dex; a split's dex under another name is what a merge adds. The
    # base itself as the clean side passes.
    $asBase = Invoke-DexDiff -Clean $cleanApk -Patched (Join-Path $caseRoot 'good.apk') `
        -Allowlist $emptyAllowlist -Name 'merge-is-base' -Contracts $contracts -Base $cleanApk
    Assert-True ($asBase.ExitCode -eq 0) "A clean APK held to itself as its base was refused.`n$($asBase.Output -join "`n")"
    foreach ($case in @(
            @{ Name = 'merge-changes'; Clean = (Join-Path $caseRoot 'clean-no-hand-over.apk'); Base = $cleanApk; Says = 'changes classes.dex' },
            @{ Name = 'merge-adds'; Clean = $dexCleanApk; Base = $cleanApk; Says = 'adds classes2.dex' },
            @{ Name = 'merge-lacks'; Clean = $cleanApk; Base = $dexCleanApk; Says = 'lacks classes2.dex' })) {
        $refused = Invoke-DexDiff -Clean $case.Clean -Patched (Join-Path $caseRoot 'good.apk') `
            -Allowlist $emptyAllowlist -Name $case.Name -Contracts $contracts -Base $case.Base
        $expected = "[diff] FAIL: the clean APK $(Split-Path -Leaf $case.Clean) doesn't carry " +
            "$(Split-Path -Leaf $case.Base)'s code as it is: it $($case.Says)."
        Assert-True ($refused.ExitCode -ne 0 -and @($refused.Output | Where-Object { $_.StartsWith($expected) }).Count -eq 1) `
            "A merge that $($case.Says) was not refused for it.`n$($refused.Output -join "`n")"
    }

    $same = Invoke-DexDiff -Clean $cleanApk -Patched $cleanApk -Allowlist $emptyAllowlist `
        -Name 'same-file' -Contracts $contracts
    Assert-True ($same.ExitCode -ne 0 -and ($same.Output -join "`n") -match 'no host method differs') `
        'One APK compared with itself was accepted.'
} finally {
    if ($caseRoot.StartsWith($requiredPrefix, [System.StringComparison]::OrdinalIgnoreCase) -and `
        (Test-Path -LiteralPath $caseRoot)) {
        Remove-Item -LiteralPath $caseRoot -Recurse -Force
    }
}

$global:LASTEXITCODE = 0
Write-Host "[scripts] injected-register and mutation contracts passed ($($bad.Count) bad fixtures, each refused for its own check)"
