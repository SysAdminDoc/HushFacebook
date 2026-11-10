<#
.SYNOPSIS
    The release provenance receipt: how it is built, and what makes one valid.

.DESCRIPTION
    Dot-sourced by build-release-receipt.ps1 and validate-release-facts.ps1. A checksum on its
    own says a file has not changed since somebody hashed it. It cannot say which APK the patches
    were proved against, which commit built the bundle, which toolchain stamped it, or what the
    patches did to the Android manifest, and those are the facts that decide whether a local
    patch run is the one the release describes.

    Everything here is read back out of files rather than taken on trust: the APK hashes off the
    fixtures, the bundle hash and size off the .mpp, the patch verdicts out of the desktop CLI's
    own result report, and the manifest delta out of aapt2's reading of both APKs.

    The manifest delta is the part that needs a human. A patch that adds a permission or exports
    a component changes what the patched app can do and what other apps can reach, so every entry
    has to be written down in scripts/manifest-delta-allowlist.txt and stays until somebody takes
    it out. An entry nothing produces any more fails the run too: an allowlist that outlives its
    reason stops being a review.

    The SBOM :patches:releaseSbom writes beside the bundle says what the bundle carries: every
    library and the version it resolved to. The receipt records its hash, and the SBOM records the
    bundle's, so neither can be swapped for another build's without the pair coming apart.

    What a bundle says about its own build is the one fact taken from the build's word. A release
    builds its commit a second time for that, and Compare-BundleContent and Test-RebuiltBundle hold
    the staged bundle to the second build entry by entry.
#>

function Get-ReleaseReceiptSchemaVersion {
    <#
    .SYNOPSIS
        Bumped when the shape changes in a way a reader has to know about.
    .DESCRIPTION
        Validation refuses a receipt written to a different version rather than guessing which
        fields moved. A function rather than a variable because this file is dot-sourced into
        several scripts, and a script-scoped variable in a dot-sourced file belongs to whichever
        one sourced it. Resolve-ReceiptSchema reads the number out of this function's body at an
        older commit, so it stays a bare return.

        2 added sbom: the file name, SHA-256 and component count of the release SBOM.
        3 added a separate tooling graph and its advisory-check status.
    #>
    return 3
}

function Resolve-ReceiptSchema {
    <#
    .SYNOPSIS
        The schema a receipt should be held to: the one its own commit's builder wrote.
    .DESCRIPTION
        A receipt describes a release that has shipped, and one cut before schema 2 has no SBOM
        to name. Holding it to schema 2 would refuse every later push from the checkout that cut
        it, which is the trap Resolve-ReceiptToolchain describes for the patcher pin. So the
        number is read out of scripts/release-receipt.ps1 at the receipt's commit. On a release
        push the receipt's commit is the commit being released, whose builder writes this
        checkout's schema, so nothing is relaxed for a new release.

        A commit with no receipt script this can read is held to this checkout's schema, and one
        whose script writes a newer schema than this checkout reads throws. Answers
        @{ Version; Note }, where Note is a line worth printing or $null.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [string]$Commit
    )

    $current = Get-ReleaseReceiptSchemaVersion
    if ($Commit -notmatch '^[0-9a-f]{40}$') { return [pscustomobject]@{ Version = $current; Note = $null } }
    $script = (Invoke-RepoGit -Root $Root -Arguments @('show', "${Commit}:scripts/release-receipt.ps1")) -join "`n"
    $written = [regex]::Match($script, '(?s)function\s+Get-ReleaseReceiptSchemaVersion\b.*?#>\s*return\s+(\d+)\s*\}')
    if (-not $written.Success) { return [pscustomobject]@{ Version = $current; Note = $null } }
    $version = [int]$written.Groups[1].Value
    $short = $Commit.Substring(0, 8)
    if ($version -gt $current) {
        throw "Commit $short writes receipt schema $version, newer than the $current this checkout reads."
    }
    if ($version -eq $current) { return [pscustomobject]@{ Version = $version; Note = $null } }
    return [pscustomobject]@{
        Version = $version
        Note = if ($version -lt 2) {
            "the receipt is held to schema $version, which its own commit $short wrote, so it names no SBOM and none is checked for its release"
        } else {
            "the receipt is held to schema $version, which its own commit $short wrote; its payload SBOM is checked, but it predates tooling reports"
        }
    }
}

function ConvertTo-UtcStamp {
    <#
    .SYNOPSIS
        A date read out of JSON, as yyyy-MM-ddTHH:mm:ssZ in either shell.
    .DESCRIPTION
        PowerShell 7's ConvertFrom-Json turns an ISO 8601 string into a DateTime and Windows
        PowerShell leaves it a string, so a date is compared in this one form.
    #>
    param($Value)
    if ($Value -is [datetime]) {
        return $Value.ToUniversalTime().ToString("yyyy-MM-dd'T'HH:mm:ss'Z'", [Globalization.CultureInfo]::InvariantCulture)
    }
    return [string]$Value
}

function Get-SbomSha256 {
    <#
    .SYNOPSIS
        The SHA-256 a CycloneDX component records for itself, in lower case, or $null.
    #>
    param($Component, [string]$Label)

    $recorded = @(@($Component.hashes) | Where-Object { $null -ne $_ -and "$($_.alg)" -eq 'SHA-256' })
    if ($recorded.Count -gt 1) { throw "$Label records two SHA-256 hashes." }
    if ($recorded.Count -eq 0) { return $null }
    $value = "$($recorded[0].content)".ToLowerInvariant()
    if ($value -notmatch '^[0-9a-f]{64}$') { throw "$Label records a SHA-256 that isn't one: $($recorded[0].content)" }
    return $value
}

function Read-ReleaseSbom {
    <#
    .SYNOPSIS
        The CycloneDX SBOM :patches:releaseSbom writes beside the bundle, read into what the receipt
        and the advisory gate need.
    .DESCRIPTION
        Answers @{ Path; Sha256; BundleName; BundleVersion; BundleSha256; Timestamp; Components;
        Libraries; Payloads }. Libraries are the components with a package URL, the ones OSV is
        asked about, and Payloads the extension payload files with their SHA-256.

        Held to the shape the task writes, and refused where it differs rather than read around.
        A library whose package URL isn't a Maven one naming its own group, name and version
        would be asked about under a name OSV answers {} for, which reads as no advisories. So
        would a library with no package URL at all, which only the modules built from this
        repository may be, marked hushfacebook:first-party.
    #>
    param([Parameter(Mandatory = $true)][string]$Path)

    $Path = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "There is no SBOM at $Path." }
    $name = Split-Path -Leaf $Path
    try {
        $document = [System.IO.File]::ReadAllText($Path) | ConvertFrom-Json
    } catch {
        throw "$name is not JSON: $($_.Exception.Message)"
    }
    if ("$($document.bomFormat)" -ne 'CycloneDX' -or "$($document.specVersion)" -ne '1.6') {
        throw "$name is not a CycloneDX 1.6 SBOM."
    }
    $subject = $document.metadata.component
    $subjectHash = if ($subject) { Get-SbomSha256 -Component $subject -Label "$name's bundle" } else { $null }
    if (-not $subject -or -not $subject.name -or -not $subject.version -or -not $subjectHash) {
        throw "$name does not name the bundle it describes, its version and its SHA-256."
    }

    $components = New-Object System.Collections.Generic.List[object]
    $refs = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($component in @($document.components | Where-Object { $null -ne $_ })) {
        $ref = [string]$component.'bom-ref'
        if (-not $ref) { throw "$name lists a component with no bom-ref." }
        if (-not $refs.Add($ref)) { throw "$name lists $ref twice." }
        $entry = [pscustomobject]@{
            Ref        = $ref
            Type       = [string]$component.type
            Group      = [string]$component.group
            Name       = [string]$component.name
            Version    = [string]$component.version
            Purl       = [string]$component.purl
            FirstParty = @(@($component.properties) | Where-Object { $null -ne $_ -and "$($_.name)" -eq 'hushfacebook:first-party' }).Count -gt 0
            Sha256     = Get-SbomSha256 -Component $component -Label "$name's $ref"
        }
        switch ($entry.Type) {
            'library' {
                if ($entry.Purl) {
                    $purl = [regex]::Match($entry.Purl, '^pkg:maven/([^/@?#]+)/([^/@?#]+)@([^/@?#]+)$')
                    if (-not $purl.Success -or [Uri]::UnescapeDataString($purl.Groups[1].Value) -cne $entry.Group -or
                            [Uri]::UnescapeDataString($purl.Groups[2].Value) -cne $entry.Name -or
                            [Uri]::UnescapeDataString($purl.Groups[3].Value) -cne $entry.Version) {
                        throw ("$name lists $ref with the package URL $($entry.Purl), which doesn't name its own " +
                            'group, name and version, so OSV couldn''t be asked about it.')
                    }
                } elseif (-not $entry.FirstParty) {
                    throw "$name lists the library $ref with no package URL, so OSV couldn't be asked about it."
                }
            }
            'file' {
                if (-not $entry.Sha256) { throw "$name lists the file $ref with no SHA-256." }
            }
            default { throw "$name lists $ref as a $($entry.Type), which a release SBOM doesn't hold." }
        }
        $components.Add($entry)
    }
    if ($components.Count -eq 0) { throw "$name lists no component." }

    return [pscustomobject]@{
        Path          = $Path
        Sha256        = Get-Sha256Hex -Path $Path
        BundleName    = [string]$subject.name
        BundleVersion = [string]$subject.version
        BundleSha256  = $subjectHash
        Timestamp     = ConvertTo-UtcStamp $document.metadata.timestamp
        Components    = $components.ToArray()
        Libraries     = @($components | Where-Object { $_.Type -eq 'library' -and $_.Purl })
        Payloads      = @($components | Where-Object { $_.Type -eq 'file' })
    }
}

function Test-ReleaseSbom {
    <#
    .SYNOPSIS
        Whether an SBOM describes this bundle: @{ Valid; Reason }.
    .DESCRIPTION
        The bundle it names and that bundle's SHA-256, the version and pinned timestamp in the
        bundle's manifest, and every extension payload the bundle carries with the SHA-256 of its
        bytes. An SBOM left beside a newer build fails on the hash, and one dated from the clock
        rather than from the pinned stamp fails on the timestamp. -BundleName is the name the
        bundle is published under, since a downloaded copy has a temporary one. -ExpectedCommit is
        the clean commit a bundle carrying a build identity must name; a legacy bundle has none.
    #>
    param(
        [Parameter(Mandatory = $true)]$Sbom,
        [Parameter(Mandatory = $true)][string]$BundlePath,
        [Parameter(Mandatory = $true)][string]$BundleName,
        [string]$ExpectedCommit
    )

    function Fail { param([string]$Reason) return [pscustomobject]@{ Valid = $false; Reason = $Reason } }

    $sbomName = Split-Path -Leaf $Sbom.Path
    if ($Sbom.BundleName -cne $BundleName) { return Fail "$sbomName describes $($Sbom.BundleName), not $BundleName." }
    $BundlePath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($BundlePath)
    $bundleHash = (Get-Sha256Hex -Path $BundlePath).ToLowerInvariant()
    if ($Sbom.BundleSha256 -ne $bundleHash) {
        return Fail ("$sbomName describes a $BundleName that hashes to $($Sbom.BundleSha256), and the bundle " +
            "hashes to $bundleHash. It was written for another build.")
    }
    $manifest = Get-BundleManifestFacts -BundlePath $BundlePath
    $identity = Get-BundleIdentityFacts -BundlePath $BundlePath
    if ($identity.Present -and -not $ExpectedCommit) {
        return Fail "$BundleName carries a build identity, and no source commit was given to hold it to."
    }
    if ($identity.Present -and (-not $identity.Valid -or $identity.SourceState -cne 'clean' -or
            $identity.SourceCommit -cne $ExpectedCommit)) { return Fail 'The bundle build identity does not bind to this clean source commit.' }
    if ($Sbom.BundleVersion -ne $manifest.version) {
        return Fail "$sbomName says $BundleName is version $($Sbom.BundleVersion); its manifest says $($manifest.version)."
    }
    $stamp = [DateTimeOffset]::FromUnixTimeMilliseconds($manifest.timestamp).UtcDateTime.ToString(
        "yyyy-MM-dd'T'HH:mm:ss'Z'", [Globalization.CultureInfo]::InvariantCulture)
    if ($Sbom.Timestamp -ne $stamp) {
        return Fail ("$sbomName is dated $($Sbom.Timestamp), and $BundleName is stamped $stamp. The build " +
            'that pinned the bundle did not write it.')
    }

    $carried = [System.Collections.Generic.Dictionary[string, string]]::new([System.StringComparer]::Ordinal)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($BundlePath)
    try {
        foreach ($entry in @($archive.Entries | Where-Object { $_.FullName -like 'extensions/*.mpe' })) {
            $stream = $entry.Open()
            $sha = [System.Security.Cryptography.SHA256]::Create()
            try {
                $carried[$entry.FullName] = ($sha.ComputeHash($stream) | ForEach-Object { '{0:x2}' -f $_ }) -join ''
            } finally {
                $sha.Dispose()
                $stream.Dispose()
            }
        }
    } finally {
        $archive.Dispose()
    }
    $described = [System.Collections.Generic.Dictionary[string, string]]::new([System.StringComparer]::Ordinal)
    foreach ($payload in @($Sbom.Payloads)) { $described[$payload.Ref] = $payload.Sha256 }
    foreach ($payload in @($carried.Keys | Sort-Object)) {
        if (-not $described.ContainsKey($payload)) { return Fail "$BundleName carries $payload, which $sbomName doesn't describe." }
        if ($described[$payload] -ne $carried[$payload]) {
            return Fail "$sbomName describes $payload hashing to $($described[$payload]); the one $BundleName carries hashes to $($carried[$payload])."
        }
    }
    foreach ($payload in @($described.Keys | Sort-Object)) {
        if (-not $carried.ContainsKey($payload)) { return Fail "$sbomName describes $payload, which $BundleName doesn't carry." }
    }
    return [pscustomobject]@{ Valid = $true; Reason = $null }
}

function Get-Sha256Hex {
    <#
    .SYNOPSIS
        A file's SHA-256 in upper-case hex, which is the form the release notes use.
    #>
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Cannot hash a file that is not there: $Path"
    }
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToUpperInvariant()
}

function Read-ToolingScopeManifest {
    param([Parameter(Mandatory = $true)][string]$Text)
    $canonical = $Text.Replace("`r`n", "`n").TrimEnd([char[]]"`n") + "`n"
    $entries = New-Object System.Collections.Generic.List[object]
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($line in $canonical -split "`n") {
        $line = $line.Trim()
        if (-not $line -or $line.StartsWith('#')) { continue }
        $match = [regex]::Match($line, '^(required|conditional)\s+(\S+)(?:\s+(\S+))?$')
        if (-not $match.Success -or ($match.Groups[1].Value -ceq 'conditional') -ne $match.Groups[3].Success) {
            throw "Invalid tooling scope declaration: $line"
        }
        $id = $match.Groups[2].Value
        if ($id -cnotmatch '^(settings/buildscript/classpath|:[A-Za-z0-9_:-]*/(buildscript/classpath|configuration/[A-Za-z0-9_.-]+))$' -or
                -not $seen.Add($id)) { throw "Invalid or duplicate tooling scope: $id" }
        $entries.Add([pscustomobject]@{ Id = $id; Requirement = $match.Groups[1].Value; TestTask = $match.Groups[3].Value })
    }
    if (-not $seen.Contains('settings/buildscript/classpath')) { throw 'The tooling scope manifest omits the settings-plugin classpath.' }
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $hash = [BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($canonical))).Replace('-', '').ToLowerInvariant() }
    finally { $sha.Dispose() }
    return [pscustomobject]@{ Text = $canonical; Sha256 = $hash; Entries = $entries.ToArray() }
}

