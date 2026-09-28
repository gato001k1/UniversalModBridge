# Boot the legacy universe headless via LegacyBridgeImpl and drive the DESIGN.md LANE A step 6
# scenario (M1Probe): create the Brick Furnace's tile, activate() it, tick it, round-trip its NBT.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\run-legacy-m1-probe.ps1 [-Heap 1G] [-TimeoutSec 120]
#
# Same JVM-flag discipline as tools\run-legacy-boot.ps1 (see that script's comments for why each one
# is needed) - this is a SEPARATE process/boot, never run in the same JVM as a run-legacy-boot.ps1
# invocation (FML's Loader/GameData singletons are global and single-shot).
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
$timeoutSec = 120
if ($args -contains '-Heap')       { $heap       = $args[([array]::IndexOf($args,'-Heap')) + 1] }
if ($args -contains '-TimeoutSec') { $timeoutSec = [int]$args[([array]::IndexOf($args,'-TimeoutSec')) + 1] }
if ($args -contains '-OutDir') { $outDir = $args[([array]::IndexOf($args,'-OutDir')) + 1] }
# UNIVERSALITY (harness-purge, finding 19): $ModJar lets any 1.7.10 mod jar be staged into mods\
# instead of only HBM's. Default kept as the historical HBM path so an unchanged invocation
# reproduces today's behaviour exactly. NOTE (disclosed, not fixed here - umb-legacy is another
# lane's ownership): dev.umb.legacy.boot.M1ProbeMain itself still drives a hardcoded HBM scenario
# (the Brick Furnace tile) regardless of which jar is staged, so passing -ModJar here alone does
# NOT make the M1 scenario itself mod-generic - only the boot/classload step.
$modJar = Join-Path $repo 'research\mods-hbm\HBM-NTM-1.0.27_X5771.jar'
$modJars = @($modJar)
if ($args -contains '-ModJar') { $modJars = @($args[([array]::IndexOf($args,'-ModJar')) + 1]) }
if ($args -contains '-ModJars') {
  $modJars = $args[([array]::IndexOf($args,'-ModJars')) + 1].Split(';') | Where-Object { $_ }
}
$probeName = 'dev.umb.legacy.legacyside.M1Probe'
if ($args -contains '-Probe') { $probeName = $args[([array]::IndexOf($args,'-Probe')) + 1] }
$persistenceTileId = ''
$persistenceEntityItemId = ''
$persistenceEntityItemClass = ''
if ($args -contains '-PersistenceTileId') { $persistenceTileId = $args[([array]::IndexOf($args,'-PersistenceTileId')) + 1] }
if ($args -contains '-PersistenceEntityItemId') { $persistenceEntityItemId = $args[([array]::IndexOf($args,'-PersistenceEntityItemId')) + 1] }
if ($args -contains '-PersistenceEntityItemClass') { $persistenceEntityItemClass = $args[([array]::IndexOf($args,'-PersistenceEntityItemClass')) + 1] }
$legacySideOverride = $lsJar
if ($args -contains '-LegacySideJar') { $legacySideOverride = $args[([array]::IndexOf($args,'-LegacySideJar')) + 1] }
$bootOverride = $bootJar
if ($args -contains '-BootJar') { $bootOverride = $args[([array]::IndexOf($args,'-BootJar')) + 1] }
$bridgeApiOverride = $bridgeApiJar
if ($args -contains '-BridgeApiJar') { $bridgeApiOverride = $args[([array]::IndexOf($args,'-BridgeApiJar')) + 1] }

New-Item -ItemType Directory -Force $outDir | Out-Null
New-Item -ItemType Directory -Force (Join-Path $outDir 'mods') | Out-Null
New-Item -ItemType Directory -Force (Join-Path $outDir 'config') | Out-Null

foreach ($probeMod in $modJars) {
  if (-not (Test-Path $probeMod)) { Write-Error ("missing: " + $probeMod); exit 1 }
  $staged = Join-Path $outDir ('mods\' + (Split-Path -Leaf $probeMod))
  if (-not (Test-Path $staged)) { Copy-Item $probeMod $staged }
}

# ---- RAM guard (same discipline as run-legacy-boot.ps1) ----------------------------------------
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

$hostCp = @($bootOverride, $apiJar, $bridgeApiOverride, $lwJar, $joptJar, $log4jApi, $log4jCore) -join ';'
$probeProps = @()
if ($persistenceTileId -ne '') { $probeProps += ('-Dumb.persistence.tileId=' + $persistenceTileId) }
if ($persistenceEntityItemId -ne '') { $probeProps += ('-Dumb.persistence.entityItemId=' + $persistenceEntityItemId) }
if ($persistenceEntityItemClass -ne '') { $probeProps += ('-Dumb.persistence.entityItemClass=' + $persistenceEntityItemClass) }
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
  ('-Dumb.legacy.legacysideJar=' + $legacySideOverride),
  ('-Dumb.legacy.probe=' + $probeName),
  ('-Dumb.legacy.timeoutSeconds=' + $timeoutSec)
)
$jvm += $probeProps
$jvm += @('-cp', $hostCp, 'dev.umb.legacy.boot.M1ProbeMain')

$logFile = Join-Path $outDir 'm1-probe.log'
Write-Output ("heap        : " + $heap)
Write-Output ("log         : " + $logFile)

$sw = [System.Diagnostics.Stopwatch]::StartNew()
$proc = Start-Process -FilePath $jdk25 -ArgumentList $jvm -NoNewWindow -PassThru `
        -RedirectStandardOutput $logFile -RedirectStandardError (Join-Path $outDir 'm1-probe.err.log')
Write-Output ("pid         : " + $proc.Id)

if (-not $proc.WaitForExit(($timeoutSec + 30) * 1000)) {
  Write-Output ("TIMEOUT - killing only pid " + $proc.Id)
  Stop-Process -Id $proc.Id -Force -Confirm:$false
  exit 3
}
$sw.Stop()
Write-Output ("exit        : " + $proc.ExitCode + " after " + [int]$sw.Elapsed.TotalSeconds + "s")
Write-Output '--- m1-probe.txt ---'
Get-Content (Join-Path $outDir 'm1-probe.txt')
exit $proc.ExitCode
