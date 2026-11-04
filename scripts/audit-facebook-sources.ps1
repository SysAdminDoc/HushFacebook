<#
.SYNOPSIS
    Refresh the Facebook-family source census and report every way the sources moved.

.DESCRIPTION
    Holds sources/facebook-sources.json to what the indexes and the forges say today, and changes
    nothing but dates. It reads:

    - the official Morphe patch bundle's patches-list.json, which has shipped no Facebook-family
      patch so far;
    - the Morphe community directory, Awesome Morphe, the Morphe Patch Tracker and Jman's bundle
      index, for every bundle that targets com.facebook.katana, com.facebook.orca or
      com.facebook.lite, and for whether Hushfacebook is listed on each, plus the Morphe Archive for
      Hushfacebook's own listing;
    - GitHub code search for the three package names in patch lists, patch code and Xposed hooks,
      with the two archive mirrors mapped back to the repositories they copied, and GitLab code
      search when GITLAB_TOKEN is set and -SkipGitLabCodeSearch isn't passed;
    - every ledger source's repository, licence, branches and forks. A source with watchPaths is
      read through the newest commit that touched paths still present on the branch. A deletion
      commit is not a current source. Retired sources keep reachable historical pins, while an
      unavailable source records that those pins no longer resolve. Either returning is a finding.

    A repository the ledger doesn't know is an addition, unless every file code search found in it
    is byte for byte a file the ledger already holds (the same git blob id), which makes it a copy
    of that lineage: it's reported under that lineage and not counted again. A removed or changed
    licence, a branch head that moved or a new branch with Facebook-family work, a fork the ledger
    hasn't seen, a recorded fork or out-of-scope repository an index lists as a bundle of its own
    (for an out-of-scope one, under a package its listedFor doesn't name), a repository that's
    gone, renamed or archived, and a listing that changed are the other findings.
    Any finding, or a source that couldn't be read or was skipped, writes the report and exits 1
    with the ledger untouched. The one exception is GitLab's global code search, which GitLab.com
    doesn't offer: a run that skips it may still stamp, and the census then names
    gitlab-code-search under skipped. A clean run stamps today's date on the census and every
    record it checked, and exits 0. Commit that change: a local release refuses a census more than
    14 days old.

    The audit never imports code. It reads metadata through the forges' APIs; a licence is hashed
    in memory and a patch list is read for its names. A GitLab source's branches are read from a
    blobless git mirror under -MirrorRoot, which holds commits and folder listings and never a
    file's contents, so a census asks gitlab.com for one fetch instead of a request per branch and
    path. No file contents are downloaded to disk, and nothing is written but those mirrors, the
    report and the ledger's dates. The report can't go under patches/ or extensions/, and the
    mirrors can't go anywhere under the repository.

    GitHub requests use GITHUB_TOKEN or the gh CLI's token (code search needs one). GitLab requests
    use GITLAB_TOKEN, but GitLab.com does not enable global code search. A token alone does not
    supply that coverage. A missing token or -SkipGitLabCodeSearch records the gap in the report
    and in census.skipped; a search that runs and is refused fails the audit. Tokens go in request
    headers only, never into the report or ledger.

.EXAMPLE
    pwsh -File scripts/audit-facebook-sources.ps1 -SkipGitLabCodeSearch
.EXAMPLE
    pwsh -File scripts/audit-facebook-sources.ps1 -ValidateOnly
#>
[CmdletBinding()]
param(
    [string]$Root,
    # Defaults to sources/facebook-sources.json under -Root.
    [string]$Ledger,
    # Defaults to a dated file in the temporary folder.
    [string]$ReportPath,
    # Only the offline rules, with no network. The pre-push suite runs this.
    [switch]$ValidateOnly,
    # Report without stamping the ledger, even when nothing moved.
    [switch]$NoStamp,
    [switch]$SkipGitLabCodeSearch,
    [string]$GitHubToken,
    [string]$GitLabToken,
    # The date the census is stamped with and ledger dates are held to, yyyy-MM-dd. UTC today by default.
    [string]$Today,
    # GitHub allows ten code searches a minute.
    [double]$SearchDelaySeconds = 7,
    # How long to wait out a rate limit before trying again.
    [int]$RateLimitWaitSeconds = 61,
    # Where the blobless GitLab mirrors are kept between runs. Hushfacebook\source-mirrors under the
    # local app data folder by default.
    [string]$MirrorRoot
)

$ErrorActionPreference = 'Stop'
# Not a parameter default: Windows PowerShell leaves $PSScriptRoot empty while it evaluates the
# defaults of an advanced script started with -File.
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
. (Join-Path $PSScriptRoot 'facebook-sources.ps1')

if ($PSVersionTable.PSVersion.Major -lt 6) {
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
}

$rootPath = (Resolve-Path -LiteralPath $Root).Path
$ledgerPath = if ($Ledger) { $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Ledger) } else {
    Get-SourceLedgerPath -Root $rootPath
}
$todayDate = Get-SourceToday $Today
$todayText = $todayDate.ToString('yyyy-MM-dd')

