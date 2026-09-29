# fetch-host.ps1 - download a Minecraft host instance (jar + version json + libraries)
# for static analysis and future runtime harness use. UMB policy: jars are fetched from
# official Mojang distribution endpoints only (spec licensing rules; see
# docs/DEPENDENCY_LICENSES.md). Mappings: for >=26.x hosts client.txt does not exist
# (26.x ships deobfuscated, D3); for <=1.21.x hosts the published client_mappings
# (ProGuard-format client.txt) IS fetched to research\mappings\<Version>-client.txt -
# same runtime-fetch-only rule as the jar, sha1-verified, never committed.
#
# Usage: .\tools\windows\fetch-host.ps1 [-Version 26.2] [-Out research\jars]
param(
    [string]$Version = '26.2',
    [string]$Out = 'research\jars'
)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))

$manifestUrl = 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json'

function Get-Json($url) {
    (Invoke-RestMethod -Uri $url -UseBasicParsing)
}

Write-Host "[fetch-host] resolving $Version from piston-meta..."
$manifest = Get-Json $manifestUrl
$entry = $manifest.versions | Where-Object { $_.id -eq $Version } | Select-Object -First 1
if (-not $entry) {
    Write-Error "version '$Version' not in manifest (latest.release=$($manifest.latest.release))"; exit 1
}
$vjson = Get-Json $entry.url

$dir = Join-Path $Out $Version
New-Item -ItemType Directory -Force -Path $dir | Out-Null

# Version json (ground truth for mainClass, javaVersion, libraries).
$jsonPath = Join-Path "$Out" "$Version.json"
$vjson | ConvertTo-Json -Depth 40 | Out-File $jsonPath -Encoding utf8
Write-Host "[fetch-host] wrote $($jsonPath)"

# Client jar with sha1 verification.
$clientUrl = $vjson.downloads.client.url
$shaUrl = $vjson.downloads.client.sha1
$jarPath = Join-Path $dir 'client.jar'
$needFetch = $true
if (Test-Path $jarPath) {
    $actual = (Get-FileHash $jarPath -Algorithm SHA1).Hash.ToLower()
    if ($actual -eq $shaUrl) {
        Write-Host "[fetch-host] client.jar already present and sha1-valid"
        $needFetch = $false
    } else {
        Write-Host "[fetch-host] client.jar present but hash mismatch - refetching"
    }
}
if ($needFetch) {
    Write-Host "[fetch-host] downloading client jar ($([math]::Round($vjson.downloads.client.size/1MB,1)) MB)..."
    Invoke-WebRequest -Uri $clientUrl -OutFile $jarPath -UseBasicParsing
    $actual = (Get-FileHash $jarPath -Algorithm SHA1).Hash.ToLower()
    if ($actual -ne $shaUrl) {
        Write-Error "sha1 mismatch after download: expected $shaUrl got $actual"; exit 1
    }
}

# Published client mappings (ProGuard-format client.txt), <=1.21.x only.
if ($vjson.downloads.client_mappings) {
    $cm = $vjson.downloads.client_mappings
    $txtPath = Join-Path 'research\mappings' "$Version-client.txt"
    $needCm = $true
    if (Test-Path $txtPath) {
        if ((Get-FileHash $txtPath -Algorithm SHA1).Hash.ToLower() -eq $cm.sha1) {
            Write-Host "[fetch-host] client mappings already present and sha1-valid"
            $needCm = $false
        } else {
            Write-Host "[fetch-host] client mappings present but hash mismatch - refetching"
        }
    }
    if ($needCm) {
        Write-Host "[fetch-host] downloading client mappings ($([math]::Round($cm.size/1MB,1)) MB)..."
        Invoke-WebRequest -Uri $cm.url -OutFile $txtPath -UseBasicParsing
        $actual = (Get-FileHash $txtPath -Algorithm SHA1).Hash.ToLower()
        if ($actual -ne $cm.sha1) {
            Write-Error "client mappings sha1 mismatch: expected $($cm.sha1) got $actual"; exit 1
        }
    }
    Write-Host "[fetch-host] mappings -> $txtPath"
} else {
    Write-Host "[fetch-host] no client_mappings in downloads (expected for >=26.x, D3)"
}

# Libraries (skip natives-windows classifiers we cannot resolve? no - resolve rules:)
# Each library entry has either a direct artifact download or classifier map. We fetch the
# main artifact always, plus windows-natives when declared, verifying each sha1.
$i = 0
$fetched = 0; $skipped = 0
foreach ($lib in $vjson.libraries) {
    if (-not $lib.downloads) { continue }
    # Skip libs whose rules exclude windows (rare but real).
    if ($lib.rules) {
        $allowed = $false
        foreach ($r in $lib.rules) {
            if (-not $r.os -or $r.os.name -eq 'windows') { if ($r.action -eq 'allow') { $allowed = $true } }
            if ($r.action -eq 'disallow' -and $r.os -and $r.os.name -eq 'windows') { $allowed = $false }
        }
        if (-not $allowed) { continue }
    }
    $targets = @()
    if ($lib.downloads.artifact) { $targets += $lib.downloads.artifact }
    if ($lib.downloads.classifiers.'natives-windows') { $targets += $lib.downloads.classifiers.'natives-windows' }

    foreach ($t in $targets) {
        $dest = Join-Path (Join-Path $dir 'libraries') ($t.path -replace '/', '\')
        if ((Test-Path $dest) -and ((Get-FileHash $dest -Algorithm SHA1).Hash.ToLower() -eq $t.sha1)) {
            $skipped++
            continue
        }
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dest) | Out-Null
        Invoke-WebRequest -Uri $t.url -OutFile $dest -UseBasicParsing
        $actual = (Get-FileHash $dest -Algorithm SHA1).Hash.ToLower()
        if ($actual -ne $t.sha1) {
            Write-Error "library sha1 mismatch: $($t.path) expected $($t.sha1) got $actual"; exit 1
        }
        $fetched++
    }
}
Write-Host "[fetch-host] libraries: $fetched fetched, $skipped already valid"
Write-Host "[fetch-host] done -> $dir"
Write-Host ("[fetch-host] host needs Java {0} ({1}); launch with tools\jdk-25* per D3" -f `
    $vjson.javaVersion.majorVersion, $vjson.javaVersion.component)
