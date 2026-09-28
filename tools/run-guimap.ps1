#requires -Version 5.1
<#
  Run dev.umb.guimap.GuiMap over a 1.7.10 Forge mod jar.
    .\tools\run-guimap.ps1 [-Jar <path>] [-Out <path>]
#>
param(
  [string]$Jar,
  [string]$Out
)
$ErrorActionPreference = 'Stop'

$root   = Split-Path -Parent $PSScriptRoot
$java   = Join-Path $root 'tools\jdk-21.0.12.1+1\bin\java.exe'
$libDir = Join-Path $root 'tools\junit'
$classes= Join-Path $root 'build\guimap\classes'

$jarWasDefaulted = -not $Jar
if (-not $Jar) { $Jar = Join-Path $root 'research\mods-hbm\HBM-NTM-1.0.27_X5771.jar' }
# UNIVERSALITY (harness-purge, finding 20): -Out used to default to a fixed gui-profile.json
# regardless of -Jar, so running this against two different mods without ALSO passing -Out
# silently overwrote one shared file. When -Jar is explicitly given but -Out is not, the default
# is now derived from the jar's own basename; the original literal is kept only when -Jar is ALSO
# defaulted (the historical hbm invocation), so that exact call reproduces today's output path.
if (-not $Out) {
  if ($jarWasDefaulted) {
    $Out = Join-Path $root 'research\out\legacy\gui-profile.json'
  } else {
    $Out = Join-Path $root ('research\out\legacy\gui-profile-' + [System.IO.Path]::GetFileNameWithoutExtension($Jar) + '.json')
  }
}

if (-not (Test-Path $classes)) { throw "not built; run tools\build-guimap.ps1 first" }
if (-not (Test-Path $Jar))     { throw "jar not found: $Jar" }

$cp = @(
  $classes,
  (Join-Path $libDir 'asm-9.9.jar'),
  (Join-Path $libDir 'asm-tree-9.9.jar'),
  (Join-Path $libDir 'asm-commons-9.9.jar'),
  (Join-Path $libDir 'gson.jar')
) -join ';'

& $java -Xmx3g -cp $cp dev.umb.guimap.GuiMap $Jar $Out
if ($LASTEXITCODE -ne 0) { throw "GuiMap failed with exit code $LASTEXITCODE" }
