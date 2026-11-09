<# Exercise phone-smoke.ps1's report reading on hand-written reports, without a phone. #>
[CmdletBinding()]
# The push gate hands every suite -Root. These checks read only the reports written below.
param([string]$Root)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'phone-smoke.ps1')

$failures = New-Object System.Collections.Generic.List[string]
function Assert-That([bool]$Condition, [string]$Message) {
    if (-not $Condition) { $failures.Add($Message) }
}

function New-Report([string[]]$Hooks, [string]$State = 'hushfacebook: running', [string[]]$Crash = @()) {
    $lines = @('MORPHE DIAGNOSTIC REPORT', 'schema: 1', 'app: com.facebook.katana 586.0.0.52.53 (2147483647)', $State,
        'debug_logging: off', '', '[APP STATE]', 'locale -> en-US')
    if ($Crash.Count -gt 0) { $lines += @('', '[LATEST JAVA CRASH]') + $Crash }
    if ($Hooks.Count -gt 0) { $lines += @('', '[HOOK STATUS]') + $Hooks }
    $lines += @('', '[PATCH OPTIONS]', 'Hide sponsored posts -> on')
    return ($lines -join "`n") + "`n"
}

$healthy = @(
    'Hide sponsored posts: invoked 237, 0 found, 0 missing',
    'Hide Meta upsells: invoked 5403, 3 found, 0 missing. Counted: Meta AI post button kept out 1740, Imagine entry kept out 348',
    'Open Messenger from the top bar: invoked 0, 1 found, 0 missing')
$read = Read-SmokeReport -Text (New-Report $healthy)
Assert-That ($read.Problems.Count -eq 0) "A healthy report reads as failing: $($read.Problems -join ' | ')"
Assert-That ($read.Families.Count -eq 3) "A healthy report gave $($read.Families.Count) families, not 3."
Assert-That ($read.Families[1].Invoked -eq 5403 -and $read.Families[1].Found -eq 3) 'Counts were read wrong.'
Assert-That ($read.Families[1].Detail -like 'Counted: Meta AI post button*') "Counted text was lost: $($read.Families[1].Detail)"
Assert-That ($read.Running) 'A running report reads as not running.'

$read = Read-SmokeReport -Text (New-Report @(
    'Hide sponsored posts: invoked 237, 0 found, 0 missing',
    "Tap to play: invoked 3, 1 found, 1 missing. First missing: a working 'tap' hook (it threw java.lang.NullPointerException)"))