function Read-ReleaseTooling {
    param([Parameter(Mandatory = $true)][string]$Path)
    $Path = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "There is no tooling report at $Path." }
    $document = [IO.File]::ReadAllText($Path) | ConvertFrom-Json
    if ([string]$document.schemaVersion -cne '1' -or $document.source.commit -cnotmatch '^[0-9a-f]{40}$' -or
            [string]$document.source.tree -cnotin @('clean', 'dirty')) { throw 'The tooling report has an invalid schema or source binding.' }
    if (-not $document.bundle.file -or -not $document.bundle.version -or $document.bundle.sha256 -notmatch '^[0-9a-f]{64}$' -or
            [string]$document.scopeManifest.file -cne 'gradle/tooling-scopes.txt' -or
            $document.scopeManifest.sha256 -notmatch '^[0-9a-f]{64}$') { throw 'The tooling report has no valid bundle or scope-manifest binding.' }
    $scopes = [Collections.Generic.Dictionary[string, object]]::new([StringComparer]::Ordinal)
    $carriers = [Collections.Generic.Dictionary[string, object]]::new([StringComparer]::Ordinal)
    foreach ($scope in @($document.scopes)) {
        $id = [string]$scope.id
        if ($id -cnotmatch '^(settings/buildscript/classpath|:[A-Za-z0-9_:-]*/(buildscript/classpath|configuration/[A-Za-z0-9_.-]+))$' -or
                $scopes.ContainsKey($id)) { throw "Invalid or duplicate tooling scope: $id" }
        $scopes.Add($id, $scope)
        if ([string]$scope.status -ceq 'notConfigured') {
            if (-not ([string]$scope.reason).Trim()) { throw "$id is not configured, but gives no reason." }
            continue
        }
        if ([string]$scope.status -cne 'resolved') { throw "$id has an unknown tooling scope status." }
        $nodes = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($node in @($scope.nodes)) {
            $node = [string]$node
            if ($node -cne 'root' -and $node -cnotmatch '^project :[A-Za-z0-9_:-]*$' -and
                    $node -cnotmatch '^[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.+~-]+$') { throw "$id contains an invalid component identifier." }
            if (-not $nodes.Add($node)) { throw "$id contains a duplicate component." }
            if ($node -cne 'root' -and -not $node.StartsWith('project ')) {
                if (-not $carriers.ContainsKey($node)) { $carriers.Add($node, [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)) }
                [void]$carriers[$node].Add($id)
            }
        }
        if (-not $nodes.Contains([string]$scope.root)) { throw "$id contains no graph root." }
        $edges = [Collections.Generic.Dictionary[string, object]]::new([StringComparer]::Ordinal)
        foreach ($edge in @($scope.edges)) {
            if (-not $nodes.Contains([string]$edge.from) -or $edges.ContainsKey([string]$edge.from)) { throw "$id contains an invalid or duplicate graph edge." }
            $targets = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
            foreach ($target in @($edge.to)) {
                if (-not $nodes.Contains([string]$target) -or -not $targets.Add([string]$target)) { throw "$id contains an unresolved or duplicate graph edge." }
            }
            $edges.Add([string]$edge.from, @($edge.to))
        }
        if ($edges.Count -ne $nodes.Count) { throw "$id does not record every component's graph edges." }
        $reachable = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        $queue = [Collections.Generic.Queue[string]]::new()
        $queue.Enqueue([string]$scope.root)
        while ($queue.Count -gt 0) {
            $next = $queue.Dequeue()
            if (-not $reachable.Add($next)) { continue }
            foreach ($target in $edges[$next]) { $queue.Enqueue([string]$target) }
        }
        if ($reachable.Count -ne $nodes.Count) { throw "$id contains components unreachable from its root." }
    }
    if ($scopes.Count -eq 0) { throw 'The tooling report records no scopes.' }
    $libraries = New-Object System.Collections.Generic.List[object]
    $coordinates = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($component in @($document.components)) {
        $coordinate = "$($component.group):$($component.name):$($component.version)"
        $purl = [regex]::Match([string]$component.purl, '^pkg:maven/([^/@?#]+)/([^/@?#]+)@([^/@?#]+)$')
        if ([string]$component.coordinate -cne $coordinate -or -not $coordinates.Add($coordinate) -or
                -not $purl.Success -or [Uri]::UnescapeDataString($purl.Groups[1].Value) -cne [string]$component.group -or
                [Uri]::UnescapeDataString($purl.Groups[2].Value) -cne [string]$component.name -or
                [Uri]::UnescapeDataString($purl.Groups[3].Value) -cne [string]$component.version) { throw 'A tooling coordinate is duplicated or does not match its Maven package URL.' }
        $listed = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($id in @($component.scopes)) {
            if (-not $listed.Add([string]$id) -or -not $carriers.ContainsKey($coordinate) -or -not $carriers[$coordinate].Contains([string]$id)) {
                throw "$coordinate names a scope that does not contain it."
            }
        }
        if ($listed.Count -eq 0 -or $listed.Count -ne $carriers[$coordinate].Count) { throw "$coordinate omits one of its resolved scopes." }
        foreach ($artifact in @($component.artifacts)) {
            if ([string]$artifact.name -cmatch '[/\\:\x00-\x1f]' -or -not $artifact.name -or $artifact.sha256 -notmatch '^[0-9a-f]{64}$') {
                throw "$coordinate records an invalid artifact name or hash."
            }
        }
        $libraries.Add([pscustomobject]@{ Group = [string]$component.group; Name = [string]$component.name
            Version = [string]$component.version; Purl = [string]$component.purl; Scopes = @($component.scopes) })
    }
    if ($coordinates.Count -eq 0 -or $coordinates.Count -ne $carriers.Count) { throw 'The tooling report omits resolved module coordinates.' }
    $declared = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($entry in @($document.scopeManifest.entries)) {
        if (-not $declared.Add([string]$entry.id) -or -not $scopes.ContainsKey([string]$entry.id) -or
                [string]$entry.requirement -cnotin @('required', 'conditional') -or
                [string]$entry.status -cne [string]$scopes[[string]$entry.id].status -or
                ([string]$entry.requirement -ceq 'required' -and [string]$entry.status -cne 'resolved')) {
            throw 'The tooling report has an invalid, missing or unresolved declared scope.'
        }
    }
    return [pscustomobject]@{ Path = $Path; Sha256 = Get-Sha256Hex -Path $Path; Document = $document
        Libraries = $libraries.ToArray(); Components = @($document.components); Scopes = @($document.scopes) }
}

function Test-ReleaseTooling {
    param([Parameter(Mandatory = $true)]$Tooling, [Parameter(Mandatory = $true)][string]$BundlePath,
        [Parameter(Mandatory = $true)][string]$ExpectedCommit, [Parameter(Mandatory = $true)][string]$ScopeManifestText,
        [string]$BundleName)
    function Fail { param([string]$Reason) return [pscustomobject]@{ Valid = $false; Reason = $Reason } }
    $document = $Tooling.Document
    if ([string]$document.source.tree -cne 'clean' -or [string]$document.source.commit -cne $ExpectedCommit) {
        return Fail 'The tooling report does not describe the clean source commit being released.'
    }
    if (-not $BundleName) { $BundleName = Split-Path -Leaf $BundlePath }
    $manifest = Get-BundleManifestFacts -BundlePath $BundlePath
    if ([string]$document.bundle.file -cne $BundleName -or [string]$document.bundle.version -cne [string]$manifest.version -or
            [string]$document.bundle.sha256 -ne (Get-Sha256Hex -Path $BundlePath)) { return Fail 'The tooling report does not describe this bundle.' }
    if ((Split-Path -Leaf $Tooling.Path) -cne "patches-$($manifest.version).tooling.json") { return Fail 'The tooling report has the wrong release filename.' }
    try { $expected = Read-ToolingScopeManifest -Text $ScopeManifestText } catch { return Fail $_.Exception.Message }
    if ($expected.Sha256 -ne [string]$document.scopeManifest.sha256) { return Fail 'The tooling scope manifest differs from the release commit.' }
    $scopes = [Collections.Generic.Dictionary[string, object]]::new([StringComparer]::Ordinal)
    foreach ($scope in $Tooling.Scopes) { $scopes[[string]$scope.id] = $scope }
    $entries = [Collections.Generic.Dictionary[string, object]]::new([StringComparer]::Ordinal)
    foreach ($entry in @($document.scopeManifest.entries)) { $entries[[string]$entry.id] = $entry }
    if ($entries.Count -ne $expected.Entries.Count) { return Fail 'The tooling report omits or adds scope declarations.' }
    foreach ($entry in $expected.Entries) {
        if (-not $scopes.ContainsKey($entry.Id) -or -not $entries.ContainsKey($entry.Id) -or
                [string]$entries[$entry.Id].requirement -cne $entry.Requirement -or
                ($entry.Requirement -ceq 'required' -and [string]$scopes[$entry.Id].status -cne 'resolved')) {
            return Fail "The tooling report omits required scope $($entry.Id)."
        }
        if ([string]$scopes[$entry.Id].status -ceq 'notConfigured' -and
                [string]$scopes[$entry.Id].reason -notlike "*$($entry.TestTask)*") {
            return Fail "The tooling report does not identify the absent test component for $($entry.Id)."
        }
    }
    return [pscustomobject]@{ Valid = $true; Reason = $null }
}

function Get-BundleManifestFacts {
    <#
    .SYNOPSIS
        Version, timestamp and patcher stamp out of a bundle's META-INF/MANIFEST.MF.
    .DESCRIPTION
        patches/build.gradle.kts pins the timestamp to the time of the commit it builds, in
        milliseconds, and to 0 when the tree had uncommitted changes as the build started. That's
        what makes a published hash reproducible from a tag. Read back here so a receipt cannot
        describe a bundle that was built from something other than the commit it names.
    #>
    param([Parameter(Mandatory = $true)][string]$BundlePath)

    # ZipFile reads a relative path against the process directory, not PowerShell's location.
    $BundlePath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($BundlePath)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $text = $null
    $archive = [System.IO.Compression.ZipFile]::OpenRead($BundlePath)
    try {
        $entry = $archive.Entries | Where-Object { $_.FullName -eq 'META-INF/MANIFEST.MF' }
        if (-not $entry) { throw "The bundle has no META-INF/MANIFEST.MF: $BundlePath" }
        $reader = New-Object System.IO.StreamReader($entry.Open())
        try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
    } finally { $archive.Dispose() }

    # Manifest lines wrap at 72 characters with a leading space on the continuation.
    $text = $text -replace "\r?\n ", ''
    $timestamp = [regex]::Match($text, '(?m)^Timestamp:\s*(\d+)\s*$')
    $version = [regex]::Match($text, '(?m)^Version:\s*(\S+)\s*$')
    $patcher = [regex]::Match($text, '(?m)^Patcher-Version:\s*(\S+)\s*$')
    if (-not $timestamp.Success) { throw "The bundle manifest has no Timestamp: $BundlePath" }
    if (-not $version.Success) { throw "The bundle manifest has no Version: $BundlePath" }
    if (-not $patcher.Success) { throw "The bundle manifest has no Patcher-Version: $BundlePath" }

    return [pscustomobject]@{
        version        = $version.Groups[1].Value
        timestamp      = [long]$timestamp.Groups[1].Value
        patcherVersion = $patcher.Groups[1].Value
    }
}

function Get-BundlePayloadSha256 {
    # Same framing as BundleIdentity.payloadSha256. Only the identity container is excluded.
    param([Parameter(Mandatory = $true)][IO.Compression.ZipArchive]$Archive)
    $entries = [Collections.Generic.Dictionary[string, object]]::new([StringComparer]::Ordinal)
    $names = [Collections.Generic.List[string]]::new()
    foreach ($entry in $Archive.Entries) {
        if ($entries.ContainsKey($entry.FullName)) { throw 'Duplicate bundle entry.' }
        $entries.Add($entry.FullName, $entry)
        if (-not $entry.FullName.EndsWith('/') -and $entry.FullName -cne 'META-INF/hushfacebook-build.identity') {
            $names.Add($entry.FullName)
        }
    }
    $names.Sort([StringComparer]::Ordinal)
    $hash = [Security.Cryptography.SHA256]::Create()
    try {
        $prefix = [Text.Encoding]::ASCII.GetBytes("hushfacebook-bundle-1`n")
        [void]$hash.TransformBlock($prefix, 0, $prefix.Length, $null, 0)
        $buffer = [byte[]]::new(65536)
        foreach ($name in $names) {
            $entry = $entries[$name]
            $path = [Text.Encoding]::UTF8.GetBytes($name)
            $pathLength = [BitConverter]::GetBytes([int]$path.Length)
            $contentLength = [BitConverter]::GetBytes([long]$entry.Length)
            if ([BitConverter]::IsLittleEndian) { [Array]::Reverse($pathLength); [Array]::Reverse($contentLength) }
            foreach ($bytes in @($pathLength, $path, $contentLength)) {
                [void]$hash.TransformBlock($bytes, 0, $bytes.Length, $null, 0)
            }
            $input = $entry.Open()
            try {
                $count = 0L
                while (($read = $input.Read($buffer, 0, $buffer.Length)) -gt 0) {
                    $count += $read
                    if ($count -gt $entry.Length) { throw 'Bundle entry grew while reading.' }
                    [void]$hash.TransformBlock($buffer, 0, $read, $null, 0)
                }
                if ($count -ne $entry.Length) { throw 'Bundle entry is truncated.' }
            } finally { $input.Dispose() }
        }
        [void]$hash.TransformFinalBlock([byte[]]::new(0), 0, 0)
        return ([BitConverter]::ToString($hash.Hash) -replace '-', '').ToLowerInvariant()
    } finally { $hash.Dispose() }
}

function Get-BundleIdentityFacts {
    param([Parameter(Mandatory = $true)][string]$BundlePath)
    $facts = [pscustomobject]@{ Present = $false; Valid = $false; SourceState = 'unknown'
        SourceCommit = 'unknown'; SourceTree = 'unknown'; InputSha256 = $null; PayloadSha256 = $null }
    $BundlePath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($BundlePath)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($BundlePath)
    try {
        $manifest = $zip.GetEntry('META-INF/MANIFEST.MF')
        $reader = [IO.StreamReader]::new($manifest.Open())
        try { $text = $reader.ReadToEnd() -replace "\r?\n ", '' } finally { $reader.Dispose() }
        $entry = $zip.GetEntry('META-INF/hushfacebook-build.identity')
        $facts.Present = $null -ne $entry -or $null -ne $zip.GetEntry('app/morphe/util/BundleIdentity.class') -or
            $text -cmatch '(?m)^Hushfacebook-(Source-|Input-)'
        if (-not $facts.Present -or $null -eq $entry -or $entry.Length -notin 1..128) { return $facts }
        $reader = [IO.StreamReader]::new($entry.Open(), [Text.Encoding]::ASCII)
        try { $record = $reader.ReadToEnd() } finally { $reader.Dispose() }
        if ($record -cnotmatch '\Ahushfacebook-bundle-1\n([0-9a-f]{64})\n\z') { return $facts }
        $expected = $Matches[1]
        if ((Get-BundlePayloadSha256 -Archive $zip) -cne $expected) { return $facts }
        $fields = @{}
        foreach ($field in @('Source-State', 'Source-Commit', 'Source-Tree', 'Input-SHA256')) {
            $values = [regex]::Matches($text, "(?m)^Hushfacebook-${field}: ([^\r\n]+)\r?$")
            if ($values.Count -ne 1) { return $facts }
            $fields[$field] = $values[0].Groups[1].Value
        }
        if ($fields['Input-SHA256'] -cnotmatch '\A[0-9a-f]{64}\z') { return $facts }
        if ($fields['Source-State'] -cin @('clean', 'dirty')) {
            if ($fields['Source-Commit'] -cnotmatch '\A[0-9a-f]{40}\z' -or
                $fields['Source-Tree'] -cnotmatch '\A[0-9a-f]{40}\z') { return $facts }
        } elseif ($fields['Source-State'] -ceq 'unknown') {
            if ($fields['Source-Commit'] -cne 'unknown' -or $fields['Source-Tree'] -cne 'unknown') { return $facts }
        } else { return $facts }
        $facts.SourceState = $fields['Source-State']; $facts.SourceCommit = $fields['Source-Commit']
        $facts.SourceTree = $fields['Source-Tree']; $facts.InputSha256 = $fields['Input-SHA256']
        $facts.PayloadSha256 = $expected; $facts.Valid = $true
    } catch {
        $facts.Present = $true
    } finally { $zip.Dispose() }
    return $facts
}

function Get-BundleInputMismatch {
    <#
    .SYNOPSIS
        Why the bundle isn't a build of the producer inputs under -Root as they are now, or $null
        when it is.
    .DESCRIPTION
        The input digest the bundle carries against the one Get-ProducerInputSha256 (common.ps1)
        works out from the checkout. File times can't see a deleted or renamed input, or an edit
        put back with an older time, and the digest covers every input's path and bytes.
    #>
    param([Parameter(Mandatory = $true)][string]$Root, [Parameter(Mandatory = $true)][string]$BundlePath)
    $facts = Get-BundleIdentityFacts -BundlePath $BundlePath
    if (-not $facts.Valid) { return 'it carries no intact record of the sources it was built from' }
    $current = Get-ProducerInputSha256 -Root $Root
    if ($facts.InputSha256 -cne $current) {
        return "it was built from other sources (input digest $($facts.InputSha256), the checkout's now $current)"
    }
    return $null
}

function Get-ExtensionPayloads {
    <#
    .SYNOPSIS
        The extension DEX each bundle carries, by name, size and hash.
    .DESCRIPTION
        An .mpe is a bare Android DEX, checked here by its magic rather than by its extension,
        so a bundle that shipped something else in that slot cannot pass as an extension. The
        receipt builder records these, and Test-ReleaseReceipt holds a receipt's list to them.
    #>
    param([string]$BundlePath)

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $payloads = New-Object System.Collections.Generic.List[object]
    $archive = [System.IO.Compression.ZipFile]::OpenRead($BundlePath)
    try {
        foreach ($entry in @($archive.Entries | Where-Object { $_.FullName -like 'extensions/*.mpe' } |
                Sort-Object FullName)) {
            $stream = $entry.Open()
            try {
                $memory = New-Object System.IO.MemoryStream
                $stream.CopyTo($memory)
                $bytes = $memory.ToArray()
                $memory.Dispose()
            } finally { $stream.Dispose() }

            $magic = [System.Text.Encoding]::ASCII.GetString($bytes, 0, [Math]::Min(4, $bytes.Length))
            if ($magic -ne "dex`n") {
                throw "$($entry.FullName) in $BundlePath is not an Android DEX payload."
            }
            $sha = [System.Security.Cryptography.SHA256]::Create()
            try { $hash = ($sha.ComputeHash($bytes) | ForEach-Object { '{0:X2}' -f $_ }) -join '' }
            finally { $sha.Dispose() }

            $payloads.Add([ordered]@{
                name      = $entry.FullName
                sizeBytes = [long]$bytes.Length
                sha256    = $hash
            })
        }
    } finally { $archive.Dispose() }

    if ($payloads.Count -eq 0) { throw "The bundle carries no extension payload: $BundlePath" }
    return ,$payloads.ToArray()
}

