# GAP 1 (GENERALIZATION-PLAN.md): boot the legacy universe headless and probe EVERY block in
# GameData.getBlockRegistry() (vanilla + every staged mod) via BlockShapeProbe, writing
# a caller-selected JSON path and printing the BLOCK-SHAPES summary line.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\run-legacy-block-shapes.ps1 [-Heap 1G] [-TimeoutSec 180] [-ModJar jar] [-Out json]
# The historical no-argument invocation remains HBM -> research/out/legacy/block-shapes.json.
# Any non-HBM -ModJar invocation must provide -Out; this prevents foreign runs from rewriting
# the canonical HBM corpus.
#
# Same JVM-flag discipline as tools\run-legacy-tick-coverage.ps1 / run-legacy-m1-probe.ps1 - a
# SEPARATE process/boot, never run in the same JVM as another run-legacy-*.ps1 invocation (FML's
# Loader/GameData singletons are global and single-shot).
$ErrorActionPreference = 'Stop'

$repo   = Split-Path -Parent $PSScriptRoot
$jdk25  = Join-Path $repo 'tools\jdk-25.0.4.1+1\bin\java.exe'
$build  = Join-Path $repo 'build\legacy'
$outDir = Join-Path $repo 'research\out\legacy\legacy-boot'
$mod    = Join-Path $repo 'umb-legacy'
$libsDir = Join-Path $repo 'research\visual\mc1710-native\libraries'

$bootJar     = Join-Path $build 'umb-legacy-boot.jar'
$apiJar      = Join-Path $build 'umb-legacy-api.jar'
$bridgeApiJar = Join-Path $build 'umb-bridge-api.jar'
$lsJar       = Join-Path $build 'umb-legacy-legacyside.jar'
$forgeSrg    = Join-Path $build 'forge-1.7.10-10.13.4.1614-srg.jar'
$runtimeJar  = Join-Path $build '1.7.10-forge-srg-runtime-fields.jar'
$lwJar    = Join-Path $libsDir 'net\minecraft\launchwrapper\1.12\launchwrapper-1.12.jar'
$joptJar  = (Get-ChildItem -Recurse -Filter 'jopt-simple-*.jar' $libsDir | Select-Object -First 1).FullName
$log4jApi = (Get-ChildItem -Recurse -Filter 'log4j-api-*.jar' $libsDir | Select-Object -First 1).FullName
$log4jCore= (Get-ChildItem -Recurse -Filter 'log4j-core-*.jar' $libsDir | Select-Object -First 1).FullName
$log4jCfg = Join-Path $mod 'resources\log4j2-legacy.xml'

foreach ($p in @($jdk25, $bootJar, $apiJar, $bridgeApiJar, $lsJar, $forgeSrg, $runtimeJar, $lwJar, $joptJar, $log4jApi, $log4jCore, $log4jCfg)) {
  if (-not (Test-Path $p)) { Write-Error ("missing: " + $p + " - run tools\build-legacy.ps1 first"); exit 1 }
}

$heap       = '1G'
$timeoutSec = 180
if ($args -contains '-Heap')       { $heap       = $args[([array]::IndexOf($args,'-Heap')) + 1] }
if ($args -contains '-TimeoutSec') { $timeoutSec = [int]$args[([array]::IndexOf($args,'-TimeoutSec')) + 1] }
# UNIVERSALITY (harness-purge, finding 19): $ModJar lets any 1.7.10 mod jar be booted through this
# harness instead of only HBM's. Default kept as the historical HBM path so an unchanged
# invocation reproduces today's behaviour exactly. BlockShapeProbe (GAP 1) already walks EVERY
# block in GameData.getBlockRegistry() - vanilla + whichever mod is actually loaded here - so this
# was purely a tools\-level limitation, not a Java-side one.
$modJar = Join-Path $repo 'research\mods-hbm\HBM-NTM-1.0.27_X5771.jar'
$modJarExplicit = ($args -contains '-ModJar')
if ($modJarExplicit) { $modJar = $args[([array]::IndexOf($args,'-ModJar')) + 1] }
$outExplicit = ($args -contains '-Out')
$jsonOut = Join-Path $repo 'research\out\legacy\block-shapes.json'
if ($outExplicit) { $jsonOut = $args[([array]::IndexOf($args,'-Out')) + 1] }
$isHbmJar = ([IO.Path]::GetFileName($modJar) -like 'HBM-NTM-*.jar')
if ($modJarExplicit -and -not $isHbmJar -and -not $outExplicit) {
  Write-Error 'non-HBM -ModJar requires explicit -Out <per-mod block-shapes.json>'; exit 2
}
if (-not [IO.Path]::IsPathRooted($jsonOut)) { $jsonOut = Join-Path $repo $jsonOut }
if (-not $isHbmJar -and ([IO.Path]::GetFullPath($jsonOut) -ieq ([IO.Path]::GetFullPath((Join-Path $repo 'research\out\legacy\block-shapes.json'))))) {
  Write-Error 'non-HBM block-shape output may not target canonical HBM block-shapes.json'; exit 2
}

