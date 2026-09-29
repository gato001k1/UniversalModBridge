#requires -Version 5.1
<#
  Blind universality run: dev.umb.rendermap.RenderMap over a NEVER-EXTRACTED 1.7.10 mod
  with zero per-mod code, proving the generalized resolvers work on unseen bytecode.
    .\tools\windows\run-blind-rendermap.ps1 [-Jar <path>] [-Snapshot <path>] [-OutDir <path>]
  Defaults reproduce the Botania r1.8-249 run (jar on F: scratch, empty snapshot since no
  harness-lane Botania snapshot exists yet - jar-side metrics only, honestly reported).
#>
param(
  [string]$Jar,
  [string]$Snapshot,
  [string]$OutDir
)
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not $Jar)      { throw 'pass -Jar <path to a mod jar>' }
if (-not $Snapshot) { $Snapshot = Join-Path $root 'research\out\legacy\blind\empty-snapshot.json' }
if (-not $OutDir)   { $OutDir   = Join-Path $root 'research\out\legacy\blind\botania-r1.8-249' }

foreach ($p in @($Jar, $Snapshot)) {
  if (-not (Test-Path $p)) { Write-Error ("missing: " + $p); exit 1 }
}
New-Item -ItemType Directory -Force $OutDir | Out-Null

& (Join-Path $PSScriptRoot 'run-rendermap.ps1') -Jar $Jar -Snapshot $Snapshot -OutDir $OutDir
if ($LASTEXITCODE -ne 0) { throw "blind RenderMap run failed with exit code $LASTEXITCODE" }
