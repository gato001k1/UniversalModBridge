# Build umb-legacy: the "legacy universe" - real Forge 1.7.10 + SRG vanilla 1.7.10 running headless
# inside an isolated child-first classloader on JDK 25.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\windows\build-legacy.ps1 [-SkipRemap] [-SkipTests]
#
# Three artifacts, three different compilation targets, for reasons that matter:
#
#   umb-legacy-api.jar        --release 8   the plain-data handshake. Release 8 because ASM 5.0.3
#                                          (the ASM the legacy universe runs on) CANNOT READ a
#                                          classfile newer than major 52, and these types are
#                                          referenced from inside that universe.
#   umb-legacy-legacyside.jar --release 8   compiled against the 1.7.10 jars, loaded INSIDE the
#                                          legacy loader. Same ASM 5.0.3 constraint.
#   umb-legacy-boot.jar       --release 21  application-classloader side. Only touches
#                                          net.minecraft.launchwrapper.{Launch,LaunchClassLoader}
#                                          and dev.umb.legacy.api.
#
# There is a two-stage dance in the middle: the Forge UNIVERSAL jar is the production build and its
# own bytecode references vanilla by NOTCH names (Fluid.setBlock(aji)). Our vanilla is SRG-named, so
# the jar is SRG-ified once, offline, by FML's own FMLDeobfuscatingRemapper (see RemapTool). Stage 1
# of legacyside therefore builds only the srgifier (which needs nothing SRG), the remap runs, and
# stage 2 builds the rest against the freshly SRG-ified Forge jar.
$ErrorActionPreference = 'Stop'

$repo    = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$jdk21   = Join-Path $repo 'tools\jdk-21.0.12.1+1\bin'
$jdk25   = Join-Path $repo 'tools\jdk-25.0.4.1+1\bin'
$javac21 = Join-Path $jdk21 'javac.exe'
$jar21   = Join-Path $jdk21 'jar.exe'
$java25  = Join-Path $jdk25 'java.exe'

$mod       = Join-Path $repo 'umb-legacy'
$build     = Join-Path $repo 'build\legacy'
$outDir    = Join-Path $repo 'research\out\legacy\legacy-boot'

$runtimeJar = Join-Path $repo 'research\out\legacy\1.7.10-forge-srg-runtime.jar'
$forgeJar   = Join-Path $repo 'research\visual\mc1710-native\libraries\net\minecraftforge\forge\1.7.10-10.13.4.1614-1.7.10\forge-1.7.10-10.13.4.1614-1.7.10-universal.jar'
$notchJar   = Join-Path $repo 'research\visual\mc1710-native\versions\1.7.10-Forge10.13.4.1614-1.7.10\1.7.10-Forge10.13.4.1614-1.7.10.jar'
$libsDir    = Join-Path $repo 'research\visual\mc1710-native\libraries'
$lwJar      = Join-Path $libsDir 'net\minecraft\launchwrapper\1.12\launchwrapper-1.12.jar'
$forgeSrg   = Join-Path $build 'forge-1.7.10-10.13.4.1614-srg.jar'
# jopt-simple must sit on the APPLICATION classpath: net.minecraft.launchwrapper.Launch's
# private launch(String[]) is a v50 classfile, so HotSpot's inference verifier resolves the
# joptsimple types in its body when LaunchClassLoader/Launch is linked.
$joptJar    = (Get-ChildItem -Recurse -Filter 'jopt-simple-*.jar' $libsDir | Select-Object -First 1).FullName

# LaunchWrapper's own net.minecraft.launchwrapper.LogWrapper is parent-delegated (FML casts
# Launch.classLoader to the HOST's LaunchClassLoader, so that package cannot be duplicated), and
# LogWrapper's signatures mention org.apache.logging.log4j.Level. LaunchClassLoader.findClass calls
# it on the (benign) "jar has a security seal" path - lzma-0.0.1.jar seals its LZMA package - so
# without log4j on the HOST classpath a warning turns into a fatal NoClassDefFoundError.
$log4jApi   = (Get-ChildItem -Recurse -Filter 'log4j-api-*.jar' $libsDir | Select-Object -First 1).FullName
$log4jCore  = (Get-ChildItem -Recurse -Filter 'log4j-core-*.jar' $libsDir | Select-Object -First 1).FullName
$junit  = Join-Path $repo 'tools\junit\junit-platform-console-standalone.jar'
$asm    = Join-Path $repo 'tools\junit\asm-9.9.jar'
$asmTree = Join-Path $repo 'tools\junit\asm-tree-9.9.jar'
$asmCommons = Join-Path $repo 'tools\junit\asm-commons-9.9.jar'

