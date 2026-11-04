<#
.SYNOPSIS
    Hold the Facebook-family source ledger and its audit to their rules, with no network.

.DESCRIPTION
    The checked-in ledger has to keep every rule of scripts/facebook-sources.ps1, and
    docs/sources.md can't link a source the ledger doesn't know or call a behavior-only source
    portable. Then each rule is broken once on a copy, and each has to be refused by name: an
    adopted source missing its pinned commit, compatible licence, NOTICE entry, provenance rule or
    fixture evidence (every declared build, two once two are declared), an unlicensed or contaminated source that isn't behavior-only, a
    provenance rule crediting a source the ledger doesn't allow, an index with no listing record.
    The census is held to its 14 days on both sides of the line.

    scripts/audit-facebook-sources.ps1 then runs against a stand-in for every forge and index it
    reads, so nothing here touches the network: a request the stand-in doesn't know fails the case.
    A clean run stamps only dates and collapses a byte-for-byte copy into its lineage; an addition
    from an index or code search, a removed licence, a moved branch head, a new branch with
    Facebook-family work, an unseen fork, a repository that's gone and a changed listing each exit
    1 with the ledger untouched. Through all of it the audit writes nothing but its report and the
    ledger's dates, and a parse of its source finds nothing that clones, downloads to disk or
    copies files, with a copy that does each of those as the proof the parse can see them.

    scripts/gitlab-coverage.ps1 and scripts/scan-gitlab-coverage.ps1 run against a stand-in for
    gitlab.com the same way. A run pages through the project list, pins every branch at its commit
    and reads every tree by that commit. It carries on from its ledger after a stop mid-page, a
    record cut short, a 429 and a used-up rate-limit window, with nothing lost, duplicated or asked
    for twice. A repeated page, a page out of sequence, a failed or oversized tree, an unreadable
    repository and a list that ends below the ceiling each keep the ledger from being complete, and
    projects created after the ceiling was pinned are never read. The sentinel check has to match
    the stand-in asked directly and name each kind of difference. None of it may touch the census.

    The pre-push hook runs this for the audit, its rules, the ledger, provenance.json and NOTICE,
    and for the coverage ledger's two scripts.
#>
[CmdletBinding()]
param([string]$Root)

$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
. (Join-Path $PSScriptRoot 'facebook-sources.ps1')
. (Join-Path $PSScriptRoot 'gitlab-coverage.ps1')

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Assert-Throws {
    param([scriptblock]$Action, [string]$Pattern, [string]$Message)
    try {
        & $Action
    } catch {
        if ($_.Exception.Message -like $Pattern) { return }
        throw "$Message Unexpected error: $($_.Exception.Message)"
    }
    throw "$Message No error was raised."
}

function Copy-Json {
    param($Value)
    return ($Value | ConvertTo-Json -Depth 20 | ConvertFrom-Json)
}

function Get-Sha256Text {
    param([string]$Text)
    $hasher = [Security.Cryptography.SHA256]::Create()
    try {
        return -join ($hasher.ComputeHash([Text.Encoding]::UTF8.GetBytes($Text)) | ForEach-Object { $_.ToString('x2') })
    } finally { $hasher.Dispose() }
}

