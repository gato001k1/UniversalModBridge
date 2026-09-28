# Boot the legacy universe headless on JDK 25 and drive HBM's full FML lifecycle.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\run-legacy-boot.ps1
#       [-SideTransformer]         also register cpw.mods.fml.common.asm.transformers.SideTransformer
#       [-Heap 1G] [-TimeoutSec 1200] [-NoWait]
#
# No game window is ever created: side = SERVER, java.awt.headless=true, and net.minecraft.client.*
# is never touched (the runtime jar does contain the client classes, they are simply not entered).
#
# RAM discipline: another lane may be running a real 26.2 client. Before starting a JVM with
# -Xmx >= 768m this waits (up to 20 minutes) for 1.2 GB of free physical memory, and it never
# touches PIDs it did not start.
$ErrorActionPreference = 'Stop'

$repo   = Split-Path -Parent $PSScriptRoot
$jdk25  = Join-Path $repo 'tools\jdk-25.0.4.1+1\bin\java.exe'
$build  = Join-Path $repo 'build\legacy'
$outDir = Join-Path $repo 'research\out\legacy\legacy-boot'
$mod    = Join-Path $repo 'umb-legacy'
$libsDir = Join-Path $repo 'research\visual\mc1710-native\libraries'

$bootJar  = Join-Path $build 'umb-legacy-boot.jar'
$apiJar   = Join-Path $build 'umb-legacy-api.jar'
$bridgeApiJar = Join-Path $build 'umb-bridge-api.jar'
$lsJar    = Join-Path $build 'umb-legacy-legacyside.jar'
$forgeSrg = Join-Path $build 'forge-1.7.10-10.13.4.1614-srg.jar'
# see tools\build-legacy.ps1 step 0: the pristine input jar carries a bogus
# "net/minecraft/client/particle,EffectRenderer$1.class" entry that makes FML discard the whole jar.
# step 2.5 (F0 SrgFieldRepair) then field-repairs -clean into -fields; that is the jar a real boot
# (and everything after it) must run against - see g2-design/DESIGN.md "F0".
$runtimeJar = Join-Path $build '1.7.10-forge-srg-runtime-fields.jar'
$lwJar    = Join-Path $libsDir 'net\minecraft\launchwrapper\1.12\launchwrapper-1.12.jar'
$joptJar  = (Get-ChildItem -Recurse -Filter 'jopt-simple-*.jar' $libsDir | Select-Object -First 1).FullName
$log4jApi = (Get-ChildItem -Recurse -Filter 'log4j-api-*.jar' $libsDir | Select-Object -First 1).FullName
$log4jCore= (Get-ChildItem -Recurse -Filter 'log4j-core-*.jar' $libsDir | Select-Object -First 1).FullName
$log4jCfg = Join-Path $mod 'resources\log4j2-legacy.xml'

foreach ($p in @($jdk25, $bootJar, $apiJar, $bridgeApiJar, $lsJar, $forgeSrg, $runtimeJar, $lwJar, $joptJar, $log4jApi, $log4jCore, $log4jCfg)) {
  if (-not (Test-Path $p)) { Write-Error ("missing: " + $p + " - run tools\build-legacy.ps1 first"); exit 1 }
}

$heap       = '1G'
$timeoutSec = 1200
if ($args -contains '-Heap')       { $heap       = $args[([array]::IndexOf($args,'-Heap')) + 1] }
if ($args -contains '-TimeoutSec') { $timeoutSec = [int]$args[([array]::IndexOf($args,'-TimeoutSec')) + 1] }
$sideTransformer = $args -contains '-SideTransformer'
# UNIVERSALITY (harness-purge, finding 19): $ModJar lets any 1.7.10 mod jar be booted through this
# harness instead of only HBM's. Default kept as the historical HBM path so an unchanged
# invocation reproduces today's behaviour exactly. dev.umb.legacy.boot.Bootstrap itself already
# just drives whatever is discovered in the mods\ dir - this was purely a tools\-level limitation.
$modJar = Join-Path $repo 'research\mods-hbm\HBM-NTM-1.0.27_X5771.jar'
if ($args -contains '-ModJar') { $modJar = $args[([array]::IndexOf($args,'-ModJar')) + 1] }

