#requires -Version 5.1
<#
  Extract OpenGL transform constants from legacy renderer bytecode.
  Reads hbm-render-map.json to find renderer classes, then disassembles each one
  to capture glScale/glTranslate/glRotate constant argument values.
    .\tools\windows\run-renderer-transforms.ps1 [-Jar <path>] [-Rendermap <path>] [-OutDir <path>]

  UNIVERSALITY (harness-purge, finding 9): this was the only one of its sibling scripts
  (run-rendermap.ps1 / run-guimap.ps1 both already take -Jar) with no override parameter for the
  target jar at all - it could only ever be run against HBM. Defaults reproduce today's exact
  invocation.
#>
param(
  [string]$Jar,
  [string]$Rendermap,
  [string]$OutDir
)
$ErrorActionPreference = 'Stop'

$root    = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$java    = Join-Path $root 'tools\jdk-21.0.12.1+1\bin\java.exe'
$libDir  = Join-Path $root 'tools\junit'
$mainOut = Join-Path $root 'build\rendermap\classes'
if (-not $Jar)       { $Jar       = Join-Path $root 'research\mods-hbm\HBM-NTM-1.0.27_X5771.jar' }
if (-not $Rendermap) { $Rendermap = Join-Path $root 'research\out\legacy\rendermap\hbm-render-map.json' }
if (-not $OutDir)    { $OutDir    = Join-Path $root 'research\out\legacy\rendermap' }
$jarPath = $Jar
$rendermap = $Rendermap

if (-not (Test-Path $mainOut)) {
    Write-Host "[run-renderer-transforms] Building umb-rendermap..."
    & (Join-Path $PSScriptRoot 'build-rendermap.ps1')
}

if (-not (Test-Path $jarPath)) { throw "mod jar not found at $jarPath" }
if (-not (Test-Path $rendermap)) { throw "rendermap not found at $rendermap" }

$deps = @(
  (Join-Path $libDir 'asm-9.9.jar'),
  (Join-Path $libDir 'asm-tree-9.9.jar'),
  (Join-Path $libDir 'asm-commons-9.9.jar'),
  (Join-Path $libDir 'gson.jar')
)
$cp = (@($mainOut) + $deps) -join ';'

Write-Host "[run-renderer-transforms] Extracting GL transforms from $($jarPath | Split-Path -Leaf)"
& $java -cp $cp dev.umb.rendermap.RendererTransformExtractor $jarPath $rendermap $outDir
if ($LASTEXITCODE -ne 0) { throw "Extractor failed with exit code $LASTEXITCODE" }
Write-Host "[run-renderer-transforms] OK"
