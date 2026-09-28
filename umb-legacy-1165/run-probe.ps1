# Runs the headless "how far can the isolated loader get against the REAL fetched 1.16.5 jars"
# probe (see Boot1165ProbeMain's javadoc for exactly what it proves and does not attempt).
#   powershell -NoProfile -ExecutionPolicy Bypass -File umb-legacy-1165\run-probe.ps1
$ErrorActionPreference = 'Stop'

$mod   = $PSScriptRoot
$repo  = Split-Path -Parent $mod
$jdk21 = Join-Path $repo 'tools\jdk-21.0.12.1+1\bin'
$java  = Join-Path $jdk21 'java.exe'
$build = Join-Path $mod 'build'

$bootJar = Join-Path $build 'umb-legacy1165-boot.jar'
$apiJar  = Join-Path $build 'umb-legacy1165-api.jar'
# log4j on the APP classpath: the in-universe transform worker initializes Forge's engine,
# which logs through the parent-delegated org.apache.logging (the legacy side must provide
# it - same universe-root discipline as the older eras). No ASM/eventbus here: the loader
# never links them at app level (worker delegation is reflective).
$logApi  = Join-Path $repo 'research\out\legacy-1165\libs\log4j-api-2.15.0.jar'
$logCore = Join-Path $repo 'research\out\legacy-1165\libs\log4j-core-2.15.0.jar'
foreach ($p in @($java, $bootJar, $apiJar, $logApi, $logCore)) {
  if (-not (Test-Path $p)) { Write-Error ("missing: " + $p + " - run build.ps1 first"); exit 1 }
}

$cp = ($bootJar, $apiJar, $logApi, $logCore) -join ';'
& $java "-Dumb.repo=$repo" -cp $cp dev.umb.legacy1165.boot.Boot1165ProbeMain
$probeExit = $LASTEXITCODE
Write-Output ("BOOT_PROBE_EXIT=" + $probeExit)
if ($probeExit -ne 0) { exit 1 }
exit 0