$caseRoot = Join-Path ([IO.Path]::GetTempPath()) ("hushfacebook-sources-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $caseRoot -Force | Out-Null
$savedGitLabToken = $env:GITLAB_TOKEN
# A maintainer's own token never reaches the stand-ins; the cases that need one set their own.
$env:GITLAB_TOKEN = $null
try {

# --- the checked-in ledger ------------------------------------------------------------------------

$ledgerPath = Get-SourceLedgerPath -Root $Root
$ledger = Read-SourceLedger -Path $ledgerPath
$checkedIn = Test-SourceLedger -Ledger $ledger -Root $Root
Assert-True $checkedIn.Valid ("The checked-in ledger breaks its rules: " + ($checkedIn.Problems -join ' | '))
$entries = @($ledger.entries)
$availabilityCounts = Get-SourceAvailabilityCounts $entries
Assert-True ((Get-SourceAvailability ('{"availability":{"status":"retired"}}' | ConvertFrom-Json)) -eq 'retired' -and
    (Get-SourceAvailability ('{"availability":{"status":"unavailable"}}' | ConvertFrom-Json)) -eq 'unavailable' -and
    (Get-SourceAvailability ('{}' | ConvertFrom-Json)) -eq 'active' -and
    (Get-SourceAvailability ('{"availability":null}' | ConvertFrom-Json)) -eq '') `
    'Parsed JSON records lost their explicit availability or a null record became active.'
Assert-True ($availabilityCounts.activeSources + $availabilityCounts.historicalSources -eq $entries.Count -and
    $availabilityCounts.retiredSources + $availabilityCounts.unavailableSources -eq $availabilityCounts.historicalSources) `
    'The availability counts dropped or counted a source twice.'
$mben = @($entries | Where-Object { $_.id -eq 'froggo-mben' })[0]
Assert-True ((Get-SourceAvailability $mben) -eq 'retired' -and $mben.disposition -eq 'candidate' -and -not $mben.archived -and
    @($mben.branches | Where-Object { $_.commit -ne '4b5ce29fdbabc7417ed5f4c2108cdf24692896b0' }).Count -eq 0 -and
    @($mben.targetVersions.'com.facebook.katana') -contains '573.0.0.37.74') `
    'The retired mben25 source lost its reachable Facebook 573 history, or retirement changed its licence disposition or archive flag.'
Assert-True (@($entries | Where-Object { $_.disposition -eq 'adopted' }).Count -ge 1) `
    'The checked-in ledger adopts nothing, so the adopted gates below would test an empty set.'
foreach ($package in Get-SourcePackages) {
    Assert-True (@($entries | Where-Object { @($_.packages) -contains $package }).Count -gt 0) "The checked-in ledger has no $package source."
}

# docs/sources.md is the readable version of the ledger. A source it links has to be one the
# ledger knows, and a behavior-only source can never be described as portable: the line that
# said NexAlloy's Facebook code could be ported with credit is what this item started from.
$known = @{}
foreach ($entry in $entries) {
    $known[(ConvertTo-SourceKey $entry.repository)] = $entry
    foreach ($mirror in @($entry.mirrors | Where-Object { $_ })) { $known[(ConvertTo-SourceKey $mirror.repository)] = $entry }
    if ($entry.upstream) { $known[(ConvertTo-SourceKey $entry.upstream)] = $entry }
}
foreach ($item in @($ledger.outOfScope | Where-Object { $_ })) { $known[(ConvertTo-SourceKey $item.repository)] = $item }
$known[(ConvertTo-SourceKey $ledger.self.repository)] = $ledger.self
$known[(ConvertTo-SourceKey $ledger.officialBundle.repository)] = $ledger.officialBundle
foreach ($index in @($ledger.indexes)) {
    foreach ($address in @($index.url, $index.hushfacebook.url)) {
        $indexKey = ConvertTo-SourceKey $address
        if ($indexKey) { $known[$indexKey] = $index }
    }
}
function Test-SourcesDoc {
    param([string]$Text)
    $problems = New-Object System.Collections.Generic.List[string]
    foreach ($match in [regex]::Matches($Text, 'https://(?:github\.com|gitlab\.com)/[A-Za-z0-9_.\-/]+')) {
        $key = ConvertTo-SourceKey ($match.Value.TrimEnd('.', ')'))
        if ($key -and -not $known.ContainsKey($key)) { $problems.Add("docs/sources.md links $key, which the ledger doesn't know.") }
    }
    $portable = '(?i)\b(can be ported|ported with credit|port(ed)? it with credit|can be copied|copy its code)\b'
    foreach ($line in ($Text -split "`r?`n")) {
        if ($line -notmatch $portable) { continue }
        foreach ($entry in $entries | Where-Object { $_.disposition -eq 'behavior-only' }) {
            if ($line.IndexOf([string]$entry.repository, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
                $problems.Add("docs/sources.md calls $($entry.repository) portable, and the ledger says behavior-only: $line")
            }
        }
    }
    return $problems.ToArray()
}
$sourcesDoc = [IO.File]::ReadAllText((Join-Path $Root 'docs/sources.md'))
$lineageCount = @($entries.lineage | Sort-Object -Unique).Count
Assert-True ($sourcesDoc -like "*retains $($entries.Count) source records in $lineageCount lineages*" -and
    $sourcesDoc -like "*$($availabilityCounts.activeSources) active records and $($availabilityCounts.retiredSources) retired record*") `
    'docs/sources.md does not distinguish the ledger total from active and retired records.'
$docProblems = @(Test-SourcesDoc $sourcesDoc)
Assert-True ($docProblems.Count -eq 0) ($docProblems -join ' | ')
$nexalloy = @($entries | Where-Object { $_.id -eq 'nexalloy' })[0]
Assert-True ($null -ne $nexalloy -and $nexalloy.disposition -eq 'behavior-only' -and $null -ne $nexalloy.contaminatedBy) `
    'The ledger no longer holds NexAlloy as contaminated and behavior-only.'
$oldLine = "- [gnadgnaoh/NexAlloy-XES]($($nexalloy.repository)) (GPL-3.0) ports those hooks. Its code can be ported with credit."
Assert-True (@(@(Test-SourcesDoc ($sourcesDoc + "`n" + $oldLine)) -like '*calls*portable*').Count -gt 0) `
    'A docs line calling NexAlloy''s code portable passed the check.'
Assert-True (@(@(Test-SourcesDoc ($sourcesDoc + "`nSee https://github.com/unknown-owner/new-facebook-patches.")) -like '*unknown-owner*').Count -gt 0) `
    'A docs link to a source the ledger doesn''t know passed the check.'

Write-Host '[sources] the checked-in ledger and docs/sources.md keep their rules'

# --- each rule, broken once -------------------------------------------------------------------------

$rulesRoot = Join-Path $caseRoot 'rules'
New-Item -ItemType Directory -Path $rulesRoot -Force | Out-Null
foreach ($name in @('NOTICE', 'provenance.json', 'patches-list.json')) {
    Copy-Item -LiteralPath (Join-Path $Root $name) -Destination (Join-Path $rulesRoot $name)
}
$today = [datetime]::UtcNow.Date.ToString('yyyy-MM-dd')
function Test-Broken {
    param([scriptblock]$Break, [string]$Pattern, [string]$Message, [scriptblock]$BreakRoot)
    $copy = Copy-Json $ledger
    & $Break $copy
    $savedNotice = [IO.File]::ReadAllText((Join-Path $rulesRoot 'NOTICE'))
    $savedProvenance = [IO.File]::ReadAllText((Join-Path $rulesRoot 'provenance.json'))
    $savedCatalog = [IO.File]::ReadAllText((Join-Path $rulesRoot 'patches-list.json'))
    try {
        if ($BreakRoot) { & $BreakRoot }
        $result = Test-SourceLedger -Ledger $copy -Root $rulesRoot -Today $today
    } finally {
        [IO.File]::WriteAllText((Join-Path $rulesRoot 'NOTICE'), $savedNotice)
        [IO.File]::WriteAllText((Join-Path $rulesRoot 'provenance.json'), $savedProvenance)
        [IO.File]::WriteAllText((Join-Path $rulesRoot 'patches-list.json'), $savedCatalog)
    }
    Assert-True (-not $result.Valid) "$Message The ledger was accepted."
    Assert-True (@($result.Problems | Where-Object { $_ -like $Pattern }).Count -gt 0) `
        "$Message It was refused, but not for that: $($result.Problems -join ' | ')"
}
function Get-Entry { param($Copy, [string]$Id) return @($Copy.entries | Where-Object { $_.id -eq $Id })[0] }
$adoptedId = [string]@($entries | Where-Object { $_.disposition -eq 'adopted' })[0].id
$adoptedRepository = [string](Get-Entry $ledger $adoptedId).repository

$control = Test-SourceLedger -Ledger (Copy-Json $ledger) -Root $rulesRoot -Today $today
Assert-True $control.Valid ("The unchanged copy was refused, so the cases below would prove nothing: " + ($control.Problems -join ' | '))

# The five things an adopted source needs.
Test-Broken { param($c) (Get-Entry $c $adoptedId).adopted.commit = $null } '*no pinned commit its code was taken from*' `
    'An adopted source with no pinned commit'
Test-Broken { param($c) (Get-Entry $c $adoptedId).license.spdx = 'NOASSERTION' } '*has no licence compatible with GPL-3.0*' `
    'An adopted source with no compatible licence'
Test-Broken { param($c) } '*is not in NOTICE*' 'An adopted source missing from NOTICE' {
    $path = Join-Path $rulesRoot 'NOTICE'
    [IO.File]::WriteAllText($path, ([IO.File]::ReadAllText($path).Replace($adoptedRepository, 'https://example.invalid/elsewhere')))
}
Test-Broken { param($c) (Get-Entry $c $adoptedId).adopted.commit = ('f' * 40) } '*has no provenance rule*' `
    'An adopted source whose commit no provenance rule names'
Test-Broken { param($c) (Get-Entry $c $adoptedId).adopted.fixtures.builds = @('1.0.0 (1)', '1.0.1 (2)') } '*lacks fixture evidence*' `
    'An adopted source proved on two builds the catalog doesn''t declare'
# A catalog that pins a second build needs a second fixture, whatever the ledger proved before.
Test-Broken { param($c) $e = Get-Entry $c $adoptedId; $e.adopted.fixtures.builds = @($e.adopted.fixtures.builds[0]) } '*lacks fixture evidence*a second fixture*' `
    'An adopted source proved on one fixture of a two-build declaration' {
    $path = Join-Path $rulesRoot 'patches-list.json'
    $text = [IO.File]::ReadAllText($path)
    $pinned = [regex]::Replace($text, '("ARM64_V8A": \d+)', '$1, "ARMEABI_V7A": 900000002')
    if ($pinned -eq $text) { throw 'The catalog pins no arm64-v8a build to add a second build beside.' }
    [IO.File]::WriteAllText($path, $pinned)
}
Test-Broken { param($c) (Get-Entry $c $adoptedId).adopted.fixtures.receipt = 'https://example.com/receipt.json' } '*names no release receipt*' `
    'An adopted source with no release receipt'

# Unlicensed or contaminated sources stay behavior-only.
$unlicensed = [string]@($entries | Where-Object { $null -eq $_.license })[0].id
Test-Broken { param($c) (Get-Entry $c $unlicensed).disposition = 'candidate' } '*has no licence, so it is behavior-only*' `
    'An unlicensed source marked candidate'
Test-Broken { param($c) (Get-Entry $c 'nexalloy').disposition = 'candidate' } '*carries code from*behavior-only*' `
    'A contaminated source marked candidate'
$licensedCandidate = [string]@($entries | Where-Object { $_.disposition -eq 'candidate' -and $null -ne $_.license })[0].id
Test-Broken { param($c) (Get-Entry $c $licensedCandidate).license.spdx = 'CC-BY-NC-4.0' } '*can''t be combined with GPL-3.0*' `
    'A candidate whose licence can''t be combined with GPL-3.0'
Test-Broken { param($c) (Get-Entry $c $unlicensed).disposition = 'adopted' } '*has no licence*' 'An unlicensed source marked adopted'

# provenance.json can't credit what the ledger doesn't allow, and can't port from what isn't adopted.
$behaviorOnly = [string]@($entries | Where-Object { $_.disposition -eq 'behavior-only' })[0].repository
Test-Broken { param($c) } '*credits*but the ledger lists it as behavior-only*' 'A provenance rule crediting a behavior-only source' {
    $path = Join-Path $rulesRoot 'provenance.json'
    $document = [IO.File]::ReadAllText($path) | ConvertFrom-Json
    $rule = @($document.rules | Where-Object { $_.origin -eq 'ported' })[0]
    $rule.via = @(@($rule.via) + $behaviorOnly)
    [IO.File]::WriteAllText($path, ($document | ConvertTo-Json -Depth 10))
}
Test-Broken { param($c) $e = Get-Entry $c $adoptedId; $e.disposition = 'candidate'; $e.PSObject.Properties.Remove('adopted') } '*ports files from*not adopted*' `
    'A ported provenance rule from a source that isn''t adopted'
# A recorded fork or mirror carries its entry's code, so it's held to that entry's disposition and
# is never a porting source of its own.
$forkedEntry = @($entries | Where-Object { $_.disposition -eq 'behavior-only' -and @($_.forks | Where-Object { $_ }).Count -gt 0 })[0]
$behaviorOnlyFork = 'https://' + (ConvertTo-SourceKey $forkedEntry.repository).Split('/')[0] + '/' + @($forkedEntry.forks)[0]
$recordedMirror = [string]@(@($entries | Where-Object { @($_.mirrors | Where-Object { $_ }).Count -gt 0 })[0].mirrors)[0].repository
function Set-FirstPortedRule {
    param([string]$Property, [string]$Value)
    $path = Join-Path $rulesRoot 'provenance.json'
    $document = [IO.File]::ReadAllText($path) | ConvertFrom-Json
    $rule = @($document.rules | Where-Object { $_.origin -eq 'ported' })[0]
    $rule.$Property = if ($Property -eq 'via') { @(@($rule.via) + $Value) } else { $Value }
    [IO.File]::WriteAllText($path, ($document | ConvertTo-Json -Depth 10))
}
Test-Broken { param($c) } '*credits*as a fork of*behavior-only*' 'A provenance rule crediting a fork of a behavior-only source' {
    Set-FirstPortedRule 'via' $behaviorOnlyFork }
Test-Broken { param($c) } '*ports files from*only as a fork of*' 'A ported provenance rule from a fork of a behavior-only source' {
    Set-FirstPortedRule 'upstream' $behaviorOnlyFork }
Test-Broken { param($c) } '*ports files from*only as a mirror of*' 'A ported provenance rule from a recorded mirror' {
    Set-FirstPortedRule 'upstream' $recordedMirror }

# The records themselves.
Test-Broken { param($c) $c.indexes[0].hushfacebook.status = '' } '*records no Hushfacebook listing*' 'An index with no listing record'
Test-Broken { param($c) $i = @($c.indexes | Where-Object { $_.hushfacebook.status -eq 'listed' })[0]; $i.hushfacebook.url = $null } `
    '*listed but records no https listing url*' 'A listing with no URL'
Test-Broken { param($c) $c.indexes[0].hushfacebook = [pscustomobject]@{ status = 'submitted'; url = 'https://example.com/issue/1' } } `
    '*submission date is not a yyyy-MM-dd date*' 'A submission with no date'
Test-Broken { param($c) $c.indexes = @($c.indexes | Select-Object -Skip 1) } '*has no record of index*' 'A ledger missing an index'
Test-Broken { param($c) $c.entries[1].repository = $c.entries[0].repository } '*which Entry*already names*' 'A repository listed twice'
Test-Broken { param($c) $c.entries[0].branches[0].commit = 'main' } '*has no pinned commit (40 hex characters)*' 'A branch with no pinned commit'
Test-Broken { param($c) $c.entries[0].lastChecked = '25/09/2026' } '*lastChecked is not a yyyy-MM-dd date*' 'A lastChecked that isn''t ISO'
Test-Broken { param($c) $c.entries[0].lastChecked = '2999-01-01' } '*lastChecked is 2999-01-01, after today*' 'A lastChecked in the future'
Test-Broken { param($c) $c.entries[0].features = @() } '*names no features*' 'A source with no features'
Test-Broken { param($c) $c.entries[0].targetVersions = [pscustomobject]@{} } '*records no declared target versions*' 'A source with no target versions'
Test-Broken { param($c) $c.entries[0].packages = @('com.instagram.android') } '*not a Facebook-family package*' 'A source for another app'
Test-Broken { param($c) $c.entries[0].license = [pscustomobject]@{ spdx = 'GPL-3.0'; url = 'https://example.com/LICENSE'; sha256 = 'abc' } } `
    '*licence has no sha256*' 'A licence with no hash'
Test-Broken { param($c) $c.entries[0].lineage = '' } '*names no lineage*' 'A source with no lineage'
Test-Broken { param($c) $c.outOfScope[0] | Add-Member -NotePropertyName listedFor -NotePropertyValue @('com.instagram.android') -Force } `
    '*listedFor names ''com.instagram.android'', which is not a Facebook-family package*' 'An out-of-scope listing filed under another app'
Test-Broken { param($c) $c.outOfScope[0] | Add-Member -NotePropertyName listedFor -NotePropertyValue @() -Force } `
    '*has an empty listedFor*' 'An out-of-scope record with an empty listedFor'
Test-Broken { param($c) (Get-Entry $c 'froggo-mben').availability.status = 'deleted' } '*availability must be active, retired or unavailable*' `
    'An unknown availability status'
Test-Broken { param($c) (Get-Entry $c 'froggo-mben').availability.reason = '' } '*retired availability gives no reason*' `
    'A retired source with no reason'
Test-Broken { param($c) (Get-Entry $c 'froggo-mben').availability.evidence = 'https://github.com/mben25/morphe-patches/tree/main' } `
    '*availability needs immutable evidence in its own repository*' 'A retired source supported only by a movable branch'
Test-Broken { param($c) (Get-Entry $c 'froggo-mben').availability.evidence = "https://github.com/other/source/commit/$('a' * 40)" } `
    '*availability needs immutable evidence in its own repository*' 'A retired source supported by another repository'
Test-Broken { param($c) (Get-Entry $c 'froggo-mben').watchPaths = @() } '*retired availability needs a watch path*' `
    'A retired source whose return could not be detected'
Test-Broken { param($c) (Get-Entry $c 'froggo-mben').license = $null } '*has no licence, so it is behavior-only*' `
    'Retirement cannot allow unlicensed code to remain a candidate'

Write-Host '[sources] every ledger rule refuses the copy that breaks it'

# --- the census and the listings a release is held to ----------------------------------------------

$census = Copy-Json $ledger
$census.census.checkedAt = '2026-09-25'
Assert-True (Test-SourceCensus -Ledger $census -Today '2026-10-09').Valid 'A census 14 days old was refused.'
$stale = Test-SourceCensus -Ledger $census -Today '2026-10-10'
Assert-True (-not $stale.Valid -and $stale.Reason -like '*15 days old*') "A census 15 days old was accepted: $($stale.Reason)"
Assert-True (-not (Test-SourceCensus -Ledger $census -Today '2026-09-24').Valid) 'A census dated after today was accepted.'
$census.census.checkedAt = 'yesterday'
Assert-True (-not (Test-SourceCensus -Ledger $census -Today '2026-10-01').Valid) 'A census with no date was accepted.'

$listings = Copy-Json $ledger
foreach ($index in $listings.indexes) { $index.hushfacebook = [pscustomobject]@{ status = 'listed'; url = 'https://example.com/listing'; checked = '2026-09-25' } }
Assert-True (Test-SourceListings -Ledger $listings).Valid 'Every index listing Hushfacebook was refused.'
$listings.indexes[0].hushfacebook = [pscustomobject]@{ status = 'submitted'; url = 'https://example.com/issue/1'; submitted = '2026-09-25' }
Assert-True (Test-SourceListings -Ledger $listings).Valid 'A dated submission was not accepted in place of a listing.'
$listings.indexes[1].hushfacebook = [pscustomobject]@{ status = 'not-listed'; checked = '2026-09-25' }
$pending = Test-SourceListings -Ledger $listings
Assert-True (-not $pending.Valid -and $pending.Pending -contains [string]$listings.indexes[1].name) `
    'An index with neither a listing nor a submission was accepted.'

# Test-SourceReleaseGate reads the ledger where the release does, so it gets a root of its own.
$gateRoot = Join-Path $caseRoot 'gate'
New-Item -ItemType Directory -Path (Join-Path $gateRoot 'sources') -Force | Out-Null
foreach ($name in @('NOTICE', 'provenance.json', 'patches-list.json')) {
    Copy-Item -LiteralPath (Join-Path $Root $name) -Destination (Join-Path $gateRoot $name)
}
function Save-GateLedger {
    param([string]$CheckedAt, [switch]$Pending)
    $copy = Copy-Json $ledger
    $copy.census.checkedAt = $CheckedAt
    foreach ($index in $copy.indexes) {
        $index.hushfacebook = if ($Pending) { [pscustomobject]@{ status = 'not-listed'; checked = $CheckedAt } } else {
            [pscustomobject]@{ status = 'listed'; url = 'https://example.com/listing'; checked = $CheckedAt } }
    }
    # Every date the audit's clean run stamps is set here, or a ledger stamped after these fixed
    # days would put a record after the gate's today and fail the case for the wrong reason.
    foreach ($entry in $copy.entries) { $entry.lastChecked = $CheckedAt }
    foreach ($item in @($copy.outOfScope)) { $item.lastChecked = $CheckedAt }
    [IO.File]::WriteAllText((Get-SourceLedgerPath -Root $gateRoot), ($copy | ConvertTo-Json -Depth 20))
}
Save-GateLedger -CheckedAt '2026-09-25'
$gate = Test-SourceReleaseGate -Root $gateRoot -Today '2026-10-09'
Assert-True $gate.Valid "The release gate refused a census 14 days old: $($gate.Reason)"
Assert-True ($gate.Summary -like '*14 day(s) old*') "The release gate did not say how old the census is: $($gate.Summary)"
Assert-True ($gate.Summary -like "*$($availabilityCounts.activeSources) active, $($availabilityCounts.retiredSources) retired, $($availabilityCounts.unavailableSources) unavailable*") `
    'The release gate counted historical records as active sources.'
$gate = Test-SourceReleaseGate -Root $gateRoot -Today '2026-10-10'
Assert-True (-not $gate.Valid -and $gate.Reason -like '*15 days old*audit-facebook-sources.ps1*') "The release gate accepted a stale census: $($gate.Reason)"
Save-GateLedger -CheckedAt '2026-09-25' -Pending
$gate = Test-SourceReleaseGate -Root $gateRoot -Today '2026-09-26'
# An index that doesn't list Hushfacebook yet is named, not refused: a submission is a public
# request on someone else's project, so a release can't be made to wait on it.
Assert-True ($gate.Valid -and $gate.Summary -like '*Hushfacebook is not listed on *yet and has no submission recorded there*') "The release gate didn't name the indexes that don't list Hushfacebook: $($gate.Summary) $($gate.Reason)"
Remove-Item -LiteralPath (Get-SourceLedgerPath -Root $gateRoot)
$gate = Test-SourceReleaseGate -Root $gateRoot -Today '2026-09-26'
Assert-True (-not $gate.Valid -and $gate.Reason -like '*ledger is missing*') "The release gate accepted a missing ledger: $($gate.Reason)"

Write-Host '[sources] the census holds to 14 days and every index needs a listing or a dated submission'

# --- the audit, against stand-ins for every forge and index -----------------------------------------

$auditScript = Join-Path $PSScriptRoot 'audit-facebook-sources.ps1'
$fixtureRoot = Join-Path $caseRoot 'audit'
New-Item -ItemType Directory -Path (Join-Path $fixtureRoot 'sources'), (Join-Path $fixtureRoot 'patches'), (Join-Path $fixtureRoot 'extensions') -Force | Out-Null
# The fixture's alpha source is adopted, so its root carries the NOTICE entry and the provenance
# rule an adopted source needs, and the catalog whose two builds its receipt has to prove.
Copy-Item -LiteralPath (Join-Path $Root 'patches-list.json') -Destination (Join-Path $fixtureRoot 'patches-list.json')
$adoptedCommit = 'ad' * 20
$declaredBuilds = @((Get-PatchTarget -PatchList ([IO.File]::ReadAllText((Join-Path $Root 'patches-list.json')) | ConvertFrom-Json)).PackageVersions)
Assert-True ($declaredBuilds.Count -ge 1) 'The catalog declares no build, so the receipt cases would prove nothing.'
# Only the newest Facebook is declared, and an adopted source still needs two-fixture evidence: the
# declared version's two builds (arm64-v8a and armeabi-v7a carry different version codes), both
# unforced in one receipt, as the real ledger's receipts are.
$evidenceBuilds = @($declaredBuilds | ForEach-Object { "$_ (900000001)"; "$_ (900000002)" })
[IO.File]::WriteAllText((Join-Path $fixtureRoot 'NOTICE'), "Fixture NOTICE`n  alpha  https://github.com/fixture-owner/alpha-patches`n")
[IO.File]::WriteAllText((Join-Path $fixtureRoot 'provenance.json'), (@{ rules = @(@{ paths = @('patches/**'); origin = 'ported'
    upstream = 'https://github.com/fixture-owner/alpha-patches'; commit = $adoptedCommit; license = 'GPL-3.0'; via = @() }) } | ConvertTo-Json -Depth 6))
Set-Content -LiteralPath (Join-Path $fixtureRoot 'patches/Keep.kt') -Value 'class Keep' -Encoding ASCII
$receiptUrl = 'https://github.com/SysAdminDoc/Hushfacebook/releases/download/v9.9.9/release-receipt-9.9.9.json'
$fixtureLedgerPath = Get-SourceLedgerPath -Root $fixtureRoot
$fakeToken = 'fixture-token-' + [guid]::NewGuid().ToString('N')
$fakeGitLabToken = 'fixture-gitlab-' + [guid]::NewGuid().ToString('N')
$licenseText = "GNU GENERAL PUBLIC LICENSE`nVersion 3, 29 June 2007`n"
$licenseHash = Get-Sha256Text $licenseText
$licenseBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($licenseText))
$commitA1 = 'a1' * 20
$commitB1 = 'b1' * 20
$commitG1 = 'c1' * 20
$blobKnown = '1b' * 20
$blobMirror = '2b' * 20
$fixtureLedger = [ordered]@{
    schemaVersion = 1
    census = [ordered]@{ checkedAt = '2026-09-01'; skipped = @() }
    self = [ordered]@{ repository = 'https://github.com/SysAdminDoc/Hushfacebook' }
    officialBundle = [ordered]@{ repository = 'https://github.com/MorpheApp/morphe-patches'; branch = 'main'; list = 'patches-list.json'; packages = @() }
    indexes = @(
        [ordered]@{ id = 'morphe-directory'; name = 'Morphe community directory'; url = 'https://morphe-patches.software'
            hushfacebook = [ordered]@{ status = 'not-listed'; checked = '2026-09-01' } }
        [ordered]@{ id = 'awesome-morphe'; name = 'Awesome Morphe'; url = 'https://github.com/nvbangg/awesome-morphe'
            hushfacebook = [ordered]@{ status = 'submitted'; url = 'https://github.com/nvbangg/awesome-morphe/issues/1'; submitted = '2026-09-01' } }
        [ordered]@{ id = 'morphe-patch-tracker'; name = 'Morphe Patch Tracker'; url = 'https://drnx64.github.io/morphe-track-patches/'
            hushfacebook = [ordered]@{ status = 'listed'; url = 'https://drnx64.github.io/morphe-track-patches/#/bundle/sysadmindoc'; checked = '2026-09-01' } }
        [ordered]@{ id = 'jman-bundles'; name = 'Jman''s Patch Bundle Registry'; url = 'https://github.com/Jman-Github/Patch-Bundle-Registry'
            hushfacebook = [ordered]@{ status = 'not-listed'; checked = '2026-09-01' } }
        [ordered]@{ id = 'morphe-archive'; name = 'Morphe Archive'; url = 'https://rushiforai.github.io/morphe-archive/'
            hushfacebook = [ordered]@{ status = 'listed'; url = 'https://github.com/rushiforai/morphe-archive'; checked = '2026-09-01' } }
    )
    entries = @(
        [ordered]@{ id = 'alpha'; repository = 'https://github.com/fixture-owner/alpha-patches'; lineage = 'alpha'; upstream = $null
            kind = 'morphe-patches'; packages = @('com.facebook.katana'); targetVersions = [ordered]@{ 'com.facebook.katana' = @('580.0.0.51.74') }
            features = @('Hide ads'); branches = @([ordered]@{ name = 'main'; commit = $commitA1 }); watchPaths = @()
            license = [ordered]@{ spdx = 'GPL-3.0'; url = "https://github.com/fixture-owner/alpha-patches/blob/$commitA1/LICENSE"; sha256 = $licenseHash }
            contaminatedBy = $null; disposition = 'adopted'; reason = 'A fixture source.'; archived = $false
            adopted = [ordered]@{ commit = $adoptedCommit; fixtures = [ordered]@{ receipt = $receiptUrl; builds = $evidenceBuilds } }
            forks = @('someone/alpha-patches'); contentHashes = @($blobKnown)
            mirrors = @([ordered]@{ repository = 'https://github.com/copier/alpha-copy'; reason = 'A fixture copy.'; blobs = @($blobMirror) })
            lastChecked = '2026-09-01' }
        [ordered]@{ id = 'beta'; repository = 'https://github.com/fixture-owner/beta-module'; lineage = 'beta'; upstream = $null
            kind = 'xposed-module'; packages = @('com.facebook.orca'); targetVersions = [ordered]@{ 'com.facebook.orca' = @() }
            features = @('Hide inbox ads'); branches = @([ordered]@{ name = 'main'; commit = $commitB1 }); watchPaths = @('app/messenger')
            license = $null; contaminatedBy = $null; disposition = 'behavior-only'; reason = 'A fixture module with no licence.'; archived = $false
            forks = @(); contentHashes = @(); mirrors = @(); lastChecked = '2026-09-01' }
        [ordered]@{ id = 'gamma'; repository = 'https://gitlab.com/fixture-group/gamma-patches'; lineage = 'gamma'; upstream = $null
            kind = 'revanced-patches'; packages = @('com.facebook.lite'); targetVersions = [ordered]@{ 'com.facebook.lite' = @() }
            features = @('Open links outside'); branches = @([ordered]@{ name = 'main'; commit = $commitG1 }); watchPaths = @()
            license = [ordered]@{ spdx = 'GPL-3.0'; url = "https://gitlab.com/fixture-group/gamma-patches/-/blob/$commitG1/LICENSE"; sha256 = $licenseHash }
            contaminatedBy = $null; disposition = 'candidate'; reason = 'A fixture GitLab source.'; archived = $false
            forks = @(); contentHashes = @(); mirrors = @(); lastChecked = '2026-09-01' }
    )
    outOfScope = @([ordered]@{ repository = 'https://github.com/noise/mentions-facebook'; reason = 'Names the package in a list.'; lastChecked = '2026-09-01' })
}
$fixtureLedgerText = ($fixtureLedger | ConvertTo-Json -Depth 20) -replace "`r`n", "`n"
function Reset-FixtureLedger { [IO.File]::WriteAllText($fixtureLedgerPath, $fixtureLedgerText, (New-Object Text.UTF8Encoding $false)) }
Reset-FixtureLedger
Assert-True (Test-SourceLedger -Ledger ($fixtureLedgerText | ConvertFrom-Json) -Root $fixtureRoot -Today '2026-09-25').Valid `
    'The audit fixture ledger breaks the rules, so the audit would stop before any case below.'

# What each forge and index answers. A case changes one answer and puts it back.
function New-FakeAnswers {
    $tick = [string][char]96 * 3
    $answers = [ordered]@{
        official = @{ Status = 200; Content = '{"patches":[{"name":"Example","compatiblePackages":{"com.google.android.youtube":["20.1"]}}]}' }
        directory = @{ Status = 200; Content = (@{
            bundles = @(@{ source = 'github'; repo = 'fixture-owner/alpha-patches'; name = 'Alpha'; targetApps = @('com.facebook.katana')
                patches = @(@{ name = 'Hide ads'; compatiblePackagesKey = 0 }) })
            compatibilities = @(@{ packageName = 'com.facebook.katana'; targets = @(@{ version = '580.0.0.51.74' }) }) } | ConvertTo-Json -Depth 8) }
        awesome = @{ Status = 200; Content = '{"fixture-owner/alpha-patches":{"com.facebook.katana":"0123"},"SysAdminDoc/hushfeed":{"com.zhiliaoapp.musically":"4567"}}' }
        tracker = @{ Status = 200; Content = (@{
            'sysadmindoc:stable' = @{ repo_url = 'https://github.com/SysAdminDoc/Hushfacebook'; apps = @(@{ package = 'com.facebook.katana'; patches = @(@{ name = 'Hide sponsored posts' }) }) }
            'alpha:stable' = @{ repo_url = 'https://github.com/fixture-owner/alpha-patches'; apps = @(@{ package = 'com.facebook.katana'; patches = @(@{ name = 'Hide ads' }) }) }
        } | ConvertTo-Json -Depth 8) -replace '\}\s*$', (',"Alpha:stable":{"repo_url":"https://github.com/fixture-owner/alpha-patches","apps":[]}}') }
        # Jman's catalog API, a page of source addresses at a time per package. Katana's rows come in
        # two short pages, since the API's pages can run short of the limit it was asked for.
        jman = @{
            'com.facebook.katana' = @(@('https://github.com/fixture-owner/alpha-patches'), @('https://github.com/fixture-owner/alpha-patches'))
            'com.facebook.orca' = @()
            'com.facebook.lite' = @(@('https://gitlab.com/fixture-group/gamma-patches'))
        }
        archive = @{ Status = 200; Content = '{"name":"patches-list.json"}' }
        searchHits = @(
            @{ repository = @{ full_name = 'fixture-owner/alpha-patches' }; path = 'patches/Alpha.kt'; sha = $blobKnown }
            @{ repository = @{ full_name = 'copier/alpha-copy' }; path = 'patches/Alpha.kt'; sha = $blobMirror }
            @{ repository = @{ full_name = 'clone/alpha-clone' }; path = 'src/Alpha.kt'; sha = $blobKnown }
            @{ repository = @{ full_name = 'rushiforai/morphe-archive' }; path = 'examplepatches/fixture-owner/alpha-patches/patches-list.json'; sha = ('3b' * 20) }
            @{ repository = @{ full_name = 'rushiforai/morphe-archive' }; path = 'examplepatches/Jman-Github/ReVanced-Patch-Bundles/patch-bundles/x/y-patches-list.json'; sha = ('4b' * 20) }
            @{ repository = @{ full_name = 'noise/mentions-facebook' }; path = 'list.txt'; sha = ('5b' * 20) }
            @{ repository = @{ full_name = 'SysAdminDoc/Hushfacebook' }; path = 'patches-list.json'; sha = ('6b' * 20) }
            # The archive keeps a recorded fork's patch list for its entry's own package, which is no bundle of its own.
            @{ repository = @{ full_name = 'rushiforai/morphe-archive' }; path = 'examplepatches/someone/alpha-patches/patches-list.json'; sha = ('7b' * 20) }
        )
        orcaListHits = @()
        alphaRepo = @{ Status = 200; Content = '{"full_name":"fixture-owner/alpha-patches","archived":false,"default_branch":"main"}' }
        alphaLicense = @{ Status = 200; Content = (@{ path = 'LICENSE'; content = $licenseBase64; license = @{ spdx_id = 'GPL-3.0' } } | ConvertTo-Json) }
        alphaBranches = @{ Status = 200; Content = "[{`"name`":`"main`",`"commit`":{`"sha`":`"$commitA1`"}}]" }
        alphaForks = @{ Status = 200; Content = '[{"full_name":"someone/alpha-patches"}]' }
        compare = @{}
        betaRepo = @{ Status = 200; Content = '{"full_name":"fixture-owner/beta-module","archived":false,"default_branch":"main"}' }
        betaLicense = @{ Status = 404; Content = $null }
        betaBranches = @{ Status = 200; Content = "[{`"name`":`"main`",`"commit`":{`"sha`":`"$('b9' * 20)`"}}]" }
        betaWatch = @{ Status = 200; Content = "[{`"sha`":`"$commitB1`",`"commit`":{`"committer`":{`"date`":`"2026-09-01T10:00:00Z`"}}}]" }
        betaTree = @{ Status = 200; Content = '{"tree":[{"path":"app/messenger","type":"tree"}],"truncated":false}' }
        betaHistoryTree = @{ Status = 200; Content = '{"tree":[{"path":"app/messenger","type":"tree"}],"truncated":false}' }
        betaForks = @{ Status = 200; Content = '[]' }
        gammaProject = @{ Status = 200; Content = '{"path_with_namespace":"fixture-group/gamma-patches","archived":false,"default_branch":"main"}' }
        gammaTree = @{ Status = 200; Content = '[{"type":"blob","name":"LICENSE","path":"LICENSE"},{"type":"tree","name":"patches","path":"patches"}]' }
        gammaLicense = @{ Status = 200; Content = (@{ file_name = 'LICENSE'; content = $licenseBase64 } | ConvertTo-Json) }
        gammaForks = @{ Status = 200; Content = '[]' }
        # The blobless mirror the audit keeps of gamma: branch heads, the folder listing at each
        # commit, the newest commit under a path, and which commits sit behind which.
        gammaMirror = @{ Heads = [ordered]@{ main = $commitG1 }; Listings = @{ $commitG1 = @('LICENSE', 'patches') }
            History = @{ "$commitG1 patches" = "$commitG1`t2026-09-01T10:00:00+00:00" }; Behind = @(); CloneFails = $false; FetchFails = $false }
        gitlabSearch = @{ Status = 200; Content = '[{"project_id":303,"path":"patches/Lite.kt"}]' }
        gitlabSearchProject = @{ Status = 200; Content = '{"path_with_namespace":"fixture-group/gamma-patches"}' }
        repoMeta = @{ Status = 200; Content = '{"full_name":"newcomer/fb-patches","license":{"spdx_id":"MIT"},"fork":false,"archived":false,"pushed_at":"2026-09-20T00:00:00Z","description":"Facebook patches"}' }
        oldNameMeta = @{ Status = 200; Content = '{"full_name":"fixture-owner/alpha-patches","fork":false,"archived":false}' }
        receipt = @{ Status = 200; Content = (@{ targets = @($evidenceBuilds | ForEach-Object {
            $build = ConvertTo-SourceFixtureBuild $_
            @{ source = @{ versionName = $build.Name; versionCode = $build.Code; forced = $false }; patches = @(@{ name = 'Hide ads'; applied = $true }) } }) } | ConvertTo-Json -Depth 8) }
    }
    return $answers
}
$fakeForge = @{ Answers = (New-FakeAnswers); Requests = (New-Object System.Collections.Generic.List[object])
    GitCalls = (New-Object System.Collections.Generic.List[string]) }

