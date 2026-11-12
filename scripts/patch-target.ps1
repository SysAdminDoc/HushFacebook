<#
.SYNOPSIS
    Read the app target and the exact versions shared by every patch in a generated catalog, with
    the version codes the catalog pins them to.

.DESCRIPTION
    Device patching, fixture verification, heap measurements and release validation all need
    the package and version that the bundle supports. Keeping that fact in patches-list.json,
    which is generated from AppCompatibilities.kt, prevents those callers from drifting apart.
#>

function Get-PatchTarget {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][object]$PatchList)

    $patches = @($PatchList.patches)
    if ($patches.Count -eq 0) { throw 'patches-list.json contains no patches.' }

    $targets = @{}
    # The version codes the first patch to name each package pins its builds to, by version name.
    $targetCodes = @{}
    # The first patch to name each package, and the builds it declared. Every other patch has to
    # declare the same ones: a build only some patches support is one the bundle can't fully patch,
    # and a union of them would hand the release scripts that build as a declared target.
    $declaredBy = @{}
    foreach ($patch in $patches) {
        if ($null -eq $patch) { throw 'patches-list.json contains a null patch.' }
        $nameProperty = $patch.PSObject.Properties['name']
        $patchName = if ($null -ne $nameProperty -and -not [string]::IsNullOrWhiteSpace([string]$nameProperty.Value)) {
            [string]$nameProperty.Value
        } else {
            '<unnamed patch>'
        }
        $compatible = $patch.PSObject.Properties['compatiblePackages']
        if ($null -eq $compatible -or $null -eq $compatible.Value) {
            throw "$patchName has no compatible package in patches-list.json."
        }
        $packages = @($compatible.Value.PSObject.Properties)
        if ($packages.Count -eq 0) {
            throw "$patchName has no compatible package in patches-list.json."
        }
        foreach ($property in $packages) {
            $versions = @($property.Value | ForEach-Object { [string]$_ } |
                Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
            if ($versions.Count -eq 0) {
                throw "$patchName has no exact compatible version for $($property.Name)."
            }
            # The version codes its compatibility block pins each of those builds to. APKMirror
            # lists several arm64 builds of one Facebook version, each with its own dex, so the name
            # alone doesn't say which of them the patches were proved on. A patch that pins none is
            # read by the name, as before.
            $codes = @{}
            $compatibility = $patch.PSObject.Properties['compatibility']
            foreach ($entry in @(if ($null -ne $compatibility) { $compatibility.Value })) {
                if ($null -eq $entry -or [string]$entry.packageName -ne $property.Name) { continue }
                foreach ($appTarget in @($entry.targets)) {
                    if ($null -eq $appTarget -or $null -eq $appTarget.versionCodes) { continue }
                    $version = [string]$appTarget.version
                    $pinned = @($appTarget.versionCodes.PSObject.Properties | ForEach-Object { [string]$_.Value })
                    $codes[$version] = @(@($codes[$version]) + $pinned | Where-Object { $_ } | Sort-Object -Unique)
                }
            }
            $declared = @(foreach ($version in @($versions | Sort-Object -Unique)) {
                if ($codes.ContainsKey($version)) { "$version ($($codes[$version] -join ', '))" } else { $version }
            }) -join ', '
            if (-not $targets.ContainsKey($property.Name)) {
                $targets[$property.Name] = $versions
                $targetCodes[$property.Name] = $codes
                $declaredBy[$property.Name] = @($patchName, $declared)
            } elseif ($declaredBy[$property.Name][1] -ne $declared) {
                throw ("Every patch has to declare the same $($property.Name) builds: " +
                    "$($declaredBy[$property.Name][0]) declares $($declaredBy[$property.Name][1]), " +
                    "but $patchName declares $declared.")
            }
        }
    }

    $packages = @($targets.Keys | Sort-Object)
    if ($packages.Count -ne 1) {
        throw "Expected one compatible package, found $($packages -join ', ')."
    }
    $packageName = $packages[0]
    # Every version the catalog declares, newest first. Facebook moves a release a week, so the
    # bundle declares the build it was last proved on and keeps the one before it; the newest is
    # the one a device build and the README name. Compared part by part as numbers, every part:
    # Facebook's versions have five (580.0.0.51.74) and [version] takes four, so the fifth was
    # dropped, and two builds apart only there sorted as equals in whatever order the shell left
    # them, the older one first in both.
    $declared = @($targets[$packageName] | Sort-Object -Unique)
    if ($declared.Count -eq 0) {
        throw "No compatible version for $packageName."
    }
    foreach ($version in $declared) {
        if ($version -notmatch '^\d+(?:\.\d+)*$') {
            throw "$packageName is declared at $version, which isn't a version of dotted numbers."
        }
    }
    $width = ($declared | ForEach-Object { @($_ -split '\.').Count } | Measure-Object -Maximum).Maximum
    # One sort key per part, a missing part below any number, so 580.0.0.51 comes after 580.0.0.51.0.
    $keys = @(0..($width - 1) | ForEach-Object {
        $part = $_
        { $parts = @($_ -split '\.'); if ($part -lt $parts.Count) { [decimal]$parts[$part] } else { [decimal]-1 } }.GetNewClosure()
    })
    $versions = @($declared | Sort-Object -Descending -Property $keys)
    # Every declared version has an entry, empty when the catalog pins it to no code.
    $versionCodes = @{}
    foreach ($version in $versions) {
        $versionCodes[$version] = [string[]]@($targetCodes[$packageName][$version] | Where-Object { $_ })
    }
    return [pscustomobject]@{
        PackageName = $packageName
        PackageVersion = $versions[0]
        PackageVersions = [string[]]$versions
        PackageVersionCodes = $versionCodes
    }
}

function Test-DeclaredBuild {
    <#
    .SYNOPSIS
        Whether an APK is one of the builds a catalog declares.
    .DESCRIPTION
        Its version name has to be declared, and so does its version code wherever the catalog pins
        codes to that name. Another arm64 build of Facebook 580 shares the declared name and was
        never proved, so only a declared build is patched without -f, and only a run of one proves
        a release. Takes Get-PatchTarget's answer, or anything carrying its PackageVersions and
        PackageVersionCodes.
    #>
    param(
        [Parameter(Mandatory = $true)]$Target,
        [string]$VersionName,
        [string]$VersionCode
    )

    if (@($Target.PackageVersions) -cnotcontains $VersionName) { return $false }
    $pinned = @($Target.PackageVersionCodes[$VersionName] | Where-Object { $_ })
    return ($pinned.Count -eq 0 -or $pinned -ccontains $VersionCode)
}

function Format-DeclaredBuilds {
    # The declared builds the way a refusal names them: 587.0.0.39.55 (707414333).
    param([Parameter(Mandatory = $true)]$Target)

    $named = foreach ($version in @($Target.PackageVersions)) {
        $pinned = @($Target.PackageVersionCodes[$version] | Where-Object { $_ })
        if ($pinned.Count -gt 0) { "$version ($($pinned -join ' or '))" } else { $version }
    }
    return (@($named) -join ', ')
}