if (-not $ReportPath) {
    $ReportPath = Join-Path ([IO.Path]::GetTempPath()) ("hushfacebook-source-audit-" + [datetime]::UtcNow.ToString('yyyyMMdd-HHmmss') + '.json')
}
$ReportPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($ReportPath)
foreach ($guarded in @('patches', 'extensions')) {
    $guardedPath = [IO.Path]::GetFullPath((Join-Path $rootPath $guarded)).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if ([IO.Path]::GetFullPath($ReportPath).StartsWith($guardedPath, [StringComparison]::OrdinalIgnoreCase)) {
        throw "The audit report can't go under $guarded/, where the shipped sources live: $ReportPath"
    }
}
if ($ReportPath -match '(?i)\.(kt|kts|java|smali)$') {
    throw "The audit report is JSON, not source code: $ReportPath"
}
$mirrorRootPath = if ($MirrorRoot) { $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($MirrorRoot) } else {
    Join-Path $(if ($env:LOCALAPPDATA) { $env:LOCALAPPDATA } else { [IO.Path]::GetTempPath() }) 'Hushfacebook\source-mirrors'
}
$repositoryPrefix = [IO.Path]::GetFullPath($rootPath).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
if (([IO.Path]::GetFullPath($mirrorRootPath).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar).StartsWith($repositoryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "The GitLab mirrors can't go inside the repository: $mirrorRootPath"
}

$findings = New-Object System.Collections.Generic.List[object]
$notes = New-Object System.Collections.Generic.List[string]
$sourceStatus = New-Object System.Collections.Generic.List[object]
$requestCount = 0

function Write-Step {
    param([string]$Message)
    Write-Host "[sources] $Message"
}

function Add-Finding {
    param([string]$Kind, [string]$Repository, [string]$Detail, $Evidence)
    $findings.Add([pscustomobject][ordered]@{ kind = $Kind; repository = $Repository; detail = $Detail; evidence = $Evidence })
}

function Save-Report {
    param([bool]$Clean, $Counts, $Lineages, $KnownHits)
    $report = [ordered]@{
        generatedAt = [datetime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
        today = $todayText
        ledger = $ledgerPath
        clean = $Clean
        counts = $Counts
        sources = $sourceStatus.ToArray()
        lineages = @($Lineages | Where-Object { $null -ne $_ })
        findings = $findings.ToArray()
        notes = $notes.ToArray()
        knownHits = @($KnownHits | Where-Object { $null -ne $_ })
    }
    $directory = Split-Path -Parent $ReportPath
    if ($directory -and -not (Test-Path -LiteralPath $directory)) { New-Item -ItemType Directory -Path $directory -Force | Out-Null }
    [IO.File]::WriteAllText($ReportPath, ($report | ConvertTo-Json -Depth 10), (New-Object Text.UTF8Encoding $false))
    Write-Step "report: $ReportPath"
}

# --- the ledger's own rules ------------------------------------------------------------------

$ledgerDocument = Read-SourceLedger -Path $ledgerPath
$rules = Test-SourceLedger -Ledger $ledgerDocument -Root $rootPath -Today $todayText
if (-not $rules.Valid) {
    foreach ($problem in $rules.Problems) {
        Write-Step "ledger: $problem"
        Add-Finding -Kind 'ledger-invalid' -Repository $null -Detail $problem -Evidence $null
    }
    Save-Report -Clean $false -Counts ([ordered]@{ findings = $findings.Count }) -Lineages @()
    Write-Step "the ledger breaks $($rules.Problems.Count) rule(s), so nothing was fetched"
    exit 1
}
$entries = @(Get-SourceProperty $ledgerDocument 'entries' | Where-Object { $null -ne $_ })
$lineageNames = @($entries | ForEach-Object { [string]$_.lineage } | Sort-Object -Unique)
$availabilityCounts = Get-SourceAvailabilityCounts $entries
if ($ValidateOnly) {
    $census = Test-SourceCensus -Ledger $ledgerDocument -Today $todayText
    $censusLine = if ($census.Valid) { "the census is $($census.AgeDays) day(s) old" } else { $census.Reason }
    Write-Step ("the ledger keeps its rules: $($entries.Count) sources in $($lineageNames.Count) lineages " +
        "($($availabilityCounts.activeSources) active, $($availabilityCounts.retiredSources) retired, $($availabilityCounts.unavailableSources) unavailable); " + $censusLine)
    exit 0
}

# --- tokens ----------------------------------------------------------------------------------

if (-not $GitHubToken) { $GitHubToken = $env:GITHUB_TOKEN }
if (-not $GitHubToken -and (Get-Command gh -ErrorAction SilentlyContinue)) {
    $preference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $GitHubToken = "$(& gh auth token 2>$null)".Trim() } finally { $ErrorActionPreference = $preference }
}
if (-not $GitHubToken) {
    throw 'GitHub code search needs a token. Run gh auth login, or set GITHUB_TOKEN.'
}
if (-not $GitLabToken) { $GitLabToken = $env:GITLAB_TOKEN }
# The switch wins over a token in the environment, which still authenticates the ledger's GitLab reads.
$searchGitLab = [bool]$GitLabToken -and -not $SkipGitLabCodeSearch
$gitlabCoverageEvidence = [ordered]@{
    index = 'gitlab-code-search'
    endpoint = 'https://gitlab.com/api/v4/search?scope=blobs'
    documentation = @('https://docs.gitlab.com/user/search/advanced_search/',
        'https://docs.gitlab.com/user/search/exact_code_search/')
    limitation = 'GitLab.com does not enable global code search. A token or scoped search does not establish global coverage.'
}

# --- requests --------------------------------------------------------------------------------

function Get-RetryAfterSeconds {
    # The wait a 429 asks for, in seconds, or 0 when it names none. PowerShell 7 hands over typed
    # headers (RetryAfter.Delta), Windows PowerShell a collection of strings.
    param($Response)
    if ($null -eq $Response -or $null -eq $Response.PSObject.Properties['Headers'] -or $null -eq $Response.Headers) { return 0 }
    $typed = $Response.Headers.PSObject.Properties['RetryAfter']
    if ($null -ne $typed) {
        if ($null -ne $typed.Value -and $null -ne $typed.Value.Delta) { return [int][Math]::Ceiling($typed.Value.Delta.TotalSeconds) }
        return 0
    }
    $seconds = 0
    if ([int]::TryParse([string]$Response.Headers['Retry-After'], [ref]$seconds) -and $seconds -gt 0) { return $seconds }
    return 0
}

function Invoke-SourceRequest {
    <#
        One GET, answering Status and Content. A 4xx or 5xx answer comes back as its status with no
        content, since a repository that's gone is a finding and not a crash; only a request that
        never got an answer throws. A rate limit is waited out, six times at most.
    #>
    param([Parameter(Mandatory = $true)][string]$Uri, [string]$Auth = 'plain')

    $headers = @{}
    if ($Auth -eq 'github') { $headers['Authorization'] = "Bearer $GitHubToken" }
    if ($Auth -eq 'gitlab' -and $GitLabToken) { $headers['PRIVATE-TOKEN'] = $GitLabToken }
    for ($attempt = 1; ; $attempt++) {
        $script:requestCount++
        try {
            $response = Invoke-WebRequest -Uri $Uri -Headers $headers -UserAgent 'Hushfacebook-source-audit' `
                -UseBasicParsing -TimeoutSec 120
            $content = $null
            $raw = $response.PSObject.Properties['RawContentStream']
            if ($null -ne $raw -and $null -ne $raw.Value) {
                $content = [Text.Encoding]::UTF8.GetString($raw.Value.ToArray())
            } else {
                $content = $response.Content
                if ($content -is [byte[]]) { $content = [Text.Encoding]::UTF8.GetString($content) }
            }
            return [pscustomobject]@{ Status = [int]$response.StatusCode; Content = [string]$content }
        } catch {
            $status = $null
            $answer = $_.Exception.PSObject.Properties['Response']
            if ($null -ne $answer -and $null -ne $answer.Value -and $null -ne $answer.Value.StatusCode) {
                $status = [int]$answer.Value.StatusCode
            }
            if ($null -eq $status) {
                # A slow compare or a dropped connection gets two more tries before it's a failure.
                if ($attempt -lt 3) {
                    Write-Step "no answer from $([Uri]$Uri | ForEach-Object { $_.Host }), trying again ($($_.Exception.Message))"
                    Start-Sleep -Seconds ([Math]::Min($RateLimitWaitSeconds, 15))
                    continue
                }
                throw "Could not reach ${Uri}: $($_.Exception.Message)"
            }
            $said = "$($_.ErrorDetails.Message) $($_.Exception.Message)"
            $limited = $status -eq 429 -or ($status -eq 403 -and $said -match '(?i)rate limit')
            if ($limited -and $attempt -lt 7) {
                # Each wait is longer than the last, and as long as the server's Retry-After asks
                # (15 minutes at most): gitlab.com held one 429 through three flat 61 s waits.
                $wait = $RateLimitWaitSeconds * $attempt
                $asked = Get-RetryAfterSeconds $answer.Value
                if ($RateLimitWaitSeconds -gt 0 -and $asked -gt $wait) { $wait = [Math]::Min($asked, 900) }
                Write-Step "rate limited on $([Uri]$Uri | ForEach-Object { $_.Host }), waiting $wait s"
                Start-Sleep -Seconds $wait
                continue
            }
            return [pscustomobject]@{ Status = $status; Content = $null }
        }
    }
}

function ConvertFrom-SourceJson {
    # -AsHashtable is for a document whose keys differ only by case, which a plain parse refuses.
    param([string]$Text, [string]$What, [switch]$AsHashtable)
    try {
        if ($AsHashtable) {
            if ((Get-Command ConvertFrom-Json).Parameters.ContainsKey('AsHashtable')) {
                return , ($Text | ConvertFrom-Json -AsHashtable)
            }
            # Windows PowerShell has no -AsHashtable, and the pre-push hook falls back to it when
            # pwsh is off git's PATH. Its JavaScriptSerializer keeps keys case-sensitive.
            Add-Type -AssemblyName System.Web.Extensions
            $serializer = New-Object System.Web.Script.Serialization.JavaScriptSerializer
            $serializer.MaxJsonLength = [int]::MaxValue
            return , $serializer.DeserializeObject($Text)
        }
        return , ($Text | ConvertFrom-Json)
    } catch {
        throw "$What did not answer JSON: $($_.Exception.Message)"
    }
}

function Get-JsonItems {
    # The elements of a parsed JSON array, one at a time, the same way in both editions: Windows
    # PowerShell hands a top-level array over as one object, PowerShell 7 one element at a time.
    # Callers collect them with @() or foreach.
    param($Parsed)
    foreach ($item in $Parsed) { if ($null -ne $item) { $item } }
}

function Get-SourceJson {
    param([string]$Uri, [string]$Auth = 'plain', [string]$What = $Uri, [switch]$AsHashtable)
    $answer = Invoke-SourceRequest -Uri $Uri -Auth $Auth
    if ($answer.Status -ne 200) { throw "$What answered HTTP $($answer.Status)." }
    return , (ConvertFrom-SourceJson -Text $answer.Content -What $What -AsHashtable:$AsHashtable)
}

function Get-PagedItems {
    <#
        Every page of a GitHub or GitLab list, a hundred at a time. Status is the first page's
        answer; Items is $null unless that was 200.
    #>
    param([string]$Uri, [string]$Auth)
    $all = New-Object System.Collections.Generic.List[object]
    for ($page = 1; $page -le 50; $page++) {
        $separator = if ($Uri.Contains('?')) { '&' } else { '?' }
        $answer = Invoke-SourceRequest -Uri "$Uri${separator}per_page=100&page=$page" -Auth $Auth
        if ($answer.Status -ne 200) {
            if ($page -eq 1) { return [pscustomobject]@{ Status = $answer.Status; Items = $null } }
            throw "$Uri answered HTTP $($answer.Status) on page $page."
        }
        $items = @(Get-JsonItems (ConvertFrom-SourceJson -Text $answer.Content -What $Uri))
        foreach ($item in $items) { $all.Add($item) }
        if ($items.Count -lt 100) { break }
    }
    return [pscustomobject]@{ Status = 200; Items = $all }
}

function Get-Sha256Hex {
    param([byte[]]$Bytes)
    $hasher = [Security.Cryptography.SHA256]::Create()
    try { return -join ($hasher.ComputeHash($Bytes) | ForEach-Object { $_.ToString('x2') }) } finally { $hasher.Dispose() }
}

function Split-SourceKey {
    param([string]$Key)
    $at = $Key.IndexOf('/')
    return [pscustomobject]@{ Host = $Key.Substring(0, $at); Path = $Key.Substring($at + 1) }
}

function Get-EncodedPath {
    param([string]$Path)
    return (@($Path -split '/' | ForEach-Object { [Uri]::EscapeDataString($_) }) -join '/')
}

# --- what the ledger already knows ------------------------------------------------------------

$packages = Get-SourcePackages
$selfKey = ConvertTo-SourceKey $ledgerDocument.self.repository
$selfOwner = ($selfKey -split '/')[1]
$known = @{}
$knownBlobs = @{}
foreach ($entry in $entries) {
    $key = ConvertTo-SourceKey $entry.repository
    $known[$key] = [pscustomobject]@{ Kind = 'entry'; Lineage = [string]$entry.lineage; Entry = $entry; Record = $entry }
    $entryHost = (Split-SourceKey $key).Host
    foreach ($hash in @(Get-SourceProperty $entry 'contentHashes' | Where-Object { $_ })) { $knownBlobs["$hash"] = [string]$entry.lineage }
    foreach ($mirror in @(Get-SourceProperty $entry 'mirrors' | Where-Object { $_ })) {
        $known[(ConvertTo-SourceKey $mirror.repository)] = [pscustomobject]@{ Kind = 'mirror'; Lineage = [string]$entry.lineage; Entry = $entry; Record = $mirror }
        foreach ($hash in @(Get-SourceProperty $mirror 'blobs' | Where-Object { $_ })) { $knownBlobs["$hash"] = [string]$entry.lineage }
    }
    foreach ($fork in @(Get-SourceProperty $entry 'forks' | Where-Object { $_ })) {
        $forkKey = "$entryHost/$fork".ToLowerInvariant()
        if (-not $known.ContainsKey($forkKey)) {
            $known[$forkKey] = [pscustomobject]@{ Kind = 'fork'; Lineage = [string]$entry.lineage; Entry = $entry; Record = $null }
        }
    }
}
foreach ($item in @(Get-SourceProperty $ledgerDocument 'outOfScope' | Where-Object { $_ })) {
    $known[(ConvertTo-SourceKey $item.repository)] = [pscustomobject]@{ Kind = 'outOfScope'; Lineage = $null; Entry = $null; Record = $item }
}
# The indexes' own data repositories, read below as indexes. A code search hit in one is a copy of
# a bundle's patch list, never a source of its own.
$indexRepositories = @('github.com/nvbangg/awesome-morphe', 'github.com/drnx64/morphe-track-patches',
    'github.com/jman-github/revanced-patch-bundles', 'github.com/jman-github/patch-bundle-registry', 'github.com/rushiforai/morphe-archive', 'github.com/rushiforai/revanced-archive')
# The two archives keep examplepatches/<owner>/<name>/patches-list.json for every bundle they
# found, which is a pointer back to that repository.
$archiveRepositories = @('github.com/rushiforai/morphe-archive', 'github.com/rushiforai/revanced-archive')

$candidates = @{}
function Add-Candidate {
    <#
        Packages are what an index says a bundle targets. Code search only proves a file names the
        package, which a Messenger patch opening Facebook does too, so its packages are Mentions.
    #>
    param([string]$Key, [string]$Source, [string[]]$Packages, [string[]]$Mentions, [string]$Path, [string]$Blob, [string[]]$Features)
    if (-not $Key) { return }
    if (-not $candidates.ContainsKey($Key)) {
        $candidates[$Key] = [pscustomobject]@{
            Key = $Key
            Sources = New-Object System.Collections.Generic.List[string]
            Packages = New-Object System.Collections.Generic.List[string]
            Mentions = New-Object System.Collections.Generic.List[string]
            Hits = New-Object System.Collections.Generic.List[object]
            Features = New-Object System.Collections.Generic.List[string]
        }
    }
    $candidate = $candidates[$Key]
    if ($Source -and -not $candidate.Sources.Contains($Source)) { $candidate.Sources.Add($Source) }
    foreach ($package in @($Packages)) { if ($package -and -not $candidate.Packages.Contains($package)) { $candidate.Packages.Add($package) } }
    foreach ($package in @($Mentions)) { if ($package -and -not $candidate.Mentions.Contains($package)) { $candidate.Mentions.Add($package) } }
    foreach ($feature in @($Features)) { if ($feature -and -not $candidate.Features.Contains($feature)) { $candidate.Features.Add($feature) } }
    if ($Path) { $candidate.Hits.Add([pscustomobject][ordered]@{ path = $Path; blob = $Blob }) }
}

$listed = @{}
function Invoke-Source {
    # Runs one reader. A reader that throws is recorded as failed, which fails the audit, and the
    # others still run so the report shows everything that could be read.
    param([string]$Name, [scriptblock]$Read, $FailureEvidence = $null)
    try {
        $said = & $Read
        $sourceStatus.Add([pscustomobject][ordered]@{ source = $Name; status = 'read'; detail = "$said" })
        Write-Step "$Name`: $said"
    } catch {
        $sourceStatus.Add([pscustomobject][ordered]@{ source = $Name; status = 'failed'; detail = $_.Exception.Message })
        Add-Finding -Kind 'source-failed' -Repository $null -Detail "$Name could not be read: $($_.Exception.Message)" -Evidence $FailureEvidence
        Write-Step "$Name FAILED: $($_.Exception.Message)"
    }
}

# --- the official bundle and the indexes -------------------------------------------------------

$official = $ledgerDocument.officialBundle
Invoke-Source 'official Morphe bundle' {
    $officialKey = Split-SourceKey (ConvertTo-SourceKey $official.repository)
    $list = Get-SourceJson -Uri ("https://raw.githubusercontent.com/$($officialKey.Path)/" +
        "$(Get-EncodedPath ([string]$official.branch))/$(Get-EncodedPath ([string]$official.list))") -What 'the official patch list'
    $found = New-Object System.Collections.Generic.List[string]
    foreach ($patch in (Get-JsonItems $list.patches)) {
        # Assigned in place, not returned from a function or an if: either one hands a one-element
        # array back as its element, and the list form would then be read as the map form.
        $property = $patch.PSObject.Properties['compatiblePackages']
        $compatible = $null
        if ($null -ne $property) { $compatible = $property.Value }
        $names = @()
        if ($null -ne $compatible) {
            if ($compatible -is [array] -or $compatible -is [System.Collections.IList]) {
                # The list form Morphe writes now: [{"packageName": ...}, ...].
                $names = @($compatible | ForEach-Object { if ($_ -is [string]) { $_ } else { Get-SourceProperty $_ 'packageName' } })
            } else {
                # The map form: {"com.example": ["1.0"]}.
                $names = @($compatible.PSObject.Properties | ForEach-Object { $_.Name })
            }
        }
        foreach ($name in $names) { if ($packages -contains $name -and -not $found.Contains($name)) { $found.Add($name) } }
    }
    $recorded = @($official.packages | Where-Object { $_ } | ForEach-Object { "$_" })
    $added = @($found | Where-Object { $recorded -notcontains $_ })
    $dropped = @($recorded | Where-Object { $found -notcontains $_ })
    if ($added.Count -gt 0 -or $dropped.Count -gt 0) {
        Add-Finding -Kind 'official-bundle-changed' -Repository $official.repository `
            -Detail ("the official bundle now patches [" + ($found -join ', ') + "], the ledger records [" + ($recorded -join ', ') + ']') `
            -Evidence ([ordered]@{ added = $added; dropped = $dropped })
    }
    "$(@((Get-JsonItems $list.patches)).Count) patches, Facebook-family packages: " + $(if ($found.Count) { $found -join ', ' } else { 'none' })
}

Invoke-Source 'Morphe directory' {
    $directory = Get-SourceJson -Uri 'https://morphe-patches.software/data/bundles.json' -What 'the Morphe directory'
    $compatibilities = @(Get-JsonItems $directory.compatibilities)
    $count = 0
    $listed['morphe-directory'] = $false
    foreach ($bundle in (Get-JsonItems $directory.bundles)) {
        $forge = switch ("$($bundle.source)") { 'github' { 'github.com' } 'gitlab' { 'gitlab.com' } default { $null } }
        $key = if ($forge) { ConvertTo-SourceKey "https://$forge/$($bundle.repo)" } else { $null }
        if ($key -and $key -eq $selfKey) { $listed['morphe-directory'] = $true }
        $apps = @($bundle.targetApps | Where-Object { $packages -contains $_ })
        if ($apps.Count -eq 0) { continue }
        $features = @(Get-JsonItems $bundle.patches | Where-Object {
            $index = Get-SourceProperty $_ 'compatiblePackagesKey'
            $null -ne $index -and [int]$index -lt $compatibilities.Count -and
                @($compatibilities[[int]$index] | Where-Object { $packages -contains $_.packageName }).Count -gt 0
        } | ForEach-Object { [string]$_.name })
        if (-not $key) {
            Add-Finding -Kind 'index-unresolved' -Repository $null -Detail "the Morphe directory lists $($bundle.repo) on $($bundle.source), which is not GitHub or GitLab" -Evidence $null
            continue
        }
        Add-Candidate -Key $key -Source 'Morphe directory' -Packages $apps -Features $features
        $count++
    }
    "$count Facebook-family bundles; Hushfacebook " + $(if ($listed['morphe-directory']) { 'listed' } else { 'not listed' })
}

Invoke-Source 'Awesome Morphe' {
    $hashes = Get-SourceJson -Uri 'https://raw.githubusercontent.com/nvbangg/awesome-morphe/main/data/hashes.json' -What 'Awesome Morphe'
    $count = 0
    $listed['awesome-morphe'] = $false
    foreach ($property in $hashes.PSObject.Properties) {
        $key = ConvertTo-SourceKey $property.Name
        if ($key -and $key -eq $selfKey) { $listed['awesome-morphe'] = $true }
        $apps = @($property.Value.PSObject.Properties | Where-Object { $packages -contains $_.Name } | ForEach-Object { $_.Name })
        if ($apps.Count -eq 0) { continue }
        Add-Candidate -Key $key -Source 'Awesome Morphe' -Packages $apps
        $count++
    }
    "$count Facebook-family bundles; Hushfacebook " + $(if ($listed['awesome-morphe']) { 'listed' } else { 'not listed' })
}

Invoke-Source 'Morphe Patch Tracker' {
    # The tracker keys a bundle by repository and branch, and two keys can differ only by case
    # ('immortalzeus:dev' and 'ImmortalZeus:dev'), which a plain parse refuses.
    $tracker = Get-SourceJson -Uri 'https://raw.githubusercontent.com/drnx64/morphe-track-patches/main/data/bundles.json' `
        -What 'the Morphe Patch Tracker' -AsHashtable
    $seen = @{}
    $listed['morphe-patch-tracker'] = $false
    $trackerBundles = if ($tracker -is [System.Collections.IDictionary]) { @($tracker.Keys | ForEach-Object { $tracker[$_] }) }
        else { @($tracker.PSObject.Properties | ForEach-Object { $_.Value }) }
    foreach ($bundle in $trackerBundles) {
        $key = ConvertTo-SourceKey ([string]$bundle.repo_url)
        if ($key -and $key -eq $selfKey) { $listed['morphe-patch-tracker'] = $true }
        $apps = @(Get-JsonItems $bundle.apps | Where-Object { $packages -contains $_.package })
        if ($apps.Count -eq 0) { continue }
        $features = @($apps | ForEach-Object { Get-JsonItems $_.patches } | ForEach-Object { [string]$_.name })
        Add-Candidate -Key $key -Source 'Morphe Patch Tracker' -Packages @($apps | ForEach-Object { $_.package }) -Features $features
        $seen[$key] = $true
    }
    "$($seen.Count) Facebook-family bundles; Hushfacebook " + $(if ($listed['morphe-patch-tracker']) { 'listed' } else { 'not listed' })
}

Invoke-Source 'Jman''s bundle index' {
    # The registry became Patch-Bundle-Registry and serves its catalog from a read-only API. Asking
    # it for each package's bundles names the source repository of every bundle that targets it.
    $apiBase = 'https://patch-bundles.api.jmancentral.com'
    $listed['jman-bundles'] = $false
    $found = @{}
    $unresolved = @{}
    foreach ($package in $packages) {
        $offset = 0
        while ($offset -le 10000) {
            $page = Get-SourceJson -Uri "$apiBase/apps/$package/bundles?limit=100&offset=$offset" -What "Jman's catalog for $package"
            $rows = @(Get-JsonItems $page.data)
            foreach ($row in $rows) {
                $url = [string](Get-SourceProperty (Get-SourceProperty $row 'source') 'url')
                $key = ConvertTo-SourceKey $url
                if (-not $key) {
                    if (-not $unresolved.ContainsKey($url)) {
                        $unresolved[$url] = $true
                        Add-Finding -Kind 'index-unresolved' -Repository $null `
                            -Detail "Jman's catalog lists a Facebook-family bundle from '$url', which is not a GitHub or GitLab repository" -Evidence $null
                    }
                    continue
                }
                if ($key -eq $selfKey) { $listed['jman-bundles'] = $true }
                if (-not $found.ContainsKey($key)) { $found[$key] = @{} }
                $found[$key][$package] = $true
            }
            $offset += $rows.Count
            if ($rows.Count -eq 0 -or $offset -ge [int]$page.total) { break }
        }
    }
    foreach ($key in $found.Keys) {
        Add-Candidate -Key $key -Source 'Jman''s bundle index' -Packages @($found[$key].Keys)
    }
    "$($found.Count) Facebook-family bundles; Hushfacebook " + $(if ($listed['jman-bundles']) { 'listed' } else { 'not listed' })
}