& {
    # Stands in for every request the audit makes, so no case needs the network. A request it
    # doesn't recognize fails the case that made it.
    function Invoke-WebRequest {
        param($Uri, $Headers, $UserAgent, [switch]$UseBasicParsing, $TimeoutSec, $Method, $OutFile, $MaximumRedirection, [switch]$PassThru)
        $url = [string]$Uri
        $authorization = if ($Headers -and $Headers.ContainsKey('Authorization')) { [string]$Headers['Authorization'] } else { $null }
        $privateToken = if ($Headers -and $Headers.ContainsKey('PRIVATE-TOKEN')) { [string]$Headers['PRIVATE-TOKEN'] } else { $null }
        $fakeForge.Requests.Add([pscustomobject]@{ Uri = $url; Authorization = $authorization; PrivateToken = $privateToken })
        if ($fakeForge.ContainsKey('TimeoutOnce') -and $fakeForge.TimeoutOnce.Contains($url)) {
            [void]$fakeForge.TimeoutOnce.Remove($url)
            throw (New-Object System.Exception 'The request was canceled due to the configured HttpClient.Timeout of 120 seconds elapsing.')
        }
        if ($fakeForge.ContainsKey('Limited') -and $fakeForge.Limited.ContainsKey($url) -and $fakeForge.Limited[$url] -gt 0) {
            $fakeForge.Limited[$url]--
            $limitedFailure = New-Object System.Exception 'Response status code does not indicate success: 429 (Too Many Requests).'
            Add-Member -InputObject $limitedFailure -NotePropertyName Response -NotePropertyValue ([pscustomobject]@{ StatusCode = 429
                Headers = @{ 'Retry-After' = [string]$fakeForge.RetryAfter } })
            throw $limitedFailure
        }
        $a = $fakeForge.Answers
        $answer = $null
        switch -Regex ($url) {
            '^https://raw\.githubusercontent\.com/MorpheApp/morphe-patches/main/patches-list\.json$' { $answer = $a.official; break }
            '^https://morphe-patches\.software/data/bundles\.json$' { $answer = $a.directory; break }
            '^https://raw\.githubusercontent\.com/nvbangg/awesome-morphe/main/data/hashes\.json$' { $answer = $a.awesome; break }
            '^https://raw\.githubusercontent\.com/drnx64/morphe-track-patches/main/data/bundles\.json$' { $answer = $a.tracker; break }
            '^https://patch-bundles\.api\.jmancentral\.com/apps/([^/]+)/bundles\?limit=100&offset=(\d+)$' {
                $pages = @($a.jman[$Matches[1]])
                $start = 0
                $rows = @()
                $total = 0
                foreach ($page in $pages) { $total += @($page).Count }
                foreach ($page in $pages) {
                    if ($start -eq [int]$Matches[2]) { $rows = @($page | ForEach-Object { @{ id = 'b'; source = @{ url = $_ } } }); break }
                    $start += @($page).Count
                }
                $answer = @{ Status = 200; Content = (@{ data = $rows; total = $total; limit = 100; offset = [int]$Matches[2] } | ConvertTo-Json -Depth 8) }
                break
            }
            '^https://api\.github\.com/repos/rushiforai/morphe-archive/contents/examplepatches/SysAdminDoc/Hushfacebook/patches-list\.json$' { $answer = $a.archive; break }
            '^https://api\.github\.com/search/code\?q=([^&]+)&per_page=100&page=1$' {
                $query = [Uri]::UnescapeDataString($Matches[1])
                $items = if ($query -eq '"com.facebook.katana" bytecodePatch') { @($a.searchHits) }
                    elseif ($query -eq '"com.facebook.orca" filename:patches-list.json') { @($a.orcaListHits) } else { @() }
                $answer = @{ Status = 200; Content = (@{ total_count = $items.Count; incomplete_results = $false; items = $items } | ConvertTo-Json -Depth 8) }
                break
            }
            '^https://api\.github\.com/repos/fixture-owner/alpha-patches$' { $answer = $a.alphaRepo; break }
            '^https://api\.github\.com/repos/fixture-owner/alpha-patches/license$' { $answer = $a.alphaLicense; break }
            '^https://api\.github\.com/repos/fixture-owner/alpha-patches/branches\?per_page=100&page=1$' { $answer = $a.alphaBranches; break }
            '^https://api\.github\.com/repos/fixture-owner/alpha-patches/forks\?per_page=100&page=1$' { $answer = $a.alphaForks; break }
            '^https://api\.github\.com/repos/fixture-owner/alpha-patches/compare/([0-9a-f]{40})\.\.\.([0-9a-f]{40})$' {
                $status = $a.compare[$Matches[2]]
                if ($status) { $answer = @{ Status = 200; Content = "{`"status`":`"$status`"}" } }
                break
            }
            '^https://api\.github\.com/repos/fixture-owner/beta-module$' { $answer = $a.betaRepo; break }
            '^https://api\.github\.com/repos/fixture-owner/beta-module/license$' { $answer = $a.betaLicense; break }
            '^https://api\.github\.com/repos/fixture-owner/beta-module/branches\?per_page=100&page=1$' { $answer = $a.betaBranches; break }
            '^https://api\.github\.com/repos/fixture-owner/beta-module/commits\?sha=main&path=app%2Fmessenger&per_page=1$' { $answer = $a.betaWatch; break }
            '^https://api\.github\.com/repos/fixture-owner/beta-module/git/trees/([0-9a-f]{40})\?recursive=1$' {
                $answer = if ($Matches[1] -eq $commitB1) { $a.betaHistoryTree } else { $a.betaTree }
                break
            }
            '^https://api\.github\.com/repos/fixture-owner/beta-module/forks\?per_page=100&page=1$' { $answer = $a.betaForks; break }
            '^https://gitlab\.com/api/v4/projects/fixture-group%2Fgamma-patches$' { $answer = $a.gammaProject; break }
            '^https://gitlab\.com/api/v4/search\?scope=blobs&search=([^&]+)&per_page=100&page=1$' {
                $package = [Uri]::UnescapeDataString($Matches[1])
                $answer = if ($package -eq '"com.facebook.lite"') { $a.gitlabSearch } else { @{ Status = 200; Content = '[]' } }
                break
            }
            '^https://gitlab\.com/api/v4/projects/303$' { $answer = $a.gitlabSearchProject; break }
            '^https://gitlab\.com/api/v4/projects/fixture-group%2Fgamma-patches/repository/tree\?ref=main&per_page=100&page=1$' { $answer = $a.gammaTree; break }
            '^https://gitlab\.com/api/v4/projects/fixture-group%2Fgamma-patches/repository/files/LICENSE\?ref=main$' { $answer = $a.gammaLicense; break }
            '^https://gitlab\.com/api/v4/projects/fixture-group%2Fgamma-patches/forks\?per_page=100&page=1$' { $answer = $a.gammaForks; break }
            '^https://api\.github\.com/repos/(newcomer/fb-patches|clone/alpha-clone)$' { $answer = $a.repoMeta; break }
            '^https://api\.github\.com/repos/fixture-owner/alpha-old$' { $answer = $a.oldNameMeta; break }
            '^https://github\.com/SysAdminDoc/Hushfacebook/releases/download/v9\.9\.9/release-receipt-9\.9\.9\.json$' { $answer = $a.receipt; break }
        }
        if ($null -eq $answer) { throw "The audit made a request no stand-in answers: $url" }
        if ($answer.Status -ne 200) {
            $failure = New-Object System.Exception "Response status code does not indicate success: $($answer.Status)."
            Add-Member -InputObject $failure -NotePropertyName Response -NotePropertyValue ([pscustomobject]@{ StatusCode = $answer.Status })
            throw $failure
        }
        return [pscustomobject]@{ StatusCode = 200; Content = [string]$answer.Content }
    }

    # Stands in for git, which the audit runs only on its GitLab mirrors. Every call is kept, so a
    # case can check what was asked; a command it doesn't know fails the case that ran it.
    function git {
        $arguments = @($args | ForEach-Object { "$_" })
        $fakeForge.GitCalls.Add($arguments -join ' ')
        $mirror = $fakeForge.Answers.gammaMirror
        $directory = $null
        $at = 0
        while ($at -lt $arguments.Count -and $arguments[$at] -in @('--literal-pathspecs', '-c', '-C')) {
            if ($arguments[$at] -eq '-C') { $directory = $arguments[$at + 1] }
            $at += $(if ($arguments[$at] -eq '--literal-pathspecs') { 1 } else { 2 })
        }
        $command = $arguments[$at]
        $rest = @($arguments | Select-Object -Skip ($at + 1))
        $global:LASTEXITCODE = 0
        if ($command -ne 'clone' -and $directory -notlike '*\gitlab.com\fixture-group\gamma-patches.git') { throw "git ran outside gamma's mirror: $($arguments -join ' ')" }
        switch -CaseSensitive ($command) {
            'clone' {
                if ($rest[-2] -ne 'https://gitlab.com/fixture-group/gamma-patches.git' -or $mirror.CloneFails) {
                    Write-Error "fatal: repository '$($rest[-2])' not found"
                    $global:LASTEXITCODE = 128
                    return
                }
                New-Item -ItemType Directory -Path $rest[-1] -Force | Out-Null
                [IO.File]::WriteAllText((Join-Path $rest[-1] 'HEAD'), "ref: refs/heads/main`n")
                return
            }
            'fetch' { if ($mirror.FetchFails) { Write-Error 'fatal: could not read from remote repository'; $global:LASTEXITCODE = 128 }; return }
            'for-each-ref' { foreach ($name in $mirror.Heads.Keys) { "$name`t$($mirror.Heads[$name])" }; return }
            'ls-tree' {
                if (-not $mirror.Listings.ContainsKey($rest[0])) { Write-Error "fatal: not a tree object: $($rest[0])"; $global:LASTEXITCODE = 128; return }
                # Matched without regard to case, as git on a case-insensitive disk may, so the
                # audit's own exact match is what a renamed folder meets.
                foreach ($path in @($mirror.Listings[$rest[0]])) { if ($path -eq $rest[2]) { "040000 tree $('e0' * 20)`t$path" } }
                return
            }
            'log' { $line = $mirror.History["$($rest[2]) $($rest[4])"]; if ($line) { $line }; return }
            'merge-base' {
                if ($rest[1] -ne $rest[2] -and $mirror.Behind -notcontains "$($rest[1]) $($rest[2])") { $global:LASTEXITCODE = 1 }
                return
            }
        }
        throw "The audit ran a git command no stand-in answers: $($arguments -join ' ')"
    }

    function Invoke-Audit {
        param([hashtable]$Extra = @{})
        $report = Join-Path $caseRoot ('report-' + [guid]::NewGuid().ToString('N') + '.json')
        $fakeForge.Requests.Clear()
        $fakeForge.GitCalls.Clear()
        $arguments = @{ Root = $fixtureRoot; GitHubToken = $fakeToken; GitLabToken = $fakeGitLabToken; SearchDelaySeconds = 0
            RateLimitWaitSeconds = 0; Today = '2026-09-25'; ReportPath = $report; MirrorRoot = (Join-Path $caseRoot 'mirrors') }
        foreach ($key in $Extra.Keys) { $arguments[$key] = $Extra[$key] }
        $global:LASTEXITCODE = 0
        $said = @(& $auditScript @arguments 6>&1 | ForEach-Object { "$_" }) -join "`n"
        $code = $LASTEXITCODE
        $document = if (Test-Path -LiteralPath $report) { [IO.File]::ReadAllText($report) | ConvertFrom-Json } else { $null }
        return [pscustomobject]@{ ExitCode = $code; Said = $said; Report = $document; ReportText = $(if ($document) { [IO.File]::ReadAllText($report) } else { '' }) }
    }

    function Get-TreeState {
        # Every file under the fixture root with its hash, the ledger included.
        return @(Get-ChildItem -LiteralPath $fixtureRoot -Recurse -File | Sort-Object FullName | ForEach-Object {
            $_.FullName.Substring($fixtureRoot.Length) + ' ' + (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash })
    }

    function Assert-Drift {
        param([string]$Kind, [string]$Detail, [string]$Case)
        $beforeLedger = [IO.File]::ReadAllText($fixtureLedgerPath)
        $result = Invoke-Audit
        $found = @($result.Report.findings | Where-Object { $_.kind -eq $Kind -and "$($_.repository) $($_.detail)" -like $Detail })
        Assert-True ($result.ExitCode -eq 1) "$Case exited $($result.ExitCode), not 1: $($result.Said)"
        Assert-True ($found.Count -gt 0) "$Case was not reported as $Kind ($Detail): $(@($result.Report.findings | ForEach-Object { "$($_.kind): $($_.repository) $($_.detail)" }) -join ' | ')"
        Assert-True ([IO.File]::ReadAllText($fixtureLedgerPath) -ceq $beforeLedger) "$Case changed the ledger although the audit found drift."
    }

    function Assert-Incomplete {
        param([hashtable]$Extra, [string]$Kind, [string]$Detail, [string]$Case)
        $beforeLedger = [IO.File]::ReadAllText($fixtureLedgerPath)
        $result = Invoke-Audit $Extra
        Assert-True ($result.ExitCode -eq 1 -and $null -ne $result.Report -and -not $result.Report.clean) `
            "$Case was accepted as a clean audit: $($result.Said)"
        $found = @($result.Report.findings | Where-Object { $_.kind -eq $Kind -and $_.detail -like $Detail })
        Assert-True ($found.Count -eq 1 -and $found[0].evidence.index -eq 'gitlab-code-search' -and
            $found[0].evidence.documentation -contains 'https://docs.gitlab.com/user/search/advanced_search/' -and
            $found[0].evidence.documentation -contains 'https://docs.gitlab.com/user/search/exact_code_search/') `
            "$Case did not retain the unavailable global-index evidence."
        Assert-True (@($result.Report.sources | Where-Object { $_.source -eq 'https://gitlab.com/fixture-group/gamma-patches' -and $_.status -eq 'read' }).Count -eq 1) `
            "$Case stopped the remaining GitLab ledger reads."
        Assert-True ([IO.File]::ReadAllText($fixtureLedgerPath) -ceq $beforeLedger) "$Case stamped or otherwise changed the ledger."
    }

    # The control: nothing moved. Only dates change in the ledger, nothing else under the root
    # changes, and the copy with the lineage's own blob counts as that lineage.
    $before = Get-TreeState
    $clean = Invoke-Audit
    Assert-True ($clean.ExitCode -eq 0) ("The audit refused a fixture where nothing moved: " +
        (@($clean.Report.findings | ForEach-Object { "$($_.kind): $($_.repository) $($_.detail)" }) -join ' | ') + "`n$($clean.Said)")
    Assert-True ($clean.Report.clean -and @($clean.Report.sources | Where-Object { $_.status -ne 'read' }).Count -eq 0 -and
        @($fakeForge.Requests | Where-Object { $_.Uri -like 'https://gitlab.com/api/v4/search*' }).Count -eq 3 -and
        @($clean.Report.knownHits | Where-Object { $_.repository -eq 'https://gitlab.com/fixture-group/gamma-patches' }).Count -eq 1) `
        'The control stamped its census without searching every Facebook-family package on GitLab and resolving its hit.'
    $stamped = [IO.File]::ReadAllText($fixtureLedgerPath)
    Assert-True (($stamped -replace '\d{4}-\d{2}-\d{2}', 'DATE') -ceq ($fixtureLedgerText -replace '\d{4}-\d{2}-\d{2}', 'DATE')) `
        'The clean audit changed more of the ledger than its dates.'
    $stampedLedger = $stamped | ConvertFrom-Json
    Assert-True ($stampedLedger.census.checkedAt -eq '2026-09-25' -and @($stampedLedger.entries | Where-Object { $_.lastChecked -ne '2026-09-25' }).Count -eq 0 -and
        $stampedLedger.indexes[2].hushfacebook.checked -eq '2026-09-25' -and $stampedLedger.indexes[1].hushfacebook.submitted -eq '2026-09-01') `
        'The clean audit did not stamp the census and every checked record with today, or it moved a submission date.'
    $after = Get-TreeState
    $changed = @(Compare-Object $before $after | ForEach-Object { ($_.InputObject -split ' ')[0] } | Sort-Object -Unique)
    Assert-True ($changed.Count -eq 1 -and $changed[0] -like '*facebook-sources.json') `
        "The audit wrote more than the ledger's dates under the root: $($changed -join ', ')"
    Assert-True ($clean.Report.counts.lineages -eq 3 -and $clean.Report.counts.mirrorsCollapsed -eq 1) `
        "The clean audit did not count three lineages with one copy collapsed: $($clean.Report.counts | ConvertTo-Json -Compress)"
    Assert-True ($clean.Report.counts.sources -eq 3 -and $clean.Report.counts.activeSources -eq 3 -and
        $clean.Report.counts.historicalSources -eq 0) 'Existing entries without an availability record stopped counting as active.'
    Assert-True (@($clean.Report.notes | Where-Object { $_ -like '*clone/alpha-clone is a byte-for-byte copy*alpha lineage*' }).Count -eq 1) `
        "The copy of the alpha lineage's file was not collapsed into it: $($clean.Report.notes -join ' | ')"
    Assert-True (@($clean.Report.lineages | Where-Object { $_.lineage -eq 'alpha' })[0].mirrors -contains 'https://github.com/clone/alpha-clone') `
        'The collapsed copy was not listed under its lineage.'
    $leaked = @($fakeForge.Requests | Where-Object { $_.Authorization -and $_.Uri -notlike 'https://api.github.com/*' })
    Assert-True ($leaked.Count -eq 0) "The GitHub token went to another host: $($leaked[0].Uri)"
    Assert-True (@($fakeForge.Requests | Where-Object { $_.Uri -like 'https://api.github.com/*' -and $_.Authorization -ne "Bearer $fakeToken" }).Count -eq 0) `
        'A GitHub API request went out without the token.'
    Assert-True ($clean.ReportText.IndexOf($fakeToken) -lt 0 -and $stamped.IndexOf($fakeToken) -lt 0) 'The token was written to the report or the ledger.'
    Assert-True (@($fakeForge.Requests | Where-Object { $_.PrivateToken -and $_.Uri -notlike 'https://gitlab.com/*' }).Count -eq 0 -and
        @($fakeForge.Requests | Where-Object { $_.Uri -like 'https://gitlab.com/api/*' -and $_.PrivateToken -ne $fakeGitLabToken }).Count -eq 0 -and
        $clean.ReportText.IndexOf($fakeGitLabToken) -lt 0 -and $stamped.IndexOf($fakeGitLabToken) -lt 0) `
        'The GitLab token leaked or a GitLab request lacked its private-token header.'
    Reset-FixtureLedger

    # A request that times out is tried again, and the run that gets its answer then reads clean.
    $fakeForge.TimeoutOnce = New-Object System.Collections.Generic.List[string]
    $fakeForge.TimeoutOnce.Add($receiptUrl)
    $retried = Invoke-Audit
    Assert-True ($retried.ExitCode -eq 0 -and $retried.Report.clean -and $fakeForge.TimeoutOnce.Count -eq 0 -and
        @($fakeForge.Requests | Where-Object { $_.Uri -eq $receiptUrl }).Count -eq 2) `
        "A request that timed out once was not tried again: $($retried.Said)"
    $fakeForge.Remove('TimeoutOnce')
    Reset-FixtureLedger

    # A 429 is waited out six times and the seventh try's answer reads clean; a seventh 429 is a
    # failure that leaves the ledger alone. The cases wait nothing, so Retry-After gets its own.
    $gammaProjectUrl = 'https://gitlab.com/api/v4/projects/fixture-group%2Fgamma-patches'
    $plainRun = Invoke-Audit
    $plainAsks = @($fakeForge.Requests | Where-Object { $_.Uri -eq $gammaProjectUrl }).Count
    Assert-True ($plainRun.ExitCode -eq 0 -and $plainAsks -ge 1) `
        "The rate-limit cases' control run didn't read $gammaProjectUrl cleanly (exit $($plainRun.ExitCode), $plainAsks requests): $($plainRun.Said)"
    Reset-FixtureLedger
    foreach ($limitedTimes in @(6, 7)) {
        $fakeForge.Limited = @{ $gammaProjectUrl = $limitedTimes }
        $limitedRun = Invoke-Audit
        $asks = @($fakeForge.Requests | Where-Object { $_.Uri -eq $gammaProjectUrl }).Count
        if ($limitedTimes -eq 6) {
            Assert-True ($limitedRun.ExitCode -eq 0 -and $limitedRun.Report.clean -and $asks -eq $plainAsks + 6) `
                "A request rate limited six times wasn't read on its seventh try ($asks requests): $($limitedRun.Said)"
        } else {
            Assert-True ($limitedRun.ExitCode -eq 1 -and -not $limitedRun.Report.clean -and $asks -ge 7 -and $limitedRun.Said -like '*HTTP 429*' -and
                [IO.File]::ReadAllText($fixtureLedgerPath) -ceq $fixtureLedgerText) `
                "A request rate limited seven times wasn't a failure that left the ledger alone ($asks requests): $($limitedRun.Said)"
        }
        Reset-FixtureLedger
    }
    $fakeForge.Limited = @{ $gammaProjectUrl = 1 }
    $fakeForge.RetryAfter = 2
    $stretched = Invoke-Audit @{ RateLimitWaitSeconds = 1 }
    Assert-True ($stretched.ExitCode -eq 0 -and $stretched.Said -like '*rate limited on gitlab.com, waiting 2 s*') `
        "A 429's Retry-After didn't set the wait: $($stretched.Said)"
    $fakeForge.Remove('Limited')
    $fakeForge.Remove('RetryAfter')
    Reset-FixtureLedger

    # GitLab.com has no global code search, so skipping it (on purpose or for want of a token) is
    # the one gap a census may carry. The run stamps, the census names the skip, and the report
    # keeps the evidence for it. It never reads as coverage, and -NoStamp still changes nothing.
    function Assert-SkippedGitLab {
        param([hashtable]$Extra, [string]$Detail, [string]$Case)
        $result = Invoke-Audit $Extra
        $skip = @($result.Report.sources | Where-Object { $_.source -eq 'GitLab code search' })
        Assert-True ($result.ExitCode -eq 0 -and $result.Report.clean -and $skip.Count -eq 1 -and $skip[0].status -eq 'skipped' -and
            $skip[0].detail -like $Detail -and $skip[0].evidence.index -eq 'gitlab-code-search' -and
            $skip[0].evidence.documentation -contains 'https://docs.gitlab.com/user/search/advanced_search/') `
            "$Case wasn't stamped with its skip and evidence on record: $($result.Said)"
        Assert-True (@($result.Report.findings).Count -eq 0 -and
            @($fakeForge.Requests | Where-Object { $_.Uri -like 'https://gitlab.com/api/v4/search*' }).Count -eq 0 -and
            @($result.Report.sources | Where-Object { $_.source -eq 'https://gitlab.com/fixture-group/gamma-patches' -and $_.status -eq 'read' }).Count -eq 1) `
            "$Case ran GitLab code search, raised a finding, or stopped the GitLab ledger reads."
        $after = [IO.File]::ReadAllText($fixtureLedgerPath) | ConvertFrom-Json
        Assert-True ($after.census.checkedAt -eq '2026-09-25' -and (@($after.census.skipped) -join ',') -eq 'gitlab-code-search') `
            "$Case didn't stamp the census with gitlab-code-search under skipped."
        Reset-FixtureLedger
    }
    Assert-SkippedGitLab @{ SkipGitLabCodeSearch = $true; GitLabToken = '' } '*-SkipGitLabCodeSearch*GitLab.com*global code search*' 'An explicit GitLab skip'
    Assert-SkippedGitLab @{ GitLabToken = '' } '*GITLAB_TOKEN*' 'A GitLab search with no token'
    $env:GITLAB_TOKEN = 'fixture-gitlab-' + [guid]::NewGuid().ToString('N')
    try {
        Assert-SkippedGitLab @{ SkipGitLabCodeSearch = $true; GitLabToken = '' } '*-SkipGitLabCodeSearch*' 'An explicit GitLab skip with a token in the environment'
    } finally { $env:GITLAB_TOKEN = $null }
    $unstamped = Invoke-Audit @{ SkipGitLabCodeSearch = $true; GitLabToken = ''; NoStamp = $true }
    Assert-True ($unstamped.ExitCode -eq 0 -and [IO.File]::ReadAllText($fixtureLedgerPath) -ceq $fixtureLedgerText) `
        'A skipping run with -NoStamp changed the ledger.'
    # Any other gap still refuses, with the skip beside it.
    $fakeForge.Answers.awesome = @{ Status = 500; Content = $null }
    $both = Invoke-Audit @{ SkipGitLabCodeSearch = $true; GitLabToken = '' }
    Assert-True ($both.ExitCode -eq 1 -and -not $both.Report.clean -and [IO.File]::ReadAllText($fixtureLedgerPath) -ceq $fixtureLedgerText) `
        'A run that skipped GitLab and failed another source stamped the census.'
    $fakeForge.Answers = New-FakeAnswers
    Reset-FixtureLedger

    $fakeForge.Answers.gitlabSearch = @{ Status = 403; Content = $null }
    Assert-Incomplete @{} 'source-failed' '*GitLab code search*HTTP 403*global code search*' 'A denied global GitLab search'
    $fakeForge.Answers = New-FakeAnswers
    $fakeForge.Answers.gitlabSearchProject = @{ Status = 403; Content = $null }
    Assert-Incomplete @{} 'source-failed' '*GitLab project 303*HTTP 403*' 'A GitLab search hit whose project cannot be read'
    $fakeForge.Answers = New-FakeAnswers
    $fakeForge.Answers.gitlabSearch.Content = '[{"path":"patches/Lite.kt"}]'
    Assert-Incomplete @{} 'source-failed' '*hit without a project ID or path*' 'A GitLab search hit with incomplete metadata'
    $fakeForge.Answers = New-FakeAnswers
    $fakeForge.Answers.gitlabSearchProject.Content = '{}'
    Assert-Incomplete @{} 'source-failed' '*GitLab project 303*no repository path*' 'An unresolved GitLab repository path'
    $fakeForge.Answers = New-FakeAnswers

    # -ValidateOnly and an invalid ledger never reach the network.
    $validate = Invoke-Audit @{ ValidateOnly = $true }
    Assert-True ($validate.ExitCode -eq 0 -and $fakeForge.Requests.Count -eq 0 -and $validate.Said -like '*3 sources in 3 lineages*') `
        "-ValidateOnly made $($fakeForge.Requests.Count) requests or refused the fixture: $($validate.Said)"
    $broken = $fixtureLedgerText | ConvertFrom-Json
    $broken.entries[1].disposition = 'candidate'
    [IO.File]::WriteAllText($fixtureLedgerPath, ($broken | ConvertTo-Json -Depth 20))
    $invalid = Invoke-Audit
    Assert-True ($invalid.ExitCode -eq 1 -and $fakeForge.Requests.Count -eq 0 -and
        @($invalid.Report.findings | Where-Object { $_.kind -eq 'ledger-invalid' }).Count -gt 0) `
        "An invalid ledger was audited ($($fakeForge.Requests.Count) requests, exit $($invalid.ExitCode))."
    Reset-FixtureLedger

    # Each way a source can move, one at a time, each put back after.
    $fakeForge.Answers.directory.Content = $fakeForge.Answers.directory.Content.Replace('"repo":  "fixture-owner/alpha-patches"', '"repo":  "newcomer/fb-patches"').Replace(
        '"repo": "fixture-owner/alpha-patches"', '"repo": "newcomer/fb-patches"')
    Assert-True ($fakeForge.Answers.directory.Content -like '*newcomer/fb-patches*') 'The directory answer was not edited, so the addition case would prove nothing.'
    Assert-Drift 'addition' '*github.com/newcomer/fb-patches*Morphe directory*' 'A bundle only the directory knows'
    $fakeForge.Answers = New-FakeAnswers

    $fakeForge.Answers.searchHits[2].sha = ('9b' * 20)
    Assert-Drift 'addition' '*github.com/clone/alpha-clone*GitHub code search*' 'A code search hit whose file no lineage holds'
    $fakeForge.Answers = New-FakeAnswers

    $fakeForge.Answers.alphaLicense = @{ Status = 404; Content = $null }
    Assert-Drift 'license-removed' '*alpha-patches*licence*gone*' 'A source whose licence was removed'
    $fakeForge.Answers = New-FakeAnswers

    $fakeForge.Answers.gammaLicense.Content = (@{ content = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('All rights reserved.')) } | ConvertTo-Json)
    Assert-Drift 'license-changed' '*gamma-patches*licence text changed*' 'A GitLab source whose licence text changed'
    $fakeForge.Answers = New-FakeAnswers

    $fakeForge.Answers.alphaBranches.Content = $fakeForge.Answers.alphaBranches.Content.Replace($commitA1, ('a2' * 20))
    Assert-Drift 'changed-head' "*alpha-patches*branch main moved from $commitA1 to $('a2' * 20)*" 'A pinned branch that moved'
    $fakeForge.Answers = New-FakeAnswers

    $fakeForge.Answers.betaWatch.Content = $fakeForge.Answers.betaWatch.Content.Replace($commitB1, ('b2' * 20))
    Assert-Drift 'changed-head' '*beta-module*branch main moved*' 'A watched path that moved under a branch whose head the ledger never pinned'
    $fakeForge.Answers = New-FakeAnswers

    # The last commit of a deleted path can equal its pin. Tree metadata, not that commit alone,
    # establishes whether the branch still holds the source. An incomplete tree proves no absence.
    $fakeForge.Answers.betaTree.Content = '{"tree":[],"truncated":false}'
    Assert-Drift 'changed-head' '*beta-module*to no Facebook-family code*' 'A deleted watched path whose last commit still equals its pin'
    $fakeForge.Answers.betaTree.Content = '{"tree":[],"truncated":true}'
    Assert-Drift 'source-failed' '*beta-module*truncated*' 'A truncated tree that could not prove the watched source absent'
    $fakeForge.Answers.betaTree.Content = '{"tree":[{"path":"app/Messenger","type":"tree"}],"truncated":false}'
    Assert-Drift 'changed-head' '*beta-module*to no Facebook-family code*' 'A watched path renamed with different case'
    $fakeForge.Answers.betaTree.Content = '{}'
    Assert-Drift 'source-failed' '*beta-module*no paths or truncation flag*' 'An invalid tree answer that could not prove source availability'
    $fakeForge.Answers = New-FakeAnswers

    # Retirement preserves the historical pin, the package/target record and licence disposition.
    # Current branches can keep moving through unrelated work without replacing that history.
    $historical = $fixtureLedgerText | ConvertFrom-Json
    $historical.entries[1] | Add-Member -NotePropertyName availability -NotePropertyValue ([pscustomobject]@{
        status = 'retired'; reason = 'The Messenger source was removed.'
        evidence = "https://github.com/fixture-owner/beta-module/commit/$('b9' * 20)" })
    $historicalText = $historical | ConvertTo-Json -Depth 20
    [IO.File]::WriteAllText($fixtureLedgerPath, $historicalText)
    $fakeForge.Answers.betaTree.Content = '{"tree":[],"truncated":false}'
    $retired = Invoke-Audit @{ NoStamp = $true }
    Assert-True ($retired.ExitCode -eq 0 -and $retired.Report.counts.sources -eq 3 -and
        $retired.Report.counts.activeSources -eq 2 -and $retired.Report.counts.retiredSources -eq 1 -and
        $retired.Report.counts.unavailableSources -eq 0 -and $retired.Report.counts.historicalSources -eq 1 -and
        [IO.File]::ReadAllText($fixtureLedgerPath) -ceq $historicalText) `
        "A retired source with reachable history did not preserve the census total and ledger: $($retired.Said)"
    $retiredSource = @($retired.Report.lineages | Where-Object { $_.lineage -eq 'beta' })[0].sources[0]
    Assert-True ($retiredSource.availability -eq 'retired' -and $retiredSource.disposition -eq 'behavior-only' -and
        $null -ne $retiredSource.targetVersions.PSObject.Properties['com.facebook.orca']) `
        'The report lost the retired source target record or conflated availability with its licence disposition.'
    $fakeForge.Answers.betaHistoryTree = @{ Status = 404; Content = $null }
    Assert-Drift 'historical-pin-unavailable' '*beta-module*retired source pin*unavailable*' 'A retired source whose historical path disappeared'
    $fakeForge.Answers = New-FakeAnswers
    Assert-Drift 'source-reactivated' '*beta-module*retired source*main again*' 'A retired source that returned on a current branch'

    $historical.entries[1].availability.status = 'unavailable'
    $unavailableText = $historical | ConvertTo-Json -Depth 20
    [IO.File]::WriteAllText($fixtureLedgerPath, $unavailableText)
    $fakeForge.Answers.betaTree.Content = '{"tree":[],"truncated":false}'
    $fakeForge.Answers.betaHistoryTree = @{ Status = 404; Content = $null }
    $unavailable = Invoke-Audit @{ NoStamp = $true }
    Assert-True ($unavailable.ExitCode -eq 0 -and $unavailable.Report.counts.sources -eq 3 -and
        $unavailable.Report.counts.activeSources -eq 2 -and $unavailable.Report.counts.unavailableSources -eq 1 -and
        $unavailable.Report.counts.historicalSources -eq 1 -and [IO.File]::ReadAllText($fixtureLedgerPath) -ceq $unavailableText) `
        "An explicitly unavailable history was not counted or preserved: $($unavailable.Said)"
    $fakeForge.Answers.betaHistoryTree = (New-FakeAnswers).betaHistoryTree
    Assert-Drift 'historical-pin-restored' '*beta-module*unavailable source pin*reachable again*' 'An unavailable historical source that became reachable'
    $fakeForge.Answers.betaHistoryTree = @{ Status = 404; Content = $null }
    $fakeForge.Answers.betaRepo = @{ Status = 404; Content = $null }
    $gone = Invoke-Audit @{ NoStamp = $true }
    Assert-True ($gone.ExitCode -eq 0) "A repository and history explicitly recorded as unavailable were refused: $($gone.Said)"
    $fakeForge.Answers.betaRepo = @{ Status = 500; Content = $null }
    Assert-Drift 'repository-gone' '*beta-module*HTTP 500*' 'An unavailable source with a transient server error'
    Reset-FixtureLedger
    $fakeForge.Answers = New-FakeAnswers

    # GitLab checks the same distinction through its blobless mirror, without fetching any source
    # file: branches, folder listings and history come from git, and none of it from the API.
    $gitlabWatched = $fixtureLedgerText | ConvertFrom-Json
    $gitlabWatched.entries[2].watchPaths = @('patches')
    [IO.File]::WriteAllText($fixtureLedgerPath, ($gitlabWatched | ConvertTo-Json -Depth 20))
    $gitlabControl = Invoke-Audit @{ NoStamp = $true }
    $mirrorReads = @($fakeForge.GitCalls | Where-Object { $_ -match '(^| )(ls-tree|log) ' })
    Assert-True ($gitlabControl.ExitCode -eq 0 -and $mirrorReads.Count -eq 2 -and
        @($fakeForge.GitCalls | Where-Object { $_ -match '(^| )(clone|fetch) ' -and $_ -notmatch '--filter=blob:none' }).Count -eq 0 -and
        @($fakeForge.Requests | Where-Object { $_.Uri -match 'gamma-patches/repository/(branches|commits|compare)|tree\?ref=[0-9a-f]{40}' }).Count -eq 0) `
        "A watched GitLab source wasn't read through its blobless mirror alone: $($fakeForge.GitCalls -join ' | ') $($gitlabControl.Said)"
    $fakeForge.Answers.gammaMirror.History["$commitG1 patches"] = "$('c2' * 20)`t2026-09-02T10:00:00+00:00"
    Assert-Drift 'changed-head' "*gamma-patches*branch main moved from $commitG1 to $('c2' * 20)*" 'A GitLab watched path with a newer commit'
    $fakeForge.Answers = New-FakeAnswers
    $fakeForge.Answers.gammaMirror.Listings[$commitG1] = @('LICENSE', 'Patches')
    Assert-Drift 'changed-head' '*gamma-patches*to no Facebook-family code*' 'A GitLab watched path renamed with different case'
    $fakeForge.Answers.gammaMirror.Listings[$commitG1] = @('LICENSE')
    Assert-Drift 'changed-head' '*gamma-patches*to no Facebook-family code*' 'A deleted GitLab watched path whose last commit still equals its pin'
    Reset-FixtureLedger
    $fakeForge.Answers = New-FakeAnswers

    # A GitLab branch behind the pinned one carries nothing new, and the mirror says so locally.
    $fakeForge.Answers.gammaMirror.Heads['side'] = 'c3' * 20
    $fakeForge.Answers.gammaMirror.Behind = @("$('c3' * 20) $commitG1")
    $behind = Invoke-Audit @{ NoStamp = $true }
    Assert-True ($behind.ExitCode -eq 0 -and @($fakeForge.GitCalls | Where-Object { $_ -like "*merge-base --is-ancestor $('c3' * 20) $commitG1" }).Count -eq 1) `
        "A GitLab branch behind its pinned one was reported, or not compared in the mirror: $($fakeForge.GitCalls -join ' | ') $($behind.Said)"
    $fakeForge.Answers.gammaMirror.Behind = @()
    Assert-Drift 'new-branch' '*gamma-patches*branch side carries Facebook-family work*' 'A GitLab branch with work of its own'
    $fakeForge.Answers = New-FakeAnswers

    # A mirror that won't fetch is cloned again; a source that won't clone can't be read.
    $fakeForge.Answers.gammaMirror.FetchFails = $true
    $recloned = Invoke-Audit @{ NoStamp = $true }
    Assert-True ($recloned.ExitCode -eq 0 -and ($fakeForge.GitCalls -join ' | ') -match ' fetch .* \| .* clone ') `
        "A mirror that wouldn't fetch wasn't cloned again: $($fakeForge.GitCalls -join ' | ') $($recloned.Said)"
    $fakeForge.Answers.gammaMirror.CloneFails = $true
    Assert-Drift 'source-failed' '*gamma-patches*mirror of fixture-group/gamma-patches could not be fetched*' 'A GitLab source whose mirror would not clone'
    $fakeForge.Answers = New-FakeAnswers
    $inside = $null
    try { Invoke-Audit @{ MirrorRoot = (Join-Path $fixtureRoot 'mirrors') } | Out-Null } catch { $inside = "$_" }
    Assert-True ($inside -like "*mirrors can't go inside the repository*" -and -not (Test-Path -LiteralPath (Join-Path $fixtureRoot 'mirrors'))) `
        "A mirror root inside the repository was accepted: $inside"

    # A branch cut from main before its Facebook code last moved carries nothing new; one ahead of it does.
    $fakeForge.Answers.alphaBranches.Content = "[{`"name`":`"main`",`"commit`":{`"sha`":`"$commitA1`"}},{`"name`":`"old`",`"commit`":{`"sha`":`"$('a3' * 20)`"}}]"
    $fakeForge.Answers.compare[('a3' * 20)] = 'behind'
    $contained = Invoke-Audit
    Assert-True ($contained.ExitCode -eq 0) ("A branch behind a pinned one was reported: " +
        (@($contained.Report.findings | ForEach-Object { "$($_.kind): $($_.detail)" }) -join ' | '))
    Reset-FixtureLedger
    $fakeForge.Answers.compare[('a3' * 20)] = 'ahead'
    Assert-Drift 'new-branch' '*alpha-patches*branch old carries Facebook-family work*' 'A branch with work of its own'
    $fakeForge.Answers = New-FakeAnswers

    $fakeForge.Answers.alphaForks.Content = '[{"full_name":"someone/alpha-patches"},{"full_name":"stranger/alpha-patches"}]'
    Assert-Drift 'unseen-fork' '*alpha-patches*fork stranger/alpha-patches*' 'A fork the ledger has not seen'
    $fakeForge.Answers = New-FakeAnswers

    # A GitLab fork waiting to be deleted carries its own id in its name; one whose suffix names another id is a real fork.
    $fakeForge.Answers.gammaForks.Content = '[{"id":42,"path_with_namespace":"scanner/gamma-patches-deletion_scheduled-42"}]'
    $scheduled = Invoke-Audit
    Assert-True ($scheduled.ExitCode -eq 0) ("A GitLab fork waiting to be deleted was reported: " +
        (@($scheduled.Report.findings | ForEach-Object { "$($_.kind): $($_.detail)" }) -join ' | '))
    Reset-FixtureLedger
    $fakeForge.Answers.gammaForks.Content = '[{"id":41,"path_with_namespace":"scanner/gamma-patches-deletion_scheduled-42"}]'
    Assert-Drift 'unseen-fork' '*gamma-patches*fork scanner/gamma-patches-deletion_scheduled-42*' 'A GitLab fork named after another id'
    $fakeForge.Answers = New-FakeAnswers

    $fakeForge.Answers.gammaProject = @{ Status = 404; Content = $null }
    Assert-Drift 'repository-gone' '*gamma-patches*HTTP 404*' 'A GitLab source that is gone'
    $fakeForge.Answers = New-FakeAnswers

    $fakeForge.Answers.alphaRepo.Content = $fakeForge.Answers.alphaRepo.Content.Replace('"archived":false', '"archived":true')
    Assert-Drift 'archived-changed' '*alpha-patches*archived*' 'A source that was archived'
    $fakeForge.Answers = New-FakeAnswers

    $fakeForge.Answers.tracker.Content = $fakeForge.Answers.tracker.Content.Replace('SysAdminDoc/Hushfacebook', 'SysAdminDoc/hushfeed')
    Assert-Drift 'listing-changed' '*Morphe Patch Tracker no longer lists Hushfacebook*' 'A listing that disappeared'
    $fakeForge.Answers = New-FakeAnswers

    $fakeForge.Answers.official.Content = '{"patches":[{"name":"Hide ads","compatiblePackages":[{"packageName":"com.facebook.orca"}]}]}'
    Assert-Drift 'official-bundle-changed' '*official bundle now patches*com.facebook.orca*' 'The official bundle taking on Messenger'
    $fakeForge.Answers = New-FakeAnswers

    # An adopted source's receipt has to prove every build its evidence names, unforced.
    $fakeForge.Answers.receipt.Content = $fakeForge.Answers.receipt.Content -replace '"forced":\s*false', '"forced": true'
    Assert-Drift 'fixture-evidence' "*alpha-patches*no unforced run of Facebook $($evidenceBuilds[0])*" 'An adopted source whose receipt forced a build'
    $fakeForge.Answers = New-FakeAnswers

    # A version name alone doesn't prove the build its evidence names by version code.
    $fakeForge.Answers.receipt.Content = $fakeForge.Answers.receipt.Content -replace '900000002', '900000007'
    Assert-Drift 'fixture-evidence' "*alpha-patches*no unforced run of Facebook $($evidenceBuilds[1])*" 'An adopted source whose receipt lacks one build by version code'
    $fakeForge.Answers = New-FakeAnswers

    $fakeForge.Answers.alphaRepo.Content = $fakeForge.Answers.alphaRepo.Content.Replace('"fixture-owner/alpha-patches"', '"fixture-owner/alpha-renamed"')
    Assert-Drift 'renamed' '*alpha-patches*now https://github.com/fixture-owner/alpha-renamed*' 'A source that was renamed'
    $fakeForge.Answers = New-FakeAnswers

    $fakeForge.Answers.directory.Content = $fakeForge.Answers.directory.Content -replace '"com\.facebook\.katana"(\s*)\]', '"com.facebook.katana", "com.facebook.orca"$1]'
    Assert-True ($fakeForge.Answers.directory.Content -like '*com.facebook.orca*') 'The directory answer was not edited, so the packages case would prove nothing.'
    Assert-Drift 'packages-changed' '*alpha-patches*now targets com.facebook.orca*' 'A source an index says now targets Messenger'
    $fakeForge.Answers = New-FakeAnswers

    # A recorded fork or an out-of-scope repository that an index lists as a bundle of its own. The
    # control's archived copy of the fork's list, for alpha's own package, stayed quiet.
    foreach ($listed in @('someone/alpha-patches', 'noise/mentions-facebook')) {
        $fakeForge.Answers.directory.Content = $fakeForge.Answers.directory.Content.Replace('"repo":  "fixture-owner/alpha-patches"', "`"repo`":  `"$listed`"").Replace(
            '"repo": "fixture-owner/alpha-patches"', "`"repo`": `"$listed`"")
        Assert-True ($fakeForge.Answers.directory.Content -like "*$listed*") "The directory answer was not edited, so the $listed case would prove nothing."
        $expected = if ($listed -like 'someone/*') { "*github.com/$listed*recorded fork of alpha*Morphe directory*" } else { "*$listed*out of scope*Morphe directory*" }
        Assert-Drift 'listed-as-bundle' $expected "The directory listing $listed as a bundle"
        $fakeForge.Answers = New-FakeAnswers
    }
    # An out-of-scope listing whose package listedFor names was checked already and stays quiet. The
    # same listing against a listedFor that names another package is the control.
    foreach ($reviewedPackage in @('com.facebook.katana', 'com.facebook.orca')) {
        $reviewedLedger = $fixtureLedgerText | ConvertFrom-Json
        $reviewedLedger.outOfScope[0] | Add-Member -NotePropertyName listedFor -NotePropertyValue @($reviewedPackage) -Force
        [IO.File]::WriteAllText($fixtureLedgerPath, ($reviewedLedger | ConvertTo-Json -Depth 20), (New-Object Text.UTF8Encoding $false))
        $fakeForge.Answers.directory.Content = $fakeForge.Answers.directory.Content.Replace('"repo":  "fixture-owner/alpha-patches"', '"repo":  "noise/mentions-facebook"').Replace(
            '"repo": "fixture-owner/alpha-patches"', '"repo": "noise/mentions-facebook"')
        Assert-True ($fakeForge.Answers.directory.Content -like '*noise/mentions-facebook*') 'The directory answer was not edited, so the listedFor case would prove nothing.'
        if ($reviewedPackage -eq 'com.facebook.katana') {
            $quiet = Invoke-Audit
            Assert-True ($null -ne $quiet.Report -and @($quiet.Report.findings | Where-Object { $_.kind -eq 'listed-as-bundle' }).Count -eq 0) `
                "An out-of-scope listing its listedFor names was a finding again: $($quiet.Said)"
        } else {
            Assert-Drift 'listed-as-bundle' '*mentions-facebook*bundle for com.facebook.katana;*listedFor*' `
                'An out-of-scope listing under a package its listedFor doesn''t name'
        }
        $fakeForge.Answers = New-FakeAnswers
        Reset-FixtureLedger
    }
    $fakeForge.Answers.orcaListHits = @(@{ repository = @{ full_name = 'rushiforai/morphe-archive' }
        path = 'examplepatches/someone/alpha-patches/patches-list.json'; sha = ('7c' * 20) })
    Assert-Drift 'listed-as-bundle' '*someone/alpha-patches*com.facebook.orca*' 'A recorded fork whose archived patch list takes on Messenger'
    $fakeForge.Answers = New-FakeAnswers

    $fakeForge.Answers.searchHits[1].sha = ('8b' * 20)
    Assert-Drift 'mirror-changed' '*copier/alpha-copy*' 'A recorded copy whose file changed'
    $fakeForge.Answers = New-FakeAnswers

    $fakeForge.Answers.alphaBranches.Content = "[{`"name`":`"trunk`",`"commit`":{`"sha`":`"$commitA1`"}}]"
    Assert-Drift 'branch-removed' '*alpha-patches*branch main is gone*' 'A pinned branch that was deleted'
    $fakeForge.Answers = New-FakeAnswers

    $fakeForge.Answers.betaLicense = @{ Status = 200; Content = (@{ path = 'LICENSE'; content = $licenseBase64; license = @{ spdx_id = 'MIT' } } | ConvertTo-Json) }
    Assert-Drift 'license-added' '*beta-module*now has a licence (MIT*' 'An unlicensed source that gained a licence'
    $fakeForge.Answers = New-FakeAnswers

    $fakeForge.Answers.jman['com.facebook.orca'] = @(, @('https://codeberg.org/fixture-owner/delta-patches'))
    Assert-Drift 'index-unresolved' "*Jman's catalog lists a Facebook-family bundle from 'https://codeberg.org/fixture-owner/delta-patches'*" 'A Jman bundle whose source is not on GitHub or GitLab'
    $fakeForge.Answers = New-FakeAnswers

    # Jman's pages can run short of the limit, so a source only its second page lists is still read.
    $fakeForge.Answers.jman['com.facebook.katana'] = @(@('https://github.com/fixture-owner/alpha-patches'), @('https://github.com/newcomer/fb-patches'))
    Assert-Drift 'addition' '*github.com/newcomer/fb-patches*Jman*' 'A source only the second page of a Jman answer lists'
    $fakeForge.Answers = New-FakeAnswers

    # An index still spelling a source by its old name is that source, not an addition.
    $fakeForge.Answers.directory.Content = $fakeForge.Answers.directory.Content -replace '"fixture-owner/alpha-patches"', '"fixture-owner/alpha-old"'
    $oldName = Invoke-Audit
    Assert-True ($oldName.ExitCode -eq 0 -and @($oldName.Report.notes | Where-Object { $_ -like '*alpha-old is an old name of*alpha-patches*' }).Count -eq 1) `
        ("An index spelling a source by its old name was not read as that source: " +
            (@($oldName.Report.findings | ForEach-Object { "$($_.kind): $($_.repository)" }) -join ' | '))
    Reset-FixtureLedger
    $fakeForge.Answers = New-FakeAnswers

    # An old name gets the checks the current name gets: here it's the recorded fork's old name,
    # listed as a bundle of its own.
    $fakeForge.Answers.directory.Content = $fakeForge.Answers.directory.Content -replace '"fixture-owner/alpha-patches"', '"fixture-owner/alpha-old"'
    $fakeForge.Answers.oldNameMeta.Content = '{"full_name":"someone/alpha-patches","fork":true,"archived":false}'
    Assert-Drift 'listed-as-bundle' '*github.com/someone/alpha-patches*recorded fork of alpha*' 'A recorded fork an index lists by its old name'
    $fakeForge.Answers = New-FakeAnswers

    $fakeForge.Answers.awesome = @{ Status = 500; Content = $null }
    Assert-Drift 'source-failed' '*Awesome Morphe could not be read*' 'An index that could not be read'
    $fakeForge.Answers = New-FakeAnswers

    Assert-Throws { Invoke-Audit @{ ReportPath = (Join-Path $fixtureRoot 'patches/report.json') } } '*under patches/*' `
        'The audit wrote its report among the patch sources.'
    Assert-True ([IO.File]::ReadAllText($fixtureLedgerPath) -ceq $fixtureLedgerText) 'A refused run changed the ledger.'
}

