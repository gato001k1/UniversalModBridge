# Runs the headless mod-loading lifecycle gate: builds the isolated universe, delegates to
# the in-universe Legacy1165Lifecycle (real ModLoader phases, see its javadoc), writes
# research/out/legacy-1165/registry-probe.txt (REGISTRY-OK / REGISTRY-PARTIAL) and
# research/out/legacy-1165/registrations.json. No game window, no world, no ModLauncher boot.
#   powershell -NoProfile -ExecutionPolicy Bypass -File umb-legacy-1165\run-lifecycle.ps1
$ErrorActionPreference = 'Stop'

$mod   = $PSScriptRoot
$repo  = Split-Path -Parent $mod
$jdk21 = Join-Path $repo 'tools\jdk-21.0.12.1+1\bin'
$java  = Join-Path $jdk21 'java.exe'
$build = Join-Path $mod 'build'

$bootJar = Join-Path $build 'umb-legacy1165-boot.jar'
$apiJar  = Join-Path $build 'umb-legacy1165-api.jar'
# bridge-api on the APP classpath: legacyside implements the shared contract, which the child
# loader parent-delegates, so the parent must provide it (universe-root discipline).
$bridgeApiJar = Join-Path $build 'umb-legacy1165-bridge-api.jar'
$tomlJar = Join-Path $repo 'research\out\legacy-1165\libs\night-config-toml-3.6.3.jar'
$coreJar = Join-Path $repo 'research\out\legacy-1165\libs\night-config-core-3.6.3.jar'
# One shared log4j via the parent (universe-root discipline, same as the 1.7.10/1.12.2 eras:
# the child loader parent-delegates org.apache.logging.*, so the parent must provide it -
# the probe never needed this because Class.forName(..., false) never executes <clinit>).
$logApi = Join-Path $repo 'research\out\legacy-1165\libs\log4j-api-2.15.0.jar'
$logCore = Join-Path $repo 'research\out\legacy-1165\libs\log4j-core-2.15.0.jar'
foreach ($p in @($java, $bootJar, $apiJar, $bridgeApiJar, $tomlJar, $coreJar, $logApi, $logCore)) {
  if (-not (Test-Path $p)) { Write-Error ("missing: " + $p + " - run build.ps1 first"); exit 1 }
}

$cp = ($bootJar, $apiJar, $bridgeApiJar, $tomlJar, $coreJar, $logApi, $logCore) -join ';'
# Mod jars under test (the lifecycle discovers exactly these via Forge's own locator).
# Forge FIRST (mirrors production: forge boots before content mods; its ATTRIBUTES registry
# and capability registrations must exist when mods build), then the content mod.
$forgeJar = Join-Path $repo 'research\out\legacy-1165\forge-1.16.5-36.2.34-universal.jar'
$modJar = Join-Path $repo 'research\out\legacy-1165\ironchest-1.16.5-11.2.21.jar'
$modJars = ($forgeJar, $modJar) -join ';'
# Era-native code on a modern JDK needs explicit opens (same family as the 1.12.2 lane's
# java.io open for its Pack200 shim): ModLauncher 8.1.3's SecureJarHandler touches
# sun.security.util.ManifestEntryVerifier (legal on Java 8, encapsulated since 16) and
# reflectively opens java.util.jar.Manifest (SecureJarHandler.<clinit>). One flag per
# proven-necessary package, nothing speculative.
& $java "--add-opens" "java.base/sun.security.util=ALL-UNNAMED" "--add-opens" "java.base/java.util.jar=ALL-UNNAMED" "-Dumb.repo=$repo" ("-Dumb.1165.modjars=" + $modJars) -cp $cp dev.umb.legacy1165.boot.Registry1165DriverMain
$driverExit = $LASTEXITCODE
Write-Output ("LIFECYCLE_DRIVER_EXIT=" + $driverExit)
if ($driverExit -ne 0) { exit 1 }
exit 0