foreach ($p in @($javac21, $jar21, $java25, $runtimeJar, $forgeJar, $notchJar, $libsDir, $lwJar, $joptJar, $junit, $asm, $asmTree, $asmCommons)) {
  if (-not (Test-Path $p)) { Write-Error ("missing: " + $p); exit 1 }
}

$skipRemap = $args -contains '-SkipRemap'
$skipTests = $args -contains '-SkipTests'

New-Item -ItemType Directory -Force $build  | Out-Null
New-Item -ItemType Directory -Force $outDir | Out-Null

# The 1.7.10 libraries come from the launcher's OWN -cp string, not a directory walk: the libraries
# tree holds both guava-15.0 and guava-17.0, a sorted walk picks 15.0, and FML then dies in
# AccessTransformer with NoSuchMethodError CharSource.readLines(LineProcessor) (@since guava 16).
$cpFile = Join-Path $repo 'research\visual\mc1710-native\classpath.txt'
if (-not (Test-Path $cpFile)) { Write-Error ("missing: " + $cpFile); exit 1 }
$cpAll = (Get-Content $cpFile -Raw).Trim() -split ';' | Where-Object { $_ } | ForEach-Object { $_.Trim() }
$missing = $cpAll | Where-Object { -not (Test-Path $_) }
if ($missing) { Write-Error ("classpath.txt references missing files: " + ($missing -join ', ')); exit 1 }
$libs = $cpAll | Where-Object {
  $n = Split-Path -Leaf $_
  ($n -notlike 'launchwrapper-*') -and ($n -notlike '1.7.10-Forge*') -and ($n -notlike 'forge-1.7.10-*-universal.jar')
}
Write-Output ("libs        : " + $libs.Count + " (from classpath.txt)")

function Compile([string]$name, [string[]]$srcRoots, [string]$cp, [string]$release, [string]$dest) {
  if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }
  New-Item -ItemType Directory -Force $dest | Out-Null
  $sources = @()
  foreach ($r in $srcRoots) { $sources += (Get-ChildItem -Recurse -Filter *.java $r | ForEach-Object { $_.FullName }) }
  if ($sources.Count -eq 0) { Write-Error ("no sources under " + ($srcRoots -join ';')); exit 1 }
  $argfile = Join-Path $build ("javac-" + $name + ".args")
  ($sources | ForEach-Object { '"' + ($_ -replace '\\', '\\\\') + '"' }) -join "`n" | Out-File $argfile -Encoding ascii
  $cpArgs = @()
  if ($cp) { $cpArgs = @('-cp', $cp) }
  & $javac21 -nowarn -encoding UTF-8 --release $release @cpArgs -d $dest ('@' + $argfile)
  if ($LASTEXITCODE -ne 0) { Write-Error ("javac failed for " + $name); exit 1 }
  Write-Output ("compiled    : " + $name + " (" + $sources.Count + " sources, release " + $release + ")")
}

function MakeJar([string]$dest, [string]$jarPath, [string]$mainClass, [string[]]$extraDirs) {
  foreach ($d in $extraDirs) { if (Test-Path $d) { Copy-Item -Recurse -Force (Join-Path $d '*') $dest } }
  if ($mainClass) { & $jar21 --create --file $jarPath --main-class $mainClass -C $dest . }
  else            { & $jar21 --create --file $jarPath -C $dest . }
  if ($LASTEXITCODE -ne 0) { Write-Error ("jar failed for " + $jarPath); exit 1 }
  Write-Output ("packaged    : " + (Split-Path -Leaf $jarPath) + " (" + [int]((Get-Item $jarPath).Length/1024) + " KB)")
}