Write-Host '[sources] the audit reports every kind of drift, collapses copies by content and stamps only a clean census'

# --- the GitLab coverage ledger, against a stand-in for gitlab.com --------------------------------

# scripts/gitlab-coverage.ps1 is a prototype of what a census of GitLab would have to keep, and it
# counts for nothing in the census yet. So the checked-in census has to go on saying GitLab's code
# search was skipped, and nothing below may change that file.
$scanScript = Join-Path $PSScriptRoot 'scan-gitlab-coverage.ps1'
$coverageRoot = Join-Path $caseRoot 'coverage'
New-Item -ItemType Directory -Path $coverageRoot -Force | Out-Null
$censusBefore = [IO.File]::ReadAllText($ledgerPath)
Assert-True (@($ledger.census.skipped) -contains 'gitlab-code-search') `
    'The checked-in census stopped recording gitlab-code-search as skipped, and no full coverage ledger stands behind that.'

& {
    $c11a = '1a' * 20
    $c11b = '1b' * 20
    $c15 = '15' * 20
    $c20 = '20' * 20
    $c21 = '21' * 20
    $c30 = '30' * 20

    function ConvertTo-FakeList {
        param($Items)
        if (@($Items).Count -eq 0) { return '[]' }
        return (ConvertTo-Json -InputObject @($Items) -Depth 6 -Compress)
    }

    function New-FakeTree {
        # A tree of blobs for one commit, each with an id of its own.
        param([string]$Seed, [int]$Count)
        return @(for ($i = 1; $i -le $Count; $i++) {
            [ordered]@{ id = (Get-Sha256Text "$Seed-$i").Substring(0, 40); name = ('f{0:d4}.kt' -f $i); type = 'blob'
                path = ('src/{0}/f{1:d4}.kt' -f $Seed, $i); mode = '100644' } })
    }

    function Add-FakeProject {
        # -Branches is name to commit, in the order GitLab lists them. -Trees is commit to entry count.
        param($Lab, [long]$Id, [string]$Path, $Branches, $Trees)
        $Lab.Projects["$Id"] = @{ id = $Id; path = $Path; branches = $Branches }
        foreach ($commit in @($Trees.Keys)) { $Lab.Trees["$Id@$commit"] = @(New-FakeTree "$Id-$commit" $Trees[$commit]) }
    }

    function New-FakeLab {
        <#
            Six public projects, ids 11 to 30. Project 11 has three branches, two of them at one
            commit, and a tree of 150 entries that takes two pages. Project 12 is empty, 21 has a
            commit with no files, and 30 is the newest, so it is the ceiling a run pins.
        #>
        $lab = @{ Projects = @{}; Trees = @{}; Pages = @{}; Remaining = $null; RepeatTree = $null
            Requests = (New-Object System.Collections.Generic.List[object])
            Failures = (New-Object System.Collections.Generic.List[object]) }
        Add-FakeProject $lab 11 'one/alpha' ([ordered]@{ main = $c11a; dev = $c11b; copy = $c11a }) @{ $c11a = 3; $c11b = 150 }
        Add-FakeProject $lab 12 'one/empty' ([ordered]@{}) @{}
        Add-FakeProject $lab 15 'two/beta' ([ordered]@{ main = $c15 }) @{ $c15 = 2 }
        Add-FakeProject $lab 20 'two/gamma' ([ordered]@{ main = $c20 }) @{ $c20 = 1 }
        Add-FakeProject $lab 21 'three/delta' ([ordered]@{ main = $c21 }) @{ $c21 = 0 }
        Add-FakeProject $lab 30 'three/omega' ([ordered]@{ 'release/1.0' = $c30 }) @{ $c30 = 4 }
        return $lab
    }

    function Get-FakeProjectPage {
        # What the keyset project list answers after an id. GitLab names a next page whenever the
        # one it served is full, and continues it from that page's last id.
        param($Lab, [long]$After, [int]$PerPage)
        $rest = @($Lab.Projects.Values | Where-Object { $_.id -gt $After } | Sort-Object { $_.id } | Select-Object -First $PerPage)
        $headers = @{}
        if ($rest.Count -eq $PerPage) {
            $headers['Link'] = "<https://gitlab.com/api/v4/projects?id_after=$($rest[-1].id)&imported=false&order_by=id&owned=false&page=1" +
                "&pagination=keyset&per_page=$PerPage&sort=asc>; rel=`"next`""
        }
        $items = @($rest | ForEach-Object { [ordered]@{ id = $_.id; path_with_namespace = $_.path; default_branch = 'main' } })
        return @{ Status = 200; Content = (ConvertTo-FakeList $items); Headers = $headers }
    }

    # Stands in for gitlab.com, so no case needs the network. A request it doesn't recognize fails
    # the case that made it. $fakeLab.Failures answers a matching request with a status instead,
    # .Pages replaces one page of the project list, .RepeatTree serves a tree's first page for
    # every page, and .Remaining counts the rate-limit window down in the headers.
    function Invoke-WebRequest {
        param($Uri, $Headers, $UserAgent, [switch]$UseBasicParsing, $TimeoutSec, $Method, $OutFile, $MaximumRedirection, [switch]$PassThru, $Body)
        $url = [string]$Uri
        $lab = $fakeLab
        $lab.Requests.Add([pscustomobject]@{ Uri = $url; Headers = $Headers; Method = $Method; Body = $Body; OutFile = $OutFile })
        $answer = $null
        foreach ($failure in $lab.Failures) {
            if ($null -eq $answer -and $failure.Times -gt 0 -and $url -match $failure.Pattern) {
                $failure.Times--
                $answer = @{ Status = $failure.Status; Content = $null; Headers = $failure.Headers }
            }
        }
        if ($null -eq $answer) {
            switch -Regex ($url) {
                '^https://gitlab\.com/api/v4/projects\?pagination=keyset&order_by=id&sort=desc&per_page=1$' {
                    $newest = @($lab.Projects.Values | Sort-Object { $_.id } | Select-Object -Last 1 |
                        ForEach-Object { [ordered]@{ id = $_.id; path_with_namespace = $_.path } })
                    $answer = @{ Status = 200; Content = (ConvertTo-FakeList $newest); Headers = @{} }
                    break
                }
                '^https://gitlab\.com/api/v4/projects\?pagination=keyset&order_by=id&sort=asc&per_page=(\d+)&id_after=(\d+)$' {
                    $perPage = [int]$Matches[1]
                    $after = [long]$Matches[2]
                    $answer = if ($lab.Pages.ContainsKey("$after")) { $lab.Pages["$after"] } else { Get-FakeProjectPage $lab $after $perPage }
                    break
                }
                '^https://gitlab\.com/api/v4/projects/(\d+)$' {
                    $project = $lab.Projects[$Matches[1]]
                    $answer = if ($null -eq $project) { @{ Status = 404 } } else {
                        @{ Status = 200; Content = ([ordered]@{ id = $project.id; path_with_namespace = $project.path } | ConvertTo-Json -Compress) } }
                    break
                }
                '^https://gitlab\.com/api/v4/projects/(\d+)/repository/branches\?per_page=(\d+)(?:&page=(\d+))?$' {
                    $project = $lab.Projects[$Matches[1]]
                    $perPage = [int]$Matches[2]
                    $page = if ($Matches[3]) { [int]$Matches[3] } else { 1 }
                    if ($null -eq $project) { $answer = @{ Status = 404 }; break }
                    $all = @($project.branches.Keys | ForEach-Object { [ordered]@{ name = $_; commit = [ordered]@{ id = $project.branches[$_] }; default = $false } })
                    $slice = @($all | Select-Object -Skip (($page - 1) * $perPage) -First $perPage)
                    $nextPage = if ($page * $perPage -lt $all.Count) { [string]($page + 1) } else { '' }
                    $answer = @{ Status = 200; Content = (ConvertTo-FakeList $slice); Headers = @{ 'x-total' = [string]$all.Count; 'x-next-page' = $nextPage } }
                    break
                }
                '^https://gitlab\.com/api/v4/projects/(\d+)/repository/branches/([^/?]+)$' {
                    $project = $lab.Projects[$Matches[1]]
                    $name = [Uri]::UnescapeDataString($Matches[2])
                    $answer = if ($null -eq $project -or -not $project.branches.Contains($name)) { @{ Status = 404 } } else {
                        @{ Status = 200; Content = ([ordered]@{ name = $name; commit = [ordered]@{ id = $project.branches[$name] } } | ConvertTo-Json -Compress) } }
                    break
                }
                '^https://gitlab\.com/api/v4/projects/(\d+)/repository/commits\?per_page=1$' {
                    $project = $lab.Projects[$Matches[1]]
                    if ($null -eq $project) { $answer = @{ Status = 404 }; break }
                    $commits = @($project.branches.Values | Select-Object -First 1 | ForEach-Object { [ordered]@{ id = $_ } })
                    $answer = @{ Status = 200; Content = (ConvertTo-FakeList $commits) }
                    break
                }
                '^https://gitlab\.com/api/v4/projects/(\d+)/repository/tree\?ref=([^&]+)&recursive=true&pagination=keyset&per_page=100(?:&page_token=([^&]+))?$' {
                    # Only a commit has a tree here: a branch name for ref answers 404.
                    $treeKey = "$($Matches[1])@$($Matches[2])"
                    $projectId = $Matches[1]
                    $ref = $Matches[2]
                    $token = $Matches[3]
                    $entries = $lab.Trees[$treeKey]
                    if ($null -eq $entries) { $answer = @{ Status = 404 }; break }
                    $start = 0
                    if ($token -and $lab.RepeatTree -ne $treeKey) {
                        $after = ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String([Uri]::UnescapeDataString($token))) | ConvertFrom-Json).file_name
                        for ($k = 0; $k -lt $entries.Count; $k++) { if ($entries[$k].path -ceq $after) { $start = $k + 1 } }
                    }
                    $slice = @($entries | Select-Object -Skip $start -First 100)
                    $treeHeaders = @{}
                    if ($start + $slice.Count -lt $entries.Count) {
                        $next = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(
                            ([ordered]@{ file_name = $slice[-1].path; tree_oid = ('7e' * 20) } | ConvertTo-Json -Compress)))
                        $treeHeaders['Link'] = "<https://gitlab.com/api/v4/projects/$projectId/repository/tree?id=$projectId&page=1" +
                            "&page_token=$([Uri]::EscapeDataString($next))&pagination=keyset&per_page=100&recursive=true&ref=$ref>; rel=`"next`""
                    }
                    $answer = @{ Status = 200; Content = (ConvertTo-FakeList $slice); Headers = $treeHeaders }
                    break
                }
                '^https://gitlab\.com/api/v4/projects/(\d+)/repository/tree\?ref=([^&]+)&recursive=true&per_page=100&page=(\d+)$' {
                    $entries = $lab.Trees["$($Matches[1])@$($Matches[2])"]
                    $page = [int]$Matches[3]
                    if ($null -eq $entries) { $answer = @{ Status = 404 }; break }
                    $slice = @($entries | Select-Object -Skip (($page - 1) * 100) -First 100)
                    $nextPage = if ($page * 100 -lt $entries.Count) { [string]($page + 1) } else { '' }
                    $answer = @{ Status = 200; Content = (ConvertTo-FakeList $slice); Headers = @{ 'x-total' = [string]$entries.Count; 'x-next-page' = $nextPage } }
                    break
                }
            }
        }
        if ($null -eq $answer) { throw "The coverage code made a request no stand-in answers: $url" }
        $sent = @{}
        if ($answer.Headers) { foreach ($name in @($answer.Headers.Keys)) { $sent[$name] = $answer.Headers[$name] } }
        if ($null -ne $lab.Remaining) {
            $lab.Remaining = [Math]::Max(0, $lab.Remaining - 1)
            $sent['ratelimit-remaining'] = [string]$lab.Remaining
            $sent['ratelimit-reset'] = '1791936900'
        }
        if ($answer.Status -ne 200) {
            $failed = New-Object System.Exception "Response status code does not indicate success: $($answer.Status)."
            Add-Member -InputObject $failed -NotePropertyName Response -NotePropertyValue ([pscustomobject]@{ StatusCode = $answer.Status; Headers = $sent })
            throw $failed
        }
        return [pscustomobject]@{ StatusCode = 200; Content = [string]$answer.Content; Headers = $sent }
    }

    function Invoke-Run {
        param([string]$Path, [hashtable]$Extra = @{})
        $arguments = @{ Path = $Path; PerPage = 2; DelayMilliseconds = 0 }
        foreach ($key in $Extra.Keys) { $arguments[$key] = $Extra[$key] }
        $fakeLab.Requests.Clear()
        return Invoke-GitLabCoverageRun @arguments
    }

    function Get-Open {
        # The unresolved records of one kind, each as 'key result'.
        param($Coverage, [string]$Kind)
        return @($Coverage.Unresolved | Where-Object { $_.Kind -eq $Kind } | ForEach-Object { "$($_.Key) $($_.Result)" })
    }

    function Get-LedgerRecords {
        param([string]$Path)
        return @([IO.File]::ReadAllLines($Path) | Where-Object { $_ } | ForEach-Object { $_ | ConvertFrom-Json })
    }

    function Get-LedgerLines {
        # A ledger's records without the time each was written, sorted, so two ledgers that hold
        # the same records compare equal whatever order two runs wrote them in.
        param([string]$Path)
        return (@([IO.File]::ReadAllLines($Path) | Where-Object { $_ } | ForEach-Object { $_ -replace ',"at":"[^"]*"', '' } | Sort-Object) -join "`n")
    }

    function Save-Lines {
        param([string]$Path, [string[]]$Lines)
        [IO.File]::WriteAllText($Path, (($Lines -join "`n") + "`n"), (New-Object Text.UTF8Encoding $false))
    }

    # The control: one run with nothing in its way. Three pages of two, every project with a record
    # of its own, every branch at a commit, and a tree for every commit, read by that commit.
    $fakeLab = New-FakeLab
    $control = Join-Path $coverageRoot 'control.jsonl'
    $none = Test-GitLabCoverage -Path $control
    Assert-True (-not $none.Complete -and @($none.Problems -like '*no ledger yet*').Count -eq 1) 'A ledger that does not exist was called complete.'
    $outcome = Invoke-Run $control
    $coverage = Test-GitLabCoverage -Path $control
    Assert-True ($null -eq $outcome.Stopped -and $coverage.Complete -and $coverage.FullRun -and $coverage.StartAfter -eq 0 -and $coverage.Ceiling -eq 30) `
        ("A run with nothing in its way did not finish a full range up to the newest project: stopped '$($outcome.Stopped)', " +
            (@($coverage.Problems) + @($coverage.Unresolved | ForEach-Object { "$($_.Kind) $($_.Key) $($_.Result) $($_.Detail)" }) -join ' | '))
    Assert-True ($coverage.Pages -eq 3 -and $coverage.Projects -eq 6 -and $coverage.Branches -eq 7 -and $coverage.Trees -eq 6 -and
        $coverage.Unresolved.Count -eq 0 -and $coverage.Problems.Count -eq 0) `
        "The control ledger does not hold 3 pages, 6 projects, 7 branches and 6 trees: $($coverage | ConvertTo-Json -Depth 3 -Compress)"
    Assert-True ($outcome.Requests -eq 17 -and $fakeLab.Requests.Count -eq 17 -and
        @($fakeLab.Requests | Group-Object Uri | Where-Object { $_.Count -gt 1 }).Count -eq 0) `
        "The control run made $($fakeLab.Requests.Count) requests, not one each for the ceiling, 3 pages, 6 branch lists and 7 tree pages."
    Assert-True (@($fakeLab.Requests | Where-Object { $_.Uri -notlike 'https://gitlab.com/api/v4/*' -or $null -ne $_.Headers -or $_.Method -or
        $_.Body -or $_.OutFile }).Count -eq 0) 'The run sent a header, a body or a file request, or asked somewhere other than gitlab.com.'
    $refs = @($fakeLab.Requests | Where-Object { $_.Uri -like '*/repository/tree?*' } | ForEach-Object { if ($_.Uri -match '[?&]ref=([^&]+)') { $Matches[1] } })
    Assert-True ($refs.Count -eq 7 -and @($refs | Where-Object { $_ -notmatch '^[0-9a-f]{40}$' }).Count -eq 0) `
        "A tree was asked for by something other than a commit id: $($refs -join ', ')"
    $records = @(Get-LedgerRecords $control)
    $header = $records[0]
    Assert-True ($header.kind -eq 'run' -and $header.ceiling -eq 30 -and $header.startAfter -eq 0 -and $header.ceilingSource -eq 'highest-public-id' -and
        @($records | Where-Object { $_.kind -eq 'run' }).Count -eq 1) 'The ledger does not open with the one record that pins its range.'
    $empty = @($records | Where-Object { $_.kind -eq 'project' -and $_.id -eq 12 })
    Assert-True ($empty.Count -eq 1 -and $empty[0].result -eq 'empty' -and @($empty[0].branches).Count -eq 0) `
        'The empty project has no record of its own saying it is empty.'
    $alpha = @($records | Where-Object { $_.kind -eq 'project' -and $_.id -eq 11 })[0]
    Assert-True ((@($alpha.branches | ForEach-Object { "$($_.name)=$($_.commit)" }) -join ' ') -eq "main=$c11a dev=$c11b copy=$c11a") `
        'A project''s record does not hold every branch it declares at its commit.'
    Assert-True (@($records | Where-Object { $_.kind -eq 'tree' -and $_.project -eq 11 }).Count -eq 2) `
        'Two branches at one commit did not share one tree record.'
    $big = @($records | Where-Object { $_.kind -eq 'tree' -and $_.commit -eq $c11b })
    Assert-True ($big.Count -eq 1 -and $big[0].result -eq 'pinned' -and $big[0].pages -eq 2 -and $big[0].entries -eq 150 -and
        @($big[0].files).Count -eq 150 -and @($big[0].files | Where-Object { $_.id -notmatch '^[0-9a-f]{40}$' -or -not $_.path }).Count -eq 0) `
        'The two-page tree was not recorded whole, every path with its blob id.'
    $bare = @($records | Where-Object { $_.kind -eq 'tree' -and $_.commit -eq $c21 })
    Assert-True ($bare.Count -eq 1 -and $bare[0].result -eq 'pinned' -and $bare[0].entries -eq 0) 'A commit with no files has no pinned tree record.'

    # Stopped anywhere and carried on. Three requests a run stops the first one with a page half
    # done: its first project read, its second not yet. Every run after picks up from the ledger.
    $fakeLab = New-FakeLab
    $resumed = Join-Path $coverageRoot 'resumed.jsonl'
    $asked = New-Object System.Collections.Generic.List[string]
    $runs = 0
    do {
        $runs++
        $outcome = Invoke-Run $resumed @{ MaxRequests = 3 }
        foreach ($request in $fakeLab.Requests) { $asked.Add($request.Uri) }
        $coverage = Test-GitLabCoverage -Path $resumed
        Assert-True ($coverage.Problems.Count -eq 0) "A stopped run left a ledger its own rules refuse: $($coverage.Problems -join ' | ')"
        if ($runs -eq 1) {
            Assert-True ($outcome.Stopped -eq 'request-budget' -and -not $coverage.Complete -and
                (Get-Open $coverage 'project') -contains '12 not-read' -and (Get-Open $coverage 'page') -contains 'after 12 not-read' -and
                @(Get-Open $coverage 'tree').Count -eq 2) `
                ("A run stopped mid-page was called complete, or did not list what it had not read: " +
                    (@($coverage.Unresolved | ForEach-Object { "$($_.Kind) $($_.Key) $($_.Result)" }) -join ' | '))
        }
    } while ($outcome.Stopped -and $runs -lt 30)
    Assert-True ($null -eq $outcome.Stopped -and $runs -gt 3 -and (Test-GitLabCoverage -Path $resumed).Complete) `
        "Carrying on three requests at a time never finished ($runs runs, stopped '$($outcome.Stopped)')."
    Assert-True ((Get-LedgerLines $resumed) -ceq (Get-LedgerLines $control)) 'A ledger carried on across stops lost or duplicated a record.'
    $twice = @($asked | Group-Object | Where-Object { $_.Count -gt 1 } | ForEach-Object { $_.Name })
    Assert-True (@($twice | Where-Object { $_ -notlike '*/repository/tree?*' }).Count -eq 0 -and @($asked | Where-Object { $_ -like '*sort=desc*' }).Count -eq 1) `
        "Carrying on asked again for something the ledger had settled, or read the ceiling twice: $($twice -join ', ')"

    # A run killed while it was writing leaves a last record cut short. That is never complete, and
    # the next run drops the stump and reads that part again without touching what came before.
    $controlBytes = [IO.File]::ReadAllBytes($control)
    $newlines = @(0..($controlBytes.Length - 1) | Where-Object { $controlBytes[$_] -eq 10 })
    $torn = Join-Path $coverageRoot 'torn.jsonl'
    foreach ($cut in @(($controlBytes.Length - 9), ($newlines[4] + 26))) {
        [IO.File]::WriteAllBytes($torn, [byte[]]@($controlBytes[0..($cut - 1)]))
        $coverage = Test-GitLabCoverage -Path $torn
        Assert-True (-not $coverage.Complete -and @($coverage.Problems -like '*never finished*').Count -eq 1) 'A ledger ending in half a record was called complete.'
        $outcome = Invoke-Run $torn
        Assert-True ($null -eq $outcome.Stopped -and (Test-GitLabCoverage -Path $torn).Complete -and (Get-LedgerLines $torn) -ceq (Get-LedgerLines $control)) `
            'A ledger ending in half a record was not carried on to the same records as the control.'
        Assert-True (@($fakeLab.Requests | Where-Object { $_.Uri -like '*sort=desc*' -or $_.Uri -like '*id_after=0' -or
            $_.Uri -like '*/projects/11/repository/branches*' -or $_.Uri -like "*ref=$c11a*" }).Count -eq 0) `
            'Carrying on from half a record asked again for records that were whole.'
    }

    # A repeated page, and a page that starts at the cursor instead of after it, are refused: the
    # list stops there, nothing on the page is taken, and the ledger is not complete until a later
    # run gets a page that follows on.
    foreach ($servedAfter in 0, 11) {
        $fakeLab = New-FakeLab
        $repeated = Join-Path $coverageRoot "repeated-$servedAfter.jsonl"
        $fakeLab.Pages['12'] = Get-FakeProjectPage $fakeLab $servedAfter 2
        $outcome = Invoke-Run $repeated
        $coverage = Test-GitLabCoverage -Path $repeated
        Assert-True ($null -eq $outcome.Stopped -and -not $coverage.Complete -and $coverage.Projects -eq 2 -and
            (Get-Open $coverage 'page') -contains 'after 12 repeated-page' -and $coverage.Unresolved.Count -eq 1) `
            "A page served again after project 12 (the page after $servedAfter) was accepted, or the ledger was called complete."
        $outcome = Invoke-Run $repeated
        Assert-True ($outcome.Requests -eq 1 -and -not (Test-GitLabCoverage -Path $repeated).Complete) 'A refused page was asked for more than once in a run, or stopped being listed.'
        $fakeLab.Pages.Remove('12')
        $outcome = Invoke-Run $repeated
        $pageRecords = @(Get-LedgerRecords $repeated | Where-Object { $_.kind -eq 'page' -and $_.after -eq 12 } | ForEach-Object { $_.result })
        Assert-True ((Test-GitLabCoverage -Path $repeated).Complete -and ($pageRecords -join ',') -eq 'repeated-page,repeated-page,read') `
            "A refused page was not kept in the ledger and read again once GitLab served the right one: $($pageRecords -join ',')"
    }
    $fakeLab = New-FakeLab
    $mislinked = Join-Path $coverageRoot 'mislinked.jsonl'
    $fakeLab.Pages['0'] = Get-FakeProjectPage $fakeLab 0 2
    $fakeLab.Pages['0'].Headers['Link'] = $fakeLab.Pages['0'].Headers['Link'].Replace('id_after=12', 'id_after=11')
    $outcome = Invoke-Run $mislinked
    $coverage = Test-GitLabCoverage -Path $mislinked
    Assert-True (-not $coverage.Complete -and $coverage.Projects -eq 0 -and (Get-Open $coverage 'page') -contains 'after 0 link-mismatch') `
        'A page whose next link does not carry on from its last project was accepted.'

    # The same rules hold for a ledger read cold: a page twice, a page missing, a record for
    # something already settled, a record nothing listed and a line that isn't a record each make
    # the check say no by name, and a run won't add to a ledger like that.
    $lines = @([IO.File]::ReadAllLines($control))
    $pageAt = @(0..($lines.Count - 1) | Where-Object { $lines[$_] -like '{"kind":"page"*' })
    $projectLine = @($lines | Where-Object { $_ -like '{"kind":"project","id":15,*' })[0]
    $doctored = Join-Path $coverageRoot 'doctored.jsonl'
    Save-Lines $doctored $lines
    Assert-True (Test-GitLabCoverage -Path $doctored).Complete 'The unchanged copy was refused, so the cases below would prove nothing.'
    foreach ($case in @(
            @{ Lines = @($lines[0..$pageAt[0]]) + @($lines[$pageAt[0]]) + @($lines[($pageAt[0] + 1)..($lines.Count - 1)])
                Pattern = '*asked after 0 is out of sequence*missing or repeated*'; What = 'A page recorded twice' }
            @{ Lines = @($lines | Where-Object { $_ -cne $lines[$pageAt[1]] }); Pattern = '*asked after 20 is out of sequence*'; What = 'A missing page' }
            @{ Lines = @($lines) + @($projectLine); Pattern = '*project 15 has a record after the one that settled it*'; What = 'A project recorded twice' }
            @{ Lines = @($lines) + @($lines[$lines.Count - 1]); Pattern = "*tree 30@$c30 has a record after the one that settled it*"; What = 'A tree recorded twice' }
            @{ Lines = @($lines) + @($projectLine.Replace('"id":15,', '"id":16,')); Pattern = "*names '16', which no page before it lists*"; What = 'A record for a project no page lists' }
            @{ Lines = @($lines[0..3]) + @('not a record') + @($lines[4..($lines.Count - 1)]); Pattern = '*line 5 of the ledger is not a record*'; What = 'A line that is not a record' }
            @{ Lines = @($lines) + @($lines[0]); Pattern = '*a run record is not the first record*'; What = 'A second run record' })) {
        Save-Lines $doctored $case.Lines
        $coverage = Test-GitLabCoverage -Path $doctored
        Assert-True (-not $coverage.Complete -and @($coverage.Problems -like $case.Pattern).Count -gt 0) `
            "$($case.What) did not keep the ledger from being complete, or not by name: $($coverage.Problems -join ' | ')"
        $doctoredText = [IO.File]::ReadAllText($doctored)
        Assert-Throws { Invoke-Run $doctored } '*can''t be added to until this is settled*' "$($case.What) did not stop a run from adding to the ledger."
        Assert-True ([IO.File]::ReadAllText($doctored) -ceq $doctoredText -and $fakeLab.Requests.Count -eq 0) "$($case.What): the refused run still wrote or asked."
    }

    # A tree that fails, a tree too large to finish, a tree whose pages repeat and a repository
    # that can't be read each stay in the ledger as unresolved, with the status where there is
    # one. The rest of the run carries on, and the ledger is not complete until a later run
    # settles them.
    $fakeLab = New-FakeLab
    $failed = Join-Path $coverageRoot 'failed.jsonl'
    $fakeLab.Failures.Add(@{ Pattern = '/projects/15/repository/tree\?'; Status = 500; Headers = @{}; Times = 1 })
    $fakeLab.Failures.Add(@{ Pattern = '/projects/20/repository/branches\?'; Status = 404; Headers = @{}; Times = 1 })
    $outcome = Invoke-Run $failed
    $coverage = Test-GitLabCoverage -Path $failed
    $failedTree = @($coverage.Unresolved | Where-Object { $_.Kind -eq 'tree' })
    $closedProject = @($coverage.Unresolved | Where-Object { $_.Kind -eq 'project' })
    Assert-True ($null -eq $outcome.Stopped -and -not $coverage.Complete -and $coverage.Unresolved.Count -eq 2 -and $coverage.Problems.Count -eq 0 -and
        $failedTree.Count -eq 1 -and $failedTree[0].Key -eq "15@$c15" -and $failedTree[0].Result -eq 'http-failure' -and $failedTree[0].Status -eq 500 -and
        $closedProject.Count -eq 1 -and $closedProject[0].Key -eq '20' -and $closedProject[0].Result -eq 'inaccessible' -and $closedProject[0].Status -eq 404) `
        ("A failed tree and an unreadable repository did not each keep the ledger incomplete with their status: " +
            (@($coverage.Unresolved | ForEach-Object { "$($_.Kind) $($_.Key) $($_.Result) $($_.Status)" }) -join ' | '))
    Assert-True ($coverage.Trees -eq 4 -and $coverage.Pages -eq 3) 'One failed tree stopped the rest of the run from being read.'
    $outcome = Invoke-Run $failed
    $treeRecords = @(Get-LedgerRecords $failed | Where-Object { $_.kind -eq 'tree' -and $_.project -eq 15 } | ForEach-Object { $_.result })
    Assert-True ($outcome.Requests -eq 3 -and (Test-GitLabCoverage -Path $failed).Complete -and ($treeRecords -join ',') -eq 'http-failure,pinned') `
        "The next run did not settle only what was unresolved ($($outcome.Requests) requests; tree records $($treeRecords -join ','))."

    $fakeLab = New-FakeLab
    $large = Join-Path $coverageRoot 'large.jsonl'
    $outcome = Invoke-Run $large @{ MaxTreePages = 1 }
    $coverage = Test-GitLabCoverage -Path $large
    Assert-True (-not $coverage.Complete -and (Get-Open $coverage 'tree') -contains "11@$c11b too-large" -and $coverage.Unresolved.Count -eq 1) `
        'A tree that ran past the page limit was recorded as read, or not listed.'
    $outcome = Invoke-Run $large
    Assert-True ($outcome.Requests -eq 2 -and (Test-GitLabCoverage -Path $large).Complete) 'A tree that was too large was not read again once the limit allowed it.'

    $fakeLab = New-FakeLab
    $looping = Join-Path $coverageRoot 'looping.jsonl'
    $fakeLab.RepeatTree = "11@$c11b"
    $outcome = Invoke-Run $looping
    $coverage = Test-GitLabCoverage -Path $looping
    $loopingTree = @($coverage.Unresolved | Where-Object { $_.Kind -eq 'tree' })
    Assert-True (-not $coverage.Complete -and $loopingTree.Count -eq 1 -and $loopingTree[0].Result -eq 'truncated' -and $loopingTree[0].Detail -like '*served twice*') `
        'A tree whose pages repeat was recorded as read.'
    $fakeLab.RepeatTree = $null
    $outcome = Invoke-Run $looping
    Assert-True (Test-GitLabCoverage -Path $looping).Complete 'A truncated tree was not read again.'

    # A 429 is recorded against what was being read, with the wait GitLab asked for, and ends the
    # run on the spot. So does a rate-limit window with nothing left in it, before the request
    # that would overrun it. Either way the next run carries on.
    # The wait is kept for the whole directory, so the 429 cases get directories of their own.
    $fakeLab = New-FakeLab
    $limited = Join-Path (Join-Path $coverageRoot 'limited') 'limited.jsonl'
    $fakeLab.Failures.Add(@{ Pattern = '/projects/12/repository/branches\?'; Status = 429; Headers = @{ 'Retry-After' = '30' }; Times = 1 })
    $outcome = Invoke-Run $limited
    $coverage = Test-GitLabCoverage -Path $limited
    $held = @(Get-LedgerRecords $limited | Where-Object { $_.kind -eq 'project' -and $_.id -eq 12 })
    Assert-True ($outcome.Stopped -eq 'rate-limited' -and $outcome.RetryAfter -eq 30 -and $outcome.Requests -eq 4 -and
        $fakeLab.Requests[$fakeLab.Requests.Count - 1].Uri -like '*/projects/12/repository/branches?*') `
        "A 429 did not end the run with the request that got it (stopped '$($outcome.Stopped)' after $($outcome.Requests) requests)."
    Assert-True ($held.Count -eq 1 -and $held[0].result -eq 'rate-limited' -and $held[0].status -eq 429 -and $held[0].retryAfter -eq 30 -and
        -not $coverage.Complete -and (Get-Open $coverage 'project') -contains '12 rate-limited') 'A 429 was not recorded against the project it stopped at.'
    # The wait holds for the next run as well: started inside it, a run asks for nothing.
    $limitedText = [IO.File]::ReadAllText($limited)
    $outcome = Invoke-Run $limited
    Assert-True ($outcome.Stopped -eq 'rate-limited' -and $outcome.Requests -eq 0 -and $fakeLab.Requests.Count -eq 0 -and
        $outcome.RetryAfter -gt 0 -and $outcome.RetryAfter -le 30 -and [IO.File]::ReadAllText($limited) -ceq $limitedText) `
        "A run started inside the wait a 429 asked for made a request or wrote a record ($($outcome.Requests) requests, $($outcome.RetryAfter) s left)."
    # GitLab limits the address, not the ledger: another ledger beside it waits too.
    $beside = Join-Path (Join-Path $coverageRoot 'limited') 'beside.jsonl'
    $outcome = Invoke-Run $beside
    Assert-True ($outcome.Stopped -eq 'rate-limited' -and $outcome.Requests -eq 0 -and $fakeLab.Requests.Count -eq 0 -and
        -not (Test-Path -LiteralPath $beside)) "A ledger beside one that got a 429 made $($fakeLab.Requests.Count) request(s) inside its wait."
    $outcome = Invoke-Run $limited @{ Now = [datetime]::UtcNow.AddSeconds(31) }
    $held = @(Get-LedgerRecords $limited | Where-Object { $_.kind -eq 'project' -and $_.id -eq 12 } | ForEach-Object { $_.result })
    Assert-True ($null -eq $outcome.Stopped -and (Test-GitLabCoverage -Path $limited).Complete -and ($held -join ',') -eq 'rate-limited,empty' -and
        @($fakeLab.Requests | Where-Object { $_.Uri -like '*sort=desc*' -or $_.Uri -like '*id_after=0' -or
            $_.Uri -like '*/projects/11/repository/branches*' }).Count -eq 0) `
        'The run after a 429 did not carry on from the ledger to a complete one.'

    $fakeLab = New-FakeLab
    $window = Join-Path $coverageRoot 'window.jsonl'
    $fakeLab.Remaining = 4
    $outcome = Invoke-Run $window
    Assert-True ($outcome.Stopped -eq 'rate-limit-window' -and $outcome.Requests -eq 4 -and $fakeLab.Requests.Count -eq 4 -and $outcome.WindowReset -eq '1791936900') `
        "A used-up rate-limit window did not stop the run before its next request (stopped '$($outcome.Stopped)' after $($fakeLab.Requests.Count) requests)."
    Assert-True (-not (Test-GitLabCoverage -Path $window).Complete -and
        @(Get-LedgerRecords $window | Where-Object { $_.kind -ne 'run' -and $_.result -notin @('read', 'pinned', 'empty') }).Count -eq 0) `
        'Stopping for the rate-limit window recorded a failure, or the ledger was called complete.'
    $fakeLab.Remaining = $null
    $outcome = Invoke-Run $window
    Assert-True ((Test-GitLabCoverage -Path $window).Complete -and (Get-LedgerLines $window) -ceq (Get-LedgerLines $control)) 'The run after a used-up window did not finish the ledger.'

    $fakeLab = New-FakeLab
    $unstarted = Join-Path $coverageRoot 'unstarted.jsonl'
    $fakeLab.Failures.Add(@{ Pattern = 'sort=desc'; Status = 429; Headers = @{}; Times = 1 })
    $outcome = Invoke-Run $unstarted
    Assert-True ($outcome.Stopped -eq 'rate-limited' -and -not (Test-Path -LiteralPath $unstarted)) 'A ledger was started without a ceiling to pin.'
    # A 429 on the ceiling read has no ledger to hold its wait, and the next run keeps to it anyway.
    $fakeLab = New-FakeLab
    $unread = Join-Path (Join-Path $coverageRoot 'unread') 'unread.jsonl'
    $fakeLab.Failures.Add(@{ Pattern = 'sort=desc'; Status = 429; Headers = @{ 'Retry-After' = '45' }; Times = 1 })
    $outcome = Invoke-Run $unread
    Assert-True ($outcome.Stopped -eq 'rate-limited' -and $outcome.RetryAfter -eq 45 -and $outcome.Requests -eq 1 -and -not (Test-Path -LiteralPath $unread) -and
        (Test-Path -LiteralPath (Get-GitLabCoverageWaitPath -Path $unread))) 'A 429 on the ceiling read kept no wait.'
    $outcome = Invoke-Run $unread
    Assert-True ($outcome.Stopped -eq 'rate-limited' -and $outcome.Requests -eq 0 -and $fakeLab.Requests.Count -eq 0 -and
        $outcome.RetryAfter -gt 0 -and $outcome.RetryAfter -le 45 -and -not (Test-Path -LiteralPath $unread)) `
        "A run started inside the wait a 429 on the ceiling read asked for made $($fakeLab.Requests.Count) request(s)."
    $outcome = Invoke-Run $unread @{ Now = [datetime]::UtcNow.AddSeconds(46) }
    Assert-True ($null -eq $outcome.Stopped -and (Test-GitLabCoverage -Path $unread).Complete) 'The run after the ceiling read''s wait did not start the ledger.'

    # The ceiling is pinned by the first run. Projects created after it are past the ceiling: they
    # are counted on the page that reaches it and never read, and no later run can move it.
    $fakeLab = New-FakeLab
    $pinned = Join-Path $coverageRoot 'pinned.jsonl'
    $outcome = Invoke-Run $pinned @{ MaxRequests = 2 }
    Add-FakeProject $fakeLab 31 'late/one' ([ordered]@{ main = ('31' * 20) }) @{ ('31' * 20) = 1 }
    Add-FakeProject $fakeLab 44 'late/two' ([ordered]@{ main = ('44' * 20) }) @{ ('44' * 20) = 1 }
    Assert-Throws { Invoke-Run $pinned @{ Ceiling = 44 } } '*pinned to the projects after 0 up to 30*' 'A later run moved the ceiling.'
    $outcome = Invoke-Run $pinned @{ PerPage = 3 }
    $coverage = Test-GitLabCoverage -Path $pinned
    $lastPage = @(Get-LedgerRecords $pinned | Where-Object { $_.kind -eq 'page' })[-1]
    Assert-True ($coverage.Complete -and $coverage.Ceiling -eq 30 -and $coverage.Projects -eq 6 -and
        @($fakeLab.Requests | Where-Object { $_.Uri -like '*sort=desc*' -or $_.Uri -match '/projects/(31|44)/' }).Count -eq 0) `
        'Projects created after the ceiling was pinned moved it or were read.'
    Assert-True ($lastPage.end -eq 'ceiling' -and $lastPage.beyond -eq 2 -and $lastPage.last -eq 44 -and
        (@($lastPage.projects | ForEach-Object { $_.id }) -join ',') -eq '30') `
        "The page that reached the ceiling did not keep project 30 and leave out the two after it: $($lastPage | ConvertTo-Json -Depth 3 -Compress)"

    # A list that runs out below the ceiling ended early, whatever the reason, and stays open until
    # a page reaches the ceiling.
    $fakeLab = New-FakeLab
    $early = Join-Path $coverageRoot 'early.jsonl'
    $outcome = Invoke-Run $early @{ MaxRequests = 2 }
    $fakeLab.Projects.Remove('30')
    $outcome = Invoke-Run $early
    $coverage = Test-GitLabCoverage -Path $early
    Assert-True (-not $coverage.Complete -and (Get-Open $coverage 'page') -contains 'after 20 ended-early' -and $coverage.Projects -eq 4) `
        'A project list that ran out below the ceiling was taken as finished.'
    Add-FakeProject $fakeLab 44 'late/two' ([ordered]@{ main = ('44' * 20) }) @{ ('44' * 20) = 1 }
    $outcome = Invoke-Run $early
    $coverage = Test-GitLabCoverage -Path $early
    Assert-True ($coverage.Complete -and $coverage.Projects -eq 5) 'A list that ended early was not read again once it reached past the ceiling.'

    # The second way. A project's records match GitLab asked directly, field by field, and each
    # kind of difference between the two is named.
    $fakeLab = New-FakeLab
    foreach ($case in @(@{ Id = 11; Shape = 'multi-branch'; Requests = 8 }, @{ Id = 11; Shape = 'large-tree'; Requests = 8 }, @{ Id = 12; Shape = 'empty'; Requests = 2 })) {
        $fakeLab.Requests.Clear()
        $check = Compare-GitLabCoverageSentinel -Path $control -ProjectId $case.Id -Shape $case.Shape -DelayMilliseconds 0
        Assert-True ($check.Matches -and $check.Compared -gt 0 -and $check.Requests -eq $case.Requests -and $fakeLab.Requests.Count -eq $case.Requests) `
            "Project $($case.Id) as a $($case.Shape) sentinel did not match the stand-in it was read from: $($check.Differences -join ' | ')"
        Assert-True (@($fakeLab.Requests | Where-Object { $_.Uri -notlike 'https://gitlab.com/api/v4/*' -or $null -ne $_.Headers -or $_.Method }).Count -eq 0 -and
            @($fakeLab.Requests | Where-Object { $_.Uri -like '*pagination=keyset*' }).Count -eq 0) `
            'The second way went through the keyset pages the ledger was read from, or sent a header.'
    }
    $controlText = [IO.File]::ReadAllText($control)
    function Assert-Difference {
        param([long]$Id, [string]$Shape, [string]$Pattern, [string]$Case)
        $check = Compare-GitLabCoverageSentinel -Path $control -ProjectId $Id -Shape $Shape -DelayMilliseconds 0
        Assert-True (-not $check.Matches -and @($check.Differences -like $Pattern).Count -gt 0) "$Case was not reported ($Pattern): $($check.Differences -join ' | ')"
    }
    $fakeLab.Trees["11@$c11b"][76].id = 'f' * 40
    Assert-Difference 11 'multi-branch' "*f0077.kt' id: the ledger has*GitLab has '$('f' * 40)'*" 'A blob id that differs'
    $fakeLab = New-FakeLab
    $fakeLab.Projects['11'].branches['dev'] = 'd' * 40
    Assert-Difference 11 'multi-branch' "*branch 'dev': the ledger pins $c11b, GitLab has $('d' * 40)*" 'A branch at another commit'
    $fakeLab = New-FakeLab
    $fakeLab.Projects['11'].branches['extra'] = $c11a
    Assert-Difference 11 'multi-branch' "*branches: the ledger has 3, GitLab counts '4'*" 'A branch the ledger does not hold'
    $fakeLab = New-FakeLab
    $fakeLab.Trees["11@$c11a"] = @($fakeLab.Trees["11@$c11a"] | Select-Object -First 2)
    Assert-Difference 11 'multi-branch' '*f0003.kt'' is in the ledger and not in the tree read directly*' 'A path GitLab no longer lists'
    Assert-Difference 11 'multi-branch' '*the ledger has 3 entries, GitLab counts 2*' 'A tree total that differs'
    $fakeLab = New-FakeLab
    $fakeLab.Projects['11'].path = 'one/renamed'
    Assert-Difference 11 'multi-branch' "*path: the ledger has 'one/alpha', GitLab has 'one/renamed'*" 'A project path that differs'
    $fakeLab = New-FakeLab
    $fakeLab.Projects['12'].branches['main'] = $c15
    Assert-Difference 12 'empty' '*the ledger calls it empty, and its commit list is not*' 'An empty project that has commits'
    $fakeLab = New-FakeLab
    Assert-Difference 12 'multi-branch' '*picked as a multi-branch project and no longer is one*' 'A sentinel that lost its shape'
    Assert-Difference 15 'large-tree' '*picked as a large-tree project and no longer is one*' 'A large-tree sentinel whose tree fits one page'
    Assert-Difference 99 'empty' '*holds no record of project 99*' 'A sentinel the ledger never listed'
    Assert-True ([IO.File]::ReadAllText($control) -ceq $controlText) 'The second way wrote to the ledger it was checking.'

    # The script, start to finish, in a root whose census records the GitLab skip: the ledger goes
    # under build/, a run that hits its budget exits 1 and says how to carry on, a finished full
    # range exits 0 and still says the census skips GitLab, and the census file is never touched.
    $scanRoot = Join-Path $caseRoot 'scan-root'
    New-Item -ItemType Directory -Path (Join-Path $scanRoot 'sources') -Force | Out-Null
    $scanCensus = Get-SourceLedgerPath -Root $scanRoot
    [IO.File]::WriteAllText($scanCensus, $censusBefore, (New-Object Text.UTF8Encoding $false))
    function Invoke-Scan {
        param([hashtable]$Extra = @{})
        $arguments = @{ Root = $scanRoot; DelayMilliseconds = 0 }
        foreach ($key in $Extra.Keys) { $arguments[$key] = $Extra[$key] }
        $fakeLab.Requests.Clear()
        $global:LASTEXITCODE = 0
        $said = @(& $scanScript @arguments 6>&1 | ForEach-Object { "$_" }) -join "`n"
        return [pscustomobject]@{ ExitCode = $LASTEXITCODE; Said = $said }
    }
    $fakeLab = New-FakeLab
    $scan = Invoke-Scan @{ PerPage = 2; MaxRequests = 5 }
    Assert-True ($scan.ExitCode -eq 1 -and $scan.Said -like '*stopped after 5 request(s)*run again*' -and $scan.Said -like '*unresolved page after 12: not-read*' -and
        $scan.Said -like '*not complete*') "A scan that hit its request budget did not exit 1 and say what is open: $($scan.Said)"
    $turns = 1
    do {
        $turns++
        $scan = Invoke-Scan @{ PerPage = 2; MaxRequests = 5 }
    } while ($scan.ExitCode -ne 0 -and $turns -lt 20)
    Assert-True ($scan.ExitCode -eq 0 -and $turns -gt 2 -and $scan.Said -like '*complete for a full run*still records gitlab-code-search as skipped*') `
        "Running the scan again did not finish it, or a finished scan claimed the census: $($scan.Said)"
    $scan = Invoke-Scan @{ CheckOnly = $true }
    Assert-True ($scan.ExitCode -eq 0 -and $fakeLab.Requests.Count -eq 0) "-CheckOnly made $($fakeLab.Requests.Count) requests or refused a complete ledger."
    Assert-True ((Get-LedgerLines (Join-Path $scanRoot 'build/gitlab-coverage/ledger.jsonl')) -ceq (Get-LedgerLines $control)) `
        'The scan did not leave the same ledger under build/gitlab-coverage as the control run.'

    # -Sentinels reads the fixed sentinel projects, each as a range of one id, and checks each the
    # second way. A complete sentinel ledger is still not the census.
    foreach ($sentinel in Get-GitLabCoverageSentinels) {
        $one = ('{0:x8}' -f $sentinel.Id) * 5
        $two = ('{0:x8}' -f ($sentinel.Id + 1)) * 5
        switch ($sentinel.Shape) {
            'empty' { Add-FakeProject $fakeLab $sentinel.Id 'sentinel/empty' ([ordered]@{}) @{} }
            'multi-branch' { Add-FakeProject $fakeLab $sentinel.Id 'sentinel/branches' ([ordered]@{ main = $one; next = $two }) @{ $one = 5; $two = 7 } }
            'large-tree' { Add-FakeProject $fakeLab $sentinel.Id 'sentinel/large' ([ordered]@{ main = $one }) @{ $one = 230 } }
        }
    }
    Assert-True (@(Get-GitLabCoverageSentinels).Count -ge 3 -and
        (@(Get-GitLabCoverageSentinels | ForEach-Object { $_.Shape } | Sort-Object -Unique) -join ',') -eq 'empty,large-tree,multi-branch') `
        'The sentinel set no longer has an empty project, one with several branches and one with a large tree.'
    $scan = Invoke-Scan @{ Sentinels = $true }
    Assert-True ($scan.ExitCode -eq 0 -and [regex]::Matches($scan.Said, 'matches GitLab asked directly').Count -eq @(Get-GitLabCoverageSentinels).Count -and
        $scan.Said -like '*not all of GitLab*' -and $scan.Said -notlike '*complete for a full run*') "The sentinel check did not pass on a stand-in that matches: $($scan.Said)"
    Assert-True (@($fakeLab.Requests | Where-Object { $_.Uri -match '/projects/(11|12|15|20|21|30)(/|$)' }).Count -eq 0) `
        'A sentinel range of one project read projects outside it.'
    $emptyId = @(Get-GitLabCoverageSentinels | Where-Object { $_.Shape -eq 'empty' })[0].Id
    $fakeLab.Projects["$emptyId"].branches['main'] = $c15
    $scan = Invoke-Scan @{ Sentinels = $true }
    Assert-True ($scan.ExitCode -eq 1 -and $scan.Said -like '*difference: project*calls it empty, and its commit list is not*' -and $scan.Said -like '*differs in*') `
        "A sentinel that no longer matches GitLab did not fail the check: $($scan.Said)"
    # Each sentinel has a ledger of its own, so a 429 on one has to end the check for all of them,
    # whether it came while reading or while checking the second way, and the wait holds after.
    $firstId = @(Get-GitLabCoverageSentinels)[0].Id
    $fakeLab.Failures.Add(@{ Pattern = "/projects/$firstId`$"; Status = 429; Headers = @{ 'Retry-After' = '60' }; Times = 1 })
    $scan = Invoke-Scan @{ Sentinels = $true }
    Assert-True ($scan.ExitCode -eq 1 -and $fakeLab.Requests.Count -eq 1 -and $scan.Said -like '*the sentinel check stops here*') `
        "A 429 while checking a sentinel did not end the check after that request ($($fakeLab.Requests.Count) requests): $($scan.Said)"
    # That 429 wrote no ledger record, and its wait still holds for the next scan and for a check asked for directly.
    $scan = Invoke-Scan @{ Sentinels = $true }
    Assert-True ($scan.ExitCode -eq 1 -and $fakeLab.Requests.Count -eq 0 -and $scan.Said -like '*of the wait GitLab asked for are left; nothing was requested*') `
        "A scan started inside the wait a 429 asked for while checking a sentinel made $($fakeLab.Requests.Count) request(s): $($scan.Said)"
    $fakeLab.Requests.Clear()
    $check = Compare-GitLabCoverageSentinel -Path (Join-Path (Join-Path $scanRoot 'build/gitlab-coverage') "sentinel-$firstId.jsonl") -ProjectId $firstId -DelayMilliseconds 0
    Assert-True (-not $check.Matches -and $check.Requests -eq 0 -and $fakeLab.Requests.Count -eq 0 -and $check.Stopped -eq 'rate-limited' -and $check.RetryAfter -gt 0) `
        "A second-way check started inside a kept wait made $($fakeLab.Requests.Count) request(s)."
    $again =Join-Path $scanRoot 'build/gitlab-coverage/again'
    $fakeLab.Failures.Add(@{ Pattern = 'id_after='; Status = 429; Headers = @{ 'Retry-After' = '60' }; Times = 1 })
    $scan = Invoke-Scan @{ Sentinels = $true; LedgerDirectory = $again }
    Assert-True ($scan.ExitCode -eq 1 -and $fakeLab.Requests.Count -eq 1 -and $scan.Said -like '*rate limited (HTTP 429) after 1 request(s); GitLab asked for 60 s*' -and
        $scan.Said -like '*the sentinel check stops here*') `
        "A 429 while reading a sentinel did not end the check after that request ($($fakeLab.Requests.Count) requests): $($scan.Said)"
    $scan = Invoke-Scan @{ Sentinels = $true; LedgerDirectory = $again }
    Assert-True ($scan.ExitCode -eq 1 -and $fakeLab.Requests.Count -eq 0 -and $scan.Said -like '*of the wait GitLab asked for are left; nothing was requested*') `
        "A sentinel check started inside the wait a 429 asked for made $($fakeLab.Requests.Count) request(s): $($scan.Said)"
    Assert-Throws { Invoke-Scan @{ LedgerDirectory = (Join-Path $scanRoot 'sources') } } '*can''t go under sources/*' 'The scan put its ledger beside the census.'

    $census = [IO.File]::ReadAllText($scanCensus) | ConvertFrom-Json
    Assert-True ([IO.File]::ReadAllText($scanCensus) -ceq $censusBefore -and (@($census.census.skipped) -join ',') -eq 'gitlab-code-search' -and
        $census.census.checkedAt -eq $ledger.census.checkedAt) 'The scan changed the census, its date or its gitlab-code-search skip.'
    $written = @(Get-ChildItem -LiteralPath $scanRoot -Recurse -File | ForEach-Object { $_.FullName.Substring($scanRoot.Length + 1).Replace('\', '/') } |
        Where-Object { $_ -notlike 'build/gitlab-coverage/*.jsonl' })
    Assert-True (($written -join ',') -eq 'sources/facebook-sources.json') "The scan wrote outside build/gitlab-coverage: $($written -join ', ')"
}
Assert-True ([IO.File]::ReadAllText($ledgerPath) -ceq $censusBefore) 'The coverage ledger cases changed the checked-in census.'

Write-Host '[sources] the GitLab coverage ledger carries on after any stop, refuses repeated pages and failed trees, and leaves the census alone'

# --- the audit never imports code ----------------------------------------------------------------

# What the two files can do, read through the parser: no archive or download tool, no -OutFile,
# nothing that copies or writes files except the audit's two WriteAllText calls, one for the
# report and one for the ledger's dates. git runs only inside the audit's Invoke-SourceGit, which
# may only name commands that read commits and folder listings, holds clone and fetch to
# --filter=blob:none and turns lazy fetching off. Each forbidden shape is added to a copy, and the
# copy has to be caught, so the check can't pass by seeing nothing. The coverage ledger's two
# files are held to the same, with the one append that writes its ledger as their only way to write.
function Get-ImportProblems {
    param([string]$Text, [string]$Name)
    $tokens = $null
    $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseInput($Text, [ref]$tokens, [ref]$errors)
    if ($errors.Count -gt 0) { return @("$Name does not parse: $($errors[0].Message)") }
    $problems = New-Object System.Collections.Generic.List[string]
    $forbidden = @('git', 'git.exe', 'gh', 'curl', 'curl.exe', 'wget', 'Expand-Archive', 'Start-BitsTransfer', 'Copy-Item', 'Move-Item',
        'Set-Content', 'Add-Content', 'Out-File', 'Invoke-RestMethod', 'Export-Csv')
    $gitRunners = @($ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'Invoke-SourceGit' }, $true))
    foreach ($command in $ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.CommandAst] }, $true)) {
        $commandName = $command.GetCommandName()
        if ($commandName -and ($forbidden -contains $commandName)) {
            # The token lookup for GitHub is the one gh call allowed: it prints a token, nothing more.
            if ($commandName -eq 'gh' -and $command.Extent.Text -match '^&\s*gh\s+auth\s+token\b') { continue }
            if ($commandName -in @('git', 'git.exe') -and $Name -like 'audit-facebook-sources*' -and $gitRunners.Count -eq 1) {
                $enclosing = $command.Parent
                while ($null -ne $enclosing -and $enclosing -isnot [System.Management.Automation.Language.FunctionDefinitionAst]) { $enclosing = $enclosing.Parent }
                if ($enclosing -eq $gitRunners[0]) { continue }
            }
            $problems.Add("$Name runs $commandName`: $($command.Extent.Text)")
        }
        foreach ($element in $command.CommandElements) {
            if ($element -is [System.Management.Automation.Language.CommandParameterAst] -and $element.ParameterName -eq 'OutFile') {
                $problems.Add("$Name downloads to a file: $($command.Extent.Text)")
            }
        }
    }
    $writes = 0
    $appends = 0
    foreach ($member in $ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.InvokeMemberExpressionAst] }, $true)) {
        $memberName = [string]$member.Member.Extent.Text
        if ($memberName -in @('WriteAllBytes', 'Copy', 'Move', 'ExtractToDirectory', 'CreateFromDirectory', 'DownloadFile')) {
            $problems.Add("$Name calls $memberName`: $($member.Extent.Text)")
        }
        if ($memberName -eq 'WriteAllText') { $writes++ }
        if ($memberName -eq 'AppendAllText') { $appends++ }
    }
    $allowed = if ($Name -like 'audit*') { 2 } else { 0 }
    if ($writes -gt $allowed) { $problems.Add("$Name writes $writes files with WriteAllText, and only the report and the ledger stamp may.") }
    if ($gitRunners.Count -gt 1) { $problems.Add("$Name defines Invoke-SourceGit $($gitRunners.Count) times.") }
    foreach ($runner in $gitRunners) {
        $lists = @($runner.FindAll({ param($node) $node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
            $node.Left.Extent.Text -eq '$allowed' }, $true))
        $named = @(foreach ($list in $lists) {
            $list.Right.FindAll({ param($node) $node -is [System.Management.Automation.Language.StringConstantExpressionAst] }, $true) | ForEach-Object { $_.Value } })
        if ($lists.Count -ne 1 -or $named.Count -eq 0) { $problems.Add("$Name has no single list of the git commands Invoke-SourceGit allows.") }
        foreach ($subcommand in $named) {
            if (@('clone', 'fetch', 'for-each-ref', 'ls-tree', 'log', 'merge-base') -cnotcontains $subcommand) {
                $problems.Add("$Name lets git run $subcommand, which can read or write file contents.")
            }
        }
        if ($runner.Extent.Text -notmatch "-notcontains '--filter=blob:none'") { $problems.Add("$Name lets git clone or fetch without --filter=blob:none.") }
        if ($runner.Extent.Text -notmatch "GIT_NO_LAZY_FETCH = '1'") { $problems.Add("$Name runs git without GIT_NO_LAZY_FETCH, so a read could fetch file contents.") }
    }
    # The coverage ledger is appended to a record at a time, from one place.
    $allowed = if ($Name -like 'gitlab-coverage*') { 1 } else { 0 }
    if ($appends -gt $allowed) { $problems.Add("$Name calls AppendAllText $appends time(s), and only the coverage ledger's one append may.") }
    return $problems.ToArray()
}

