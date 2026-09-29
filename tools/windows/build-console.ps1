# Build umb-console: the local web UI over harness\legacy.ps1.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\windows\build-console.ps1
#
# Portable JDK 21 (tools\jdk-21.0.12.1+1) - NEVER the system JAVA_HOME, which on this box is a
# broken JRE 8 path with a stray LRM in it. Plain javac + jar; no gradle, no framework.
# The only dependency is gson (tools\junit\gson.jar); everything else is com.sun.net.httpserver
# out of the JDK. web\index.html is packaged into the jar as a resource, and ConsoleServer also
# falls back to reading it out of the source tree so the page can be edited without a rebuild.
$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$jdk = Join-Path $repo 'tools\jdk-21.0.12.1+1\bin'
$javac = Join-Path $jdk 'javac.exe'
$jar = Join-Path $jdk 'jar.exe'
$gson = Join-Path $repo 'tools\junit\gson.jar'
$src = Join-Path $repo 'umb-console\src\main\java'
$res = Join-Path $repo 'umb-console\src\main\resources'
$build = Join-Path $repo 'build\console'
$classes = Join-Path $build 'classes'
$outJar = Join-Path $build 'umb-console.jar'

foreach ($p in @($javac, $jar, $gson, $src, $res)) {
  if (-not (Test-Path $p)) { Write-Error ("missing: " + $p); exit 1 }
}

if (Test-Path $classes) { Remove-Item -Recurse -Force $classes }
New-Item -ItemType Directory -Force $classes | Out-Null

$sources = @(Get-ChildItem -Recurse -Filter *.java $src | ForEach-Object { $_.FullName })
Write-Output ('sources: ' + $sources.Count)
$argfile = Join-Path $build 'javac-main.args'
($sources | ForEach-Object { '"' + ($_ -replace '\\', '\\\\') + '"' }) -join "`n" | Out-File $argfile -Encoding ascii
& $javac -Xlint:all -Xlint:-classfile -encoding UTF-8 --release 21 -cp $gson -d $classes ('@' + $argfile)
if ($LASTEXITCODE -ne 0) { Write-Error 'javac failed'; exit 1 }
Write-Output 'compiled'

# stage the single-page app next to the classes so it ships inside the jar
Copy-Item -Recurse -Force (Join-Path $res '*') $classes

$manifest = Join-Path $build 'MANIFEST.MF'
@(
  'Manifest-Version: 1.0',
  'Main-Class: dev.umb.console.ConsoleServer',
  'Implementation-Title: umb-console',
  'Implementation-Vendor: UniversalModBridge',
  ''
) -join "`n" | Out-File $manifest -Encoding ascii

if (Test-Path $outJar) { Remove-Item -Force $outJar }
& $jar --create --file $outJar --manifest $manifest -C $classes .
if ($LASTEXITCODE -ne 0) { Write-Error 'jar failed'; exit 1 }

Write-Output ''
Write-Output ('JAR: ' + $outJar + '  bytes=' + (Get-Item $outJar).Length)
$entries = @(& $jar --list --file $outJar)
Write-Output ('entries: ' + $entries.Count)
$entries | Where-Object { $_ -like '*.class' -or $_ -like '*index.html' } | Sort-Object | ForEach-Object { Write-Output ('  ' + $_) }