# ---------------------------------------------------------------- 0. sanitise the runtime jar
# The Forge-patched SRG runtime jar came out of a LaunchWrapper DEBUG_SAVE dump, and the dump
# faithfully saved a class under the name Forge asks for in MinecraftForge.initialize()'s
# crash-report preload list - which contains a genuine TYPO: "net.minecraft.client.particle,
# EffectRenderer$1" (comma, not dot). ASMModParser then chokes on that zero-length entry with
# ArrayIndexOutOfBoundsException and ModDiscoverer.findClasspathMods discards the ENTIRE vanilla
# jar ("Zip file 1.7.10-forge-srg-runtime.jar failed to read properly, it will be ignored").
# Harmless (vanilla declares no @Mod) but it hides real problems, so drop those entries into a
# clean copy instead of editing another lane's input file.
$runtimeJarClean = Join-Path $build '1.7.10-forge-srg-runtime-clean.jar'
Add-Type -AssemblyName System.IO.Compression.FileSystem
if (Test-Path $runtimeJarClean) { Remove-Item -Force $runtimeJarClean }
$srcZip = [System.IO.Compression.ZipFile]::OpenRead($runtimeJar)
$dstZip = [System.IO.Compression.ZipFile]::Open($runtimeJarClean, 'Create')
$dropped = @()
foreach ($e in $srcZip.Entries) {
  if ($e.FullName -match ',') { $dropped += $e.FullName; continue }
  $ne = $dstZip.CreateEntry($e.FullName)
  $rs = $e.Open(); $ws = $ne.Open(); $rs.CopyTo($ws); $ws.Close(); $rs.Close()
}
$dstZip.Dispose(); $srcZip.Dispose()
Write-Output ("runtime jar : sanitised, dropped " + $dropped.Count + " bogus entries: " + ($dropped -join ', '))
# ---------------------------------------------------------------- 1. api (release 8)
$apiClasses = Join-Path $build 'classes-api'
$apiJar     = Join-Path $build 'umb-legacy-api.jar'
Compile 'api' @((Join-Path $mod 'src\api\java')) '' '8' $apiClasses
MakeJar $apiClasses $apiJar '' @()

# ---------------------------------------------------------------- 1.5 bridge-api (release 8)
# dev.umb.bridge.api - the G2 boundary contract (g2-design/DESIGN.md "THE BOUNDARY CONTRACT").
# java.* types only, loaded by the universe-root loader and parent-delegated to BOTH sides so the
# 26.2 host and the legacy universe resolve it to the SAME Class objects. Lane A owns this source;
# Lane B compiles against a verbatim copy of the contract text and must not diverge from it.
$bridgeApiClasses = Join-Path $build 'classes-bridge-api'
$bridgeApiJar     = Join-Path $build 'umb-bridge-api.jar'
Compile 'bridge-api' @((Join-Path $mod 'src\bridge-api\java')) '' '8' $bridgeApiClasses
MakeJar $bridgeApiClasses $bridgeApiJar '' @()

# ---------------------------------------------------------------- 2. boot (release 21)
$bootClasses = Join-Path $build 'classes-boot'
$bootJar     = Join-Path $build 'umb-legacy-boot.jar'
Compile 'boot' @((Join-Path $mod 'src\boot\java')) (($apiJar, $lwJar, $asm, $asmTree, $asmCommons) -join ';') '21' $bootClasses
MakeJar $bootClasses $bootJar 'dev.umb.legacy.boot.Bootstrap' @()