function Compare-BundleContent {
    <#
    .SYNOPSIS
        Every difference between two bundles in what executes or ships, whatever their ZIP framing
        and whatever each says about its own build.
    .DESCRIPTION
        The identity a bundle carries (commit, clean flag, input digest) is written by the build
        that made it, so it says where the bundle claims to come from and proves nothing. A second
        build of the same commit is the proof, and this is how the two are compared: every entry
        by its name and the SHA-256 of its bytes as they unpack, so the order of the entries, their
        times and whether one is stored or deflated don't count. Classes, DEX files and resources
        are read here, and the extension payloads through Get-ExtensionPayloads, which refuses one
        that isn't a DEX and a bundle that carries none.

        META-INF/MANIFEST.MF and META-INF/hushfacebook-build.identity hold that identity. Two
        checkouts of one commit can differ in line endings, which moves the input digest in the
        manifest and with it the payload hash in the identity record, while every class comes out
        the same. So those two come back in Identity, each with whether it's the same on both
        sides, and never decide: alone they can't make two bundles equal, and they can't make them
        differ.

        Answers @{ Equal; Compared; Differing; Missing; Extra; Identity }. Missing is what
        -ReferencePath carries and -BundlePath lacks, Extra the other way round, and either is a
        difference. Each entry names its Kind (class, DEX, extension payload or resource) and its
        hash on each side. An entry named twice in a bundle throws: nothing says which of the two a
        loader would read.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$BundlePath,
        [Parameter(Mandatory = $true)][string]$ReferencePath
    )

    $identityNames = @('META-INF/MANIFEST.MF', 'META-INF/hushfacebook-build.identity')
    function Read-BundleEntries([string]$Path) {
        if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "There is no bundle to compare at $Path." }
        # ZipFile reads a relative path against the process directory, not PowerShell's location.
        $Path = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Path)
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $entries = [Collections.Generic.Dictionary[string, object]]::new([StringComparer]::Ordinal)
        $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        $sha = [Security.Cryptography.SHA256]::Create()
        $archive = [IO.Compression.ZipFile]::OpenRead($Path)
        try {
            foreach ($entry in $archive.Entries) {
                $name = $entry.FullName
                # A folder's own entry holds no bytes.
                if ($name.EndsWith('/') -and $entry.Length -eq 0) { continue }
                if (-not $seen.Add($name)) { throw "$Path carries $name twice." }
                # Get-ExtensionPayloads reads these below, picked by this same pattern.
                if ($name -like 'extensions/*.mpe') { continue }
                $kind = 'resource'
                if ($name.EndsWith('.class', [StringComparison]::OrdinalIgnoreCase)) { $kind = 'class' }
                elseif ($name.EndsWith('.dex', [StringComparison]::OrdinalIgnoreCase)) { $kind = 'DEX' }
                $stream = $entry.Open()
                try { $hash = [BitConverter]::ToString($sha.ComputeHash($stream)) -replace '-', '' } finally { $stream.Dispose() }
                $entries.Add($name, [pscustomobject]@{ Kind = $kind; Sha256 = $hash })
            }
        } finally {
            $archive.Dispose()
            $sha.Dispose()
        }
        foreach ($payload in (Get-ExtensionPayloads -BundlePath $Path)) {
            $entries.Add([string]$payload.name, [pscustomobject]@{ Kind = 'extension payload'; Sha256 = [string]$payload.sha256 })
        }
        return ,$entries
    }

    $bundle = Read-BundleEntries $BundlePath
    $reference = Read-BundleEntries $ReferencePath
    $names = [Collections.Generic.SortedSet[string]]::new([StringComparer]::Ordinal)
    foreach ($name in $bundle.Keys) { [void]$names.Add($name) }
    foreach ($name in $reference.Keys) { [void]$names.Add($name) }

    $differing = [Collections.Generic.List[object]]::new()
    $missing = [Collections.Generic.List[object]]::new()
    $extra = [Collections.Generic.List[object]]::new()
    $identity = [Collections.Generic.List[object]]::new()
    $compared = 0
    foreach ($name in $names) {
        $ours = if ($bundle.ContainsKey($name)) { $bundle[$name] } else { $null }
        $theirs = if ($reference.ContainsKey($name)) { $reference[$name] } else { $null }
        $oursHash = if ($null -ne $ours) { $ours.Sha256 } else { $null }
        $theirsHash = if ($null -ne $theirs) { $theirs.Sha256 } else { $null }
        if ($identityNames -ccontains $name) {
            $identity.Add([pscustomobject]@{ Name = $name; Sha256 = $oursHash; ReferenceSha256 = $theirsHash
                Same = ($null -ne $ours -and $null -ne $theirs -and $oursHash -ceq $theirsHash) })
            continue
        }
        $compared++
        $kind = if ($null -ne $ours) { $ours.Kind } else { $theirs.Kind }
        $found = [pscustomobject]@{ Name = $name; Kind = $kind; Sha256 = $oursHash; ReferenceSha256 = $theirsHash }
        if ($null -eq $ours) { $missing.Add($found) }
        elseif ($null -eq $theirs) { $extra.Add($found) }
        elseif ($oursHash -cne $theirsHash) { $differing.Add($found) }
    }
    return [pscustomobject]@{
        Equal     = ($differing.Count + $missing.Count + $extra.Count) -eq 0
        Compared  = $compared
        Differing = $differing.ToArray()
        Missing   = $missing.ToArray()
        Extra     = $extra.ToArray()
        Identity  = $identity.ToArray()
    }
}

function Get-ElfPageFacts {
    <#
    .SYNOPSIS
        What an ELF file's program headers say about a page size: its PT_LOAD alignments and where
        its PT_GNU_RELRO ends.
    .DESCRIPTION
        $Head is the start of the file, as much as the caller kept, and $Length the whole file's
        size. format is 'not-elf' without the magic, 'elf64' or 'elf32' when the program headers
        were read, and 'malformed' with a reason when the file starts as ELF and they couldn't be.
        relro is 'aligned', 'misaligned' or 'absent', and relroEnd is VirtAddr + MemSiz of the
        last PT_GNU_RELRO read.
    #>
    param([byte[]]$Head, [long]$Length, [int]$PageSize)

    $facts = [ordered]@{ format = 'not-elf' }
    if ($Head.Length -lt 4 -or $Head[0] -ne 0x7F -or $Head[1] -ne 0x45 -or $Head[2] -ne 0x4C -or $Head[3] -ne 0x46) {
        return $facts
    }
    $facts.format = 'malformed'
    if ($Head.Length -lt 6) { $facts.reason = 'ends inside the ELF identification'; return $facts }
    if ($Head[4] -ne 1 -and $Head[4] -ne 2) { $facts.reason = "ELF class $($Head[4])"; return $facts }
    if ($Head[5] -ne 1) { $facts.reason = 'not little-endian'; return $facts }
    $wide = $Head[4] -eq 2
    if ($Head.Length -lt $(if ($wide) { 64 } else { 52 })) { $facts.reason = 'ends inside the ELF header'; return $facts }
    if ($wide) {
        $tableAt = [decimal][System.BitConverter]::ToUInt64($Head, 0x20)
        $entrySize = [int][System.BitConverter]::ToUInt16($Head, 0x36)
        $count = [int][System.BitConverter]::ToUInt16($Head, 0x38)
    } else {
        $tableAt = [decimal][System.BitConverter]::ToUInt32($Head, 0x1C)
        $entrySize = [int][System.BitConverter]::ToUInt16($Head, 0x2A)
        $count = [int][System.BitConverter]::ToUInt16($Head, 0x2C)
    }
    if ($count -eq 0) { $facts.reason = 'no program headers'; return $facts }
    # PN_XNUM: the real count sits in a section header, which nothing Android loads needs.
    if ($count -eq 0xFFFF) { $facts.reason = 'its program header count is held elsewhere'; return $facts }
    if ($entrySize -ne $(if ($wide) { 56 } else { 32 })) { $facts.reason = "program header size $entrySize"; return $facts }
    $tableEnd = $tableAt + ([decimal]$count * $entrySize)
    if ($tableEnd -gt $Length) { $facts.reason = 'its program headers run past the end of the file'; return $facts }
    if ($tableEnd -gt $Head.Length) { $facts.reason = 'its program headers start too far into the file to read'; return $facts }

    $loads = 0
    $loadsOff = 0
    $relro = 'absent'
    $relroEnd = $null
    for ($index = 0; $index -lt $count; $index++) {
        $at = [int]$tableAt + $index * $entrySize
        $type = [System.BitConverter]::ToUInt32($Head, $at)
        if ($type -eq 1) {
            $align = if ($wide) { [decimal][System.BitConverter]::ToUInt64($Head, $at + 48) }
                else { [decimal][System.BitConverter]::ToUInt32($Head, $at + 28) }
            $loads++
            if ($align -eq 0 -or ($align % $PageSize) -ne 0) { $loadsOff++ }
        } elseif ($type -eq 0x6474E552) {
            $relroEnd = if ($wide) {
                [decimal][System.BitConverter]::ToUInt64($Head, $at + 16) + [decimal][System.BitConverter]::ToUInt64($Head, $at + 40)
            } else {
                [decimal][System.BitConverter]::ToUInt32($Head, $at + 8) + [decimal][System.BitConverter]::ToUInt32($Head, $at + 20)
            }
            if (($relroEnd % $PageSize) -ne 0) { $relro = 'misaligned' }
            elseif ($relro -eq 'absent') { $relro = 'aligned' }
        }
    }
    # Nothing to load is nothing to hold to a page size, so it's named rather than passed.
    if ($loads -eq 0) { $facts.reason = 'no loadable segment'; return $facts }
    $facts.format = if ($wide) { 'elf64' } else { 'elf32' }
    $facts.loadSegments = $loads
    $facts.loadMisaligned = $loadsOff
    $facts.relro = $relro
    # A whole number in the receipt's JSON, which a decimal isn't on every PowerShell.
    if ($null -ne $relroEnd) { $facts.relroEnd = if ($relroEnd -le [long]::MaxValue) { [long]$relroEnd } else { [string]$relroEnd } }
    return $facts
}

function Get-NativeLibraryAlignment {
    <#
    .SYNOPSIS
        What an APK's native libraries say about 16 KB pages, as three separate answers: where
        each sits in the ZIP, how its PT_LOAD segments are aligned, and where its PT_GNU_RELRO ends.
    .DESCRIPTION
        One answer doesn't stand in for another. zipalign speaks for a stored library's offset in
        the ZIP and nothing else. A phone with 16 KB pages also needs every loadable segment
        aligned to 16 KB, and Android's own check
        (developer.android.com/guide/practices/page-sizes#check-relro) wants the region made
        read-only after relocation to end on a 16 KB boundary: (VirtAddr + MemSiz) % 16384 == 0.
        Both are read here out of each library's program headers, a compressed library's too,
        since Android extracts it and loads the same ELF. Whether an entry is stored comes from the
        ZIP's own directory, which System.IO.Compression doesn't show.

        Only the 64-bit ABI folders are judged: 16 KB pages are a 64-bit matter, and a 32-bit
        library is listed with its hash and format alone. In a judged folder, a file that isn't ELF
        is listed as that and left out of the verdict (Facebook ships a DEX named .so), one that
        starts as ELF and can't be read to the end of its program headers is malformed and fails,
        and a library with no PT_GNU_RELRO is listed as having none and doesn't fail.

        staticChecksPass is $null with no 64-bit library to judge. $true says these checks found
        nothing, never that the app was seen running on 16 KB pages. zipOffsets carries the
        counts; the caller adds what zipalign said about the stored ones.
    #>
    param([Parameter(Mandatory = $true)][string]$Apk, [int]$PageSize = 16384)

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    # Entry names are case-sensitive in a ZIP, and a plain hashtable would fold libFoo.so into libfoo.so.
    $stored = New-Object System.Collections.Hashtable ([System.StringComparer]::Ordinal)
    $file = [System.IO.File]::OpenRead($Apk)
    try {
        $tail = New-Object byte[] ([int][Math]::Min($file.Length, 65557L))
        $null = $file.Seek(-$tail.Length, [System.IO.SeekOrigin]::End)
        $got = 0
        while ($got -lt $tail.Length) {
            $read = $file.Read($tail, $got, $tail.Length - $got)
            if ($read -le 0) { break }
            $got += $read
        }
        $end = -1
        for ($index = $tail.Length - 22; $index -ge 0; $index--) {
            if ($tail[$index] -eq 0x50 -and $tail[$index + 1] -eq 0x4B -and $tail[$index + 2] -eq 5 -and $tail[$index + 3] -eq 6) {
                $end = $index
                break
            }
        }
        if ($end -lt 0) { throw "$Apk has no ZIP end record, so its native libraries can't be read." }
        $directorySize = [System.BitConverter]::ToUInt32($tail, $end + 12)
        $directoryAt = [System.BitConverter]::ToUInt32($tail, $end + 16)
        if ($directorySize -eq [uint32]::MaxValue -or $directoryAt -eq [uint32]::MaxValue) {
            throw "$Apk keeps its ZIP directory in ZIP64 records, which Android doesn't install."
        }
        if ([long]$directoryAt + $directorySize -gt $file.Length) { throw "$Apk has a ZIP directory that runs past its end." }
        $directory = New-Object byte[] ([int]$directorySize)
        $null = $file.Seek([long]$directoryAt, [System.IO.SeekOrigin]::Begin)
        $got = 0
        while ($got -lt $directory.Length) {
            $read = $file.Read($directory, $got, $directory.Length - $got)
            if ($read -le 0) { break }
            $got += $read
        }
        $at = 0
        while ($at + 46 -le $directory.Length) {
            if ([System.BitConverter]::ToUInt32($directory, $at) -ne 0x02014B50) { throw "$Apk has a damaged ZIP directory." }
            $nameLength = [int][System.BitConverter]::ToUInt16($directory, $at + 28)
            $next = $at + 46 + $nameLength + [System.BitConverter]::ToUInt16($directory, $at + 30) +
                [System.BitConverter]::ToUInt16($directory, $at + 32)
            if ($next -gt $directory.Length) { throw "$Apk has a damaged ZIP directory." }
            $stored[[System.Text.Encoding]::UTF8.GetString($directory, $at + 46, $nameLength)] =
                ([System.BitConverter]::ToUInt16($directory, $at + 10) -eq 0)
            $at = $next
        }
    } finally { $file.Dispose() }

    $names = [string[]]@($stored.Keys | Where-Object { $_ -cmatch '^lib/[^/]+/[^/]+\.so$' })
    [Array]::Sort($names, [System.StringComparer]::Ordinal)
    $headLimit = 1048576
    $libraries = New-Object System.Collections.Generic.List[object]
    $loadMisaligned = New-Object System.Collections.Generic.List[string]
    $relroMisaligned = New-Object System.Collections.Generic.List[string]
    $relroAbsent = New-Object System.Collections.Generic.List[string]
    $notElf = New-Object System.Collections.Generic.List[string]
    $malformed = New-Object System.Collections.Generic.List[string]
    $judged = 0
    $segments = 0
    $relroPresent = 0
    $archive = [System.IO.Compression.ZipFile]::OpenRead($Apk)
    try {
        foreach ($name in $names) {
            $entry = $archive.GetEntry($name)
            $sha = [System.Security.Cryptography.SHA256]::Create()
            $head = New-Object System.IO.MemoryStream
            $stream = $entry.Open()
            try {
                $buffer = New-Object byte[] 1048576
                while (($read = $stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
                    $null = $sha.TransformBlock($buffer, 0, $read, $null, 0)
                    if ($head.Length -lt $headLimit) {
                        $head.Write($buffer, 0, [int][Math]::Min([long]$read, $headLimit - $head.Length))
                    }
                }
                $null = $sha.TransformFinalBlock($buffer, 0, 0)
                $hash = ($sha.Hash | ForEach-Object { '{0:X2}' -f $_ }) -join ''
                $facts = Get-ElfPageFacts -Head $head.ToArray() -Length $entry.Length -PageSize $PageSize
            } finally {
                $stream.Dispose()
                $head.Dispose()
                $sha.Dispose()
            }

            $record = [ordered]@{ entry = $name; stored = [bool]$stored[$name]; sha256 = $hash; format = $facts.format }
            if (@('arm64-v8a', 'x86_64') -contains $name.Split('/')[1]) {
                if ($facts.format -eq 'elf32') {
                    $facts = [ordered]@{ format = 'malformed'; reason = 'a 32-bit ELF in a 64-bit folder' }
                    $record.format = 'malformed'
                }
                if ($facts.format -eq 'not-elf') {
                    $notElf.Add($name)
                } elseif ($facts.format -eq 'malformed') {
                    $record.reason = $facts.reason
                    $malformed.Add("$name ($($facts.reason))")
                } else {
                    $judged++
                    $segments += $facts.loadSegments
                    $record.loadSegments = $facts.loadSegments
                    $record.loadMisaligned = $facts.loadMisaligned
                    $record.relro = $facts.relro
                    if ($facts.Contains('relroEnd')) { $record.relroEnd = $facts.relroEnd }
                    if ($facts.loadMisaligned -gt 0) { $loadMisaligned.Add($name) }
                    if ($facts.relro -eq 'absent') { $relroAbsent.Add($name) } else { $relroPresent++ }
                    if ($facts.relro -eq 'misaligned') { $relroMisaligned.Add($name) }
                }
            }
            $libraries.Add($record)
        }
    } finally { $archive.Dispose() }

    $storedCount = @($names | Where-Object { $stored[$_] }).Count
    $verdict = $null
    if ($judged -gt 0 -or $malformed.Count -gt 0) {
        $verdict = ($loadMisaligned.Count -eq 0 -and $relroMisaligned.Count -eq 0 -and $malformed.Count -eq 0)
    }
    return [ordered]@{
        pageSizeKb       = [int]($PageSize / 1024)
        zipOffsets       = [ordered]@{ stored = $storedCount; compressed = $names.Length - $storedCount }
        loadSegments     = [ordered]@{
            libraries  = $judged
            segments   = $segments
            misaligned = @($loadMisaligned.ToArray())
            aligned    = ($loadMisaligned.Count -eq 0)
        }
        relroEnds        = [ordered]@{
            present    = $relroPresent
            absent     = @($relroAbsent.ToArray())
            misaligned = @($relroMisaligned.ToArray())
            aligned    = ($relroMisaligned.Count -eq 0)
        }
        notElf           = @($notElf.ToArray())
        malformed        = @($malformed.ToArray())
        staticChecksPass = $verdict
        libraries        = @($libraries.ToArray())
    }
}

function Resolve-Aapt2 {
    <#
    .SYNOPSIS
        The aapt2 that reads a binary AndroidManifest.xml, or a throw saying where to get one.
    .DESCRIPTION
        Taken in order from -Explicit, HUSHFACEBOOK_AAPT2, and the newest build-tools directory of
        the SDK named in local.properties or ANDROID_HOME. Newest by version number, not by text:
        sorting build-tools as strings puts 9.0.0 above 37.0.0.
    #>
    param([string]$Explicit, [string]$Root)

    if ($Explicit) {
        if (-not (Test-Path -LiteralPath $Explicit -PathType Leaf)) {
            throw "No aapt2 at the path given: $Explicit"
        }
        return $Explicit
    }
    if ($env:HUSHFACEBOOK_AAPT2 -and (Test-Path -LiteralPath $env:HUSHFACEBOOK_AAPT2 -PathType Leaf)) {
        return $env:HUSHFACEBOOK_AAPT2
    }

    $sdk = $env:ANDROID_HOME
    $localProperties = if ($Root) { Join-Path $Root 'local.properties' } else { $null }
    if ($localProperties -and (Test-Path -LiteralPath $localProperties -PathType Leaf)) {
        $line = @(Get-Content -LiteralPath $localProperties |
            Where-Object { $_ -match '^\s*sdk\.dir\s*=' }) | Select-Object -First 1
        # A properties file escapes with a backslash; Android Studio writes C\:\\Users\\...
        if ($line) { $sdk = ($line -replace '^\s*sdk\.dir\s*=\s*', '') -replace '\\(.)', '$1' }
    }
    if (-not $sdk) { throw 'No Android SDK: set ANDROID_HOME, sdk.dir or HUSHFACEBOOK_AAPT2.' }

    $buildTools = Join-Path $sdk 'build-tools'
    if (-not (Test-Path -LiteralPath $buildTools -PathType Container)) {
        throw "The SDK has no build-tools directory: $buildTools"
    }
    $candidate = Get-ChildItem -LiteralPath $buildTools -Directory |
        Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
        Sort-Object { [version]$_.Name } -Descending |
        Select-Object -First 1
    if (-not $candidate) { throw "No versioned build-tools under $buildTools." }

    $aapt2 = Join-Path $candidate.FullName 'aapt2.exe'
    if (-not (Test-Path -LiteralPath $aapt2 -PathType Leaf)) {
        $aapt2 = Join-Path $candidate.FullName 'aapt2'
    }
    if (-not (Test-Path -LiteralPath $aapt2 -PathType Leaf)) {
        throw "build-tools $($candidate.Name) carries no aapt2."
    }
    return $aapt2
}

function ConvertFrom-XmlTreeValue {
    <#
    .SYNOPSIS
        The value of one aapt2 xmltree attribute, without its decoration.
    .DESCRIPTION
        aapt2 prints a string as `="text" (Raw: "text")` and everything else bare. The quoted
        half is taken when it is there, because the raw half repeats it and an attribute whose
        value contains a quote would otherwise be cut at the wrong place.
    #>
    param([string]$Text)

    if ($null -eq $Text) { return $null }
    $value = $Text.Trim()
    $rawAt = $value.IndexOf(' (Raw: ')
    if ($rawAt -ge 0) { $value = $value.Substring(0, $rawAt).Trim() }
    if ($value.Length -ge 2 -and $value.StartsWith('"') -and $value.EndsWith('"')) {
        return $value.Substring(1, $value.Length - 2)
    }
    return $value
}

function Get-ApkManifestFacts {
    <#
    .SYNOPSIS
        Package identity, requested permissions and exported components, read off an APK.
    .DESCRIPTION
        Components count as exported only when the manifest says so. Every target this project
        patches is above API 31, where an intent filter without an explicit android:exported is
        refused at install time, so there is no implicit case left to infer.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Apk,
        [Parameter(Mandatory = $true)][string]$Aapt2
    )

    if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) { throw "APK not found: $Apk" }

    $dump = @(& $Aapt2 dump xmltree --file AndroidManifest.xml $Apk 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "aapt2 could not read the manifest of ${Apk}: $($dump -join ' ')"
    }
    return ConvertFrom-ManifestXmlTree -Lines $dump -Source (Split-Path -Leaf $Apk)
}

