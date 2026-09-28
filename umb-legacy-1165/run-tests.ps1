# JUnit gate for umb-legacy-1165.
#   powershell -NoProfile -ExecutionPolicy Bypass -File umb-legacy-1165\run-tests.ps1
#
# The real fetched 1.16.5/Forge jars are put on the test classpath (not just referenced from a
# manifest file) for two reasons: RealForgeJarsProbeTest builds its OWN isolated loader from
# classpath-1165.txt (works either way), but Legacy1165BridgeImplTest's boot() test needs Forge
# classes reachable from the TEST's own classloader (Legacy1165BridgeImpl.boot() resolves classes
# via its own classloader, which - outside a real isolated-loader embedding - is whatever loaded
# the test). Both self-skip (Assumptions) if the manifest or jars are missing.
$ErrorActionPreference = 'Stop'

$mod   = $PSScriptRoot
$repo  = Split-Path -Parent $mod
$jdk21 = Join-Path $repo 'tools\jdk-21.0.12.1+1\bin'
$javac = Join-Path $jdk21 'javac.exe'
$java  = Join-Path $jdk21 'java.exe'
$build = Join-Path $mod 'build'
$junit = Join-Path $repo 'tools\junit\junit-platform-console-standalone.jar'
$asm   = Join-Path $repo 'tools\junit\asm-9.9.jar'

$bridgeApiJar  = Join-Path $build 'umb-legacy1165-bridge-api.jar'
$apiJar        = Join-Path $build 'umb-legacy1165-api.jar'
$legacysideJar = Join-Path $build 'umb-legacy1165-legacyside.jar'
$bootJar       = Join-Path $build 'umb-legacy1165-boot.jar'
$guigeomJar    = Join-Path $build 'umb-legacy1165-guigeom.jar'

foreach ($p in @($javac, $java, $junit, $asm, $bridgeApiJar, $apiJar, $legacysideJar, $bootJar, $guigeomJar)) {
  if (-not (Test-Path $p)) { Write-Error ("missing: " + $p + " - run build.ps1 first"); exit 1 }
}

# real jars from classpath-1165.txt, if the fetch step has been run - added to BOTH compile and
# run classpaths so Legacy1165BridgeImplTest's boot() path can genuinely succeed.
$manifest = Join-Path $mod 'resources\classpath-1165.txt'
$realJars = @()
if (Test-Path $manifest) {
  $realJars = Get-Content $manifest | Where-Object { $_.Trim() -and -not $_.Trim().StartsWith('#') } |
    ForEach-Object { (Join-Path $repo $_.Trim()) } | Where-Object { Test-Path $_ }
}
Write-Output ("real jars on test classpath: " + $realJars.Count + " / manifest present: " + (Test-Path $manifest))

$testClasses = Join-Path $build 'test-classes'
if (Test-Path $testClasses) { Remove-Item -Recurse -Force $testClasses }
New-Item -ItemType Directory -Force $testClasses | Out-Null

$compileCp = (@($bridgeApiJar, $apiJar, $legacysideJar, $bootJar, $guigeomJar, $junit, $asm) + $realJars) -join ';'
$testSrc = Join-Path $mod 'src\test\java'
$sources = Get-ChildItem -Recurse -Filter *.java $testSrc | ForEach-Object { $_.FullName }
Write-Output ("test sources: " + $sources.Count)

& $javac -nowarn -encoding UTF-8 --release 21 -cp $compileCp -d $testClasses $sources
if ($LASTEXITCODE -ne 0) { Write-Error 'javac failed for tests'; exit 1 }

$runCp = (@($testClasses) + $compileCp.Split(';')) -join ';'
# Era-native opens (same two as run-lifecycle.ps1/run-m1165.ps1): the live-universe tests
# (bridge boot, M1165 vertical) run ModLauncher-era code that touches SecureJarHandler
# internals, legal on Java 8, encapsulated since 16.
& $java "--add-opens" "java.base/sun.security.util=ALL-UNNAMED" "--add-opens" "java.base/java.util.jar=ALL-UNNAMED" "-Dumb.repo=$repo" -cp $runCp org.junit.platform.console.ConsoleLauncher `
  --scan-classpath --classpath $testClasses --disable-banner --details=tree
exit $LASTEXITCODE
