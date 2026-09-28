# Builds fixtures/umb-snapshot-1710/build/umb-snapshot-1710-0.1.0.jar
#
# B2 packaging discipline (same idiom as fixtures/fixture-1710-srg/build.ps1):
# compile with plain javac from the portable JDK 21 (NEVER the broken system
# JAVA_HOME) targeting --release 8, against the era classpath; then stage ONLY
# the mod's own package subtree (net/umb/snapshot) plus mcmod.info. Nothing from
# the compile classpath is ever unpacked, so no Mojang/Forge byte can leak into
# the jar. The final step LISTS the jar contents so a leak is visible in the log.
#
# Compile classpath notes:
#   research/out/1.7.10-client-srg.jar  vanilla 1.7.10 client remapped to SRG.
#       Its METHOD names are SRG (func_*) and match the production runtime, so
#       vanilla methods are called directly. Its FIELD names are still obfuscated
#       and it predates Forge's binpatches, so vanilla fields and Forge-added
#       members are reached reflectively at runtime (see Refl.java).
#   forge-...-universal.jar  FML/Forge API. Its own signatures still name vanilla
#       types by their OBF names (aji/adb/add/rf) because FML remaps them at load
#       time; javac tolerates that as long as we never touch such a signature.
#       The two we do need (Fluid.getIcon, Fluid.getBlock) go through Refl.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
. ..\..\tools\umb-env.ps1

$root = (Resolve-Path ..\..).Path
$javac = Join-Path $env:JAVA_HOME 'bin\javac.exe'
$jar = Join-Path $env:JAVA_HOME 'bin\jar.exe'

$libs = Join-Path $root 'research\visual\mc1710-native\libraries'
$cpParts = @(
    (Join-Path $root 'research\out\1.7.10-client-srg.jar'),
    (Join-Path $root 'research\visual\mc1710-native\scratch-installer\extracted\forge-1.7.10-10.13.4.1614-1.7.10-universal.jar'),
    (Join-Path $libs 'com\google\guava\guava\17.0\guava-17.0.jar'),
    (Join-Path $libs 'org\apache\logging\log4j\log4j-api\2.0-beta9\log4j-api-2.0-beta9.jar'),
    (Join-Path $libs 'org\apache\logging\log4j\log4j-core\2.0-beta9\log4j-core-2.0-beta9.jar'),
    (Join-Path $libs 'com\google\code\gson\gson\2.2.4\gson-2.2.4.jar'),
    (Join-Path $libs 'org\apache\commons\commons-lang3\3.3.2\commons-lang3-3.3.2.jar'),
    # LWJGL 2.9.1: IconDump reads the stitched atlas back with GL11.glGetTexImage.
    (Join-Path $libs 'org\lwjgl\lwjgl\lwjgl\2.9.1\lwjgl-2.9.1.jar')
)
foreach ($p in $cpParts) {
    if (-not (Test-Path $p)) { Write-Error "missing classpath entry: $p"; exit 1 }
}
$cp = $cpParts -join ';'

New-Item -ItemType Directory -Force -Path build\classes, build\pkg | Out-Null
Remove-Item build\classes\* -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item build\pkg\* -Recurse -Force -ErrorAction SilentlyContinue

Write-Output '[build] compiling snapshot mod (javac --release 8)...'
& $javac -encoding UTF-8 --release 8 -nowarn -Xlint:-options `
    -d build\classes -cp $cp `
    (Get-ChildItem src -Recurse -Filter *.java | ForEach-Object FullName)
if ($LASTEXITCODE -ne 0) { Write-Error 'compile failed'; exit 1 }

Write-Output '[build] staging shipped files (mod classes + metadata only)...'
New-Item -ItemType Directory -Force -Path build\pkg\net | Out-Null
Copy-Item build\classes\net\umb build\pkg\net\umb -Recurse
Copy-Item src\mcmod.info build\pkg\mcmod.info

Write-Output '[build] packaging jar...'
Remove-Item build\umb-snapshot-1710-0.1.0.jar -Force -ErrorAction SilentlyContinue
& $jar --create --file build\umb-snapshot-1710-0.1.0.jar -C build\pkg .
if ($LASTEXITCODE -ne 0) { Write-Error 'jar packaging failed'; exit 1 }

Write-Output '[build] verifying package contents:'
& $jar --list --file build\umb-snapshot-1710-0.1.0.jar