function ConvertFrom-ManifestXmlTree {
    <#
    .SYNOPSIS
        The same reading, from lines already in hand, so the parser can be tested without an APK.
    #>
    param([string[]]$Lines, [string]$Source)

    $componentElements = @('activity', 'activity-alias', 'service', 'receiver', 'provider')
    $packageName = $null
    $versionName = $null
    $versionCode = $null
    $permissions = New-Object System.Collections.Generic.List[string]
    $exported = New-Object System.Collections.Generic.List[string]

    $element = $null
    $componentName = $null
    $componentExported = $false

    function Complete-Component {
        param($Name, $Kind, $IsExported, $List)
        if ($Kind -and $IsExported -and $Name) { $List.Add("${Kind}:${Name}") }
    }

    foreach ($raw in $Lines) {
        $line = [string]$raw
        $elementMatch = [regex]::Match($line, '^\s*E:\s*([A-Za-z0-9_\-]+)\s*\(line=')
        if ($elementMatch.Success) {
            Complete-Component -Name $componentName -Kind $element -IsExported $componentExported -List $exported
            $element = $elementMatch.Groups[1].Value
            $componentName = $null
            $componentExported = $false
            continue
        }

        $attributeMatch = [regex]::Match($line,
            '^\s*A:\s*(?:http://schemas\.android\.com/apk/res/android:)?([A-Za-z0-9_\-]+)(?:\(0x[0-9a-fA-F]+\))?=(.*)$')
        if (-not $attributeMatch.Success) { continue }
        $name = $attributeMatch.Groups[1].Value
        $value = ConvertFrom-XmlTreeValue -Text $attributeMatch.Groups[2].Value

        switch ($element) {
            'manifest' {
                if ($name -eq 'package') { $packageName = $value }
                elseif ($name -eq 'versionName') { $versionName = $value }
                elseif ($name -eq 'versionCode') { $versionCode = $value }
            }
            'uses-permission' {
                if ($name -eq 'name' -and $value) { $permissions.Add($value) }
            }
            'uses-permission-sdk-23' {
                if ($name -eq 'name' -and $value) { $permissions.Add($value) }
            }
            default {
                if ($componentElements -contains $element) {
                    if ($name -eq 'name') { $componentName = $value }
                    elseif ($name -eq 'exported') { $componentExported = ($value -eq 'true') }
                }
            }
        }
    }
    Complete-Component -Name $componentName -Kind $element -IsExported $componentExported -List $exported

    if (-not $packageName) { throw "No package name in the manifest of $Source." }

    # A component written as .Name is inside the package, and the two APKs have to spell the
    # same component the same way or every one of them reads as both added and removed.
    $qualified = New-Object System.Collections.Generic.List[string]
    foreach ($entry in $exported) {
        $kind, $name = $entry -split ':', 2
        if ($name.StartsWith('.')) { $name = $packageName + $name }
        elseif ($name -notmatch '\.') { $name = $packageName + '.' + $name }
        $qualified.Add("${kind}:${name}")
    }

    return [pscustomobject]@{
        package     = $packageName
        versionName = $versionName
        versionCode = $versionCode
        permissions = @($permissions | Sort-Object -Unique -CaseSensitive)
        exported    = @($qualified | Sort-Object -Unique -CaseSensitive)
    }
}

function Get-ManifestDelta {
    <#
    .SYNOPSIS
        What patching did to the manifest, as four sorted lists.
    #>
    param([Parameter(Mandatory = $true)]$Stock, [Parameter(Mandatory = $true)]$Patched)

    return [pscustomobject]@{
        permissionsAdded          = @(Compare-Sets -Left $Patched.permissions -Right $Stock.permissions)
        permissionsRemoved        = @(Compare-Sets -Left $Stock.permissions -Right $Patched.permissions)
        exportedComponentsAdded   = @(Compare-Sets -Left $Patched.exported -Right $Stock.exported)
        exportedComponentsRemoved = @(Compare-Sets -Left $Stock.exported -Right $Patched.exported)
    }
}

function Compare-Sets {
    <#
    .SYNOPSIS
        Everything in Left that Right does not have, sorted.
    #>
    param([string[]]$Left, [string[]]$Right)

    $other = [System.Collections.Generic.HashSet[string]]::new(
        [string[]]@($Right), [System.StringComparer]::Ordinal)
    return @(@($Left) | Where-Object { -not $other.Contains($_) } | Sort-Object -Unique -CaseSensitive)
}

function ConvertTo-ManifestDeltaEntries {
    <#
    .SYNOPSIS
        One delta as the flat `kind value` lines the allowlist is written in.
    #>
    param([Parameter(Mandatory = $true)]$Delta)

    $entries = New-Object System.Collections.Generic.List[string]
    foreach ($value in @($Delta.permissionsAdded)) { $entries.Add("permission-added $value") }
    foreach ($value in @($Delta.permissionsRemoved)) { $entries.Add("permission-removed $value") }
    foreach ($value in @($Delta.exportedComponentsAdded)) { $entries.Add("exported-added $value") }
    foreach ($value in @($Delta.exportedComponentsRemoved)) { $entries.Add("exported-removed $value") }
    return @($entries | Sort-Object -Unique -CaseSensitive)
}

function Read-ManifestDeltaAllowlist {
    <#
    .SYNOPSIS
        The reviewed manifest changes. Blank lines and # comments are ignored.
    #>
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "The manifest delta allowlist is missing: $Path"
    }
    return ConvertFrom-ManifestDeltaAllowlist -Lines @(Get-Content -LiteralPath $Path)
}

function ConvertFrom-ManifestDeltaAllowlist {
    <#
    .SYNOPSIS
        The reviewed manifest changes out of an allowlist's lines, wherever they were read from.
    .DESCRIPTION
        -Source says where, for a failure: " at <commit>" for one read out of git.
    #>
    param([AllowEmptyCollection()][string[]]$Lines = @(), [string]$Source = '')

    $entries = New-Object System.Collections.Generic.List[string]
    foreach ($line in @($Lines)) {
        # A byte order mark is read as text when the lines come out of git rather than Get-Content.
        $text = ([string]$line).TrimStart([char]0xFEFF).Trim()
        if (-not $text -or $text.StartsWith('#')) { continue }
        if ($text -notmatch '^(permission-added|permission-removed|exported-added|exported-removed) \S+$') {
            throw "The manifest delta allowlist$Source has a line that is not `"<kind> <value>`": $text"
        }
        $entries.Add($text)
    }
    # An allowlist with no entries comes back as $null, not an empty array: a PowerShell function
    # returning @() hands back nothing. Comma wrapping it would fix that and break every
    # `@(Read-ManifestDeltaAllowlist ...)` call site instead, which would then see one array
    # inside an array. Test-ReleaseReceipt drops the null on the way in.
    return @($entries | Sort-Object -Unique -CaseSensitive)
}

function Resolve-ReceiptManifestAllowlist {
    <#
    .SYNOPSIS
        The reviewed manifest changes a receipt is held to: the allowlist its own commit carried.
    .DESCRIPTION
        The allowlist is the review of what a release's patches do to the manifest, so the one a
        receipt answers to is the one committed with it, for the reason Resolve-ReceiptToolchain
        gives. Read from the working tree, an entry added and never committed approved a change no
        commit ever reviewed, and one pruned after the release (an entry nothing produces fails the
        next receipt) refused the release that had needed it. On a release push the receipt's
        commit is the release commit, so the list is the one the tree carries and nothing is
        relaxed. A commit with no allowlist, and a receipt naming no commit, are held to the one at
        -WorkingPath, the first with a note. Answers @{ Entries; Note }.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [string]$Commit,
        [Parameter(Mandatory = $true)][string]$WorkingPath
    )

    if ($Commit -notmatch '^[0-9a-f]{40}$') {
        return [pscustomobject]@{ Entries = @(Read-ManifestDeltaAllowlist -Path $WorkingPath); Note = $null }
    }
    $short = $Commit.Substring(0, 8)
    $path = 'scripts/manifest-delta-allowlist.txt'
    $kind = "$(Invoke-RepoGit -Root $Root -Arguments @('cat-file', '-t', "${Commit}:$path") | Select-Object -First 1)".Trim()
    if ($kind -ne 'blob') {
        return [pscustomobject]@{
            Entries = @(Read-ManifestDeltaAllowlist -Path $WorkingPath)
            Note = "commit $short has no manifest delta allowlist, so the receipt is held to the working one"
        }
    }
    $atCommit = @(ConvertFrom-ManifestDeltaAllowlist -Lines @(Invoke-RepoGit -Root $Root -Arguments @('show', "${Commit}:$path")) `
        -Source " at $short")
    $working = @(if (Test-Path -LiteralPath $WorkingPath -PathType Leaf) { Read-ManifestDeltaAllowlist -Path $WorkingPath })
    $note = if ((@($atCommit) -join "`n") -ceq (@($working) -join "`n")) { $null } else {
        "the receipt is held to the $($atCommit.Count) manifest change(s) the allowlist at its own commit $short " +
            "reviews; the working allowlist has $($working.Count)"
    }
    return [pscustomobject]@{ Entries = $atCommit; Note = $note }
}

function Get-ChangelogVersions {
    <#
    .SYNOPSIS
        The versions a CHANGELOG names, in the order it names them.
    .DESCRIPTION
        Both shapes this file carries: a bare "## 0.32.0", a dated "## 0.14.0 (2026-09-05)", and
        the linked "## [0.1.5](compare/...) (2026-06-01)" the upstream generator wrote. Anything
        else under a level-two heading, "Unreleased" among them, is not a version and is ignored
        here; it is the absence of a version that this exists to notice.
    #>
    param([string]$Text)

    $found = New-Object System.Collections.Generic.List[string]
    foreach ($match in [regex]::Matches($Text, '(?m)^##\s+\[?v?(\d+\.\d+\.\d+(?:-[0-9A-Za-z.]+)?)')) {
        $found.Add($match.Groups[1].Value)
    }
    # No comma wrap. Every caller writes @(Get-ChangelogVersions ...), and @(,$array) is an
    # array holding one array: the membership test then finds nothing and the first element
    # prints as the whole list.
    return $found.ToArray()
}

function Test-ChangelogVersions {
    <#
    .SYNOPSIS
        Whether the CHANGELOG still describes every version it described at the last release,
        and describes the version being released now.
    .DESCRIPTION
        A released version's heading is the only record a reader has that it shipped. On
        2026-09-14 a post-release commit renamed "## 0.31.0" to "## Unreleased", so the file
        said that release never happened, and nothing noticed until the next release was cut by
        hand. No gate read this file at all.

        Held against the CHANGELOG as it stood at the last tag rather than against the tag list,
        so it needs no list of exceptions for versions that never had an entry: whatever was
        described then has to still be described now. Answers @{ Valid; Reason }.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Current,
        [Parameter(Mandatory = $true)][string]$ExpectedVersion,
        # The same file at the last release tag. Absent on a checkout with no tag yet, in which
        # case only the version being released is checked.
        [string]$Previous,
        [string]$PreviousLabel = 'the last release'
    )

    function Fail { param([string]$Reason) return [pscustomobject]@{ Valid = $false; Reason = $Reason } }

    $now = @(Get-ChangelogVersions -Text $Current)
    if ($now.Count -eq 0) { return Fail 'The CHANGELOG names no version at all.' }
    if ($now -notcontains $ExpectedVersion) {
        return Fail ("The CHANGELOG has no heading for $ExpectedVersion, the version this " +
            "checkout builds. It names $($now[0]) first.")
    }

    if ($PSBoundParameters.ContainsKey('Previous') -and $null -ne $Previous) {
        $then = @(Get-ChangelogVersions -Text $Previous)
        $present = [System.Collections.Generic.HashSet[string]]::new(
            [string[]]$now, [System.StringComparer]::Ordinal)
        $lost = @($then | Where-Object { -not $present.Contains($_) })
        if ($lost.Count -gt 0) {
            return Fail ("The CHANGELOG described " + ($lost -join ', ') + " at $PreviousLabel " +
                "and does not now. A shipped version cannot stop having an entry.")
        }
    }

    return [pscustomobject]@{ Valid = $true; Reason = 'ok' }
}

