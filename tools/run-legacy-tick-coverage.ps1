# G2 lane E step 3: boot the legacy universe headless and mass-tick ALL 372 HBM tile-entity
# classes via TickCoverageProbe, writing research/out/legacy/g2-tick-coverage.json and printing
# the TICK-COVERAGE summary line.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\run-legacy-tick-coverage.ps1 [-Heap 1G] [-TimeoutSec 180]
#
# Same JVM-flag discipline as tools\run-legacy-m1-probe.ps1 - a SEPARATE process/boot, never run in
# the same JVM as another run-legacy-*.ps1 invocation (FML's Loader/GameData singletons are global
# and single-shot).
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
# UNIVERSALITY (harness-purge, finding 19): $ModJar lets any 1.7.10 mod jar be staged into mods\
# instead of only HBM's. Default kept as the historical HBM path so an unchanged invocation
# reproduces today's behaviour exactly. NOTE (disclosed, not fixed here - umb-legacy is another
# lane's ownership): dev.umb.legacy.boot.TickCoverageMain reads a shipped, HBM-only
# te-classes.txt (372 com.hbm.*/api.hbm.* class names, see UNIVERSALITY-AUDIT.md finding 3), so for
# any other jar staged here it will report "372/372 THREW" instead of measuring anything real about
# the target mod - passing -ModJar here alone does NOT fix that; only the boot/classload step.
$modJar = Join-Path $repo 'research\mods-hbm\HBM-NTM-1.0.27_X5771.jar'
if ($args -contains '-ModJar') { $modJar = $args[([array]::IndexOf($args,'-ModJar')) + 1] }

New-Item -ItemType Directory -Force $outDir | Out-Null
New-Item -ItemType Directory -Force (Join-Path $outDir 'mods') | Out-Null
New-Item -ItemType Directory -Force (Join-Path $outDir 'config') | Out-Null

$hbm = $modJar
if (-not (Test-Path $hbm)) { Write-Error ("missing: " + $hbm); exit 1 }
$hbmStaged = Join-Path $outDir ('mods\' + (Split-Path -Leaf $hbm))
if (-not (Test-Path $hbmStaged)) { Copy-Item $hbm $hbmStaged }

$jsonOut = Join-Path $repo 'research\out\legacy\g2-tick-coverage.json'

# ---- RAM guard (same discipline as run-legacy-boot.ps1 / run-legacy-m1-probe.ps1) --------------
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
  ('-Dumb.legacy.tickCoverageJson=' + $jsonOut),
  '-cp', $hostCp,
  'dev.umb.legacy.boot.TickCoverageMain'
)

$logFile = Join-Path $outDir 'tick-coverage.log'
Write-Output ("heap        : " + $heap)
Write-Output ("log         : " + $logFile)

$sw = [System.Diagnostics.Stopwatch]::StartNew()
$proc = Start-Process -FilePath $jdk25 -ArgumentList $jvm -NoNewWindow -PassThru `
        -RedirectStandardOutput $logFile -RedirectStandardError (Join-Path $outDir 'tick-coverage.err.log')
Write-Output ("pid         : " + $proc.Id)

if (-not $proc.WaitForExit(($timeoutSec + 30) * 1000)) {
  Write-Output ("TIMEOUT - killing only pid " + $proc.Id)
  Stop-Process -Id $proc.Id -Force -Confirm:$false
  exit 3
}
$sw.Stop()
Write-Output ("exit        : " + $proc.ExitCode + " after " + [int]$sw.Elapsed.TotalSeconds + "s")
Write-Output '--- tick-coverage.txt ---'
Get-Content (Join-Path $outDir 'tick-coverage.txt')
exit $proc.ExitCode
