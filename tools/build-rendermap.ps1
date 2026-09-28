#requires -Version 5.1
# Build the umb-rendermap module with the portable JDK 21 (never the system JAVA_HOME).
$ErrorActionPreference = 'Stop'

$root    = Split-Path -Parent $PSScriptRoot
$javac   = Join-Path $root 'tools\jdk-21.0.12.1+1\bin\javac.exe'
$libDir  = Join-Path $root 'tools\junit'
$srcDir  = Join-Path $root 'umb-rendermap\src\main\java'
$outDir  = Join-Path $root 'build\rendermap\classes'

if (-not (Test-Path $javac)) { throw "portable javac not found at $javac" }

$cp = @(
  (Join-Path $libDir 'asm-9.9.jar'),
  (Join-Path $libDir 'asm-tree-9.9.jar'),
  (Join-Path $libDir 'asm-commons-9.9.jar'),
  (Join-Path $libDir 'gson.jar')
) -join ';'

if (Test-Path $outDir) { Remove-Item -Recurse -Force $outDir }
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$sources = Get-ChildItem -Recurse -Filter *.java -Path $srcDir | ForEach-Object { $_.FullName }
$argsFile = Join-Path $env:TEMP ("rendermap-javac-{0}.args" -f ([guid]::NewGuid().ToString('N')))
# UTF-8 *without* BOM: javac treats a leading BOM as part of the first filename.
[System.IO.File]::WriteAllLines($argsFile, [string[]]$sources, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "[build-rendermap] compiling $($sources.Count) files -> $outDir"
& $javac -nowarn -encoding UTF-8 -source 21 -target 21 -cp $cp -d $outDir ("@" + $argsFile)
$rc = $LASTEXITCODE
Remove-Item -Force $argsFile
if ($rc -ne 0) { throw "javac failed with exit code $rc" }
Write-Host "[build-rendermap] OK"