function Test-ChangelogManagerEntry {
    <#
    .SYNOPSIS
        Whether Morphe Manager can show the entry for the version being released.
    .DESCRIPTION
        Manager fetches this file from main and reads it with its own parser (ChangelogParser,
        Manager 1.30.0). A heading only counts when it ends in a date, "## 0.41.0 (2026-09-18)",
        and a bullet with no label is a change to the app, "* Hide the story timer." (a leftover
        "* **Facebook:** ..." label counts the same, and the release cut drops it). Manager 2.2.0
        and newer show every line of a one-app source, and older ones show an unlabeled line that
        names the app or a patch. Lines are kept one line at a time, so a bullet wrapped onto a second line loses
        the rest. Every heading from 0.23.0 to 0.40.0 was bare, Manager's list stopped at 0.22.0
        and every update showed nothing, and no gate noticed, because Test-ChangelogVersions
        reads its own heading pattern, which the bare headings matched.

        Only the section being released is held to this. Older sections are frozen as shipped.
        "* **Tooling:** ..." bullets, the development-only entries, are allowed and not counted.
        Answers @{ Valid; Reason; Date; Bullets }.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Current,
        [Parameter(Mandatory = $true)][string]$ExpectedVersion,
        [string]$App = 'Facebook'
    )

    function Fail { param([string]$Reason) return [pscustomobject]@{ Valid = $false; Reason = $Reason; Date = $null; Bullets = 0 } }

    # Manager's VERSION_HEADING and BULLET_SCOPE_RE, as its source spells them.
    $managerHeading = '^#{1,3}\s+(?:\S+\s+)?(?:\[([^\]]+)\]\([^)]*\)|([^\s\[(]+))\s+\((\d{4}-\d{2}-\d{2})\)'
    $managerScope = '^\* \*\*(.+?):\*\*'

    $lines = @($Current -split '\r?\n')
    $start = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $version = [regex]::Match($lines[$i], '^##\s+\[?v?(\d+\.\d+\.\d+(?:-[0-9A-Za-z.]+)?)')
        if ($version.Success -and $version.Groups[1].Value -eq $ExpectedVersion) { $start = $i; break }
    }
    if ($start -lt 0) { return Fail "The CHANGELOG has no heading for $ExpectedVersion." }

    $heading = [regex]::Match($lines[$start], $managerHeading)
    if (-not $heading.Success) {
        return Fail ("The $ExpectedVersion heading has no date, so Morphe Manager skips the whole " +
            "entry. Write it as ""## $ExpectedVersion (YYYY-MM-DD)"".")
    }
    $named = if ($heading.Groups[1].Success) { $heading.Groups[1].Value } else { $heading.Groups[2].Value }
    if ($named.TrimStart('v') -ne $ExpectedVersion) {
        return Fail ("Morphe Manager reads the $ExpectedVersion heading as version $named.")
    }

    $bullets = 0
    $previousWasBullet = $false
    for ($i = $start + 1; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        # The next level-one or level-two heading ends the section. "### Settings" inside it
        # groups bullets and is read by Manager as an ordinary line.
        if ($line -match '^#{1,2}(?!#)\s') { break }
        if ($line -match '^\s*[*+-]\s') {
            $scope = [regex]::Match($line, $managerScope)
            # A development-only change, the CHANGELOG's other scope. Manager shows a line only to
            # the app it's scoped to, so it shows these to nobody: allowed, and not counted.
            if ($scope.Success -and $scope.Groups[1].Value -ceq 'Tooling') {
                $previousWasBullet = $true
                continue
            }
            # No label is the app's own change. The app's label is accepted too, and any other
            # label is refused because it isn't this app's change.
            if ($scope.Success -and -not ($scope.Groups[1].Value -eq $App -or
                    $scope.Groups[1].Value.StartsWith("$App - "))) {
                return Fail ("Line $($i + 1) is a $ExpectedVersion bullet scoped to " +
                    "$($scope.Groups[1].Value), so it is not counted as a $App change. Drop the label " +
                    "(a bullet with none is a $App change): $line")
            }
            $bullets++
            $previousWasBullet = $true
            continue
        }
        if ($previousWasBullet -and -not [string]::IsNullOrWhiteSpace($line) -and $line -notmatch '^#') {
            return Fail ("Line $($i + 1) continues the bullet above it, and Morphe Manager keeps " +
                "scoped lines one at a time, so it would drop this text. Join it onto one line: $line")
        }
        $previousWasBullet = $false
    }
    if ($bullets -eq 0) {
        return Fail ("The $ExpectedVersion entry has no $App change bullet (one with no label), " +
            "so Morphe Manager shows no update for $App.")
    }

    return [pscustomobject]@{ Valid = $true; Reason = 'ok'; Date = $heading.Groups[3].Value; Bullets = $bullets }
}

function Invoke-RepoGit {
    <#
    .SYNOPSIS
        git against the repository a caller names, with no inherited git environment.
    .DESCRIPTION
        `git -C <path>` sets the working directory and does not override GIT_DIR. Every script
        here runs from the pre-push hook, and a hook is a git child process with GIT_DIR and
        GIT_WORK_TREE already in its environment, so without this a -Root parameter is a
        suggestion rather than an instruction: git reads whichever repository the hook came from.
        On 2026-09-15 that turned a temporary fixture into three commits on the real branch.

        git writes UTF-8, and PowerShell decodes a native command's output with the console's
        encoding, which is code page 437 for a hook's pwsh when the push starts in Git Bash. There a
        byte order mark came back as the three characters U+2229 U+2557 U+2510 and any other
        non-ASCII text as nonsense, so Use-Utf8ConsoleOutput (common.ps1) has PowerShell read UTF-8
        for the call, with or without a console, and puts the caller's encoding back.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    $saved = @{}
    foreach ($variable in @(Get-ChildItem Env: | Where-Object { $_.Name -like 'GIT_*' })) {
        $saved[$variable.Name] = $variable.Value
        Remove-Item -LiteralPath ('Env:\' + $variable.Name) -ErrorAction SilentlyContinue
    }
    # Windows PowerShell 5.1 turns a native command's stderr into a terminating error under
    # Stop even when it is redirected. Relax for the call and restore afterwards.
    $preference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        return Use-Utf8ConsoleOutput { & git -C $Root @Arguments 2>$null }
    } finally {
        $ErrorActionPreference = $preference
        foreach ($name in $saved.Keys) { Set-Item -LiteralPath ('Env:\' + $name) -Value $saved[$name] }
    }
}

function Read-CatalogToolchain {
    <#
    .SYNOPSIS
        The patcher pin and Manager floor out of a version catalog's text.
    .DESCRIPTION
        Takes text rather than a path, so the same reading applies to the catalog in the working
        tree and to the one at an older commit. Every failure names where the text came from,
        because "does not pin morphe-patcher" means something different in the working tree than
        at a commit from a release that shipped months ago.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Source
    )

    $patcherMatch = [regex]::Match($Text, '(?m)^\s*morphe-patcher\s*=\s*"([^"]+)"')
    if (-not $patcherMatch.Success) { throw "$Source does not pin morphe-patcher." }
    $floorMatch = [regex]::Match($Text, '(?m)^\s*manager-floor\s*=\s*"([^"]+)"')
    if (-not $floorMatch.Success) {
        throw "$Source does not pin manager-floor beside morphe-patcher."
    }
    $floor = $floorMatch.Groups[1].Value
    if ($floor -notmatch '^\d+\.\d+\.\d+$') {
        throw "$Source has an invalid manager-floor: $floor"
    }
    return [pscustomobject]@{
        PatcherVersion = $patcherMatch.Groups[1].Value
        ManagerFloor   = $floor
    }
}

function Resolve-ReceiptToolchain {
    <#
    .SYNOPSIS
        The toolchain a receipt should be held to: the one its own commit pinned.
    .DESCRIPTION
        A receipt describes a release that has already shipped. Moving the patcher pin afterwards
        does not make it wrong, but holding it to the working catalog said it was: after the pin
        moved to 1.13.0 while the tree still carried the receipt for the 0.32.0 release, every
        source push failed with "The receipt was stamped by patcher 1.12.0; the catalog pins
        1.13.0", with both files correct. Only the checkout that cut the release has a receipt at
        all, so the gate stopped exactly the machine that had done the work, and the way through
        was to move the receipt out of the tree, which turns the check off altogether.

        On a release push the receipt's commit is the release commit, so this reads the same
        catalog the working tree has and nothing is relaxed.

        Answers @{ Toolchain; Note }, where Note is a line worth printing or $null when the
        answer is simply the working catalog.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [string]$Commit,
        [Parameter(Mandatory = $true)]$WorkingToolchain
    )

    if ($Commit -notmatch '^[0-9a-f]{40}$') {
        return [pscustomobject]@{ Toolchain = $WorkingToolchain; Note = $null }
    }

    # Stderr is dropped inside the helper, because git writes "path does not exist in commit"
    # there and an empty result here has to mean "no catalog at that commit", not "git said
    # something".
    $catalogAtCommit = (Invoke-RepoGit -Root $Root -Arguments @('show', "${Commit}:gradle/libs.versions.toml")) -join "`n"
    $short = $Commit.Substring(0, 8)
    if ([string]::IsNullOrWhiteSpace($catalogAtCommit)) {
        return [pscustomobject]@{
            Toolchain = $WorkingToolchain
            Note = "commit $short has no version catalog, so the receipt is held to the working one"
        }
    }

    $atCommit = Read-CatalogToolchain -Text $catalogAtCommit `
        -Source "gradle/libs.versions.toml at $short"
    if ($atCommit.PatcherVersion -eq $WorkingToolchain.PatcherVersion -and
        $atCommit.ManagerFloor -eq $WorkingToolchain.ManagerFloor) {
        return [pscustomobject]@{ Toolchain = $atCommit; Note = $null }
    }
    return [pscustomobject]@{
        Toolchain = $atCommit
        Note = ("the receipt is held to patcher $($atCommit.PatcherVersion) and Manager floor " +
            "$($atCommit.ManagerFloor), which its own commit $short pinned; the catalog now pins " +
            "$($WorkingToolchain.PatcherVersion) and $($WorkingToolchain.ManagerFloor)")
    }
}

function Resolve-IndexManagerFloor {
    <#
    .SYNOPSIS
        The Manager floor the index description has to name: the one the published release's own
        commit pinned, found through its tag.
    .DESCRIPTION
        The index describes a release that has shipped, and that release needs the Manager its own
        commit pinned, whatever the catalog pins since. 0.1.1 was stamped by patcher 1.14.0 and
        needs Manager 1.31.0 after the catalog moved on to 1.14.1 and 1.32.0 for the next release,
        so neither the working catalog nor the index version alone gives the answer. The tag
        v<version> says which commit it was.

        -Commit is that commit when the caller has read the published tag already, as the release
        check's published asset run does. Without it, -RemoteUrl is asked first: a local tag can
        name another commit than GitHub's after a release is re-cut there, and git fetch won't move
        it, and `gh release create` makes the tag on GitHub only, so the push that writes a new
        description comes from a clone without one. The clone's own tag answers when no remote is
        named, or when the remote doesn't have the tag or can't be asked. A tag found nowhere, a
        commit this clone doesn't hold, or one with no catalog can't tell, and answers Floor $null
        with a Note saying the check didn't run.

        Answers @{ Floor; Source; Note }.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$Version,
        [string]$Commit,
        [string]$RemoteUrl
    )

    $tag = "v$Version"
    $source = "tag $tag"
    $unchecked = 'so the Manager floor the index names wasn''t checked'
    $remoteSaid = ''
    if ($Commit -notmatch '^[0-9a-f]{40}$' -and $RemoteUrl) {
        $advertised = @(Invoke-RepoGit -Root $Root -Arguments @('ls-remote', $RemoteUrl, "refs/tags/$tag", "refs/tags/$tag^{}"))
        $asked = $LASTEXITCODE -eq 0
        # An annotated tag's own line names the tag object; the peeled line names its commit.
        $escaped = [regex]::Escape("refs/tags/$tag")
        $line = @(@($advertised | Where-Object { "$_" -match "^[0-9a-f]{40}\s+$escaped\^\{\}$" }) +
            @($advertised | Where-Object { "$_" -match "^[0-9a-f]{40}\s+$escaped$" })) | Select-Object -First 1
        if (-not $asked) {
            $remoteSaid = " and $RemoteUrl couldn't be asked for it"
        } elseif (-not $line) {
            $remoteSaid = " or on $RemoteUrl"
        } else {
            $Commit = ([string]$line).Substring(0, 40)
            $source = "tag $tag on $RemoteUrl"
        }
    }
    if ($Commit -notmatch '^[0-9a-f]{40}$') {
        $Commit = "$(Invoke-RepoGit -Root $Root -Arguments @('rev-parse', '--verify', '--quiet', "refs/tags/$tag^{commit}") |
            Select-Object -First 1)".Trim()
    }
    if ($Commit -notmatch '^[0-9a-f]{40}$') {
        return [pscustomobject]@{ Floor = $null; Source = $null
            Note = "tag $tag isn't in this clone$remoteSaid, $unchecked" }
    }
    $held = "$(Invoke-RepoGit -Root $Root -Arguments @('rev-parse', '--verify', '--quiet', "$Commit^{commit}") |
        Select-Object -First 1)".Trim()
    if ($held -notmatch '^[0-9a-f]{40}$') {
        return [pscustomobject]@{ Floor = $null; Source = $null
            Note = "$source names commit $($Commit.Substring(0, 8)), which this clone doesn't have, $unchecked" }
    }
    $catalogAtTag = (Invoke-RepoGit -Root $Root -Arguments @('show', "${held}:gradle/libs.versions.toml")) -join "`n"
    if ([string]::IsNullOrWhiteSpace($catalogAtTag)) {
        return [pscustomobject]@{ Floor = $null; Source = $null; Note = "$source has no version catalog, $unchecked" }
    }
    $atTag = Read-CatalogToolchain -Text $catalogAtTag -Source "gradle/libs.versions.toml at $source"
    return [pscustomobject]@{ Floor = $atTag.ManagerFloor; Source = $source; Note = $null }
}

function Get-SelectionProperty {
    <#
    .SYNOPSIS
        One field of a parsed JSON object or a hashtable, matched by its exact name.
    .DESCRIPTION
        PowerShell's own member access ignores case, so "Use" would answer for "use" and a
        compatibility file could name a field the check never meant to read. An array value comes
        back whole: assign the answer directly, never inside @(...), which nests it.
    #>
    param($Object, [string]$Name)
    if ($null -eq $Object) { return $null }
    if ($Object -is [System.Collections.IDictionary]) {
        foreach ($key in $Object.Keys) {
            if ([string]$key -ceq $Name) { return ,$Object[$key] }
        }
    } else {
        foreach ($property in $Object.PSObject.Properties) {
            if ($property.Name -ceq $Name) { return ,$property.Value }
        }
    }
    return $null
}

function Test-SelectionFields {
    <#
    .SYNOPSIS
        Whether an object carries exactly these field names, in any order and no others.
    #>
    param($Object, [string[]]$Names)
    if ($Object -is [System.Collections.IDictionary]) { $actual = @($Object.Keys) }
    elseif ($Object -is [pscustomobject]) { $actual = @($Object.PSObject.Properties.Name) }
    else { return $false }
    if ($actual.Count -ne $Names.Count) { return $false }
    foreach ($name in $Names) { if ($actual -cnotcontains $name) { return $false } }
    return $true
}

function Resolve-ReceiptCatalog {
    <#
    .SYNOPSIS
        The patch list a receipt should be held to: the one its own commit carried.
    .DESCRIPTION
        The same reasoning as Resolve-ReceiptToolchain, for the patch names and the target. A patch
        added or renamed after a release doesn't make that release's receipt wrong, but holding
        the receipt to the working patch list said it was. While the source version stays on the
        released one, as it did through the hold after 0.58.0, every such push failed on the one
        machine that has a receipt. On Hushfeed that read: "The receipt reports com.zhiliaoapp.musically
        46.2.3 patch Hide quick comment reactions, which the catalog does not list."

        -RequireHistory refuses missing history instead of falling back to the working list. The
        patch selection check needs the published catalog itself, receipt or no receipt here, and a
        fallback would compare the working list with itself and pass.

        On a release push the receipt's commit is the release commit, so this reads the list the
        working tree has and nothing is relaxed.

        Answers @{ PatchList; Note }: the parsed patches-list.json to hold the receipt to, and a
        line worth printing, or $null when the answer is simply the working list.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [string]$Commit,
        [Parameter(Mandatory = $true)]$WorkingPatchList,
        [switch]$RequireHistory
    )

    if ($Commit -notmatch '^[0-9a-f]{40}$') {
        if ($RequireHistory) { throw 'The published catalog needs an exact historical commit.' }
        return [pscustomobject]@{ PatchList = $WorkingPatchList; Note = $null }
    }
    if ($RequireHistory) {
        $kind = Invoke-RepoGit -Root $Root -Arguments @('cat-file', '-t', $Commit)
        if ($LASTEXITCODE -ne 0 -or "$kind".Trim() -cne 'commit') {
            throw "The published catalog commit $Commit is unavailable in this clone."
        }
    }
    $listAtCommit = (Invoke-RepoGit -Root $Root -Arguments @('show', "${Commit}:patches-list.json")) -join "`n"
    if ($RequireHistory -and $LASTEXITCODE -ne 0) {
        throw "Published commit $Commit has no readable patches-list.json."
    }
    $short = $Commit.Substring(0, 8)
    if ([string]::IsNullOrWhiteSpace($listAtCommit)) {
        if ($RequireHistory) { throw "Published commit $short has no readable patches-list.json." }
        return [pscustomobject]@{
            PatchList = $WorkingPatchList
            Note = "commit $short has no patch list, so the receipt is held to the working one"
        }
    }
    try {
        $atCommit = $listAtCommit | ConvertFrom-Json
    } catch {
        throw "patches-list.json at $short is not readable: $($_.Exception.Message)"
    }
    if ($RequireHistory -and ($null -eq $atCommit -or @($atCommit.patches).Count -eq 0)) {
        throw "Published commit $short has no usable patch catalog."
    }
    $namesThen =@($atCommit.patches | ForEach-Object { [string]$_.name } | Sort-Object) -join "`n"
    $namesNow = @($WorkingPatchList.patches | ForEach-Object { [string]$_.name } | Sort-Object) -join "`n"
    $targetsThen = ($atCommit.patches | ForEach-Object { $_.compatiblePackages | ConvertTo-Json -Compress -Depth 4 } | Sort-Object -Unique) -join "`n"
    $targetsNow = ($WorkingPatchList.patches | ForEach-Object { $_.compatiblePackages | ConvertTo-Json -Compress -Depth 4 } | Sort-Object -Unique) -join "`n"
    if ($namesThen -eq $namesNow -and $targetsThen -eq $targetsNow) {
        return [pscustomobject]@{ PatchList = $atCommit; Note = $null }
    }
    return [pscustomobject]@{
        PatchList = $atCommit
        Note = ("the receipt is held to the $(@($atCommit.patches).Count) patches and the target its " +
            "own commit $short carried; the working list has $(@($WorkingPatchList.patches).Count)")
    }
}