# The coverage ledger's requests are anonymous reads, so nothing in its two files may hand a
# request a header, a method, a body or a credential.
function Get-RequestProblems {
    param([string]$Text, [string]$Name)
    $tokens = $null
    $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseInput($Text, [ref]$tokens, [ref]$errors)
    $problems = New-Object System.Collections.Generic.List[string]
    foreach ($parameter in $ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.CommandParameterAst] }, $true)) {
        if ($parameter.ParameterName -in @('Headers', 'Method', 'Body', 'InFile', 'Credential', 'Token', 'Authentication', 'CustomMethod')) {
            $problems.Add("$Name hands a command -$($parameter.ParameterName): $($parameter.Parent.Extent.Text)")
        }
    }
    if ($Text -cmatch '[A-Z]_TOKEN\b|PRIVATE-TOKEN|[Aa]uthorization') { $problems.Add("$Name names a token or an Authorization header.") }
    return $problems.ToArray()
}
$auditText = [IO.File]::ReadAllText($auditScript)
$libraryText = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'facebook-sources.ps1'))
$coverageText = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'gitlab-coverage.ps1'))
$scanText = [IO.File]::ReadAllText($scanScript)
foreach ($pair in @(@('audit-facebook-sources.ps1', $auditText), @('facebook-sources.ps1', $libraryText),
        @('gitlab-coverage.ps1', $coverageText), @('scan-gitlab-coverage.ps1', $scanText))) {
    $found = @(Get-ImportProblems -Text $pair[1] -Name $pair[0])
    Assert-True ($found.Count -eq 0) ($found -join ' | ')
}
foreach ($pair in @(@('gitlab-coverage.ps1', $coverageText), @('scan-gitlab-coverage.ps1', $scanText))) {
    $found = @(Get-RequestProblems -Text $pair[1] -Name $pair[0])
    Assert-True ($found.Count -eq 0) ($found -join ' | ')
}
Assert-True ([regex]::Matches($coverageText, 'Invoke-WebRequest').Count -eq 1 -and $scanText -notmatch 'Invoke-WebRequest|Invoke-RestMethod|Net\.WebClient|HttpClient') `
    'The coverage ledger makes requests somewhere other than the one place that holds them to gitlab.com.'
foreach ($bad in @(
        @{ Line = '[IO.File]::AppendAllText((Join-Path $rootPath ''sources/facebook-sources.json''), $line)'; Pattern = '*calls AppendAllText 2 time(s)*'; Check = 'import' }
        @{ Line = '[IO.File]::WriteAllText($Path, $text)'; Pattern = '*writes 1 files with WriteAllText*'; Check = 'import' }
        @{ Line = '& git clone "https://gitlab.com/$path.git" $env:TEMP'; Pattern = '*runs git*'; Check = 'import' }
        @{ Line = 'Invoke-WebRequest -Uri $Uri -Headers @{ ''PRIVATE-TOKEN'' = $secret }'; Pattern = '*hands a command -Headers*'; Check = 'request' }
        @{ Line = 'Invoke-WebRequest -Uri $Uri -Method Post -Body $payload'; Pattern = '*hands a command -Method*'; Check = 'request' }
        @{ Line = '$secret = $env:GITLAB_TOKEN'; Pattern = '*names a token*'; Check = 'request' })) {
    $copy = $coverageText + "`n" + $bad.Line + "`n"
    $found = if ($bad.Check -eq 'import') { @(Get-ImportProblems -Text $copy -Name 'gitlab-coverage.ps1 (copy)') } else {
        @(Get-RequestProblems -Text $copy -Name 'gitlab-coverage.ps1 (copy)') }
    Assert-True (@($found | Where-Object { $_ -like $bad.Pattern }).Count -gt 0) "The coverage check missed: $($bad.Line)"
}
foreach ($bad in @(
        @{ Line = '& git clone https://github.com/example/patches.git $env:TEMP'; Pattern = '*runs git*' }
        @{ Line = 'Invoke-WebRequest -Uri $uri -OutFile (Join-Path $rootPath ''patches/x.kt'')'; Pattern = '*downloads to a file*' }
        @{ Line = 'Copy-Item -LiteralPath $source -Destination $rootPath'; Pattern = '*runs Copy-Item*' }
        @{ Line = '[IO.Compression.ZipFile]::ExtractToDirectory($zip, $rootPath)'; Pattern = '*calls ExtractToDirectory*' }
        @{ Line = '[IO.File]::WriteAllText((Join-Path $rootPath ''patches/Ported.kt''), $code)'; Pattern = '*writes 3 files with WriteAllText*' }
        @{ Line = '& gh repo clone example/patches'; Pattern = '*runs gh*' })) {
    $found = @(Get-ImportProblems -Text ($auditText + "`n" + $bad.Line + "`n") -Name 'audit-facebook-sources.ps1 (copy)')
    Assert-True (@($found | Where-Object { $_ -like $bad.Pattern }).Count -gt 0) "The import check missed: $($bad.Line)"
}
foreach ($bad in @(
        @{ From = "'log', 'merge-base')"; To = "'log', 'merge-base', 'show')"; Pattern = '*lets git run show*' }
        @{ From = "'log', 'merge-base')"; To = "'log', 'merge-base', 'checkout')"; Pattern = '*lets git run checkout*' }
        @{ From = "-notcontains '--filter=blob:none'"; To = "-notcontains '--no-filter'"; Pattern = '*without --filter=blob:none*' }
        @{ From = "GIT_NO_LAZY_FETCH = '1'"; To = "GIT_NO_LAZY_FETCH = '0'"; Pattern = '*without GIT_NO_LAZY_FETCH*' }
        @{ From = 'function Get-GitLabMirror {'; To = "function Get-GitLabMirror {`n    & git archive HEAD"; Pattern = '*runs git: & git archive HEAD*' })) {
    Assert-True ($auditText.Contains($bad.From)) "The import check's copy can't be made: the audit no longer holds $($bad.From)"
    $found = @(Get-ImportProblems -Text $auditText.Replace($bad.From, $bad.To) -Name 'audit-facebook-sources.ps1 (copy)')
    Assert-True (@($found | Where-Object { $_ -like $bad.Pattern }).Count -gt 0) "The import check missed: $($bad.To)"
}

Write-Host '[sources] the audit and its rules download no file contents and copy no code; git keeps blobless GitLab mirrors only'
Write-Host '[sources] source ledger contracts passed'
} finally {
    $env:GITLAB_TOKEN = $savedGitLabToken
    Remove-Item -LiteralPath $caseRoot -Recurse -Force -ErrorAction SilentlyContinue
}