Invoke-Source 'Morphe Archive' {
    $self = Split-SourceKey $selfKey
    $answer = Invoke-SourceRequest -Uri ("https://api.github.com/repos/rushiforai/morphe-archive/contents/examplepatches/" +
        (Get-EncodedPath ([string]$ledgerDocument.self.repository -replace '^https://github\.com/', '')) + '/patches-list.json') -Auth github
    if ($answer.Status -eq 200) { $listed['morphe-archive'] = $true }
    elseif ($answer.Status -eq 404) { $listed['morphe-archive'] = $false }
    else { throw "the archive answered HTTP $($answer.Status) for $($self.Path)" }
    'Hushfacebook ' + $(if ($listed['morphe-archive']) { 'listed' } else { 'not listed' }) + '; its bundles are read through code search'
}

# --- code search -----------------------------------------------------------------------------

$searchCount = 0
Invoke-Source 'GitHub code search' {
    $queries = New-Object System.Collections.Generic.List[object]
    foreach ($package in $packages) {
        foreach ($term in @('filename:patches-list.json', 'bytecodePatch', 'resourcePatch', 'IXposedHookLoadPackage', 'XposedHelpers')) {
            $queries.Add([pscustomobject]@{ Package = $package; Query = "`"$package`" $term" })
        }
    }
    $hits = 0
    foreach ($query in $queries) {
        for ($page = 1; $page -le 10; $page++) {
            if ($script:searchCount -gt 0 -and $SearchDelaySeconds -gt 0) { Start-Sleep -Milliseconds ([int]($SearchDelaySeconds * 1000)) }
            $script:searchCount++
            $answer = Invoke-SourceRequest -Uri ("https://api.github.com/search/code?q=" + [Uri]::EscapeDataString($query.Query) +
                "&per_page=100&page=$page") -Auth github
            if ($answer.Status -ne 200) { throw "the search '$($query.Query)' answered HTTP $($answer.Status)" }
            $result = ConvertFrom-SourceJson -Text $answer.Content -What "the search '$($query.Query)'"
            $items = @(Get-JsonItems $result.items)
            foreach ($item in $items) {
                $key = ConvertTo-SourceKey ([string]$item.repository.full_name)
                $path = [string]$item.path
                if ($archiveRepositories -contains $key) {
                    # A copy of another repository's patch list. Only the archive's own copy of a
                    # bundle points somewhere; its copies of Jman's copies are read as Jman.
                    if ($path -match '^examplepatches/([^/]+)/([^/]+)/patches-list\.json$') {
                        Add-Candidate -Key (ConvertTo-SourceKey "$($Matches[1])/$($Matches[2])") -Source 'Morphe Archive' -Packages @($query.Package)
                    }
                    continue
                }
                if ($indexRepositories -contains $key) { continue }
                Add-Candidate -Key $key -Source 'GitHub code search' -Mentions @($query.Package) -Path $path -Blob ([string]$item.sha)
                $hits++
            }
            if ($items.Count -lt 100 -or ($page * 100) -ge [int]$result.total_count) { break }
        }
    }
    "$($queries.Count) queries, $hits hits outside the indexes"
}

if ($searchGitLab) {
    Invoke-Source 'GitLab code search' {
        $projects = @{}
        $hits = 0
        foreach ($package in $packages) {
            $result = Get-PagedItems -Uri ("https://gitlab.com/api/v4/search?scope=blobs&search=" + [Uri]::EscapeDataString("`"$package`"")) -Auth gitlab
            if ($result.Status -ne 200) {
                throw "the search for $package answered HTTP $($result.Status). $($gitlabCoverageEvidence.limitation)"
            }
            foreach ($item in $result.Items) {
                $projectId = [string]$item.project_id
                if ($projectId -notmatch '^\d+$' -or -not [string]$item.path) {
                    throw "the search for $package returned a hit without a project ID or path"
                }
                if (-not $projects.ContainsKey($projectId)) {
                    $project = Invoke-SourceRequest -Uri "https://gitlab.com/api/v4/projects/$projectId" -Auth gitlab
                    if ($project.Status -ne 200) { throw "GitLab project $projectId from the search for $package answered HTTP $($project.Status)" }
                    $path = [string](ConvertFrom-SourceJson -Text $project.Content -What "GitLab project $projectId").path_with_namespace
                    if (-not $path) { throw "GitLab project $projectId from the search for $package returned no repository path" }
                    $projects[$projectId] = ConvertTo-SourceKey ('https://gitlab.com/' + $path)
                }
                if ($projects[$projectId]) {
                    Add-Candidate -Key $projects[$projectId] -Source 'GitLab code search' -Mentions @($package) -Path ([string]$item.path) -Blob $null
                    $hits++
                }
            }
        }
        "$hits hits in $(@($projects.Values | Where-Object { $_ } | Sort-Object -Unique).Count) projects"
    } -FailureEvidence $gitlabCoverageEvidence
} else {
    # GitLab.com offers no global code search, so this is the one gap a census may carry. It never
    # counts as coverage: a stamped census names it in census.skipped, and the report keeps why.
    $reason = if ($SkipGitLabCodeSearch) { '-SkipGitLabCodeSearch' } else { 'no GITLAB_TOKEN was configured' }
    $detail = "GitLab code search was skipped ($reason). $($gitlabCoverageEvidence.limitation) The census records it as skipped."
    $notes.Add('GitLab sources in the ledger were still read; those reads do not replace the global code index.')
    $sourceStatus.Add([pscustomobject][ordered]@{ source = 'GitLab code search'; status = 'skipped'; detail = $detail
        evidence = $gitlabCoverageEvidence })
    Write-Step $detail
}

# --- what the discovery found that the ledger doesn't know --------------------------------------

$collapsed = New-Object System.Collections.Generic.List[object]
# What code search found in repositories the ledger already knows, so a maintainer can copy the
# blob ids into contentHashes or a mirror's blobs when settling a finding.
$knownHits = New-Object System.Collections.Generic.List[object]

# What an index or code search says about a repository the ledger already holds, under whatever
# name it was found by.
function Test-KnownRepository {
    param($Candidate, [string]$Key, $Record, [string[]]$Blobs)
    if ($Record.Kind -eq 'entry') {
        $recordedPackages = @($Record.Entry.packages | ForEach-Object { "$_" })
        $newPackages = @($Candidate.Packages | Where-Object { $recordedPackages -notcontains $_ })
        if ($newPackages.Count -gt 0) {
            Add-Finding -Kind 'packages-changed' -Repository ([string]$Record.Entry.repository) `
                -Detail ("now targets " + ($newPackages -join ', ') + ", which the ledger doesn't record") `
                -Evidence ([ordered]@{ sources = @($Candidate.Sources); features = @($Candidate.Features) })
        }
    } elseif ($Record.Kind -eq 'mirror') {
        $diverged = @($Blobs | Where-Object { -not $knownBlobs.ContainsKey($_) })
        if ($diverged.Count -gt 0) {
            Add-Finding -Kind 'mirror-changed' -Repository ([string]$Record.Record.repository) `
                -Detail "a copy of $($Record.Lineage) now holds Facebook-family files the lineage doesn't" `
                -Evidence ([ordered]@{ hits = @($Candidate.Hits | Where-Object { $diverged -contains $_.blob }) })
        }
    } elseif ($Candidate.Packages.Count -gt 0) {
        # An index listing a recorded fork or an out-of-scope repository for a Facebook-family
        # package means it publishes a bundle of its own. The Morphe Archive keeps every fork's
        # patch list, so a fork only it lists counts for packages its entry doesn't target.
        $listers = @($Candidate.Sources | Where-Object { $_ -notlike '*code search' }) -join ', '
        $evidence = [ordered]@{ sources = @($Candidate.Sources); packages = @($Candidate.Packages); features = @($Candidate.Features) }
        if ($Record.Kind -eq 'fork') {
            $recordedPackages = @($Record.Entry.packages | ForEach-Object { "$_" })
            $newPackages = @($Candidate.Packages | Where-Object { $recordedPackages -notcontains $_ })
            $curated = @($Candidate.Sources | Where-Object { $_ -ne 'Morphe Archive' -and $_ -notlike '*code search' })
            if ($curated.Count -gt 0 -or $newPackages.Count -gt 0) {
                Add-Finding -Kind 'listed-as-bundle' -Repository "https://$Key" `
                    -Detail ("a recorded fork of $($Record.Lineage) that $listers lists as a bundle of its own for " +
                        (@($Candidate.Packages) -join ', ') + '; give it an entry of its own') -Evidence $evidence
            }
        } else {
            # An index that files a bundle under a package it doesn't patch stays settled once the
            # record's listedFor names that package, and only until the listing names another.
            $reviewed = @(Get-SourceProperty $Record.Record 'listedFor' | Where-Object { $null -ne $_ } | ForEach-Object { "$_" })
            $unreviewed = @($Candidate.Packages | Where-Object { $reviewed -notcontains $_ })
            if ($unreviewed.Count -gt 0) {
                Add-Finding -Kind 'listed-as-bundle' -Repository ([string]$Record.Record.repository) `
                    -Detail ("out of scope in the ledger, but $listers lists it as a bundle for " + ($unreviewed -join ', ') +
                        '; record it as an entry, or name the package in its listedFor once its patches are checked') -Evidence $evidence
            }
        }
    }
}

foreach ($candidate in @($candidates.Values | Sort-Object Key)) {
    $key = $candidate.Key
    $segments = @($key -split '/')
    if ($key -eq $selfKey -or ($segments[0] -eq 'github.com' -and $segments[1] -eq $selfOwner)) { continue }
    $blobs = @($candidate.Hits | ForEach-Object { $_.blob } | Where-Object { $_ } | Sort-Object -Unique)
    if ($known.ContainsKey($key)) {
        $record = $known[$key]
        if ($candidate.Hits.Count -gt 0) {
            $knownHits.Add([pscustomobject][ordered]@{ repository = "https://$key"; kind = $record.Kind; lineage = $record.Lineage
                hits = @($candidate.Hits | Sort-Object path -Unique) })
        }
        Test-KnownRepository -Candidate $candidate -Key $key -Record $record -Blobs $blobs
        continue
    }
    $unknownBlobs = @($blobs | Where-Object { -not $knownBlobs.ContainsKey($_) })
    if ($blobs.Count -gt 0 -and $unknownBlobs.Count -eq 0) {
        $lineage = @($blobs | ForEach-Object { $knownBlobs[$_] } | Group-Object | Sort-Object Count -Descending)[0].Name
        $collapsed.Add([pscustomobject][ordered]@{ repository = "https://$key"; lineage = $lineage; blobs = $blobs })
        continue
    }
    $meta = $null
    $where = Split-SourceKey $key
    if ($where.Host -eq 'github.com') {
        $answer = Invoke-SourceRequest -Uri "https://api.github.com/repos/$($where.Path)" -Auth github
        if ($answer.Status -eq 200) {
            $repo = ConvertFrom-SourceJson -Text $answer.Content -What $key
            # An index that still spells a renamed repository by its old name: GitHub redirects
            # the old name, and the repository it lands on is one the ledger knows.
            $resolved = ConvertTo-SourceKey "https://github.com/$($repo.full_name)"
            if ($resolved -and $resolved -ne $key -and $known.ContainsKey($resolved)) {
                $notes.Add("https://$key is an old name of https://$resolved, which the ledger knows")
                # Held to the same checks as a listing under the current name.
                Test-KnownRepository -Candidate $candidate -Key $resolved -Record $known[$resolved] -Blobs $blobs
                continue
            }
            $meta = [ordered]@{ fullName = [string]$repo.full_name; license = [string](Get-SourceProperty (Get-SourceProperty $repo 'license') 'spdx_id')
                fork = [bool]$repo.fork; parent = [string](Get-SourceProperty (Get-SourceProperty $repo 'parent') 'full_name')
                archived = [bool]$repo.archived; pushedAt = "$($repo.pushed_at)"; description = [string]$repo.description }
        } else {
            $meta = [ordered]@{ status = $answer.Status }
        }
    }
    Add-Finding -Kind 'addition' -Repository "https://$key" `
        -Detail ("found by " + ($candidate.Sources -join ', ') + " for " + (@(@($candidate.Packages) + @($candidate.Mentions) | Sort-Object -Unique) -join ', ') + ", and the ledger doesn't know it") `
        -Evidence ([ordered]@{ sources = @($candidate.Sources); packages = @($candidate.Packages); mentions = @($candidate.Mentions); features = @($candidate.Features)
            hits = $candidate.Hits.ToArray(); unknownBlobs = $unknownBlobs; repository = $meta })
}
foreach ($copy in $collapsed) {
    $notes.Add("$($copy.repository) is a byte-for-byte copy of files in the $($copy.lineage) lineage, so it counts as that lineage")
}

# --- every source the ledger holds ---------------------------------------------------------------

$sourceTrees = [System.Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
function Test-GitHubSourcePath {
    # Trees carry paths and blob ids, never file contents. Cache each immutable tree because
    # branches often share a head and a source can watch several paths in the same repository.
    param([string]$Path, [string]$Ref, [string]$Watch)
    $key = "$Path@$Ref"
    if (-not $sourceTrees.ContainsKey($key)) {
        $answer = Invoke-SourceRequest -Uri "https://api.github.com/repos/$Path/git/trees/$($Ref)?recursive=1" -Auth github
        if ($answer.Status -eq 404) { $sourceTrees[$key] = $null }
        elseif ($answer.Status -ne 200) { throw "the tree of $Path at $Ref answered HTTP $($answer.Status)" }
        else {
            $tree = ConvertFrom-SourceJson -Text $answer.Content -What "$Path tree"
            if ($null -eq $tree.PSObject.Properties['tree'] -or (Get-SourceProperty $tree 'truncated') -isnot [bool]) {
                throw "the tree of $Path at $Ref has no paths or truncation flag"
            }
            $paths = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
            foreach ($item in (Get-JsonItems $tree.tree)) { [void]$paths.Add([string]$item.path) }
            $sourceTrees[$key] = [pscustomobject]@{ Paths = $paths; Truncated = $tree.truncated }
        }
    }
    $tree = $sourceTrees[$key]
    if ($null -eq $tree) { return $false }
    if ($tree.Paths.Contains($Watch)) { return $true }
    if ($tree.Truncated) { throw "the tree of $Path at $Ref is truncated, so the absence of $Watch could not be checked" }
    return $false
}

function Invoke-SourceGit {
    <#
        The one place the audit runs git: it keeps a blobless mirror of a GitLab source and reads
        commits and folder listings from it. Clone and fetch must carry --filter=blob:none, and
        GIT_NO_LAZY_FETCH stops any other command from fetching a file it finds missing, so no file
        contents reach the disk. Credentials are never asked for or handed over. Answers ExitCode,
        Lines (standard output) and Errors.
    #>
    param([string]$Directory, [Parameter(Mandatory = $true)][string[]]$Arguments)
    $allowed = @('clone', 'fetch', 'for-each-ref', 'ls-tree', 'log', 'merge-base')
    if ($allowed -cnotcontains $Arguments[0]) { throw "The audit doesn't run git $($Arguments[0])." }
    if ($Arguments[0] -in @('clone', 'fetch') -and $Arguments -notcontains '--filter=blob:none') {
        throw "git $($Arguments[0]) without --filter=blob:none would download file contents."
    }
    $options = @('--literal-pathspecs', '-c', 'core.ignorecase=false', '-c', 'credential.helper=')
    if ($Directory) { $options += @('-C', $Directory) }
    # An empty value removes the variable, so no askpass helper inherited from an editor can prompt.
    $settings = [ordered]@{ GIT_TERMINAL_PROMPT = '0'; GCM_INTERACTIVE = 'never'; GIT_NO_LAZY_FETCH = '1'; GIT_ASKPASS = ''; SSH_ASKPASS = '' }
    $saved = @{}
    foreach ($name in $settings.Keys) {
        $saved[$name] = [Environment]::GetEnvironmentVariable($name)
        [Environment]::SetEnvironmentVariable($name, $settings[$name])
    }
    $preference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $said = @(& git @options @Arguments 2>&1)
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $preference
        foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name]) }
    }
    return [pscustomobject]@{ ExitCode = $code
        Lines = @($said | Where-Object { $_ -isnot [System.Management.Automation.ErrorRecord] } | ForEach-Object { "$_" })
        Errors = @($said | Where-Object { $_ -is [System.Management.Automation.ErrorRecord] } | ForEach-Object { "$_" }) }
}