function Resolve-PublishedCatalogBaseline {
    <#
    .SYNOPSIS
        The patch catalog the last published release shipped, proved from what that release hosts.
    .DESCRIPTION
        Morphe Manager remembers which patches someone picked by name, and starts a patch they never
        touched at its "use" default. A rename or removal drops their choice and a flipped default
        changes what a new install gets, so the next release is compared with the catalog people
        actually have. That catalog is read at the commit the published tag names, and the tag is
        held to the hosted receipt: the receipt's checksum has to be the one SHA256SUMS.txt lists,
        its version and commit have to be the tag's (peeled when annotated), and its bundle name and
        hash have to be the ones the same checksum list gives the indexed bundle.

        -Index is the published index (patches-bundle.json) naming the predecessor. With
        -PredecessorIndexAbsent the caller has proved there is no index at all, and that is a first
        release only while the remote has no earlier version tag either. An asset that can't be
        downloaded is never read as a first release.

        Answers @{ FirstRelease; PatchList; Version; Commit }, or throws naming what didn't line up.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)]$WorkingPatchList,
        [Parameter(Mandatory = $true)][string]$CandidateVersion,
        [AllowNull()]$Index,
        [switch]$PredecessorIndexAbsent,
        [AllowNull()][byte[]]$ReceiptBytes,
        [string]$ChecksumText,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$RemoteTagsText,
        [Parameter(Mandatory = $true)][int]$RemoteTagsExitCode
    )
    if ($CandidateVersion -notmatch '^\d+\.\d+\.\d+$') { throw 'The candidate version is invalid.' }
    if ($RemoteTagsExitCode -ne 0) { throw 'Published tag history is unavailable.' }
    $tags = @([regex]::Matches($RemoteTagsText,
        '(?m)^([0-9a-f]{40}|[0-9a-f]{64})\s+refs/tags/v(\d+\.\d+\.\d+)(-[0-9A-Za-z.-]+)?(\^\{\})?\s*$'))
    if ($PredecessorIndexAbsent) {
        if ($null -ne $Index) { throw 'A first release cannot have a predecessor index.' }
        $earlier = @($tags | Where-Object {
            [version]$_.Groups[2].Value -lt [version]$CandidateVersion -or
            ($_.Groups[2].Value -ceq $CandidateVersion -and $_.Groups[3].Success)
        })
        if ($earlier.Count -gt 0) { throw 'The predecessor index is absent but earlier version history exists.' }
        return [pscustomobject]@{ FirstRelease = $true; PatchList = $null; Version = $null; Commit = $null }
    }
    if ($null -eq $Index) { throw 'The predecessor index is unavailable, not confirmed absent.' }
    $version = [string](Get-SelectionProperty $Index 'version')
    if ($version -notmatch '^\d+\.\d+\.\d+$' -or [version]$version -gt [version]$CandidateVersion) {
        throw 'The predecessor index names an invalid or newer version.'
    }
    $uri = $null
    if (-not [Uri]::TryCreate([string](Get-SelectionProperty $Index 'download_url'), [UriKind]::Absolute, [ref]$uri) -or
        $uri.Scheme -cne 'https' -or $uri.Host -cne 'github.com' -or $uri.Port -ne 443 -or
        $uri.UserInfo -or $uri.Query -or $uri.Fragment -or
        $uri.AbsolutePath -notmatch ('^/[^/]+/[^/]+/releases/download/v' + [regex]::Escape($version) + '/patches-' + [regex]::Escape($version) + '\.mpp$')) {
        throw 'The predecessor index does not name its exact hosted bundle.'
    }
    $named = @($tags | Where-Object { $_.Groups[2].Value -ceq $version -and -not $_.Groups[3].Success })
    $plain = @($named | Where-Object { -not $_.Groups[4].Success })
    $peeled = @($named | Where-Object { $_.Groups[4].Success })
    if ($plain.Count -ne 1 -or $peeled.Count -gt 1) { throw "Published tag v$version is missing or ambiguous." }
    $commit = if ($peeled.Count -eq 1) { $peeled[0].Groups[1].Value } else { $plain[0].Groups[1].Value }
    if ($null -eq $ReceiptBytes -or $ReceiptBytes.Length -eq 0 -or [string]::IsNullOrWhiteSpace($ChecksumText)) {
        throw 'The hosted predecessor receipt or checksum is unavailable.'
    }
    function Read-HostedChecksum([string]$Name) {
        $ending = '(?:^|[\t ])\*?' + [regex]::Escape($Name) + '[\t ]*$'
        $lines = @($ChecksumText.TrimStart([char]0xFEFF) -split '\r?\n' | Where-Object { $_ -cmatch $ending })
        if ($lines.Count -ne 1) { throw "The hosted checksum must list exactly one $Name." }
        $line = [regex]::Match($lines[0], '^[\t ]*([0-9a-fA-F]{64})[\t ]+\*?' + [regex]::Escape($Name) + '[\t ]*$')
        if (-not $line.Success) { throw "The hosted checksum entry for $Name is malformed." }
        return $line.Groups[1].Value.ToLowerInvariant()
    }
    $filename = "release-receipt-$version.json"
    $listedHash = Read-HostedChecksum $filename
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $hash = ([BitConverter]::ToString($sha.ComputeHash($ReceiptBytes))).Replace('-', '').ToLowerInvariant() }
    finally { $sha.Dispose() }
    if ($hash -cne $listedHash) { throw 'The hosted predecessor receipt checksum does not match.' }
    try { $receipt = [Text.Encoding]::UTF8.GetString($ReceiptBytes).TrimStart([char]0xFEFF) | ConvertFrom-Json }
    catch { throw 'The hosted predecessor receipt is not readable JSON.' }
    $release = Get-SelectionProperty $receipt 'release'
    $receiptVersion = Get-SelectionProperty $release 'version'
    $receiptCommit = Get-SelectionProperty $release 'commit'
    if ($receiptVersion -isnot [string] -or $receiptCommit -isnot [string] -or
        $receiptVersion -cne $version -or $receiptCommit -cne $commit) {
        throw 'The hosted predecessor receipt version/commit does not match the peeled published tag.'
    }
    $bundleName = "patches-$version.mpp"
    $bundle = Get-SelectionProperty $receipt 'bundle'
    $bundleFile = Get-SelectionProperty $bundle 'file'
    $bundleHash = Get-SelectionProperty $bundle 'sha256'
    $listedBundleHash = Read-HostedChecksum $bundleName
    if ($bundleFile -isnot [string] -or $bundleFile -cne $bundleName -or
        $bundleHash -isnot [string] -or $bundleHash -notmatch '^[0-9a-fA-F]{64}$' -or
        $bundleHash.ToLowerInvariant() -cne $listedBundleHash) {
        throw 'The predecessor receipt bundle filename/hash does not match the indexed bundle checksum.'
    }
    $resolved = Resolve-ReceiptCatalog -Root $Root -Commit $commit -WorkingPatchList $WorkingPatchList -RequireHistory
    if ([string]$resolved.PatchList.version -cne "v$version") { throw 'The historical catalog version does not match the published receipt.' }
    return [pscustomobject]@{ FirstRelease = $false; PatchList = $resolved.PatchList; Version = $version; Commit = $commit }
}

function Resolve-CatalogBaselineIndex {
    <#
    .SYNOPSIS
        Which published index names the release the patch selection check compares with.
    .DESCRIPTION
        An ordinary push compares with the working patches-bundle.json, the release people have now.
        The index push that publishes a release has already pointed that file at the new release,
        so comparing with it would hold the release to itself and always pass. That run reads the
        index at the candidate's verified source commit instead, which still names the release
        before it. An index missing there answers PredecessorIndexAbsent, and only a commit git
        can read counts: an unavailable commit or an unreadable file throws.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [AllowNull()]$WorkingIndex,
        [string]$CandidateCommit,
        [switch]$VerifyPublishedAsset
    )
    if (-not $VerifyPublishedAsset) {
        if ($null -eq $WorkingIndex) { throw 'The working published index is unavailable.' }
        return [pscustomobject]@{ Index = $WorkingIndex; PredecessorIndexAbsent = $false }
    }
    if ($CandidateCommit -notmatch '^[0-9a-f]{40}$') { throw 'A published candidate needs its verified source commit.' }
    $kind = Invoke-RepoGit -Root $Root -Arguments @('cat-file', '-t', $CandidateCommit)
    if ($LASTEXITCODE -ne 0 -or "$kind".Trim() -cne 'commit') { throw 'The verified candidate source commit is unavailable.' }
    $entries = @(Invoke-RepoGit -Root $Root -Arguments @('ls-tree', '--name-only', $CandidateCommit, '--', 'patches-bundle.json'))
    if ($LASTEXITCODE -ne 0) { throw 'The predecessor index could not be probed at the candidate source commit.' }
    if ($entries.Count -eq 0) {
        return [pscustomobject]@{ Index = $null; PredecessorIndexAbsent = $true }
    }
    if ($entries.Count -ne 1 -or [string]$entries[0] -cne 'patches-bundle.json') { throw 'The predecessor index tree entry is ambiguous.' }
    $text = (Invoke-RepoGit -Root $Root -Arguments @('show', "${CandidateCommit}:patches-bundle.json")) -join "`n"
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($text)) { throw 'The predecessor index is unreadable at the candidate source commit.' }
    try { $index = $text | ConvertFrom-Json } catch { throw 'The predecessor index at the candidate source commit is not readable JSON.' }
    if ($null -eq $index) { throw 'The predecessor index at the candidate source commit is empty.' }
    return [pscustomobject]@{ Index = $index; PredecessorIndexAbsent = $false }
}