# ---------------------------------------------------------------- 2.5 F0: SrgFieldRepair
# build/legacy/1.7.10-forge-srg-runtime-clean.jar is internally inconsistent: hundreds of classes
# declare fields under raw obf names while the rest of the jar references them as field_* (see
# g2-design/DESIGN.md "F0"). This is an offline, one-shot ASM field rename + one injected method
# (Slot.getSlotIndex()) that must land before anything constructing a Container/Slot can work.
$srgFile   = Join-Path $repo 'research\mappings\joined-1.7.10.srg'
$fieldsJar = Join-Path $build '1.7.10-forge-srg-runtime-fields.jar'
$fieldRepairSummary = Join-Path $outDir 'srg-field-repair.txt'
if (-not (Test-Path $srgFile)) { Write-Error ("missing: " + $srgFile); exit 1 }
& $java25 '-cp' (($bootJar, $asm) -join ';') 'dev.umb.legacy.boot.SrgFieldRepair' `
  $srgFile $runtimeJarClean $fieldsJar $fieldRepairSummary
if ($LASTEXITCODE -ne 0) { Write-Error 'SrgFieldRepair failed'; exit 1 }
Write-Output ("field repair: " + (Get-Content $fieldRepairSummary -Raw).Trim())

# ---------------------------------------------------------------- 3. legacyside stage 1: the srgifier
# Compiled against the PRODUCTION forge jar plus the NOTCH jar, so that javac can resolve the notch
# vanilla types that appear in Forge's own signatures.
if (-not $skipRemap) {
  $stage1Src   = Join-Path $build 'src-stage1'
  if (Test-Path $stage1Src) { Remove-Item -Recurse -Force $stage1Src }
  New-Item -ItemType Directory -Force (Join-Path $stage1Src 'dev\umb\legacy\legacyside') | Out-Null
  foreach ($f in @('ForgeSrgifier.java', 'Statics.java')) {
    Copy-Item (Join-Path $mod ('src\legacyside\java\dev\umb\legacy\legacyside\' + $f)) (Join-Path $stage1Src 'dev\umb\legacy\legacyside')
  }
  $s1Classes = Join-Path $build 'classes-stage1'
  $s1Jar     = Join-Path $build 'umb-legacy-legacyside-stage1.jar'
  $s1Cp      = ((@($apiJar, $lwJar, $forgeJar, $notchJar) + $libs) -join ';')
  Compile 'legacyside-stage1' @($stage1Src) $s1Cp '8' $s1Classes
  MakeJar $s1Classes $s1Jar '' @()

  # ------------------------------------------------------------- 4. SRG-ify the Forge universal jar
  Write-Output 'remapping   : forge universal (notch refs) -> SRG, via FMLDeobfuscatingRemapper'
  $remapArgs = @(
    '-Xmx512m', '-Djava.awt.headless=true', '--sun-misc-unsafe-memory-access=allow',
    '-XX:-OmitStackTraceInFastThrow',
    ('-Dumb.repo=' + $repo),
    ('-Dumb.legacy.legacysideJar=' + $s1Jar),
    ('-Dumb.legacy.forgeSrgJar=' + $forgeSrg),
    ('-Dlog4j.configurationFile=' + (Join-Path $mod 'resources\log4j2-legacy.xml')),
    '-cp', (($bootJar, $apiJar, $lwJar, $joptJar, $log4jApi, $log4jCore) -join ';'),
    'dev.umb.legacy.boot.RemapTool'
  )
  & $java25 @remapArgs
  if ($LASTEXITCODE -ne 0) { Write-Error 'RemapTool failed'; exit 1 }
  if (-not (Test-Path $forgeSrg)) { Write-Error 'RemapTool produced no jar'; exit 1 }
  Write-Output ("srg forge   : " + [int]((Get-Item $forgeSrg).Length/1024) + " KB")
}
if (-not (Test-Path $forgeSrg)) { Write-Error ("missing " + $forgeSrg + " - run without -SkipRemap"); exit 1 }

# ---------------------------------------------------------------- 5. legacyside (release 8)
# Compiled against the FIELD-REPAIRED vanilla jar (F0), not the raw -clean one: from here on any
# legacyside code that references a vanilla field (Slot, InventoryPlayer, WorldProvider, ...) must
# see the SRG name, exactly like HBM does.
$lsClasses = Join-Path $build 'classes-legacyside'
$lsJar     = Join-Path $build 'umb-legacy-legacyside.jar'
$lsCp      = ((@($apiJar, $bridgeApiJar, $lwJar, $fieldsJar, $forgeSrg) + $libs) -join ';')
Compile 'legacyside' @((Join-Path $mod 'src\legacyside\java')) $lsCp '8' $lsClasses
MakeJar $lsClasses $lsJar '' @((Join-Path $mod 'src\legacyside\resources'))

# ---------------------------------------------------------------- 6. tests
if (-not $skipTests) {
  $tClasses = Join-Path $build 'classes-test'
  # Facade tests (UmbWorld/UmbPlayer/...) reference the vanilla+Forge SRG jars and legacyside.jar
  # DIRECTLY - no LegacyLoader/FML boot needed to unit-test a facade class, since a release-21
  # classfile can freely call into release-8 ones on the same classpath.
  $tCp = ((@($apiJar, $bridgeApiJar, $bootJar, $lwJar, $fieldsJar, $forgeSrg, $lsJar, $junit, $asm, $asmTree, $asmCommons) + $libs) -join ';')
  Compile 'test' @((Join-Path $mod 'src\test\java')) $tCp '21' $tClasses
  Write-Output 'running     : junit'
  $tRunCp = (@($tClasses, $apiJar, $bridgeApiJar, $bootJar, $lwJar, $fieldsJar, $forgeSrg, $lsJar,
               $joptJar, $log4jApi, $log4jCore, $asm) + $libs) -join ';'
  & $java25 '-Djava.awt.headless=true' ('-Dumb.repo=' + $repo) `
    '--sun-misc-unsafe-memory-access=allow' `
    '--add-opens', 'java.base/java.lang=ALL-UNNAMED' `
    '--add-opens', 'java.base/java.lang.reflect=ALL-UNNAMED' `
    '-jar' $junit 'execute' `
    '--class-path' $tRunCp `
    '--select-package' 'dev.umb.legacy.test' '--details=summary' '--disable-banner'
  if ($LASTEXITCODE -ne 0) { Write-Error 'junit failed'; exit 1 }
}

Write-Output ''
Write-Output 'artifacts:'
Get-ChildItem $build -Filter *.jar | ForEach-Object { Write-Output ("  " + $_.Name + "  " + [int]($_.Length/1024) + " KB") }