$sourceMirrors = @{}
function Get-GitLabMirror {
    # A bare, blobless mirror of a GitLab source's branches, cloned once and fetched on each run
    # after. One that won't fetch is cloned again; one that won't clone fails the source.
    param([string]$Path)
    if ($sourceMirrors.ContainsKey($Path)) { return $sourceMirrors[$Path] }
    if ($Path -notmatch '^[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)+$' -or $Path -match '(^|/)\.{1,2}(/|$)') { throw "$Path is no GitLab project path." }
    $directory = Join-Path $mirrorRootPath ('gitlab.com\' + $Path.Replace('/', '\') + '.git')
    $synced = $null
    if (Test-Path -LiteralPath (Join-Path $directory 'HEAD')) {
        $synced = Invoke-SourceGit -Directory $directory -Arguments @('fetch', '--quiet', '--prune', '--no-tags', '--filter=blob:none', 'origin',
            '+refs/heads/*:refs/heads/*')
    }
    if ($null -eq $synced -or $synced.ExitCode -ne 0) {
        if (Test-Path -LiteralPath $directory) { Remove-Item -LiteralPath $directory -Recurse -Force }
        New-Item -ItemType Directory -Path (Split-Path -Parent $directory) -Force | Out-Null
        $synced = Invoke-SourceGit -Arguments @('clone', '--quiet', '--bare', '--no-tags', '--filter=blob:none', "https://gitlab.com/$Path.git", $directory)
        if ($synced.ExitCode -ne 0) { throw "the mirror of $Path could not be fetched: $(@($synced.Errors) -join ' ')" }
    }
    $sourceMirrors[$Path] = $directory
    return $directory
}

function Test-GitLabMirrorPath {
    # Whether $Watch is in the folder listing at $Ref, letter case and all.
    param([string]$Mirror, [string]$Ref, [string]$Watch)
    $listing = Invoke-SourceGit -Directory $Mirror -Arguments @('ls-tree', $Ref, '--', $Watch)
    if ($listing.ExitCode -ne 0) { throw "the folder listing at $Ref could not be read from the mirror: $(@($listing.Errors) -join ' ')" }
    return @($listing.Lines | Where-Object { ($_ -split "`t", 2)[-1] -ceq $Watch }).Count -gt 0
}

function Test-GitLabSourcePath {
    # Through the API, for the pins of a retired or unavailable source: GitLab keeps answering for a
    # commit a force push left behind, which a fresh mirror never holds.
    param([string]$Project, [string]$Ref, [string]$Watch)
    $at = $Watch.LastIndexOf('/')
    $parent = if ($at -ge 0) { $Watch.Substring(0, $at) } else { '' }
    $key = "$Project@$Ref/$parent"
    if (-not $sourceTrees.ContainsKey($key)) {
        $uri = "https://gitlab.com/api/v4/projects/$Project/repository/tree?ref=$([Uri]::EscapeDataString($Ref))"
        if ($parent) { $uri += '&path=' + [Uri]::EscapeDataString($parent) }
        $tree = Get-PagedItems -Uri $uri -Auth gitlab
        if ($tree.Status -eq 404) { $sourceTrees[$key] = @() }
        elseif ($tree.Status -ne 200) { throw "the tree of $Project at $Ref answered HTTP $($tree.Status)" }
        else { $sourceTrees[$key] = $tree.Items.ToArray() }
    }
    return @($sourceTrees[$key] | Where-Object { [string]$_.path -ceq $Watch }).Count -gt 0
}

function Test-SourcePin {
    param($Where, $Entry, [string]$Commit)
    $watch = @(Get-SourceProperty $Entry 'watchPaths' | Where-Object { $_ })
    if ($watch.Count -gt 0) {
        foreach ($path in $watch) {
            $present = if ($Where.Host -eq 'github.com') {
                Test-GitHubSourcePath -Path $Where.Path -Ref $Commit -Watch $path
            } else {
                Test-GitLabSourcePath -Project ([Uri]::EscapeDataString($Where.Path)) -Ref $Commit -Watch $path
            }
            if ($present) { return $true }
        }
        return $false
    }
    $uri = if ($Where.Host -eq 'github.com') { "https://api.github.com/repos/$($Where.Path)/git/commits/$Commit" } else {
        "https://gitlab.com/api/v4/projects/$([Uri]::EscapeDataString($Where.Path))/repository/commits/$Commit" }
    $answer = Invoke-SourceRequest -Uri $uri -Auth $(if ($Where.Host -eq 'github.com') { 'github' } else { 'gitlab' })
    if ($answer.Status -notin @(200, 404)) { throw "the pin $Commit answered HTTP $($answer.Status)" }
    return $answer.Status -eq 200
}

function Get-GitHubFacebookHead {
    param([string]$Path, [string]$Branch, [string[]]$WatchPaths, [string]$Head)
    if (@($WatchPaths).Count -eq 0) { return $Head }
    $newest = $null
    foreach ($watch in $WatchPaths) {
        if (-not (Test-GitHubSourcePath -Path $Path -Ref $Head -Watch $watch)) { continue }
        $answer = Invoke-SourceRequest -Uri ("https://api.github.com/repos/$Path/commits?sha=" + [Uri]::EscapeDataString($Branch) +
            '&path=' + [Uri]::EscapeDataString($watch) + '&per_page=1') -Auth github
        if ($answer.Status -ne 200) { throw "the commits of $Branch under $watch answered HTTP $($answer.Status)" }
        foreach ($commit in (Get-JsonItems (ConvertFrom-SourceJson -Text $answer.Content -What "$Path commits"))) {
            $date = [datetime]::Parse("$($commit.commit.committer.date)", [Globalization.CultureInfo]::InvariantCulture,
                [Globalization.DateTimeStyles]::AdjustToUniversal)
            if ($null -eq $newest -or $date -gt $newest.Date) { $newest = [pscustomobject]@{ Sha = [string]$commit.sha; Date = $date } }
        }
    }
    if ($null -eq $newest) { return $null }
    return $newest.Sha
}

function Get-GitLabFacebookHead {
    param([string]$Mirror, [string]$Branch, [string[]]$WatchPaths, [string]$Head)
    if (@($WatchPaths).Count -eq 0) { return $Head }
    $newest = $null
    foreach ($watch in $WatchPaths) {
        if (-not (Test-GitLabMirrorPath -Mirror $Mirror -Ref $Head -Watch $watch)) { continue }
        $history = Invoke-SourceGit -Directory $Mirror -Arguments @('log', '-1', '--format=%H%x09%cI', $Head, '--', $watch)
        if ($history.ExitCode -ne 0) { throw "the commits of $Branch under $watch could not be read from the mirror: $(@($history.Errors) -join ' ')" }
        foreach ($line in $history.Lines) {
            $sha, $when = $line -split "`t", 2
            if ($sha -notmatch '^[0-9a-f]{40}$') { throw "the commits of $Branch under $watch came back as '$line'" }
            $date = [datetimeoffset]::Parse($when, [Globalization.CultureInfo]::InvariantCulture).UtcDateTime
            if ($null -eq $newest -or $date -gt $newest.Date) { $newest = [pscustomobject]@{ Sha = $sha; Date = $date } }
        }
    }
    if ($null -eq $newest) { return $null }
    return $newest.Sha
}

function Test-CommitContained {
    # Whether $Commit is $Within or one of its ancestors. A commit either forge can't compare (a
    # pin rewritten away by a force push, say) counts as not contained, so it gets looked at.
    param($Where, [string]$Commit, [string]$Within)
    if ($Commit -notmatch '^[0-9a-f]{40}$' -or $Within -notmatch '^[0-9a-f]{40}$') { return $false }
    if ($Where.Host -eq 'github.com') {
        $answer = Invoke-SourceRequest -Uri "https://api.github.com/repos/$($Where.Path)/compare/$Within...$Commit" -Auth github
        if ($answer.Status -ne 200) { return $false }
        $status = [string](ConvertFrom-SourceJson -Text $answer.Content -What "$($Where.Path) comparison").status
        return $status -eq 'identical' -or $status -eq 'behind'
    }
    # The mirror answers 1 for "not an ancestor" and 128 for a commit it doesn't hold.
    $answer = Invoke-SourceGit -Directory (Get-GitLabMirror -Path $Where.Path) -Arguments @('merge-base', '--is-ancestor', $Commit, $Within)
    return $answer.ExitCode -eq 0
}

function Read-GitHubSource {
    param($Entry, [string]$Path)
    $answer = Invoke-SourceRequest -Uri "https://api.github.com/repos/$Path" -Auth github
    if ($answer.Status -ne 200) { return [pscustomobject]@{ Status = $answer.Status } }
    $repo = ConvertFrom-SourceJson -Text $answer.Content -What $Path
    $license = $null
    $licenseAnswer = Invoke-SourceRequest -Uri "https://api.github.com/repos/$Path/license" -Auth github
    if ($licenseAnswer.Status -eq 200) {
        $document = ConvertFrom-SourceJson -Text $licenseAnswer.Content -What "$Path licence"
        $license = [pscustomobject]@{ Spdx = [string](Get-SourceProperty $document.license 'spdx_id'); Path = [string]$document.path
            Sha256 = Get-Sha256Hex ([Convert]::FromBase64String((([string]$document.content) -replace '\s', ''))) }
    } elseif ($licenseAnswer.Status -ne 404) {
        throw "the licence of $Path answered HTTP $($licenseAnswer.Status)"
    }
    $branchList = Get-PagedItems -Uri "https://api.github.com/repos/$Path/branches" -Auth github
    if ($branchList.Status -ne 200) { throw "the branches of $Path answered HTTP $($branchList.Status)" }
    $forkList = Get-PagedItems -Uri "https://api.github.com/repos/$Path/forks" -Auth github
    if ($forkList.Status -ne 200) { throw "the forks of $Path answered HTTP $($forkList.Status)" }
    $watch = @(Get-SourceProperty $Entry 'watchPaths' | Where-Object { $_ })
    $branches = [ordered]@{}
    foreach ($branch in $branchList.Items) {
        $branches[[string]$branch.name] = [pscustomobject]@{ Head = [string]$branch.commit.sha
            Facebook = (Get-GitHubFacebookHead -Path $Path -Branch ([string]$branch.name) -WatchPaths $watch -Head ([string]$branch.commit.sha)) }
    }
    return [pscustomobject]@{ Status = 200; FullName = "github.com/$($repo.full_name)".ToLowerInvariant(); Archived = [bool]$repo.archived
        License = $license; Branches = $branches; Forks = @($forkList.Items | ForEach-Object { [string]$_.full_name }) }
}

function Read-GitLabSource {
    param($Entry, [string]$Path)
    $project = [Uri]::EscapeDataString($Path)
    $answer = Invoke-SourceRequest -Uri "https://gitlab.com/api/v4/projects/$project" -Auth gitlab
    if ($answer.Status -ne 200) { return [pscustomobject]@{ Status = $answer.Status } }
    $repo = ConvertFrom-SourceJson -Text $answer.Content -What $Path
    $defaultBranch = [string]$repo.default_branch
    $license = $null
    $tree = Get-PagedItems -Uri ("https://gitlab.com/api/v4/projects/$project/repository/tree?ref=" + [Uri]::EscapeDataString($defaultBranch)) -Auth gitlab
    if ($tree.Status -ne 200) { throw "the file list of $Path answered HTTP $($tree.Status)" }
    $licenseFile = @($tree.Items | Where-Object { $_.type -eq 'blob' -and "$($_.name)" -match '^(?i)(LICEN[CS]E|COPYING)(\.(md|txt))?$' } | Select-Object -First 1)
    if ($licenseFile.Count -gt 0) {
        $file = Get-SourceJson -Uri ("https://gitlab.com/api/v4/projects/$project/repository/files/" +
            [Uri]::EscapeDataString([string]$licenseFile[0].path) + '?ref=' + [Uri]::EscapeDataString($defaultBranch)) -Auth gitlab -What "$Path licence"
        $license = [pscustomobject]@{ Spdx = $null; Path = [string]$licenseFile[0].path
            Sha256 = Get-Sha256Hex ([Convert]::FromBase64String((([string]$file.content) -replace '\s', ''))) }
    }
    $forkList = Get-PagedItems -Uri "https://gitlab.com/api/v4/projects/$project/forks" -Auth gitlab
    if ($forkList.Status -ne 200) { throw "the forks of $Path answered HTTP $($forkList.Status)" }
    $mirror = Get-GitLabMirror -Path $Path
    $heads = Invoke-SourceGit -Directory $mirror -Arguments @('for-each-ref', '--format=%(refname:strip=2)%09%(objectname)', 'refs/heads')
    if ($heads.ExitCode -ne 0) { throw "the branches of $Path could not be read from its mirror: $(@($heads.Errors) -join ' ')" }
    $watch = @(Get-SourceProperty $Entry 'watchPaths' | Where-Object { $_ })
    $branches = [ordered]@{}
    foreach ($line in $heads.Lines) {
        $name, $head = $line -split "`t", 2
        if ($head -notmatch '^[0-9a-f]{40}$') { throw "a branch of $Path came back from its mirror as '$line'" }
        $branches[$name] = [pscustomobject]@{ Head = $head
            Facebook = (Get-GitLabFacebookHead -Mirror $mirror -Branch $name -WatchPaths $watch -Head $head) }
    }
    # GitLab renames a project waiting to be deleted after its own id. A scanner forks the mirror and deletes the fork again every few hours, faster than any census could record them.
    $keptForks = @($forkList.Items | Where-Object { [string]$_.path_with_namespace -cnotlike "*-deletion_scheduled-$($_.id)" })
    return [pscustomobject]@{ Status = 200; FullName = "gitlab.com/$($repo.path_with_namespace)".ToLowerInvariant(); Archived = [bool]$repo.archived
        License = $license; Branches = $branches; Forks = @($keptForks | ForEach-Object { [string]$_.path_with_namespace }) }
}

$forkCount = 0
foreach ($entry in $entries) {
    $repository = [string]$entry.repository
    $key = ConvertTo-SourceKey $repository
    $where = Split-SourceKey $key
    Invoke-Source $repository {
        $live = if ($where.Host -eq 'github.com') { Read-GitHubSource -Entry $entry -Path $where.Path } else { Read-GitLabSource -Entry $entry -Path $where.Path }
        $availability = Get-SourceAvailability $entry
        $pins = @(Get-SourceProperty $entry 'branches' | Where-Object { $_ })
        if ($availability -ne 'active') {
            foreach ($commit in @($pins | ForEach-Object { [string]$_.commit } | Sort-Object -Unique)) {
                $present = Test-SourcePin -Where $where -Entry $entry -Commit $commit
                if ($availability -eq 'retired' -and -not $present) {
                    Add-Finding -Kind 'historical-pin-unavailable' -Repository $repository `
                        -Detail "retired source pin $commit no longer contains its recorded Facebook-family paths; record it as unavailable" `
                        -Evidence ([ordered]@{ commit = $commit; watchPaths = @($entry.watchPaths) })
                } elseif ($availability -eq 'unavailable' -and $present) {
                    Add-Finding -Kind 'historical-pin-restored' -Repository $repository `
                        -Detail "unavailable source pin $commit is reachable again; review its availability" -Evidence ([ordered]@{ commit = $commit })
                }
            }
        }
        if ($live.Status -ne 200) {
            if ($availability -eq 'unavailable' -and $live.Status -eq 404) { return 'unavailable as recorded (HTTP 404)' }
            Add-Finding -Kind 'repository-gone' -Repository $repository -Detail "the repository answers HTTP $($live.Status)" -Evidence $null
            return "gone (HTTP $($live.Status))"
        }
        if ($live.FullName -ne $key) {
            Add-Finding -Kind 'renamed' -Repository $repository -Detail "the repository is now https://$($live.FullName)" -Evidence $null
        }
        if ($live.Archived -ne [bool]$entry.archived) {
            Add-Finding -Kind 'archived-changed' -Repository $repository `
                -Detail $(if ($live.Archived) { 'the repository was archived' } else { 'the repository is no longer archived' }) -Evidence $null
        }
        $recordedLicense = Get-SourceProperty $entry 'license'
        if ($null -ne $recordedLicense -and $null -eq $live.License) {
            Add-Finding -Kind 'license-removed' -Repository $repository -Detail "the $($recordedLicense.spdx) licence the ledger records is gone" -Evidence $null
        } elseif ($null -eq $recordedLicense -and $null -ne $live.License) {
            Add-Finding -Kind 'license-added' -Repository $repository -Detail "the repository now has a licence ($($live.License.Spdx), $($live.License.Path))" `
                -Evidence ([ordered]@{ spdx = $live.License.Spdx; path = $live.License.Path; sha256 = $live.License.Sha256 })
        } elseif ($null -ne $recordedLicense -and $live.License.Sha256 -ne [string]$recordedLicense.sha256) {
            Add-Finding -Kind 'license-changed' -Repository $repository -Detail "the licence text changed ($($live.License.Path))" `
                -Evidence ([ordered]@{ spdx = $live.License.Spdx; path = $live.License.Path; recorded = [string]$recordedLicense.sha256
                    sha256 = $live.License.Sha256 })
        }
        $pinnedCommits = @($pins | ForEach-Object { [string]$_.commit })
        $pinnedNames = @($pins | ForEach-Object { [string]$_.name })
        if ($availability -ne 'active') {
            foreach ($name in @($live.Branches.Keys)) {
                if ($live.Branches[$name].Facebook) {
                    Add-Finding -Kind 'source-reactivated' -Repository $repository `
                        -Detail "$availability source has Facebook-family code on branch $name again" `
                        -Evidence ([ordered]@{ branch = $name; facebookHead = $live.Branches[$name].Facebook; head = $live.Branches[$name].Head })
                }
            }
        }
        foreach ($pin in $pins) {
            if ($availability -ne 'active') { continue }
            $name = [string]$pin.name
            if (-not $live.Branches.Contains($name)) {
                Add-Finding -Kind 'branch-removed' -Repository $repository -Detail "branch $name is gone" -Evidence $null
                continue
            }
            $now = $live.Branches[$name]
            if ($now.Facebook -ne [string]$pin.commit) {
                Add-Finding -Kind 'changed-head' -Repository $repository `
                    -Detail ("branch $name moved from $([string]$pin.commit) to " + $(if ($now.Facebook) { $now.Facebook } else { 'no Facebook-family code' })) `
                    -Evidence ([ordered]@{ branch = $name; pinned = [string]$pin.commit; facebookHead = $now.Facebook; head = $now.Head })
            }
        }
        foreach ($name in @($live.Branches.Keys)) {
            if ($availability -ne 'active') { continue }
            if ($pinnedNames -contains $name) { continue }
            $now = $live.Branches[$name]
            if (-not $now.Facebook -or $pinnedCommits -contains $now.Facebook) { continue }
            # A branch cut from a pinned one before its Facebook-family code last moved carries
            # nothing new, and busy repositories keep dozens of those.
            $contained = $false
            foreach ($pinned in $pinnedCommits) {
                if (Test-CommitContained -Where $where -Commit $now.Facebook -Within $pinned) { $contained = $true; break }
            }
            if (-not $contained) {
                Add-Finding -Kind 'new-branch' -Repository $repository -Detail "branch $name carries Facebook-family work the ledger hasn't pinned" `
                    -Evidence ([ordered]@{ branch = $name; facebookHead = $now.Facebook; head = $now.Head })
            }
        }
        $recordedForks = @(Get-SourceProperty $entry 'forks' | Where-Object { $_ } | ForEach-Object { "$_".ToLowerInvariant() })
        foreach ($fork in $live.Forks) {
            if ($recordedForks -notcontains $fork.ToLowerInvariant()) {
                Add-Finding -Kind 'unseen-fork' -Repository $repository -Detail "fork $fork isn't in the ledger" -Evidence ([ordered]@{ fork = $fork })
            }
        }
        foreach ($fork in $recordedForks) {
            if (@($live.Forks | ForEach-Object { $_.ToLowerInvariant() }) -notcontains $fork) { $notes.Add("$repository no longer lists the fork $fork") }
        }
        $script:forkCount += @($live.Forks).Count
        "$availability source, $(@($live.Branches.Keys).Count) branches, $(@($live.Forks).Count) forks, licence " +
            $(if ($live.License) { $live.License.Sha256.Substring(0, 12) } else { 'none' })
    }

    $adopted = Get-SourceProperty $entry 'adopted'
    if ($null -ne $adopted) {
        Invoke-Source "fixture evidence for $repository" {
            $receiptUri = [string]$adopted.fixtures.receipt
            $receipt = Get-SourceJson -Uri $receiptUri -What 'the release receipt'
            foreach ($build in @($adopted.fixtures.builds)) {
                # "name" matches any run of that version; "name (versionCode)" only the run with that code.
                $wanted = ConvertTo-SourceFixtureBuild $build
                $proved = @(Get-JsonItems $receipt.targets | Where-Object {
                    [string]$_.source.versionName -eq $wanted.Name -and
                        (-not $wanted.Code -or [string]$_.source.versionCode -eq $wanted.Code) -and
                        -not [bool]$_.source.forced -and
                        @(Get-JsonItems $_.patches | Where-Object { -not [bool]$_.applied }).Count -eq 0 })
                if ($proved.Count -eq 0) {
                    Add-Finding -Kind 'fixture-evidence' -Repository $repository `
                        -Detail "the receipt $receiptUri has no unforced run of Facebook $build with every patch applied" -Evidence $null
                }
            }
            "receipt $([IO.Path]::GetFileName($receiptUri)) read for $(@($adopted.fixtures.builds) -join ', ')"
        }
    }
}

# --- Hushfacebook's own listings -----------------------------------------------------------------

foreach ($index in @($ledgerDocument.indexes)) {
    $id = [string]$index.id
    if (-not $listed.ContainsKey($id)) { continue }
    $status = [string]$index.hushfacebook.status
    if ($listed[$id] -and $status -ne 'listed') {
        Add-Finding -Kind 'listing-changed' -Repository $null -Detail "$($index.name) lists Hushfacebook now; record the listing URL" -Evidence ([ordered]@{ index = $id; recorded = $status })
    } elseif (-not $listed[$id] -and $status -eq 'listed') {
        Add-Finding -Kind 'listing-changed' -Repository $null -Detail "$($index.name) no longer lists Hushfacebook" -Evidence ([ordered]@{ index = $id; recorded = $status })
    } elseif (-not $listed[$id]) {
        $what = if ($status -eq 'submitted') { "submitted $($index.hushfacebook.submitted), not listed yet" } else { 'not listed, and no submission is recorded' }
        $notes.Add("$($index.name): Hushfacebook is $what")
    }
}

# --- the report, and the stamp -------------------------------------------------------------------

$lineages = @($entries | Group-Object { [string]$_.lineage } | Sort-Object Name | ForEach-Object {
    $lineage = $_.Name
    [ordered]@{
        lineage = $lineage
        sources = @($_.Group | ForEach-Object { [ordered]@{ repository = [string]$_.repository; disposition = [string]$_.disposition
            availability = Get-SourceAvailability $_; targetVersions = $_.targetVersions } })
        mirrors = @(@($_.Group | ForEach-Object { Get-SourceProperty $_ 'mirrors' } | Where-Object { $_ } | ForEach-Object { [string]$_.repository }) +
            @($collapsed | Where-Object { $_.lineage -eq $lineage } | ForEach-Object { $_.repository }))
    }
})
$counts = [ordered]@{
    sources = $entries.Count
    activeSources = $availabilityCounts.activeSources
    retiredSources = $availabilityCounts.retiredSources
    unavailableSources = $availabilityCounts.unavailableSources
    historicalSources = $availabilityCounts.historicalSources
    lineages = $lineageNames.Count
    mirrorsRecorded = @($entries | ForEach-Object { Get-SourceProperty $_ 'mirrors' } | Where-Object { $_ }).Count
    mirrorsCollapsed = $collapsed.Count
    forks = $forkCount
    candidatesFound = $candidates.Count
    requests = $requestCount
    findings = $findings.Count
}
$clean = $findings.Count -eq 0 -and @($sourceStatus | Where-Object {
    $_.status -ne 'read' -and -not ($_.source -eq 'GitLab code search' -and $_.status -eq 'skipped') }).Count -eq 0
Save-Report -Clean $clean -Counts $counts -Lineages $lineages -KnownHits $knownHits
Write-Step ("$($entries.Count) sources in $($lineageNames.Count) lineages, $($counts.mirrorsRecorded) recorded mirrors, " +
    "$($collapsed.Count) more collapsed by content, $forkCount forks; " +
    "$($counts.activeSources) active, $($counts.retiredSources) retired, $($counts.unavailableSources) unavailable; " +
    "$($candidates.Count) repositories found by the indexes and code search")
foreach ($note in $notes) { Write-Step "note: $note" }
if (-not $clean) {
    foreach ($finding in $findings) {
        $who = if ($finding.repository) { "$($finding.repository): " } else { '' }
        Write-Step "$($finding.kind): $who$($finding.detail)"
    }
    Write-Step "$($findings.Count) finding(s). Settle them in the ledger and run the audit again; the census was not stamped."
    exit 1
}

if ($NoStamp) {
    Write-Step 'nothing moved; -NoStamp left the ledger as it was'
    exit 0
}
$skipped = if ($searchGitLab) { @() } else { @('gitlab-code-search') }
$text = [IO.File]::ReadAllText($ledgerPath)
$stamped = $text -replace '("(?:lastChecked|checked|checkedAt)"\s*:\s*")\d{4}-\d{2}-\d{2}(")', ('${1}' + $todayText + '${2}')
# Only rewritten when it changes, so the file keeps the layout it was written with.
if ((@($ledgerDocument.census.skipped | Where-Object { $_ }) -join ',') -ne ($skipped -join ',')) {
    $skippedJson = '[' + (@($skipped | ForEach-Object { '"' + $_ + '"' }) -join ', ') + ']'
    $stamped = $stamped -replace '("skipped"\s*:\s*)\[[^\]]*\]', ('${1}' + $skippedJson)
}
$check = Test-SourceLedger -Ledger ($stamped | ConvertFrom-Json) -Root $rootPath -Today $todayText
if (-not $check.Valid) { throw "Stamping the ledger broke it, so it was left as it was: $($check.Problems[0])" }
[IO.File]::WriteAllText($ledgerPath, $stamped, (New-Object Text.UTF8Encoding $false))
Write-Step "nothing moved; the census and every record it checked are stamped $todayText"
exit 0