New-Item -ItemType Directory -Force $outDir | Out-Null
New-Item -ItemType Directory -Force (Join-Path $outDir 'mods') | Out-Null
New-Item -ItemType Directory -Force (Join-Path $outDir 'config') | Out-Null

$hbm = $modJar
if (-not (Test-Path $hbm)) { Write-Error ("missing: " + $hbm); exit 1 }
$targetName = Split-Path -Leaf $modJar
$foreignJars = @(Get-ChildItem (Join-Path $outDir 'mods') -Filter '*.jar' -File |
  Where-Object { $_.Name -ne $targetName })
$foreignDirs = @(Get-ChildItem (Join-Path $outDir 'mods') -Directory |
  Where-Object { $_.Name -ne ([IO.Path]::GetFileNameWithoutExtension($targetName)) })
if ($foreignJars.Count -or $foreignDirs.Count) {
  $names = @($foreignJars + $foreignDirs | ForEach-Object Name) -join ', '
  Write-Error ('foreign mods already staged in ' + (Join-Path $outDir 'mods') + ': ' + $names + ' - refusing to run'); exit 2
}
$staged = Join-Path $outDir ('mods\' + $targetName)
if (-not (Test-Path $staged)) { Copy-Item $modJar $staged }

# ---- RAM guard (same discipline as run-legacy-boot.ps1 / run-legacy-tick-coverage.ps1) --------------
$needMB = 1229
$waited = 0
while ($true) {
  $freeMB = [int]((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1024)
  if ($freeMB -ge $needMB) { Write-Output ("free RAM    : " + $freeMB + " MB - ok"); break }
  if ($waited -ge 1200) { Write-Error ("only " + $freeMB + " MB free after 20 min; refusing to start"); exit 1 }
  Write-Output ("free RAM    : " + $freeMB + " MB - waiting for " + $needMB + " MB (" + $waited + "s)")
  Start-Sleep -Seconds 20
  $waited += 20
}

$hostCp = @($bootJar, $apiJar, $bridgeApiJar, $lwJar, $joptJar, $log4jApi, $log4jCore) -join ';'
$jvm = @(
  ('-Xmx' + $heap),
  '-Djava.awt.headless=true',
  '--sun-misc-unsafe-memory-access=allow',
  '--add-opens', 'java.base/java.lang=ALL-UNNAMED',
  '--add-opens', 'java.base/java.lang.reflect=ALL-UNNAMED',
  '--add-opens', 'java.base/java.util=ALL-UNNAMED',
  '--add-opens', 'java.base/java.util.concurrent=ALL-UNNAMED',
  '--add-opens', 'java.base/java.net=ALL-UNNAMED',
  '--add-opens', 'java.base/java.nio=ALL-UNNAMED',
  '--add-opens', 'java.base/java.io=ALL-UNNAMED',
  '--add-opens', 'java.base/java.text=ALL-UNNAMED',
  '-XX:-OmitStackTraceInFastThrow',
  '-Dfile.encoding=UTF-8',
  '-Duser.language=en', '-Duser.country=US',
  ('-Dlog4j.configurationFile=' + $log4jCfg),
  '-Dlog4j2.disable.jmx=true',
  '-Dfml.queryResult=confirm',
  '-Dfml.doNotBackup=true',
  '-Dfml.ignoreInvalidMinecraftCertificates=true',
  '-Dfml.ignorePatchDiscrepancies=true',
  ('-Dumb.repo=' + $repo),
  ('-Dumb.legacy.out=' + $outDir),
  ('-Dumb.legacy.forgeJar=' + $forgeSrg),
  ('-Dumb.legacy.runtimeJar=' + $runtimeJar),
  ('-Dumb.legacy.legacysideJar=' + $lsJar),
  ('-Dumb.legacy.timeoutSeconds=' + $timeoutSec),
  ('-Dumb.legacy.blockShapesJson=' + $jsonOut),
  '-cp', $hostCp,
  'dev.umb.legacy.boot.BlockShapeMain'
)

$logFile = Join-Path $outDir 'block-shapes.log'
Write-Output ("heap        : " + $heap)
Write-Output ("log         : " + $logFile)

$sw = [System.Diagnostics.Stopwatch]::StartNew()
$proc = Start-Process -FilePath $jdk25 -ArgumentList $jvm -NoNewWindow -PassThru `
        -RedirectStandardOutput $logFile -RedirectStandardError (Join-Path $outDir 'block-shapes.err.log')
Write-Output ("pid         : " + $proc.Id)

if (-not $proc.WaitForExit(($timeoutSec + 30) * 1000)) {
  Write-Output ("TIMEOUT - killing only pid " + $proc.Id)
  Stop-Process -Id $proc.Id -Force -Confirm:$false
  exit 3
}
$sw.Stop()
Write-Output ("exit        : " + $proc.ExitCode + " after " + [int]$sw.Elapsed.TotalSeconds + "s")
if (Test-Path $jsonOut) {
  $raw = [IO.File]::ReadAllText($jsonOut)
  [IO.File]::WriteAllText($jsonOut, $raw, (New-Object Text.UTF8Encoding($false)))
}
Write-Output '--- block-shapes.txt ---'
Get-Content (Join-Path $outDir 'block-shapes.txt')
exit $proc.ExitCode
