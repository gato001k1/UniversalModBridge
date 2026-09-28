# JUnit gate for umb-hostagent. JDK 25 only (client.jar is classfile major 69).
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\run-hostagent-tests.ps1
param([switch]$Conformance)
$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent $PSScriptRoot
$jdk25 = Join-Path $repo 'tools\jdk-25.0.4.1+1\bin'
$javac = Join-Path $jdk25 'javac.exe'
$java = Join-Path $jdk25 'java.exe'
$junit = Join-Path $repo 'tools\junit\junit-platform-console-standalone.jar'
$cpFile = Join-Path $repo 'research\visual\mc262-vanilla\classpath.txt'
$mainClasses = Join-Path $repo 'build\hostagent\classes'
$bridgeApiMirror = Join-Path $repo 'build\hostagent\classes-bridge-api-mirror'
$testSrc = Join-Path $repo 'umb-hostagent\src\test\java'
$testClasses = Join-Path $repo 'build\hostagent\test-classes'
$agentJar = Join-Path $repo 'build\hostagent\umb-hostagent.jar'

foreach ($p in @($javac, $java, $junit, $cpFile, $mainClasses, $agentJar)) {
  if (-not (Test-Path $p)) { Write-Error ("missing: " + $p + " (run tools\build-hostagent.ps1 first)"); exit 1 }
}