function Test-PatchCatalogCompatibility {
    <#
    .SYNOPSIS
        Whether every published patch keeps its name and its "use" default, or the change is declared.
    .DESCRIPTION
        Adding a patch is always fine. A rename, a removal or a flipped default has to be listed in
        scripts/patch-catalog-compatibility.json for this exact published version and commit and
        this candidate version, each with a migration note that appears word for word in the
        candidate's CHANGELOG section (Unreleased while the version is held at the published one).
        A rename or removal also has to warn about the selection reset and the manual re-selection,
        since Morphe doesn't carry a choice across a new name. Declarations that duplicate, don't
        match the catalogs or change nothing are refused, so a consumed one can't linger.

        Names compare ordinally, so a change of case is a rename. Answers @{ Valid; Reason }.
    #>
    param(
        [AllowNull()]$BaselinePatchList,
        [string]$BaselineVersion,
        [string]$BaselineCommit,
        [Parameter(Mandatory = $true)]$CandidatePatchList,
        [Parameter(Mandatory = $true)][string]$CandidateVersion,
        [AllowNull()]$Compatibility,
        [AllowEmptyString()][string]$Changelog,
        [switch]$FirstRelease
    )
    function Fail([string]$Reason) { return [pscustomobject]@{ Valid = $false; Reason = $Reason } }
    function Read-Selections($Catalog, [string]$Label) {
        $patches = Get-SelectionProperty $Catalog 'patches'
        if ($patches -isnot [array] -and $patches -isnot [System.Collections.IList]) { throw "$Label patches is not an array." }
        if ($patches.Count -eq 0) { throw "$Label contains no patches." }
        $map = [System.Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
        foreach ($patch in $patches) {
            $name = Get-SelectionProperty $patch 'name'
            $use = Get-SelectionProperty $patch 'use'
            if ($name -isnot [string] -or [string]::IsNullOrWhiteSpace($name) -or $name.Trim() -cne $name -or $name -match '[\r\n]') {
                throw "$Label has an invalid patch name."
            }
            if ($use -isnot [bool]) { throw "$Label patch $name has no Boolean use field." }
            if ($map.ContainsKey($name)) { throw "$Label duplicates patch $name." }
            $map.Add($name, [pscustomobject]@{ name = $name; use = $use })
        }
        return ,$map
    }
    function Read-SelectionState($State, [string]$Label) {
        if (-not (Test-SelectionFields $State @('name', 'use'))) { throw "$Label must contain exactly name and use." }
        $name = Get-SelectionProperty $State 'name'
        $use = Get-SelectionProperty $State 'use'
        if ($name -isnot [string] -or [string]::IsNullOrWhiteSpace($name) -or $name.Trim() -cne $name -or $name -match '[\r\n]' -or $use -isnot [bool]) {
            throw "$Label must have an exact name and Boolean use."
        }
        return [pscustomobject]@{ name = $name; use = $use }
    }
    try {
        if ($CandidateVersion -notmatch '^\d+\.\d+\.\d+$' -or
            [string](Get-SelectionProperty $CandidatePatchList 'version') -cne "v$CandidateVersion") {
            throw 'The candidate catalog version is invalid or mismatched.'
        }
        $now = Read-Selections $CandidatePatchList 'Candidate catalog'
        if ($FirstRelease) {
            if ($null -ne $BaselinePatchList -or $null -ne $Compatibility -or $BaselineVersion -or $BaselineCommit) {
                throw 'A first-release comparison cannot carry a baseline or compatibility declarations.'
            }
            return [pscustomobject]@{ Valid = $true; Reason = $null }
        }
        if ($BaselineVersion -notmatch '^\d+\.\d+\.\d+$' -or $BaselineCommit -notmatch '^[0-9a-f]{40}$' -or
            [string](Get-SelectionProperty $BaselinePatchList 'version') -cne "v$BaselineVersion" -or
            [version]$CandidateVersion -lt [version]$BaselineVersion) {
            throw 'A comparison needs the exact published baseline version and commit.'
        }
        $then = Read-Selections $BaselinePatchList 'Published catalog'
        $declared = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        $destinations = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        if ($null -ne $Compatibility) {
            foreach ($field in @('baselineVersion', 'baselineCommit', 'candidateVersion')) {
                if ((Get-SelectionProperty $Compatibility $field) -isnot [string]) {
                    throw 'Compatibility metadata does not identify this exact baseline and candidate.'
                }
            }
            if (-not (Test-SelectionFields $Compatibility @('baselineVersion', 'baselineCommit', 'candidateVersion', 'changes')) -or
                (Get-SelectionProperty $Compatibility 'baselineVersion') -cne $BaselineVersion -or
                (Get-SelectionProperty $Compatibility 'baselineCommit') -cne $BaselineCommit -or
                (Get-SelectionProperty $Compatibility 'candidateVersion') -cne $CandidateVersion) {
                throw 'Compatibility metadata does not identify this exact baseline and candidate.'
            }
            $changes = Get-SelectionProperty $Compatibility 'changes'
            if (($changes -isnot [array] -and $changes -isnot [System.Collections.IList]) -or $changes.Count -eq 0) {
                throw 'Compatibility changes must be a nonempty array.'
            }
            $section = if ($BaselineVersion -ceq $CandidateVersion) { 'Unreleased' } else { $CandidateVersion }
            # Only what a reader sees counts. A note inside an HTML comment, closed or not, is no warning.
            $visibleChangelog = [regex]::Replace($Changelog, '(?s)<!--.*?(?:-->|\z)', '')
            $bodies = New-Object System.Collections.Generic.List[string]
            $headings = @([regex]::Matches($visibleChangelog, '(?m)^##\s+[^\r\n]+'))
            for ($i = 0; $i -lt $headings.Count; $i++) {
                $heading = $headings[$i].Value
                $matchesSection = if ($section -ceq 'Unreleased') { $heading -cmatch '^##\s+Unreleased\s*$' } else {
                    $v = [regex]::Match($heading, '^##\s+\[?v?(\d+\.\d+\.\d+)(?:\]|\s|$)')
                    $v.Success -and $v.Groups[1].Value -ceq $section
                }
                if ($matchesSection) {
                    $start = $headings[$i].Index + $headings[$i].Length
                    $end = if ($i + 1 -lt $headings.Count) { $headings[$i + 1].Index } else { $visibleChangelog.Length }
                    $bodies.Add($visibleChangelog.Substring($start, $end - $start))
                }
            }
            if ($bodies.Count -ne 1) { throw "Compatibility notes need exactly one CHANGELOG section for $section." }
            foreach ($change in $changes) {
                if (-not (Test-SelectionFields $change @('before', 'after', 'migrationNote'))) { throw 'A compatibility change has malformed fields.' }
                $before = Read-SelectionState (Get-SelectionProperty $change 'before') 'Before'
                $afterValue = Get-SelectionProperty $change 'after'
                $after = if ($null -eq $afterValue) { $null } else { Read-SelectionState $afterValue 'After' }
                if (-not $declared.Add($before.name)) { throw "Compatibility duplicates $($before.name)." }
                if (-not $then.ContainsKey($before.name) -or $then[$before.name].use -ne $before.use) {
                    throw "Compatibility before state does not match published $($before.name)."
                }
                $renamed = $null -ne $after -and $after.name -cne $before.name
                if ($null -eq $after -or $renamed) {
                    if ($now.ContainsKey($before.name)) { throw "Compatibility removal/rename of $($before.name) is unused." }
                } elseif ($before.use -eq $after.use) { throw "Compatibility for $($before.name) is a no-op." }
                if ($null -ne $after) {
                    if (-not $destinations.Add($after.name) -or ($renamed -and $then.ContainsKey($after.name))) {
                        throw "Compatibility destination $($after.name) collides."
                    }
                    if (-not $now.ContainsKey($after.name) -or $now[$after.name].use -ne $after.use) {
                        throw "Compatibility after state does not match candidate $($after.name)."
                    }
                }
                $note = Get-SelectionProperty $change 'migrationNote'
                if ($note -isnot [string] -or [string]::IsNullOrWhiteSpace($note) -or $note -match '[\r\n]' -or
                    $bodies[0].IndexOf($note, [StringComparison]::Ordinal) -lt 0) {
                    throw "Compatibility for $($before.name) has no exact migration note in $section."
                }
                if (($null -eq $after -or $renamed) -and
                    ($note -notmatch '(?i)\bselection reset\b' -or $note -notmatch '(?i)\bmanual re-selection\b')) {
                    throw "Removing/renaming $($before.name) needs a selection reset and manual re-selection warning."
                }
            }
        }
        foreach ($name in $then.Keys) {
            if ((-not $now.ContainsKey($name) -or $then[$name].use -ne $now[$name].use) -and -not $declared.Contains($name)) {
                throw "Patch $name was removed, renamed or changed its default without compatibility metadata."
            }
        }
        return [pscustomobject]@{ Valid = $true; Reason = $null }
    } catch { return Fail $_.Exception.Message }
}

function Test-ReleaseReceipt {
    <#
    .SYNOPSIS
        Whether a receipt describes this checkout's release, and whether its manifest changes
        were reviewed.
    .DESCRIPTION
        Answers @{ Valid; Reason }. Every failure names the one fact that did not line up,
        because the point of the receipt is to say which input was wrong, not that something was.
    #>
    param(
        [Parameter(Mandatory = $true)]$Receipt,
        [Parameter(Mandatory = $true)][string]$ExpectedVersion,
        [Parameter(Mandatory = $true)][string[]]$ExpectedPatchNames,
        [Parameter(Mandatory = $true)][string]$ExpectedPatcherVersion,
        [Parameter(Mandatory = $true)][string]$ExpectedManagerFloor,
        # The package and every version the catalog declares. Without them a receipt built only
        # from forced runs against newer builds reads as proof of the release, when nothing in it
        # was patched the way a user's Manager patches it. Each declared version needs its own
        # run: the README says the patches were checked on every one of them.
        [Parameter(Mandatory = $true)][string]$ExpectedPackageName,
        [Parameter(Mandatory = $true)][string[]]$ExpectedPackageVersions,
        # The version codes the catalog pins those versions to (Get-PatchTarget's PackageVersionCodes).
        # Another build of a declared version isn't the declared build, so a run of it proves nothing
        # without -f. A version pinned to no code is matched by its name.
        [System.Collections.IDictionary]$ExpectedPackageVersionCodes,
        [string]$BundlePath,
        [string[]]$ApprovedManifestDelta = @(),
        # When the commit the receipt names was made, read out of git by the caller. Without it
        # the receipt's commit and its timestamp are only checked against each other, which any
        # receipt agrees with, including one left over from an earlier release.
        [long]$ActualCommitTimestamp = 0,
        # The commit this run is about. Only a release is held to it: on an ordinary push the
        # receipt legitimately describes the commit it was generated at, not HEAD.
        [string]$ExpectedCommit,
        # The schema the receipt's own commit writes (Resolve-ReceiptSchema). From 2 a receipt
        # names the release SBOM.
        [int]$ExpectedSchemaVersion = (Get-ReleaseReceiptSchemaVersion),
        # The SBOM itself, when the caller has it: its hash and component count have to be what
        # the receipt records, and with -BundlePath it has to describe that bundle.
        [string]$SbomPath,
        [string]$ToolingPath,
        [string]$ToolingScopeManifestText,
        [switch]$RequireToolingAudit
    )

    function Fail { param([string]$Reason) return [pscustomobject]@{ Valid = $false; Reason = $Reason } }

    if ($null -eq $Receipt) { return Fail 'There is no receipt to check.' }
    if ([int]$Receipt.schemaVersion -ne $ExpectedSchemaVersion) {
        return Fail ("The receipt is schema version $($Receipt.schemaVersion); its release is read at " +
            "version $ExpectedSchemaVersion.")
    }
    if ($Receipt.release.version -ne $ExpectedVersion) {
        return Fail "The receipt is for $($Receipt.release.version), not $ExpectedVersion."
    }
    if ($Receipt.release.tag -ne "v$ExpectedVersion") {
        return Fail "The receipt names tag $($Receipt.release.tag) for version $ExpectedVersion."
    }
    if ($Receipt.release.commit -notmatch '^[0-9a-f]{40}$') {
        return Fail "The receipt has no full commit: $($Receipt.release.commit)"
    }
    if ([long]$Receipt.release.commitTimestamp -le 0) {
        return Fail 'The receipt does not say when the commit it names was made.'
    }
    if ($ExpectedCommit -and $Receipt.release.commit -ne $ExpectedCommit) {
        return Fail ("The receipt describes commit $($Receipt.release.commit); this release is " +
            "$ExpectedCommit.")
    }
    # Against git, not against the receipt's own other field. The bundle stamp check below
    # compares two numbers that both came out of this document, so on its own it proves only
    # that the document agrees with itself.
    if ($ActualCommitTimestamp -gt 0 -and
            [long]$Receipt.release.commitTimestamp -ne $ActualCommitTimestamp) {
        return Fail ("The receipt says commit $($Receipt.release.commit) was made at " +
            "$($Receipt.release.commitTimestamp); git says $ActualCommitTimestamp.")
    }
    if ([int]$Receipt.release.patchCount -ne $ExpectedPatchNames.Count) {
        return Fail ("The receipt counts $($Receipt.release.patchCount) patches; the catalog " +
            "has $($ExpectedPatchNames.Count).")
    }
    if ($Receipt.toolchain.patcherVersion -ne $ExpectedPatcherVersion) {
        return Fail ("The receipt was stamped by patcher $($Receipt.toolchain.patcherVersion); " +
            "the catalog pins $ExpectedPatcherVersion.")
    }
    if ($Receipt.toolchain.managerFloor -ne $ExpectedManagerFloor) {
        return Fail ("The receipt names Manager floor $($Receipt.toolchain.managerFloor); " +
            "the catalog pins $ExpectedManagerFloor.")
    }
    if ($ExpectedSchemaVersion -ge 2) {
        $sbom = $Receipt.sbom
        if ($null -eq $sbom) { return Fail 'The receipt names no SBOM, so nothing records what the bundle carries.' }
        if ([string]$sbom.file -cne "patches-$ExpectedVersion.cdx.json") {
            return Fail ("The receipt names the SBOM $($sbom.file); the one for $ExpectedVersion is " +
                "patches-$ExpectedVersion.cdx.json.")
        }
        if ([string]$sbom.sha256 -cnotmatch '^[0-9A-F]{64}$') {
            return Fail "The receipt records no SHA-256 for $($sbom.file)."
        }
        $sbomCount = 0L
        if (-not [long]::TryParse("$($sbom.components)", [ref]$sbomCount) -or $sbomCount -le 0) {
            return Fail "The receipt counts no component in $($sbom.file)."
        }
    }

    if ($ExpectedSchemaVersion -ge 3) {
        $tooling = $Receipt.tooling
        if ($null -eq $tooling -or [string]$tooling.file -cne "patches-$ExpectedVersion.tooling.json" -or
                [string]$tooling.sha256 -notmatch '^[0-9a-f]{64}$') { return Fail 'The receipt names no valid release tooling report.' }
        foreach ($field in @('components', 'scopes')) {
            $count = 0L
            if (-not [long]::TryParse("$($tooling.$field)", [ref]$count) -or $count -le 0) { return Fail "The receipt counts no tooling $field." }
        }
        if ([string]$tooling.audit.status -cnotin @('checked', 'skipped')) { return Fail 'The receipt has no known tooling advisory status.' }
        if ($RequireToolingAudit -and [string]$tooling.audit.status -cne 'checked') { return Fail 'A skipped tooling advisory audit cannot authorize publication.' }
    }

    if ($BundlePath) {
        if (-not (Test-Path -LiteralPath $BundlePath -PathType Leaf)) {
            return Fail "The receipt cannot be checked against a bundle that is not there: $BundlePath"
        }
        $actualSize = (Get-Item -LiteralPath $BundlePath).Length
        if ([long]$Receipt.bundle.sizeBytes -ne $actualSize) {
            return Fail ("The receipt says the bundle is $($Receipt.bundle.sizeBytes) bytes; " +
                "$BundlePath is $actualSize.")
        }
        $actualHash = Get-Sha256Hex -Path $BundlePath
        if ([string]$Receipt.bundle.sha256 -ne $actualHash) {
            return Fail ("The receipt says the bundle hashes to $($Receipt.bundle.sha256); " +
                "$BundlePath hashes to $actualHash.")
        }

        $manifest = Get-BundleManifestFacts -BundlePath $BundlePath
        $identity = Get-BundleIdentityFacts -BundlePath $BundlePath
        if ($identity.Present -and (-not $identity.Valid -or $identity.SourceState -cne 'clean' -or
                $identity.SourceCommit -cne [string]$Receipt.release.commit)) {
            return Fail 'The bundle build identity does not bind to the clean source commit in the receipt.'
        }
        if ($manifest.version -ne $ExpectedVersion) {
            return Fail "The bundle's manifest says version $($manifest.version), not $ExpectedVersion."
        }
        if ($manifest.patcherVersion -ne $ExpectedPatcherVersion) {
            return Fail ("The bundle was stamped by patcher $($manifest.patcherVersion); the " +
                "catalog pins $ExpectedPatcherVersion.")
        }
        if ([long]$Receipt.bundle.timestamp -ne $manifest.timestamp) {
            return Fail ("The receipt says the bundle is stamped $($Receipt.bundle.timestamp); " +
                "$BundlePath is stamped $($manifest.timestamp).")
        }
        # The one fact that makes a published hash reproducible from a tag. patches/build.gradle.kts
        # pins this to the time of the commit it builds, and to 0 when the tree had uncommitted
        # changes as the build started, so anything else means the bundle was built from a
        # different commit, or from a tree with uncommitted changes in it, and nobody can rebuild
        # it from the source the receipt names. v0.28.0 shipped exactly that way. Until 2026-09-26
        # a tree with changes got HEAD's time as well, so a match didn't rule them out. It still
        # can't for an edit made after the build started, which build-release-receipt.ps1 catches
        # by refusing a bundle older than any of its sources.
        $expectedStamp = [long]$Receipt.release.commitTimestamp * 1000
        if ($manifest.timestamp -ne $expectedStamp) {
            return Fail ("The bundle is stamped $($manifest.timestamp) but the commit it is " +
                "attributed to was made at $expectedStamp. It was built from a different " +
                "commit, or from a tree that had uncommitted changes.")
        }
    }

    if ($SbomPath) {
        if ($ExpectedSchemaVersion -lt 2) { return Fail "A schema $ExpectedSchemaVersion receipt names no SBOM to hold $SbomPath to." }
        if (-not (Test-Path -LiteralPath $SbomPath -PathType Leaf)) {
            return Fail "The receipt cannot be checked against an SBOM that is not there: $SbomPath"
        }
        $actualSbomHash = Get-Sha256Hex -Path $SbomPath
        if ([string]$Receipt.sbom.sha256 -ne $actualSbomHash) {
            return Fail ("The receipt says $($Receipt.sbom.file) hashes to $($Receipt.sbom.sha256); " +
                "$SbomPath hashes to $actualSbomHash.")
        }
        try {
            $document = Read-ReleaseSbom -Path $SbomPath
        } catch {
            return Fail "The SBOM the receipt names can't be read: $($_.Exception.Message)"
        }
        if ($document.Components.Count -ne [long]$Receipt.sbom.components) {
            return Fail ("The receipt counts $($Receipt.sbom.components) components in $($Receipt.sbom.file); " +
                "it lists $($document.Components.Count).")
        }
        if ($BundlePath) {
            $bound = Test-ReleaseSbom -Sbom $document -BundlePath $BundlePath -BundleName ([string]$Receipt.bundle.file) `
                -ExpectedCommit ([string]$Receipt.release.commit)
            if (-not $bound.Valid) { return Fail $bound.Reason }
        }
    }

    if ($ToolingPath) {
        if ($ExpectedSchemaVersion -lt 3) { return Fail "A schema $ExpectedSchemaVersion receipt names no tooling report." }
        if (-not (Test-Path -LiteralPath $ToolingPath -PathType Leaf)) { return Fail "The tooling report is not there: $ToolingPath" }
        if ([string]$Receipt.tooling.sha256 -ne (Get-Sha256Hex -Path $ToolingPath)) { return Fail 'The tooling report changed after its advisory audit.' }
        try { $toolingDocument = Read-ReleaseTooling -Path $ToolingPath } catch { return Fail "The tooling report cannot be read: $($_.Exception.Message)" }
        if ($toolingDocument.Components.Count -ne [long]$Receipt.tooling.components -or
                $toolingDocument.Scopes.Count -ne [long]$Receipt.tooling.scopes) { return Fail 'The tooling report counts differ from the receipt.' }
        if (-not $BundlePath -or -not $ToolingScopeManifestText) { return Fail 'Tooling validation needs the bundle and the source commit scope manifest.' }
        $bound = Test-ReleaseTooling -Tooling $toolingDocument -BundlePath $BundlePath `
            -ExpectedCommit ([string]$Receipt.release.commit) -ScopeManifestText $ToolingScopeManifestText -BundleName $Receipt.bundle.file
        if (-not $bound.Valid) { return Fail $bound.Reason }
    }

    # Nulls dropped first. A receipt with the key missing altogether gives $null here, and
    # @($null) is an array holding one null, so the count guard below would walk past a truncated
    # document and then blame the nameless entry it found instead of the missing section.
    $payloads = @(@($Receipt.extension.dexPayloads) | Where-Object { $null -ne $_ })
    if ($payloads.Count -eq 0) {
        return Fail 'The receipt records no extension DEX payload, so it identifies no extension.'
    }
    foreach ($payload in $payloads) {
        if (-not $payload.name) { return Fail 'A recorded DEX payload has no name.' }
        if ([long]$payload.sizeBytes -le 0) {
            return Fail "The recorded DEX payload $($payload.name) is empty."
        }
        if ([string]$payload.sha256 -notmatch '^[0-9A-F]{64}$') {
            return Fail "The recorded DEX payload $($payload.name) has no SHA-256."
        }
    }
    # And to the bundle when there is one: every extension payload it carries, once, by name, size
    # and hash, and nothing else. Only the form was checked before, so a receipt could name
    # payloads the bundle doesn't carry while the SBOM beside it named the right ones.
    if ($BundlePath) {
        # Not @(...): the list comes back as one object, which @() would wrap in another array.
        try { $carried = Get-ExtensionPayloads -BundlePath $BundlePath }
        catch { return Fail "The bundle's extension payloads can't be read: $($_.Exception.Message)" }
        $recorded = @($payloads | ForEach-Object { '{0} ({1} bytes, {2})' -f $_.name, [long]$_.sizeBytes, [string]$_.sha256 })
        $actual = @($carried | ForEach-Object { '{0} ({1} bytes, {2})' -f $_.name, $_.sizeBytes, $_.sha256 })
        $extra = @($recorded | Where-Object { $actual -cnotcontains $_ })
        if ($extra.Count -gt 0) { return Fail "The receipt records an extension payload the bundle doesn't carry: $($extra[0])." }
        $missing = @($actual | Where-Object { $recorded -cnotcontains $_ })
        if ($missing.Count -gt 0) { return Fail "The receipt leaves out an extension payload the bundle carries: $($missing[0])." }
        if ($recorded.Count -ne $actual.Count) { return Fail 'The receipt records an extension payload more than once.' }
    }

    $targets = @(@($Receipt.targets) | Where-Object { $null -ne $_ })
    if ($targets.Count -eq 0) { return Fail 'The receipt records no target, so nothing was proved.' }

    $expected = [System.Collections.Generic.HashSet[string]]::new(
        [string[]]$ExpectedPatchNames, [System.StringComparer]::Ordinal)
    $produced = New-Object System.Collections.Generic.List[string]
    $provedVersions = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    # What Test-DeclaredBuild (patch-target.ps1) reads.
    $declaredTarget = [pscustomobject]@{
        PackageVersions = $ExpectedPackageVersions
        PackageVersionCodes = if ($null -ne $ExpectedPackageVersionCodes) { $ExpectedPackageVersionCodes } else { @{} }
    }
    foreach ($target in $targets) {
        $label = "$($target.source.package) $($target.source.versionName)"
        if ([string]$target.source.sha256 -notmatch '^[0-9A-F]{64}$') {
            return Fail "The receipt records no source APK hash for $label."
        }
        if ([string]$target.source.package -ne $ExpectedPackageName) {
            return Fail ("The receipt records a run against $($target.source.package); the " +
                "catalog targets $ExpectedPackageName.")
        }
        if ([string]::IsNullOrWhiteSpace([string]$target.source.versionName)) {
            return Fail "The receipt records a $ExpectedPackageName run with no version name."
        }
        # Whether the CLI was told to ignore the declared version. Recorded as a boolean by the
        # builder; a receipt that leaves it out cannot say which of its runs were the real one.
        $forcedProperty = $target.source.PSObject.Properties['forced']
        if ($null -eq $forcedProperty -or $forcedProperty.Value -isnot [bool]) {
            return Fail "The receipt does not say whether $label was patched under -f."
        }
        $atDeclaredVersion = Test-DeclaredBuild -Target $declaredTarget -VersionName ([string]$target.source.versionName) `
            -VersionCode ([string]$target.source.versionCode)
        if ($forcedProperty.Value -eq $atDeclaredVersion) {
            return Fail ("The receipt says $label (version code $($target.source.versionCode)) was " +
                $(if ($forcedProperty.Value) { 'forced past' } else { 'patched without -f at' }) +
                " a declared build, but the catalog declares $(Format-DeclaredBuilds -Target $declaredTarget).")
        }
        if ($atDeclaredVersion) { [void]$provedVersions.Add([string]$target.source.versionName) }
        $verdicts = @($target.patches)
        if ($verdicts.Count -ne $ExpectedPatchNames.Count) {
            return Fail ("The receipt records $($verdicts.Count) patch verdicts for $label; " +
                "the catalog has $($ExpectedPatchNames.Count).")
        }
        $seen = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
        foreach ($verdict in $verdicts) {
            $name = [string]$verdict.name
            if (-not $expected.Contains($name)) {
                return Fail "The receipt reports $label patch $name, which the catalog does not list."
            }
            if (-not $seen.Add($name)) {
                return Fail "The receipt reports $label patch $name twice."
            }
            if (-not $verdict.applied) {
                return Fail "The receipt reports $label patch $name as not applied."
            }
        }
        foreach ($entry in ConvertTo-ManifestDeltaEntries -Delta $target.manifestDelta) {
            $produced.Add($entry)
        }
    }
    # Every declared version, not just one of them. With two declared, a receipt that ran only
    # the newest still found one declared target, and the release would claim a build nothing
    # in it had patched.
    $unproved = @($ExpectedPackageVersions | Where-Object { -not $provedVersions.Contains([string]$_) })
    if ($unproved.Count -gt 0) {
        $ran = @($targets | ForEach-Object { [string]$_.source.versionName }) -join ', '
        return Fail ("No target in the receipt is the declared $ExpectedPackageName " +
            "$($unproved -join ', ') patched without -f; it only records $ran.")
    }

    # A PowerShell function that returns an empty array hands back nothing, so an allowlist with
    # no entries arrives here as $null, and @($null) is an array holding one null. Left alone,
    # that null is an approved entry no delta produces and every clean run fails on it.
    $approvedEntries = @(@($ApprovedManifestDelta) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    $approved = [System.Collections.Generic.HashSet[string]]::new(
        [string[]]$approvedEntries, [System.StringComparer]::Ordinal)
    $unapproved = @(@($produced | Sort-Object -Unique -CaseSensitive) | Where-Object { -not $approved.Contains($_) })
    if ($unapproved.Count -gt 0) {
        return Fail ('The patched manifest changed in ways nobody reviewed: ' +
            ($unapproved -join ', '))
    }
    $seenEntries = [System.Collections.Generic.HashSet[string]]::new(
        [string[]]@($produced), [System.StringComparer]::Ordinal)
    $stale = @($approvedEntries | Where-Object { -not $seenEntries.Contains($_) })
    if ($stale.Count -gt 0) {
        return Fail ('The manifest delta allowlist approves changes no patch makes any more: ' +
            ($stale -join ', '))
    }

    return [pscustomobject]@{ Valid = $true; Reason = $null }
}

function Test-StagedRelease {
    <#
    .SYNOPSIS
        Whether a staged release folder is the release its checksums and its receipt describe,
        before any of it is tagged or uploaded.
    .DESCRIPTION
        The publish stage used to look only for the five files and the commit the receipt names.
        A bundle, SBOM, tooling report or receipt changed after the bundle stage went out under
        SHA256SUMS.txt hashes that no longer matched, and the download-back comparison held the
        hosted copies to the same changed files. The index check catches that, once the release
        is public.

        SHA256SUMS.txt has to list the four assets for -Version and nothing else but Hush Unpacker's
        APK, each once, with the hash of the file staged beside it. The receipt then has to pass Test-ReleaseReceipt
        against the staged bundle, SBOM and tooling report, with its advisory audit run, and with
        -Expected giving the rest of Test-ReleaseReceipt's parameters. Answers @{ Valid; Reason }.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Folder,
        [Parameter(Mandatory = $true)][string]$Version,
        [Parameter(Mandatory = $true)][hashtable]$Expected
    )

    function Fail { param([string]$Reason) return [pscustomobject]@{ Valid = $false; Reason = $Reason } }

    $bundleName = "patches-$Version.mpp"
    $sbomName = "patches-$Version.cdx.json"
    $toolingName = "patches-$Version.tooling.json"
    $receiptName = "release-receipt-$Version.json"
    $assets = @($bundleName, $sbomName, $toolingName, $receiptName)
    # The bundle stage stages and lists Hush Unpacker's APK too. No receipt covers it, so its line
    # here is what holds it.
    $helperName = "hush-unpacker-$Version.apk"
    foreach ($name in $assets + 'SHA256SUMS.txt') {
        if (-not (Test-Path -LiteralPath (Join-Path $Folder $name) -PathType Leaf)) { return Fail "The staged release has no $name." }
    }

    # As the bundle stage writes it: one "<sha256>  <name>" line per asset, lower-case hex.
    $listed = @{}
    foreach ($line in ([IO.File]::ReadAllText((Join-Path $Folder 'SHA256SUMS.txt')) -split "`r?`n")) {
        if (-not $line) { continue }
        $entry = [regex]::Match($line, '^([0-9a-f]{64}) [ *](.+)$')
        if (-not $entry.Success) { return Fail "SHA256SUMS.txt has a line that isn't a checksum: $line" }
        $name = $entry.Groups[2].Value
        if ($assets -cnotcontains $name -and $name -cne $helperName) { return Fail "SHA256SUMS.txt lists $name, which isn't one of the release's assets." }
        if ($listed.ContainsKey($name)) { return Fail "SHA256SUMS.txt lists $name twice." }
        $listed[$name] = $entry.Groups[1].Value
    }
    foreach ($name in $assets + @(@($listed.Keys) -ceq $helperName)) {
        if (-not $listed.ContainsKey($name)) { return Fail "SHA256SUMS.txt has no entry for $name." }
        if (-not (Test-Path -LiteralPath (Join-Path $Folder $name) -PathType Leaf)) { return Fail "The staged release has no $name." }
        $staged = (Get-Sha256Hex -Path (Join-Path $Folder $name)).ToLowerInvariant()
        if ($listed[$name] -cne $staged) {
            return Fail "SHA256SUMS.txt lists $($listed[$name]) for $name, but the staged $name hashes to $staged."
        }
    }

    try {
        $receipt = [IO.File]::ReadAllText((Join-Path $Folder $receiptName)) | ConvertFrom-Json
    } catch {
        return Fail "The staged $receiptName can't be read: $($_.Exception.Message)"
    }
    if ($null -eq $receipt) { return Fail "The staged $receiptName is empty." }
    # Test-ReleaseReceipt holds the bundle it's handed to the receipt, not the name the receipt gives it.
    if ([string]$receipt.bundle.file -cne $bundleName) {
        return Fail "The staged receipt names the bundle $($receipt.bundle.file), not $bundleName."
    }
    $arguments = @{} + $Expected
    return Test-ReleaseReceipt @arguments -Receipt $receipt -ExpectedVersion $Version -BundlePath (Join-Path $Folder $bundleName) `
        -SbomPath (Join-Path $Folder $sbomName) -ToolingPath (Join-Path $Folder $toolingName) -RequireToolingAudit
}

function Test-StagedReleaseAt {
    <#
    .SYNOPSIS
        Test-StagedRelease, held to what the release commit carries.
    .DESCRIPTION
        The patch list, the toolchain pins, the tooling scope manifest and the commit's time come
        out of -Commit through git, and the reviewed manifest changes the way
        Resolve-ReceiptManifestAllowlist reads them, with -AllowlistPath (the checkout's own by
        default) for a commit that carries none. So the staged release is held to the commit its
        tag will name, and the receipt to that same commit. The bundle is then held to its source:
        its build identity has to name -Commit, clean, and its input digest has to be that of
        -Root, which has to be -Commit with nothing changed. Answers @{ Valid; Reason }.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$Folder,
        [Parameter(Mandatory = $true)][string]$Version,
        [Parameter(Mandatory = $true)][string]$Commit,
        [string]$AllowlistPath
    )

    function Fail { param([string]$Reason) return [pscustomobject]@{ Valid = $false; Reason = $Reason } }

    if ($Commit -notmatch '^[0-9a-f]{40}$') { return Fail "$Commit isn't a full commit to hold the staged release to." }
    if (-not $AllowlistPath) { $AllowlistPath = Join-Path $Root 'scripts/manifest-delta-allowlist.txt' }
    $short = $Commit.Substring(0, 8)
    try {
        $listText = @(Invoke-RepoGit -Root $Root -Arguments @('show', "${Commit}:patches-list.json")) -join "`n"
        if (-not $listText.Trim()) { return Fail "Commit $short carries no patches-list.json." }
        $list = $listText | ConvertFrom-Json
        $target = Get-PatchTarget -PatchList $list
        $toolchain = Read-CatalogToolchain -Source "gradle/libs.versions.toml at $short" `
            -Text (@(Invoke-RepoGit -Root $Root -Arguments @('show', "${Commit}:gradle/libs.versions.toml")) -join "`n")
        $allowlist = Resolve-ReceiptManifestAllowlist -Root $Root -Commit $Commit -WorkingPath $AllowlistPath
        $scopeText = @(Invoke-RepoGit -Root $Root -Arguments @('show', "${Commit}:gradle/tooling-scopes.txt")) -join "`n"
        if (-not $scopeText.Trim()) { return Fail "Commit $short carries no gradle/tooling-scopes.txt." }
        $seconds = "$(Invoke-RepoGit -Root $Root -Arguments @('log', '-1', '--format=%ct', $Commit) | Select-Object -First 1)".Trim()
        if ($seconds -notmatch '^\d+$') { return Fail "git can't say when commit $short was made." }
        $schema = (Resolve-ReceiptSchema -Root $Root -Commit $Commit).Version
    } catch {
        return Fail "What commit $short carries can't be read: $($_.Exception.Message)"
    }
    $staged = Test-StagedRelease -Folder $Folder -Version $Version -Expected @{
        ExpectedPatchNames          = @($list.patches | ForEach-Object { [string]$_.name })
        ExpectedPatcherVersion      = $toolchain.PatcherVersion
        ExpectedManagerFloor        = $toolchain.ManagerFloor
        ExpectedPackageName         = $target.PackageName
        ExpectedPackageVersions     = $target.PackageVersions
        ExpectedPackageVersionCodes = $target.PackageVersionCodes
        ApprovedManifestDelta       = @($allowlist.Entries)
        ActualCommitTimestamp       = [long]$seconds
        ExpectedCommit              = $Commit
        ExpectedSchemaVersion       = $schema
        ToolingScopeManifestText    = $scopeText
    }
    if (-not $staged.Valid) { return $staged }

    # And to its source, not only to itself. Whoever can rewrite every staged file can keep the folder
    # consistent, and a bundle with its build identity taken out passed as one from before bundles
    # carried one, which skips the receipt's clean-commit check. So the bundle has to carry an intact
    # identity naming this commit, clean, and the input digest of the checkout, which is the
    # commit's own only while the checkout is that commit with nothing changed.
    $bundleName = "patches-$Version.mpp"
    $bundlePath = Join-Path $Folder $bundleName
    $identity = Get-BundleIdentityFacts -BundlePath $bundlePath
    if (-not $identity.Valid) { return Fail "The staged $bundleName carries no intact build identity, so nothing ties it to commit $short." }
    if ($identity.SourceState -cne 'clean' -or $identity.SourceCommit -cne $Commit) {
        return Fail "The staged $bundleName was built from $($identity.SourceState) source at $($identity.SourceCommit), not commit $short."
    }
    $head = "$(Invoke-RepoGit -Root $Root -Arguments @('rev-parse', 'HEAD') | Select-Object -First 1)".Trim()
    $global:LASTEXITCODE = -1
    $changed = @(Invoke-RepoGit -Root $Root -Arguments @('status', '--porcelain'))
    if ($LASTEXITCODE -ne 0 -or $head -cne $Commit -or $changed.Count -gt 0) {
        return Fail "The checkout isn't commit $short with nothing changed, so the staged $bundleName can't be held to its sources."
    }
    $mismatch = Get-BundleInputMismatch -Root $Root -BundlePath $bundlePath
    if ($mismatch) { return Fail "The staged $bundleName isn't a build of commit ${short}: $mismatch." }
    return $staged
}

function Test-RebuiltBundle {
    <#
    .SYNOPSIS
        Whether a staged bundle is what its commit builds, held to a second build of that commit
        made in a checkout of its own.
    .DESCRIPTION
        Test-StagedReleaseAt holds a bundle to the identity it carries, and the build wrote that
        identity itself, so a bundle built from other source with a copied identity block reads the
        same. The release's rebuild stage builds the commit again in a fresh worktree, and this
        holds the staged bundle to what came out.

        Each bundle is first held to the checkout it was built in: an intact identity naming
        -Commit, clean, a checkout that is -Commit with nothing changed, and the input digest of
        that checkout's files as they are on disk. Nothing is normalized first. Two checkouts of
        one commit can hold a file with different line endings and git calls both clean, so the two
        digests can differ while each bundle is right about its own checkout. The rebuild's
        checkout has to be another folder than the staged bundle's, or the second build is the
        first one again.

        Compare-BundleContent then decides: every class, DEX, extension payload and resource has to
        be the same in both, and the identity entries are left out of it. So a checkout with other
        line endings that builds the same classes passes, and one changed class doesn't, whatever
        the bundle says about itself. Answers @{ Valid; Reason; Comparison }, with Comparison $null
        when a bundle was refused before the two were compared.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$BundlePath,
        [Parameter(Mandatory = $true)][string]$RebuiltRoot,
        [Parameter(Mandatory = $true)][string]$RebuiltPath,
        [Parameter(Mandatory = $true)][string]$Commit
    )

    function Fail { param([string]$Reason, $Comparison) return [pscustomobject]@{ Valid = $false; Reason = $Reason; Comparison = $Comparison } }

    if ($Commit -notmatch '^[0-9a-f]{40}$') { return Fail "$Commit isn't a full commit to hold the rebuilt bundle to." }
    $short = $Commit.Substring(0, 8)
    $folders = @($Root, $RebuiltRoot | ForEach-Object {
        $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($_).TrimEnd('\', '/') })
    if ($folders[0] -ieq $folders[1]) {
        return Fail "The rebuild has to come from a checkout of its own, and $RebuiltRoot is the one the staged bundle was built in."
    }
    $bundleName = Split-Path -Leaf $BundlePath
    foreach ($side in @(@{ What = 'staged'; Root = $Root; Path = $BundlePath }, @{ What = 'rebuilt'; Root = $RebuiltRoot; Path = $RebuiltPath })) {
        $what = "$($side.What) $bundleName"
        if (-not (Test-Path -LiteralPath $side.Path -PathType Leaf)) { return Fail "There is no $what at $($side.Path)." }
        try {
            $identity = Get-BundleIdentityFacts -BundlePath $side.Path
            if (-not $identity.Valid) { return Fail "The $what carries no intact build identity, so nothing ties it to commit $short." }
            if ($identity.SourceState -cne 'clean' -or $identity.SourceCommit -cne $Commit) {
                return Fail "The $what was built from $($identity.SourceState) source at $($identity.SourceCommit), not commit $short."
            }
            $head = "$(Invoke-RepoGit -Root $side.Root -Arguments @('rev-parse', 'HEAD') | Select-Object -First 1)".Trim()
            $global:LASTEXITCODE = -1
            $changed = @(Invoke-RepoGit -Root $side.Root -Arguments @('status', '--porcelain'))
            if ($LASTEXITCODE -ne 0 -or $head -cne $Commit -or $changed.Count -gt 0) {
                return Fail "$($side.Root) isn't commit $short with nothing changed, so the $what can't be held to its sources."
            }
            $mismatch = Get-BundleInputMismatch -Root $side.Root -BundlePath $side.Path
            if ($mismatch) { return Fail "The $what isn't a build of commit $short as $($side.Root) holds it: $mismatch." }
        } catch {
            return Fail "The $what can't be held to its sources: $($_.Exception.Message)"
        }
    }

    try {
        $comparison = Compare-BundleContent -BundlePath $BundlePath -ReferencePath $RebuiltPath
    } catch {
        return Fail "The staged and rebuilt $bundleName can't be compared: $($_.Exception.Message)"
    }
    if (-not $comparison.Equal) {
        $found = @($comparison.Differing | ForEach-Object { "$($_.Kind) $($_.Name) differs" }) +
            @($comparison.Missing | ForEach-Object { "$($_.Kind) $($_.Name) is only in the rebuild" }) +
            @($comparison.Extra | ForEach-Object { "$($_.Kind) $($_.Name) isn't in the rebuild" })
        $more = if ($found.Count -gt 5) { ", and $($found.Count - 5) more" } else { '' }
        return Fail ("The staged $bundleName isn't what commit $short builds: " +
            (@($found | Select-Object -First 5) -join '; ') + "$more.") $comparison
    }
    return [pscustomobject]@{ Valid = $true; Reason = $null; Comparison = $comparison }
}
