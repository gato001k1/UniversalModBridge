# Build umb-legacy-1165: the 1.16.5-era skeleton (loader/classpath plumbing + the SAME
# dev.umb.bridge.api contract the 1.7.10 and 1.12.2 modules speak). See README.md and
# research/out/legacy-1165/ERA-1165-PLAN.md for what this module does and does NOT do yet.
#
# Four compilation units:
#   bridge-api  --release 8   dev.umb.bridge.api - byte-identical mirror of umb-legacy's copy
#   api         --release 8   dev.umb.legacy1165.api - plain-data types
#   legacyside  --release 8   dev.umb.legacy1165.legacyside - compiled against bridge-api
#                             PLUS the real era jars (Forge universal+launch, SRG game) for the
#                             lifecycle driver, facades and bridge (same discipline as umb-legacy,
#                             whose legacyside compiles against the 1.7.10 jars). Never the mod jar.
#   transform   --release 8   dev.umb.legacy1165.transform - the eventbus-transformation worker;
#                             lives INSIDE the child universe (own jar on the manifest) so its
#                             ASM/engine references resolve to one consistent copy. Compiled
#                             against the pinned ASM + eventbus jars (never invented).
#   boot        --release 21  dev.umb.legacy1165.boot - application-classloader side; a plain
#                             URLClassLoader policy (no LaunchWrapper in the ModLauncher
#                             generation) plus dev.umb.legacy1165.api. Calls the transform worker
#                             reflectively so boot links nothing outside the JDK + api.
#
# --release 8 for everything that could eventually sit on the mod-scanned classpath (ASM 9.1,
# the ASM Forge 1.16.5 itself bundles, reads newer classfiles than the 1.7.10/1.12.2-era ASM 5.x
# could - but major 52 stays the safe uniform ceiling for all three eras' legacyside jars).
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File umb-legacy-1165\build.ps1
$ErrorActionPreference = 'Stop'

$mod    = $PSScriptRoot
$repo   = Split-Path -Parent $mod
$jdk21  = Join-Path $repo 'tools\jdk-21.0.12.1+1\bin'
$javac  = Join-Path $jdk21 'javac.exe'
$jar    = Join-Path $jdk21 'jar.exe'
$build  = Join-Path $mod 'build'

foreach ($p in @($javac, $jar)) {
  if (-not (Test-Path $p)) { Write-Error ("missing: " + $p); exit 1 }
}

New-Item -ItemType Directory -Force $build | Out-Null

function Compile([string]$name, [string]$srcRoot, [string]$cp, [string]$release, [string]$dest) {
  if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }
  New-Item -ItemType Directory -Force $dest | Out-Null
  $sources = Get-ChildItem -Recurse -Filter *.java $srcRoot | ForEach-Object { $_.FullName }
  if ($sources.Count -eq 0) { Write-Error ("no sources under " + $srcRoot); exit 1 }
  $argfile = Join-Path $build ("javac-" + $name + ".args")
  ($sources | ForEach-Object { '"' + ($_ -replace '\\', '\\\\') + '"' }) -join "`n" | Out-File $argfile -Encoding ascii
  $cpArgs = @()
  if ($cp) { $cpArgs = @('-cp', $cp) }
  & $javac -nowarn -encoding UTF-8 --release $release @cpArgs -d $dest ('@' + $argfile)
  if ($LASTEXITCODE -ne 0) { Write-Error ("javac failed for " + $name); exit 1 }
  Write-Output ("compiled  : " + $name + " (" + $sources.Count + " sources, release " + $release + ")")
}

function MakeJar([string]$dest, [string]$jarPath) {
  & $jar --create --file $jarPath -C $dest .
  if ($LASTEXITCODE -ne 0) { Write-Error ("jar failed for " + $jarPath); exit 1 }
  Write-Output ("jar       : " + $jarPath)
}

Compile 'bridge-api' (Join-Path $mod 'src\bridge-api\java') $null '8' (Join-Path $build 'classes-bridge-api')
MakeJar (Join-Path $build 'classes-bridge-api') (Join-Path $build 'umb-legacy1165-bridge-api.jar')

Compile 'api' (Join-Path $mod 'src\api\java') $null '8' (Join-Path $build 'classes-api')
MakeJar (Join-Path $build 'classes-api') (Join-Path $build 'umb-legacy1165-api.jar')