$rawGameCp = (Get-Content $cpFile -Raw).Trim()
$gameCpParts = $rawGameCp -split ';'
if ($gameCpParts.Count -eq 1 -and $rawGameCp -match '/mnt/[A-Za-z]/') {
  $gameCpParts = $rawGameCp -split ':'
}
$gameCp = (($gameCpParts | ForEach-Object {
  $entry = $_
  if ($entry -match '^/mnt/([A-Za-z])/(.*)$') {
    $entry = ($matches[1].ToUpper() + ':\' + ($matches[2] -replace '/', '\'))
  }
  $entry
}) -join ';')
# asm-util + asm-analysis are TEST-ONLY (CheckClassAdapter.verify); the agent jar bundles only
# asm, asm-tree and asm-commons.
$asm = @(
  (Join-Path $repo 'tools\junit\asm-9.9.jar'),
  (Join-Path $repo 'tools\junit\asm-tree-9.9.jar'),
  (Join-Path $repo 'tools\junit\asm-commons-9.9.jar'),
  (Join-Path $repo 'tools\junit\asm-analysis-9.9.jar'),
  (Join-Path $repo 'tools\junit\asm-util-9.9.jar')
)
# G2 task 2: dev.umb.hostagent.HostAgent now references dev.umb.hostagent.content.UmbUniverse,
# which imports LegacyLoader/LegacyClasspath/Launch directly (see its javadoc) - these must resolve
# on the test classpath too, or class VERIFICATION of UmbUniverse's own methods fails the moment
# anything loads that class (not just when boot() is actually called).
$legacyBuild = Join-Path $repo 'build\legacy'
$libsDir = Join-Path $repo 'research\visual\mc1710-native\libraries'
$launchwrapper = Join-Path $libsDir 'net\minecraft\launchwrapper\1.12\launchwrapper-1.12.jar'
$tier1Jars = @(
  (Join-Path $legacyBuild 'umb-legacy-boot.jar'),
  (Join-Path $legacyBuild 'umb-legacy-api.jar'),
  $launchwrapper,
  (Get-ChildItem -Recurse -Filter 'jopt-simple-*.jar' $libsDir | Select-Object -First 1).FullName
)
foreach ($j in $tier1Jars) { if (-not $j -or -not (Test-Path $j)) { Write-Error ("missing tier-1 jar: " + $j); exit 1 } }

if (Test-Path $testClasses) { Remove-Item -Recurse -Force $testClasses }
New-Item -ItemType Directory -Force $testClasses | Out-Null

$apiPrefix = @()
if (Test-Path $bridgeApiMirror) { $apiPrefix = @($bridgeApiMirror) }
$cp = ((($apiPrefix + @($mainClasses, $junit)) + $asm + $tier1Jars + @($gameCp)) -join ';')
if ($Conformance) {
  $sources = @(
    (Join-Path $repo 'umb-hostagent\src\main\java\dev\umb\hostagent\content\BridgeRouter.java'),
    (Join-Path $repo 'umb-hostagent\src\main\java\dev\umb\hostagent\content\UmbSettings.java')
  ) + @(Get-ChildItem -Filter 'LegacyConformance*.java' (Join-Path $testSrc 'dev\umb\hostagent\content') |
    ForEach-Object { $_.FullName })
} else {
  $sources = Get-ChildItem -Recurse -Filter *.java $testSrc | ForEach-Object { $_.FullName }
}
Write-Output ("test sources: " + $sources.Count)

$argfile = Join-Path $repo 'build\hostagent\javac-test.args'
($sources | ForEach-Object { '"' + ($_ -replace '\\', '\\\\') + '"' }) -join "`n" | Out-File $argfile -Encoding ascii
& $javac -nowarn -encoding UTF-8 -cp $cp -d $testClasses ('@' + $argfile)
if ($LASTEXITCODE -ne 0) { Write-Error 'test javac failed'; exit 1 }

if ($Conformance) {
  Push-Location $repo
  Write-Output '--- conformance suite (tag=conformance; expected red assertions are intentional) ---'
  & $java -jar $junit execute --class-path ($testClasses + ';' + $cp) `
    --select-package dev.umb.hostagent.content --include-tag conformance `
    --details=summary --disable-ansi-colors
  $rc = $LASTEXITCODE
  Pop-Location
  exit $rc
}

Push-Location $repo

# UmbLegacyBlockTest constructs FRESH Block/BlockEntityType instances repeatedly, which needs
# BuiltInRegistries to stay UNFROZEN for the whole process (TestSupport.ensureBootstrappedWithoutFreezing).
# DynFieldChannelTest does the same (fresh UmbLegacyBlock per dynamic-field case), so it runs here too.
# Every other test class needs the REAL, freezing Bootstrap.bootStrap() instead (TestSupport.ensureBootstrapped),
# because new ItemStack(...) reads bound default components that only get bound during that freeze pass.
# The two are mutually exclusive within one JVM (see TestSupport's javadoc), so this runs
# UmbLegacyBlockTest in its own separate `java` invocation and combines both exit codes.
Write-Output '--- run 1: UmbLegacyBlockTest + DynFieldChannelTest (unfrozen-registry mode) ---'
& $java -jar $junit execute --class-path ($testClasses + ';' + $cp) `
  --select-class dev.umb.hostagent.content.UmbLegacyBlockTest `
  --select-class dev.umb.hostagent.content.DynFieldChannelTest --details=summary --disable-ansi-colors
$rc1 = $LASTEXITCODE

Write-Output '--- run 2: everything else (real frozen-registry bootstrap) ---'
& $java -jar $junit execute --class-path ($testClasses + ';' + $cp) --scan-class-path $testClasses `
  --exclude-classname 'dev\.umb\.hostagent\.content\.UmbLegacyBlockTest' `
  --exclude-classname 'dev\.umb\.hostagent\.content\.DynFieldChannelTest' `
  --exclude-tag conformance `
  --exclude-classname 'dev\.umb\.hostagent\.content\.UmbMenuAdapterCrossLoaderTest' `
  --exclude-classname 'dev\.umb\.hostagent\.content\.UmbMenuContentParityTest' `
  --details=summary --disable-ansi-colors
$rc2 = $LASTEXITCODE

# UmbMenuAdapterCrossLoaderTest reproduces the G2 live crash (IllegalAccessError: a
# UmbDynamicAdapters-generated adapter cannot call its own package-private helper on
# UmbMenuRegistration because the two used to live in different runtime packages -- see that
# class's own javadoc). Reproducing it needs dev.umb.hostagent.UmbAccessWidener to have ACTUALLY
# widened MenuType$MenuSupplier / MenuScreens$ScreenConstructor before they are ever loaded, which
# only happens with the REAL agent attached as -javaagent (exactly like the live client) -- runs 1
# and 2 above never attach it, so this class cannot run there. Own separate `java` invocation, own
# exit code, combined below like runs 1/2.
#
# Deliberately NOT `-jar $junit ... --class-path ...` like runs 1/2: the console launcher's
# `--class-path` option loads test classes through its OWN dedicated URLClassLoader, whose PARENT
# is the system classloader. `-javaagent:$agentJar` appends the agent jar to the SYSTEM
# classloader's own search path, so any dev.umb.hostagent.* class also bundled in that jar
# (Hooks/Registrar/etc.) resolves via the parent (system loader) the moment it is referenced,
# instead of via the console launcher's child loader -- but net.minecraft.* is only on the CHILD
# loader's URLs, so the system-loader-resolved Registrar then fails with
# NoClassDefFoundError/ClassNotFoundException trying to reach net.minecraft.core.Registry (verified
# empirically while building this test: swapping to `--class-path` reproduces that split-loader
# failure instead of the real bug). Passing a real `-cp` + the launcher's actual main class instead
# keeps this single-classloader, exactly like a real java/game process (and like
# tools/probe-hostagent.ps1's own agent-attached invocation) -- confirmed this is what reproduces
# the EXACT live IllegalAccessError pre-fix.
Write-Output '--- run 3: UmbMenuAdapterCrossLoaderTest + UmbMenuContentParityTest (real -javaagent attached, widener active) ---'
$agentLog = Join-Path $repo 'build\hostagent\test-agent.log'
# The agent takes no silent mod-identity default: ns= is declared here, explicitly, because
# the content under test is the HBM corpus (UmbMenuContentParityTest asserts HBM menu
# content). This is an operational declaration in the driver script, not a code default.
& $java ('-javaagent:' + $agentJar + '=log=' + $agentLog + ';ns=hbm;launchwrapper=' + $launchwrapper) -cp ($testClasses + ';' + $cp) org.junit.platform.console.ConsoleLauncher execute `
  --select-class dev.umb.hostagent.content.UmbMenuAdapterCrossLoaderTest `
  --select-class dev.umb.hostagent.content.UmbMenuContentParityTest --details=summary --disable-ansi-colors
$rc3 = $LASTEXITCODE

Pop-Location
$rc = 0
if ($rc1 -ne 0) { $rc = $rc1 }
if ($rc2 -ne 0) { $rc = $rc2 }
if ($rc3 -ne 0) { $rc = $rc3 }
Write-Output ('combined exit: run1=' + $rc1 + ' run2=' + $rc2 + ' run3=' + $rc3 + ' -> ' + $rc)
exit $rc
