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
#>

function Get-ReleaseReceiptSchemaVersion {
    <#
    .SYNOPSIS
        Bumped when the shape changes in a way a reader has to know about.
    .DESCRIPTION
        Validation refuses a receipt written to a different version rather than guessing which
        fields moved. A function rather than a variable because this file is dot-sourced into
        several scripts, and a script-scoped variable in a dot-sourced file belongs to whichever
        one sourced it.
    #>
    return 1
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

function Resolve-Aapt2 {
    <#
    .SYNOPSIS
        The aapt2 that reads a binary AndroidManifest.xml, or a throw saying where to get one.
    .DESCRIPTION
        Taken in order from -Explicit, HUSHFEED_AAPT2, and the newest build-tools directory of
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
    if ($env:HUSHFEED_AAPT2 -and (Test-Path -LiteralPath $env:HUSHFEED_AAPT2 -PathType Leaf)) {
        return $env:HUSHFEED_AAPT2
    }

    $sdk = $env:ANDROID_HOME
    $localProperties = if ($Root) { Join-Path $Root 'local.properties' } else { $null }
    if ($localProperties -and (Test-Path -LiteralPath $localProperties -PathType Leaf)) {
        $line = @(Get-Content -LiteralPath $localProperties |
            Where-Object { $_ -match '^\s*sdk\.dir\s*=' }) | Select-Object -First 1
        if ($line) { $sdk = ($line -replace '^\s*sdk\.dir\s*=\s*', '') -replace '\\\\', '\' }
    }
    if (-not $sdk) { throw 'No Android SDK: set ANDROID_HOME, sdk.dir or HUSHFEED_AAPT2.' }

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
        permissions = @($permissions | Sort-Object -Unique)
        exported    = @($qualified | Sort-Object -Unique)
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
    return @(@($Left) | Where-Object { -not $other.Contains($_) } | Sort-Object -Unique)
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
    return @($entries | Sort-Object -Unique)
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
    $entries = New-Object System.Collections.Generic.List[string]
    foreach ($line in Get-Content -LiteralPath $Path) {
        $text = ([string]$line).Trim()
        if (-not $text -or $text.StartsWith('#')) { continue }
        if ($text -notmatch '^(permission-added|permission-removed|exported-added|exported-removed) \S+$') {
            throw "The manifest delta allowlist has a line that is not `"<kind> <value>`": $text"
        }
        $entries.Add($text)
    }
    return @($entries | Sort-Object -Unique)
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
        [string]$BundlePath,
        [string[]]$ApprovedManifestDelta = @()
    )

    function Fail { param([string]$Reason) return [pscustomobject]@{ Valid = $false; Reason = $Reason } }

    if ($null -eq $Receipt) { return Fail 'There is no receipt to check.' }
    if ([int]$Receipt.schemaVersion -ne (Get-ReleaseReceiptSchemaVersion)) {
        return Fail ("The receipt is schema version $($Receipt.schemaVersion); this checkout " +
            "reads version $(Get-ReleaseReceiptSchemaVersion).")
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
    }

    if (@($Receipt.extension.dexPayloads).Count -eq 0) {
        return Fail 'The receipt records no extension DEX payload, so it identifies no extension.'
    }
    foreach ($payload in @($Receipt.extension.dexPayloads)) {
        if (-not $payload.name) { return Fail 'A recorded DEX payload has no name.' }
        if ([long]$payload.sizeBytes -le 0) {
            return Fail "The recorded DEX payload $($payload.name) is empty."
        }
        if ([string]$payload.sha256 -notmatch '^[0-9A-F]{64}$') {
            return Fail "The recorded DEX payload $($payload.name) has no SHA-256."
        }
    }

    $targets = @($Receipt.targets)
    if ($targets.Count -eq 0) { return Fail 'The receipt records no target, so nothing was proved.' }

    $expected = [System.Collections.Generic.HashSet[string]]::new(
        [string[]]$ExpectedPatchNames, [System.StringComparer]::Ordinal)
    $produced = New-Object System.Collections.Generic.List[string]
    foreach ($target in $targets) {
        $label = "$($target.source.package) $($target.source.versionName)"
        if ([string]$target.source.sha256 -notmatch '^[0-9A-F]{64}$') {
            return Fail "The receipt records no source APK hash for $label."
        }
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

    $approved = [System.Collections.Generic.HashSet[string]]::new(
        [string[]]@($ApprovedManifestDelta), [System.StringComparer]::Ordinal)
    $unapproved = @(@($produced | Sort-Object -Unique) | Where-Object { -not $approved.Contains($_) })
    if ($unapproved.Count -gt 0) {
        return Fail ('The patched manifest changed in ways nobody reviewed: ' +
            ($unapproved -join ', '))
    }
    $seenEntries = [System.Collections.Generic.HashSet[string]]::new(
        [string[]]@($produced), [System.StringComparer]::Ordinal)
    $stale = @(@($ApprovedManifestDelta) | Where-Object { -not $seenEntries.Contains($_) })
    if ($stale.Count -gt 0) {
        return Fail ('The manifest delta allowlist approves changes no patch makes any more: ' +
            ($stale -join ', '))
    }

    return [pscustomobject]@{ Valid = $true; Reason = $null }
}