New-Item -ItemType Directory -Force $outDir | Out-Null
New-Item -ItemType Directory -Force (Join-Path $outDir 'mods') | Out-Null
New-Item -ItemType Directory -Force (Join-Path $outDir 'config') | Out-Null

# the target mod jar must be discovered by FML from the mods dir, NOT be on the loader's initial sources
$hbm = $modJar
if (-not (Test-Path $hbm)) { Write-Error ("missing: " + $hbm); exit 1 }
$hbmStaged = Join-Path $outDir ('mods\' + (Split-Path -Leaf $hbm))
if (-not (Test-Path $hbmStaged)) { Copy-Item $hbm $hbmStaged }
Write-Output ("mods dir    : " + ((Get-ChildItem (Join-Path $outDir 'mods') -Filter *.jar | ForEach-Object { $_.Name }) -join ', '))

# ---- RAM guard -------------------------------------------------------------------------------
$needMB = 1229   # 1.2 GB
$waited = 0
while ($true) {
  $freeMB = [int]((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1024)
  if ($freeMB -ge $needMB) { Write-Output ("free RAM    : " + $freeMB + " MB - ok"); break }
  if ($waited -ge 1200) { Write-Error ("only " + $freeMB + " MB free after 20 min; refusing to start"); exit 1 }
  Write-Output ("free RAM    : " + $freeMB + " MB - waiting for " + $needMB + " MB (" + $waited + "s)")
  Start-Sleep -Seconds 20
  $waited += 20
}

# ---- the exact JVM flags ---------------------------------------------------------------------
# --sun-misc-unsafe-memory-access=allow : netty-all-4.0.10 PlatformDependent0 uses the terminally
#                                         deprecated sun.misc.Unsafe memory accessors
# --add-opens java.base/java.lang       : guava 17 / FML reflect into JDK internals
#              java.util, java.net, java.nio, java.lang.reflect, java.io, java.util.concurrent
# -Djava.awt.headless=true              : no AWT, ever
# -Dlog4j.configurationFile              : replaces the Forge jar's log4j2.xml (Queue appender +
#                                         Routing + OnStartupTriggeringPolicy on log4j 2.0-beta9)
# -Dfml.queryResult=confirm             : StartupQuery must never block a headless boot
# -XX:-OmitStackTraceInFastThrow        : we need the FIRST real stack, not a recycled one
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
  ('-Dumb.legacy.sideTransformer=' + $sideTransformer.ToString().ToLower()),
  ('-Dumb.legacy.timeoutSeconds=' + $timeoutSec),
  '-cp', $hostCp,
  'dev.umb.legacy.boot.Bootstrap'
)

$logFile = Join-Path $outDir 'boot.log'
Write-Output ("heap        : " + $heap)
Write-Output ("log         : " + $logFile)
Write-Output ('--- java flags ---')
($jvm -join ' ') | Out-File (Join-Path $outDir 'jvm-flags.txt') -Encoding utf8
Write-Output ($jvm -join ' ')

$sw = [System.Diagnostics.Stopwatch]::StartNew()
$proc = Start-Process -FilePath $jdk25 -ArgumentList $jvm -NoNewWindow -PassThru `
        -RedirectStandardOutput $logFile -RedirectStandardError (Join-Path $outDir 'boot.err.log')
Write-Output ("pid         : " + $proc.Id)
$proc.Id | Out-File (Join-Path $outDir 'boot.pid') -Encoding ascii

if ($args -contains '-NoWait') { exit 0 }

if (-not $proc.WaitForExit(($timeoutSec + 120) * 1000)) {
  Write-Output ("TIMEOUT - killing only pid " + $proc.Id)
  Stop-Process -Id $proc.Id -Force -Confirm:$false
  exit 3
}
$sw.Stop()
Write-Output ("exit        : " + $proc.ExitCode + " after " + [int]$sw.Elapsed.TotalSeconds + "s")
Write-Output '--- tail of boot.log ---'
Get-Content $logFile -Tail 40
exit $proc.ExitCode
