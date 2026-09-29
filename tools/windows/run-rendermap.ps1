#requires -Version 5.1
<#
  Run dev.umb.rendermap.RenderMap over a mod jar + registry snapshot.
    .\tools\windows\run-rendermap.ps1 [-Jar <path>] [-Snapshot <path>] [-OutDir <path>] [-AlsoWrite <file>]
  Defaults to the HBM jar + hbm-snapshot.json. -AlsoWrite drops a byte-identical compat
  copy of <namespace>-render-map.json under another file name (for callers still pointed
  at a legacy layout, e.g. a manifest referencing rendermap-mcheli/hbm-render-map.json).
#>
param(
  [string]$Jar,
  [string]$Snapshot,
  [string]$OutDir,
  [string]$AlsoWrite
)
$ErrorActionPreference = 'Stop'

$root   = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$java   = Join-Path $root 'tools\jdk-21.0.12.1+1\bin\java.exe'
$libDir = Join-Path $root 'tools\junit'
$classes= Join-Path $root 'build\rendermap\classes'

if (-not $Jar)      { $Jar      = Join-Path $root 'research\mods-hbm\HBM-NTM-1.0.27_X5771.jar' }
if (-not $Snapshot) { $Snapshot = Join-Path $root 'research\out\legacy\hbm-snapshot.json' }
if (-not $OutDir)   { $OutDir   = Join-Path $root 'research\out\legacy\rendermap' }

if (-not (Test-Path $classes)) { throw "not built; run tools\windows\build-rendermap.ps1 first" }
if (-not (Test-Path $Jar))      { throw "jar not found: $Jar" }
if (-not (Test-Path $Snapshot)) { throw "snapshot not found: $Snapshot" }

$cp = @(
  $classes,
  (Join-Path $libDir 'asm-9.9.jar'),
  (Join-Path $libDir 'asm-tree-9.9.jar'),
  (Join-Path $libDir 'asm-commons-9.9.jar'),
  (Join-Path $libDir 'gson.jar')
) -join ';'

$renderArgs = @($Jar, $Snapshot, $OutDir)
if ($AlsoWrite) { $renderArgs += @('--also-write', $AlsoWrite) }
& $java -Xmx3g -cp $cp dev.umb.rendermap.RenderMap @renderArgs
if ($LASTEXITCODE -ne 0) { throw "RenderMap failed with exit code $LASTEXITCODE" }