Assert-That ($read.Problems.Count -eq 1 -and $read.Problems[0] -like 'Tap to play: 1 missing*NullPointerException*') `
    "A hook that threw wasn't the one problem: $($read.Problems -join ' | ')"

$read = Read-SmokeReport -Text (New-Report @('Hide tabs: invoked 12, 0 found, 2 ambiguous, 0 missing. First ambiguous: field LX/abc;->a'))
Assert-That ($read.Problems.Count -eq 1 -and $read.Families[0].Ambiguous -eq 2) 'An ambiguous lookup passed.'

$read = Read-SmokeReport -Text (New-Report @('Hide tabs: invoked 12, 0 found, 9 missing, and more it stopped counting'))
Assert-That ($read.Problems.Count -eq 1) 'A truncated family passed.'

$read = Read-SmokeReport -Text (New-Report $healthy -State 'hushfacebook: paused (user), every hook a setting controls takes Facebook''s own path')
Assert-That (-not $read.Running -and ($read.Problems -like '*paused*').Count -eq 1) 'A paused report passed.'

$read = Read-SmokeReport -Text (New-Report @('Masquer les publications: invoqué 3, 1 trouvé, 0 manquant'))
Assert-That ($read.Unread.Count -eq 1 -and ($read.Problems -like '*could not be read*').Count -eq 1) 'A translated line passed unread.'

$read = Read-SmokeReport -Text (New-Report @())
Assert-That (($read.Problems -like '*no `[HOOK STATUS`] table*').Count -eq 1) 'A report without the table passed.'

$read = Read-SmokeReport -Text (New-Report $healthy) -ExpectInvoked @('Open Messenger from the top bar', 'Comment sheet options')
Assert-That ($read.Problems.Count -eq 2) "Expected families didn't each fail once: $($read.Problems -join ' | ')"

$crash = @('schema: 1', 'timestamp_utc: 2026-10-07T21:13:05.931Z', 'exception: java.lang.IllegalStateException')
$walk = [DateTime]::SpecifyKind([DateTime]'2026-10-08T20:30:00', 'Utc')
$read = Read-SmokeReport -Text (New-Report $healthy -Crash $crash) -Since $walk
Assert-That ($read.Problems.Count -eq 0) "A crash from the day before failed the walk: $($read.Problems -join ' | ')"
$read = Read-SmokeReport -Text (New-Report $healthy -Crash $crash) -Since $walk.AddDays(-2)
Assert-That ($read.Problems.Count -eq 1 -and $read.Problems[0] -like '*IllegalStateException*') 'A crash during the walk passed.'
$read = Read-SmokeReport -Text (New-Report $healthy -Crash @('schema: 1', 'exception: java.lang.Error')) -Since $walk
Assert-That ($read.Problems.Count -eq 1) 'A crash with no time passed.'

$read = Read-SmokeReport -Text 'not a report'
Assert-That ($read.Problems.Count -ge 1) 'Text that is not a report passed.'

# device-audit.ps1 lifts Read-SmokeReport out of this script by name and reads the page and family
# names out of the extension's source, so a rename on either side has to fail here, not on a phone.
$errors = $null
$audit = [System.Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'device-audit.ps1'), [ref]$null, [ref]$errors)
Assert-That ($errors.Count -eq 0) "device-audit.ps1 doesn't parse: $($errors | Select-Object -First 1)"
$smokeAst = [System.Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'phone-smoke.ps1'), [ref]$null, [ref]$null)
Assert-That ($null -ne $smokeAst.Find({ param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Read-SmokeReport' }, $true)) `
    'device-audit.ps1 looks for Read-SmokeReport in phone-smoke.ps1 and it is gone.'
$settingsSource = Join-Path $PSScriptRoot '..\extensions\facebook\src\main\java\app\morphe\extension\facebook\settings'
$declaredSections = [regex]::Matches([IO.File]::ReadAllText((Join-Path $settingsSource 'SettingsNavigation.java')), 'section\("([^"]+)"')
Assert-That ($declaredSections.Count -eq 21) `
    'device-audit.ps1 reads the accordion sections as section("..." in SettingsNavigation.java and did not find all 21.'
$pageReader = $audit.Find({ param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Get-SettingsPages' }, $true)
Assert-That ($null -ne $pageReader) 'The settings route reader is missing.'
if ($pageReader) {
    $testScriptRoot = $PSScriptRoot
    . ([scriptblock]::Create($pageReader.Extent.Text.Replace('$PSScriptRoot', '$testScriptRoot')))
    $sweptPages = @(Get-SettingsPages)
    Assert-That ($sweptPages.Count -eq 23 -and $sweptPages[0] -ceq '' -and $sweptPages[-1] -ceq 'support' -and
        $sweptPages -cnotcontains 'more') 'The audit sweep does not cover the home list, all 21 categories and Support directly.'
    foreach ($section in $declaredSections) {
        Assert-That (@($sweptPages | Where-Object { $_ -ceq $section.Groups[1].Value }).Count -eq 1) `
            "The audit did not sweep section $($section.Groups[1].Value) exactly once."
    }
}
Assert-That ([regex]::Matches([IO.File]::ReadAllText((Join-Path $settingsSource 'FamilyNames.java')), 'public static final String \w+ = "([^"]+)"').Count -ge 50) `
    'device-audit.ps1 reads the family names out of FamilyNames.java and found fewer than 50.'
$expectations = Join-Path $PSScriptRoot 'device-audit-expectations.json'
if (Test-Path -LiteralPath $expectations) {
    $baseline = Get-Content -Raw -LiteralPath $expectations | ConvertFrom-Json
    Assert-That (@($baseline.ranOnWalk).Count -gt 0 -and $baseline.facebook) 'device-audit-expectations.json has no families or no Facebook version.'
}

# The launcher entry the smoke walk starts: default, alternate icon, none and several.
. (Join-Path $PSScriptRoot 'device-install.ps1')
$pkg = 'com.facebook.katana'
$main = 'com.facebook.katana.LoginActivity'
$alias = 'com.facebook.katana.LoginActivity.fab_ic'
function Get-TestLauncher([string[]]$Lines) { Select-AndroidLauncher -Lines $Lines -PackageName $pkg -LauncherClass $main }
function Test-LauncherRefused([string[]]$Lines) {
    try { Get-TestLauncher $Lines | Out-Null; return $false } catch { return $true }
}
Assert-That ((Get-TestLauncher @('1 activities found:', "  $pkg/.LoginActivity")) -ceq $main) 'The default launcher was not resolved.'
Assert-That ((Get-TestLauncher @('1 activities found:', "  $pkg/$alias")) -ceq $alias) 'The enabled alternate launcher alias was not resolved.'
Assert-That ((Get-TestLauncher @("  $pkg/.LoginActivity.fab_ic")) -ceq $alias) 'A short alias name was not expanded.'
Assert-That ((Get-TestLauncher @("1 activities found:`r", "  $pkg/$alias`r")) -ceq $alias) 'A CRLF launcher line was not resolved.'
Assert-That (Test-LauncherRefused @('No activities found')) 'A missing launcher passed.'
Assert-That (Test-LauncherRefused @()) 'An empty launcher query passed.'
Assert-That (Test-LauncherRefused @("  $pkg/com.facebook.katana.OtherActivity", '  com.other.app/.Main')) 'Unrelated activities passed as a launcher.'
Assert-That (Test-LauncherRefused @("  $pkg/.LoginActivity", "  $pkg/$alias")) 'Two enabled launchers passed as one.'
$smokeText = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'phone-smoke.ps1'))
Assert-That (-not $smokeText.Contains('/.LoginActivity"')) 'phone-smoke.ps1 hardcodes the default launcher again.'
Assert-That ($smokeText.IndexOf('Select-AndroidLauncher') -lt $smokeText.IndexOf('am force-stop')) 'The launcher is not resolved before Facebook is started.'
Assert-That ([regex]::Matches($smokeText, 'am start -W (--activity-clear-top )?-n \$Entry').Count -eq 2) 'A launch in phone-smoke.ps1 bypasses the resolved launcher.'
# The walk can end on a reel or another full-screen page, and a launcher intent that only adds an
# extra matches the task's root, so Android drops it there unless the screens above are cleared.
Assert-That ([regex]::IsMatch($smokeText, 'am start -W --activity-clear-top -n \$Entry --ez app\.morphe\.extension\.facebook\.OPEN_SETTINGS true')) `
    'The settings request after the walk can be dropped under another screen.'
if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Host "[phone-smoke] FAIL $_" }
    throw "$($failures.Count) phone-smoke report check(s) failed."
}
Write-Host '[phone-smoke] report reading checks passed'
& (Join-Path $PSScriptRoot 'test-device-audit.ps1')
