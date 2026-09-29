# G2 task 4: the headless end-to-end gate for the REAL UmbUniverse (dev.umb.hostagent.content.
# UmbUniverse) - not FakeLegacyBridge, not umb-legacy's own in-process M1Probe. This runs
# dev.umb.hostagent.probe.M1UniverseProbe, which builds UmbUniverse, boots the REAL three-tier
# classloader stack (see UmbUniverse's javadoc), reaches the REAL LegacyBridgeImpl through it, and
# drives the Brick Furnace scenario against in-memory Fake HostWorld/HostPlayer (no ServerLevel
# needed - see the probe's own javadoc for why that is enough to prove the embedding).
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\windows\run-hostagent-e2e-m1.ps1 [-TimeoutSec 120] [-Scenario <path>]
#
# -Scenario points at the mod-specific end-to-end scenario the probe drives
# (default research/out/legacy/m1-scenario-hbm.json - block/item ids, slot layout,
# fuel and ticks for the furnace + RTG liveness proofs). The probe Java itself takes
# the scenario path as its only argument and names no mod.
#
# Same legacy JVM-flag discipline as tools\windows\run-legacy-m1-probe.ps1 (this is what UmbUniverse.boot()
# itself checks for before doing anything else, and fails loudly - not obscurely - if missing).
# Separate JVM from anything else that boots FML's Loader singleton (never share a process with
# tools\windows\run-legacy-boot.ps1 / tools\windows\run-legacy-m1-probe.ps1 / umb-legacy's own JUnit gate).
$ErrorActionPreference = 'Stop'

$repo   = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$java   = Join-Path $repo 'tools\jdk-25.0.4.1+1\bin\java.exe'
$classes = Join-Path $repo 'build\hostagent\classes'
$outDir = Join-Path $repo 'research\out\legacy\g2-integration'
$libsDir = Join-Path $repo 'research\visual\mc1710-native\libraries'
$legacyBuild = Join-Path $repo 'build\legacy'

$umbLegacyBootJar = Join-Path $legacyBuild 'umb-legacy-boot.jar'
$umbLegacyApiJar = Join-Path $legacyBuild 'umb-legacy-api.jar'
$launchwrapperJar = Join-Path $libsDir 'net\minecraft\launchwrapper\1.12\launchwrapper-1.12.jar'
$joptJar = (Get-ChildItem -Recurse -Filter 'jopt-simple-*.jar' $libsDir | Select-Object -First 1).FullName
# gson: the probe parses its scenario JSON with it (same 2.14.0 jar hostagent main compiles
# against in the game classpath - JsonParser has been stable forever, but exact-match is free).
$gsonJar = Join-Path $repo 'research\jars\26.2\libraries\com\google\code\gson\gson\2.14.0\gson-2.14.0.jar'

foreach ($p in @($java, $classes, $umbLegacyBootJar, $umbLegacyApiJar, $launchwrapperJar, $joptJar, $gsonJar)) {
  if (-not $p -or -not (Test-Path $p)) { Write-Error ("missing: " + $p + " - run tools\windows\build-hostagent.ps1 and tools\windows\build-legacy.ps1 first"); exit 1 }
}

$timeoutSec = 120
if ($args -contains '-TimeoutSec') { $timeoutSec = [int]$args[([array]::IndexOf($args,'-TimeoutSec')) + 1] }
$scenario = Join-Path $repo 'research\out\legacy\m1-scenario-hbm.json'
if ($args -contains '-Scenario') { $scenario = $args[([array]::IndexOf($args,'-Scenario')) + 1] }
if (-not (Test-Path $scenario)) { Write-Error ("missing scenario: " + $scenario); exit 1 }

New-Item -ItemType Directory -Force $outDir | Out-Null

# ---- RAM guard (same discipline as run-legacy-m1-probe.ps1) ------------------------------------
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

$cp = @($classes, $umbLegacyBootJar, $umbLegacyApiJar, $launchwrapperJar, $joptJar, $gsonJar) -join ';'
$jvm = @(
  '-Xmx1G',
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
  ('-Dumb.repo=' + $repo),
  '-cp', $cp,
  'dev.umb.hostagent.probe.M1UniverseProbe',
  $scenario
)

$logFile = Join-Path $outDir 'm1-universe-probe.log'
$errFile = Join-Path $outDir 'm1-universe-probe.err.log'
Write-Output ('log         : ' + $logFile)

$proc = Start-Process -FilePath $java -ArgumentList $jvm -NoNewWindow -PassThru `
        -RedirectStandardOutput $logFile -RedirectStandardError $errFile
Write-Output ('pid         : ' + $proc.Id)

if (-not $proc.WaitForExit($timeoutSec * 1000)) {
  Write-Output ('TIMEOUT - killing only pid ' + $proc.Id)
  Stop-Process -Id $proc.Id -Force -Confirm:$false
  exit 3
}
Write-Output ('exit        : ' + $proc.ExitCode)
Write-Output '--- m1-universe-probe.log ---'
Get-Content $logFile
exit $proc.ExitCode