$bridgeApiJar = Join-Path $build 'umb-legacy1165-bridge-api.jar'
# legacyside compiles against the REAL era jars (same discipline as umb-legacy's legacyside,
# which compiles against the 1.7.10 jars): bridge-api for the contract, Forge universal +
# launch for FML/registries, the SRG+binpatched+AT game jars for vanilla. The mod jar itself
# is deliberately NOT a compile dep: all mod access goes through live registries by name.
# --release 8 keeps every unit ASM-scanner-safe (same ceiling as the older eras).
$forgeUniversal = Join-Path $repo 'research\out\legacy-1165\forge-1.16.5-36.2.34-universal.jar'
$forgeLaunch    = Join-Path $repo 'research\out\legacy-1165\libs\forge-1.16.5-36.2.34-launch.jar'
$modlauncherJar = Join-Path $repo 'research\out\legacy-1165\libs\modlauncher-8.1.3.jar'
$eventbusJar    = Join-Path $repo 'research\out\legacy-1165\libs\eventbus-4.0.0.jar'
$srgServerJar   = Join-Path $repo 'research\out\legacy-1165\rename\output\mc-server-srg-at.jar'
$srgClientJar   = Join-Path $repo 'research\out\legacy-1165\rename\output\mc-client-srg-at.jar'
$forgespiJar    = Join-Path $repo 'research\out\legacy-1165\libs\forgespi-3.2.0.jar'
$mavenArtifactJar = Join-Path $repo 'research\out\legacy-1165\libs\maven-artifact-3.6.3.jar'
$asmJar         = Join-Path $repo 'research\out\legacy-1165\libs\asm-9.1.jar'
$asmTreeJar     = Join-Path $repo 'research\out\legacy-1165\rename\tools\asm-tree-9.1.jar'
foreach ($p in @($forgeUniversal, $forgeLaunch, $modlauncherJar, $eventbusJar, $srgServerJar, $srgClientJar, $forgespiJar, $mavenArtifactJar, $asmJar, $asmTreeJar)) {
  if (-not (Test-Path $p)) { Write-Error ("missing legacyside compile dep: " + $p); exit 1 }
}
# Vanilla support libs (authlib/guava/datafixerupper) ride the vanilla manifest at runtime;
# the compiler needs them too for supertypes/members the facades touch.
$vanillaLibs = Join-Path $repo 'research\jars\1.16.5\libraries'
$authlibJar  = Join-Path $vanillaLibs 'com\mojang\authlib\2.1.28\authlib-2.1.28.jar'
$guavaJar    = Join-Path $vanillaLibs 'com\google\guava\guava\21.0\guava-21.0.jar'
$dfuJar      = Join-Path $vanillaLibs 'com\mojang\datafixerupper\4.0.26\datafixerupper-4.0.26.jar'
$fastutilJar = Join-Path $vanillaLibs 'it\unimi\dsi\fastutil\8.2.1\fastutil-8.2.1.jar'
foreach ($p in @($authlibJar, $guavaJar, $dfuJar, $fastutilJar)) {
  if (-not (Test-Path $p)) { Write-Error ("missing legacyside compile dep: " + $p); exit 1 }
}
$legacysideCp = ($bridgeApiJar, $forgeUniversal, $forgeLaunch, $modlauncherJar, $eventbusJar, $srgServerJar, $srgClientJar, $forgespiJar, $mavenArtifactJar, $asmJar, $asmTreeJar, $authlibJar, $guavaJar, $dfuJar, $fastutilJar) -join ';'
Compile 'legacyside' (Join-Path $mod 'src\legacyside\java') $legacysideCp '8' (Join-Path $build 'classes-legacyside')
MakeJar (Join-Path $build 'classes-legacyside') (Join-Path $build 'umb-legacy1165-legacyside.jar')

$apiJar = Join-Path $build 'umb-legacy1165-api.jar'
Compile 'boot' (Join-Path $mod 'src\boot\java') $apiJar '21' (Join-Path $build 'classes-boot')
MakeJar (Join-Path $build 'classes-boot') (Join-Path $build 'umb-legacy1165-boot.jar')

# The in-universe transform worker (see Legacy1165Loader's field note for why it is separate).
$asmJar      = Join-Path $repo 'research\out\legacy-1165\libs\asm-9.1.jar'
$asmTreeJar  = Join-Path $repo 'research\out\legacy-1165\rename\tools\asm-tree-9.1.jar'
$eventbusJar = Join-Path $repo 'research\out\legacy-1165\libs\eventbus-4.0.0.jar'
$launchJar   = Join-Path $repo 'research\out\legacy-1165\libs\forge-1.16.5-36.2.34-launch.jar'
$modlauncherJar2 = Join-Path $repo 'research\out\legacy-1165\libs\modlauncher-8.1.3.jar'
foreach ($p in @($asmJar, $asmTreeJar, $eventbusJar, $launchJar, $modlauncherJar2)) {
  if (-not (Test-Path $p)) { Write-Error ("missing transform compile dep: " + $p); exit 1 }
}
$transformCp = ($asmJar, $asmTreeJar, $eventbusJar, $launchJar, $modlauncherJar2) -join ';'
Compile 'transform' (Join-Path $mod 'src\transform\java') $transformCp '8' (Join-Path $build 'classes-transform')
MakeJar (Join-Path $build 'classes-transform') (Join-Path $build 'umb-legacy1165-transform.jar')

# ironchest-visuals lane: build-time 1.16.5 screen-geometry extractor (ASM over the mod
# jar, no Forge/MC classes needed - only org.objectweb.asm). Own jar, additive: no
# existing unit is touched. Run via research/out/legacy-1165/build-gui1165.ps1.
$guigeomAsm = Join-Path $repo 'research\out\legacy-1165\libs\asm-9.1.jar'
$guigeomAsmTree = Join-Path $repo 'research\out\legacy-1165\libs\asm-tree-9.1.jar'
foreach ($p in @($guigeomAsm, $guigeomAsmTree)) {
  if (-not (Test-Path $p)) { Write-Error ("missing guigeom compile dep: " + $p); exit 1 }
}
Compile 'guigeom' (Join-Path $mod 'src\guigeom\java') ($guigeomAsm + ';' + $guigeomAsmTree) '8' (Join-Path $build 'classes-guigeom')
MakeJar (Join-Path $build 'classes-guigeom') (Join-Path $build 'umb-legacy1165-guigeom.jar')

Write-Output '---- build.ps1 done ----'
