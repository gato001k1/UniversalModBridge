# Build umb-objbridge: the SECOND -javaagent, which makes HBM's 1.7.10 OBJ meshes render as real
# geometry in Minecraft 26.2 (items via a umb:obj client-item model type, blocks via a splice into
# the live block-state model map), plus dev.umb.objbridge.gen.ObjPackGen.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\windows\build-objbridge.ps1
#
# Compiles on JDK 25 ONLY (research\jars\26.2\client.jar is classfile major 69).
# gson is compiled against the EXACT jar on the game classpath.
# ASM (asm, asm-tree, asm-commons - BSD 3-clause) is bundled unrelocated, same as umb-hostagent:
# the game classpath ships no ASM at all, so there is nothing to collide with.
#
# This script never touches umb-hostagent/ or build\hostagent\.
$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$jdk25 = Join-Path $repo 'tools\jdk-25.0.4.1+1\bin'
$javac = Join-Path $jdk25 'javac.exe'
$jar = Join-Path $jdk25 'jar.exe'
$client = Join-Path $repo 'research\jars\26.2\client.jar'
$cpFile = Join-Path $repo 'research\visual\mc262-vanilla\classpath.txt'
$src = Join-Path $repo 'umb-objbridge\src\main\java'
$build = Join-Path $repo 'build\objbridge'
$classes = Join-Path $build 'classes'
$stage = Join-Path $build 'stage'
$outJar = Join-Path $build 'umb-objbridge.jar'

foreach ($p in @($javac, $jar, $client, $cpFile, $src)) {
  if (-not (Test-Path $p)) { Write-Error ("missing: " + $p); exit 1 }
}

$gameCp = (Get-Content $cpFile -Raw).Trim()
$gson = ($gameCp -split ';' | Where-Object { $_ -match 'gson' } | Select-Object -First 1)
if (-not $gson) { Write-Error 'no gson on the game classpath'; exit 1 }
Write-Output ("gson  : " + $gson)
$joml = ($gameCp -split ';' | Where-Object { $_ -match 'joml' } | Select-Object -First 1)
Write-Output ("joml  : " + $joml)

$asm = @(
  (Join-Path $repo 'tools\junit\asm-9.9.jar'),
  (Join-Path $repo 'tools\junit\asm-tree-9.9.jar'),
  (Join-Path $repo 'tools\junit\asm-commons-9.9.jar')
)
foreach ($a in $asm) { if (-not (Test-Path $a)) { Write-Error ("missing asm jar: " + $a); exit 1 } }

if (Test-Path $classes) { Remove-Item -Recurse -Force $classes }
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
New-Item -ItemType Directory -Force $classes | Out-Null
New-Item -ItemType Directory -Force $stage | Out-Null

$compileCp = ((@($gameCp) + $asm) -join ';')
$sources = Get-ChildItem -Recurse -Filter *.java $src | ForEach-Object { $_.FullName }
Write-Output ("sources: " + $sources.Count)

$argfile = Join-Path $build 'javac-main.args'
($sources | ForEach-Object { '"' + ($_ -replace '\\', '\\\\') + '"' }) -join "`n" | Out-File $argfile -Encoding ascii
& $javac -nowarn -encoding UTF-8 -cp $compileCp -d $classes ('@' + $argfile)
if ($LASTEXITCODE -ne 0) { Write-Error 'javac failed'; exit 1 }
Write-Output 'compiled main'

Copy-Item -Recurse -Force (Join-Path $classes '*') $stage
foreach ($a in $asm) {
  Push-Location $stage
  & $jar --extract --file $a
  Pop-Location
}
Remove-Item -Recurse -Force (Join-Path $stage 'META-INF') -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force (Join-Path $stage 'module-info.class') -ErrorAction SilentlyContinue

$manifest = Join-Path $build 'MANIFEST.MF'
@(
  'Manifest-Version: 1.0',
  'Premain-Class: dev.umb.objbridge.ObjBridgeAgent',
  'Agent-Class: dev.umb.objbridge.ObjBridgeAgent',
  'Can-Retransform-Classes: true',
  'Implementation-Title: umb-objbridge',
  'Implementation-Vendor: UniversalModBridge',
  ''
) -join "`n" | Out-File $manifest -Encoding ascii

if (Test-Path $outJar) { Remove-Item -Force $outJar }
& $jar --create --file $outJar --manifest $manifest -C $stage .
if ($LASTEXITCODE -ne 0) { Write-Error 'jar failed'; exit 1 }

Write-Output ''
Write-Output ('JAR: ' + $outJar + '  bytes=' + (Get-Item $outJar).Length)
$entries = (& $jar --list --file $outJar)
Write-Output ('entries: ' + $entries.Count)
Write-Output '--- dev/umb entries ---'
$entries | Where-Object { $_ -like 'dev/umb/*' -and $_ -like '*.class' } | Sort-Object | ForEach-Object { Write-Output ('  ' + $_) }
Write-Output ('--- bundled asm classes: ' + ($entries | Where-Object { $_ -like 'org/objectweb/asm/*.class' }).Count + ' ---')
