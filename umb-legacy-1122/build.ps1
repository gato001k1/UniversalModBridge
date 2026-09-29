# Build umb-legacy-1122: the 1.12.2-era skeleton (loader/classpath plumbing + the SAME
# dev.umb.bridge.api contract the 1.7.10 module speaks). See README.md and
# research/out/legacy-1122/ERA-1122-PLAN.md for what this module does and does NOT do yet.
#
# Four compilation units, same discipline as umb-legacy/tools/windows/build-legacy.ps1 and for the same
# reason: anything that will eventually sit on the FML-scanned classpath must stay classfile major
# <= 52 (ASM 5.2, the ASM Forge 1.12.2 itself bundles, refuses newer - verified: the fetched
# forge-1.12.2-14.23.5.2860-universal.jar ships org/ow2/asm 5.2 per its own MANIFEST.MF Class-Path).
#
#   bridge-api  --release 8   dev.umb.bridge.api - byte-identical mirror of umb-legacy's copy
#   api         --release 8   dev.umb.legacy1122.api - plain-data types
#   legacyside  --release 8   dev.umb.legacy1122.legacyside - compiled against bridge-api only
#                             (no 1.12.2 jars yet - there are no facades to compile against)
#   boot        --release 21  dev.umb.legacy1122.boot - application-classloader side; touches
#                             net.minecraft.launchwrapper.LaunchClassLoader and dev.umb.legacy1122.api
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File umb-legacy-1122\build.ps1
$ErrorActionPreference = 'Stop'

$mod    = $PSScriptRoot
$repo   = Split-Path -Parent $mod
$jdk21  = Join-Path $repo 'tools\jdk-21.0.12.1+1\bin'
$javac  = Join-Path $jdk21 'javac.exe'
$jar    = Join-Path $jdk21 'jar.exe'
$build  = Join-Path $mod 'build'

$lwJar = Join-Path $repo 'research\out\legacy-1122\libs\launchwrapper-1.12.jar'
$asmJar = Join-Path $repo 'research\out\legacy-1122\libs\asm-debug-all-5.2.jar'
$guavaJar = Join-Path $repo 'research\jars\1.12.2\libraries\com\google\guava\guava\21.0\guava-21.0.jar'
foreach ($p in @($javac, $jar, $lwJar, $asmJar, $guavaJar)) {
  if (-not (Test-Path $p)) { Write-Error ("missing: " + $p + " - see README.md 'fetching the real jars'"); exit 1 }
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

$pack200Src = Join-Path $mod 'src\pack200\java'
$pack200Classes = Join-Path $build 'classes-pack200'
if (Test-Path $pack200Classes) { Remove-Item -Recurse -Force $pack200Classes }
New-Item -ItemType Directory -Force $pack200Classes | Out-Null
$pack200Sources = Get-ChildItem -Recurse -Filter *.java $pack200Src | ForEach-Object { $_.FullName }
# The bridge-api here is a build-time copy of the canonical umb-legacy source (byte-identical,
# checked by BridgeApiMirrorTest). Change the contract there, never in this module.
$canonicalApi = Join-Path (Split-Path -Parent $mod) 'umb-legacy\src\bridge-api\java\dev\umb\bridge\api'
$mirrorApi = Join-Path $mod 'src\bridge-api\java\dev\umb\bridge\api'
if (-not (Test-Path $canonicalApi)) { Write-Error ("missing canonical bridge-api: " + $canonicalApi); exit 1 }
New-Item -ItemType Directory -Force $mirrorApi | Out-Null
Get-ChildItem -Path $canonicalApi -Filter *.java | ForEach-Object {
  Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $mirrorApi $_.Name) -Force
}
& $javac -nowarn -encoding UTF-8 --patch-module ("java.base=" + $pack200Src) -d $pack200Classes $pack200Sources
if ($LASTEXITCODE -ne 0) { Write-Error 'javac failed for pack200 shim'; exit 1 }
MakeJar $pack200Classes (Join-Path $build 'umb-legacy1122-pack200.jar')

Compile 'bridge-api' (Join-Path $mod 'src\bridge-api\java') $null '8' (Join-Path $build 'classes-bridge-api')
MakeJar (Join-Path $build 'classes-bridge-api') (Join-Path $build 'umb-legacy1122-bridge-api.jar')

Compile 'api' (Join-Path $mod 'src\api\java') $null '8' (Join-Path $build 'classes-api')
MakeJar (Join-Path $build 'classes-api') (Join-Path $build 'umb-legacy1122-api.jar')

$bridgeApiJar = Join-Path $build 'umb-legacy1122-bridge-api.jar'
$legacysideCp = ($bridgeApiJar, $lwJar, $asmJar, $guavaJar) -join ';'
Compile 'legacyside' (Join-Path $mod 'src\legacyside\java') $legacysideCp '8' (Join-Path $build 'classes-legacyside')
MakeJar (Join-Path $build 'classes-legacyside') (Join-Path $build 'umb-legacy1122-legacyside.jar')

$apiJar = Join-Path $build 'umb-legacy1122-api.jar'
$bootCp = ($apiJar, $lwJar) -join ';'
# Keep the application-side probe Java-8 runnable: Forge 1.12.2's own reflective
# ObjectHolder and Pack200 paths target Java 8. The source is still compiled by the
# pinned JDK 21 toolchain, while the classfile remains compatible with the native JRE.
Compile 'boot' (Join-Path $mod 'src\boot\java') $bootCp '8' (Join-Path $build 'classes-boot')
MakeJar (Join-Path $build 'classes-boot') (Join-Path $build 'umb-legacy1122-boot.jar')

Write-Output '---- build.ps1 done ----'
