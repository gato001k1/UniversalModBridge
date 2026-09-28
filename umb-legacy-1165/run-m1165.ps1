# Headless M1-style vertical gate (see M1165ProbeMain/M1165Probe javadocs): full ModLoader
# boot inside the isolated universe, then place iron chest -> activate -> 54 slots ->
# put/take diamonds -> tick -> NBT round-trip. Writes research/out/legacy-1165/m1165-probe.txt
# (M1165-OK / M1165-FAIL). No game window: universe-root discipline (shared log4j +
# bridge-api on the parent, everything else child-first) plus the two era-native add-opens.
#   powershell -NoProfile -ExecutionPolicy Bypass -File umb-legacy-1165\run-m1165.ps1
$ErrorActionPreference = 'Stop'

$mod   = $PSScriptRoot
$repo  = Split-Path -Parent $mod
$jdk21 = Join-Path $repo 'tools\jdk-21.0.12.1+1\bin'
$java  = Join-Path $jdk21 'java.exe'
$build = Join-Path $mod 'build'

$bootJar     = Join-Path $build 'umb-legacy1165-boot.jar'
$apiJar      = Join-Path $build 'umb-legacy1165-api.jar'
$bridgeApiJar = Join-Path $build 'umb-legacy1165-bridge-api.jar'
$logApi  = Join-Path $repo 'research\out\legacy-1165\libs\log4j-api-2.15.0.jar'
$logCore = Join-Path $repo 'research\out\legacy-1165\libs\log4j-core-2.15.0.jar'
foreach ($p in @($java, $bootJar, $apiJar, $bridgeApiJar, $logApi, $logCore)) {
  if (-not (Test-Path $p)) { Write-Error ("missing: " + $p + " - run build.ps1 first"); exit 1 }
}

$cp = ($bootJar, $apiJar, $bridgeApiJar, $logApi, $logCore) -join ';'
$forgeJar = Join-Path $repo 'research\out\legacy-1165\forge-1.16.5-36.2.34-universal.jar'
$modJar   = Join-Path $repo 'research\out\legacy-1165\ironchest-1.16.5-11.2.21.jar'
& $java "--add-opens" "java.base/sun.security.util=ALL-UNNAMED" "--add-opens" "java.base/java.util.jar=ALL-UNNAMED" "-Dumb.repo=$repo" ("-Dumb.1165.modjars=" + $forgeJar + ";" + $modJar) -cp $cp dev.umb.legacy1165.boot.M1165ProbeMain
$m1165Exit = $LASTEXITCODE
Write-Output ("M1165_PROBE_EXIT=" + $m1165Exit)
if ($m1165Exit -ne 0) { exit 1 }
exit 0
