# Headless proof that the OBJ bridge really registers its client-item model type on the real
# Minecraft 26.2 client jar, with BOTH agents attached (umb-hostagent first, umb-objbridge second -
# the same order the windowed launcher uses).
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\windows\probe-objbridge.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\windows\probe-objbridge.ps1 -Namespace ironchest
#
# Prints PROBE-OK / PROBE-FAIL and exits 0 / 1. No window, no GL.
#
# UNIVERSALITY (harness-purge, headline finding 3): this used to hardcode every one of its
# research\out\legacy\hbm-* paths and ns=hbm with no parameters at all, so this headless gate
# could only ever prove ObjBridge works for hbm. Defaults reproduce today's exact hbm invocation.
param(
  [string]$Namespace = 'hbm',
  [string]$Snapshot,
  [string]$RenderMap,
  [string]$Assets,
  [string]$OutDir,
  [string]$PackItems,
  [string]$ObjFile
)
$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$java = Join-Path $repo 'tools\jdk-25.0.4.1+1\bin\java.exe'
$cpFile = Join-Path $repo 'research\visual\mc262-vanilla\classpath.txt'
$objAgent = Join-Path $repo 'build\objbridge\umb-objbridge.jar'
$hostAgent = Join-Path $repo 'build\hostagent\umb-hostagent.jar'
$launchwrapper = Join-Path $repo 'research\visual\mc1710-native\libraries\net\minecraft\launchwrapper\1.12\launchwrapper-1.12.jar'
if ($Snapshot) { $snapshot = $Snapshot } else { $snapshot = Join-Path $repo ('research\out\legacy\' + $Namespace + '-snapshot.json') }
if ($RenderMap) { $renderMap = $RenderMap } elseif ($Namespace -eq 'hbm') { $renderMap = Join-Path $repo 'research\out\legacy\rendermap\hbm-render-map.json' } else { $renderMap = Join-Path $repo ('research\out\legacy\rendermap-' + $Namespace + '\hbm-render-map.json') }
if ($Assets) { $assets = $Assets } else { $assets = Join-Path $repo ('research\out\legacy\' + $Namespace + '-assets') }
if ($OutDir) { $outDir = $OutDir } elseif ($Namespace -eq 'hbm') { $outDir = Join-Path $repo 'research\out\legacy\objbridge' } else { $outDir = Join-Path $repo ('research\out\legacy\objbridge-' + $Namespace) }
# Probe target selection (defaults reproduce today's exact hbm invocation; the Java probe
# itself takes explicit paths and knows no mod names).
if ($PackItems) { $packItems = $PackItems } else { $packItems = Join-Path $repo 'research\out\legacy\packs\hbm-objmodels\assets\hbm\items' }
if ($ObjFile) { $objFile = $ObjFile } else { $objFile = Join-Path $repo 'research\out\legacy\hbm-assets\assets\hbm\models\weapons\minigun.obj' }

foreach ($p in @($java, $cpFile, $objAgent, $renderMap, $assets)) {
  if (-not (Test-Path $p)) { Write-Error ("missing: " + $p); exit 1 }
}
New-Item -ItemType Directory -Force $outDir | Out-Null

$cp = (Get-Content $cpFile -Raw).Trim()
$objLog = Join-Path $outDir 'probe-objbridge.log'
if (Test-Path $objLog) { Remove-Item -Force $objLog }

$jvm = @('-Xmx2G', '--sun-misc-unsafe-memory-access=allow', '--enable-native-access=ALL-UNNAMED')

# umb-hostagent first (it patches BuiltInRegistries + the creative screen), objbridge second.
if (Test-Path $hostAgent) {
  if (-not (Test-Path $launchwrapper)) { Write-Error ("missing: " + $launchwrapper); exit 1 }
  $hostLog = Join-Path $outDir 'probe-hostagent-side.log'
  $jvm += ('-javaagent:' + $hostAgent + '=snapshot=' + $snapshot + ';log=' + $hostLog + ';ns=' + $Namespace + ';launchwrapper=' + $launchwrapper)
  Write-Output ('hostagent  : ' + $hostAgent)
} else {
  Write-Output 'hostagent  : absent - probing objbridge alone'
}
$jvm += ('-javaagent:' + $objAgent + '=rendermap=' + $renderMap + ';assets=' + $assets + ';log=' + $objLog)
Write-Output ('objbridge  : ' + $objAgent)

$full = $jvm + @('-cp', $cp, 'dev.umb.objbridge.probe.ObjBridgeProbe', $packItems, $objFile)
Push-Location $repo
& $java @full
$rc = $LASTEXITCODE
Pop-Location

Write-Output ''
Write-Output ('--- ' + $objLog + ' ---')
if (Test-Path $objLog) { Get-Content $objLog }
Write-Output ('probe exit=' + $rc)
exit $rc
