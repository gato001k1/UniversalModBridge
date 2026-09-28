#requires -Version 5.1
<#
  Compile and run the umb-guimap unit tests (JUnit 5 console standalone, portable JDK 21).
#>
$ErrorActionPreference = 'Stop'

$root    = Split-Path -Parent $PSScriptRoot
$javac   = Join-Path $root 'tools\jdk-21.0.12.1+1\bin\javac.exe'
$java    = Join-Path $root 'tools\jdk-21.0.12.1+1\bin\java.exe'
$libDir  = Join-Path $root 'tools\junit'
$mainOut = Join-Path $root 'build\guimap\classes'
$testSrc = Join-Path $root 'umb-guimap\src\test\java'
$testOut = Join-Path $root 'build\guimap\test-classes'

if (-not (Test-Path $mainOut)) { & (Join-Path $PSScriptRoot 'build-guimap.ps1') }

$deps = @(
  (Join-Path $libDir 'asm-9.9.jar'),
  (Join-Path $libDir 'asm-tree-9.9.jar'),
  (Join-Path $libDir 'asm-commons-9.9.jar'),
  (Join-Path $libDir 'gson.jar'),
  (Join-Path $libDir 'junit-platform-console-standalone.jar')
)
$cp = (@($mainOut) + $deps) -join ';'

if (Test-Path $testOut) { Remove-Item -Recurse -Force $testOut }
New-Item -ItemType Directory -Force -Path $testOut | Out-Null

$sources = Get-ChildItem -Recurse -Filter *.java -Path $testSrc | ForEach-Object { $_.FullName }
$argsFile = Join-Path $env:TEMP ("guimap-test-javac-{0}.args" -f ([guid]::NewGuid().ToString('N')))
[System.IO.File]::WriteAllLines($argsFile, [string[]]$sources, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "[run-guimap-tests] compiling $($sources.Count) test files"
& $javac -nowarn -encoding UTF-8 -source 21 -target 21 -cp $cp -d $testOut ("@" + $argsFile)
$rc = $LASTEXITCODE
Remove-Item -Force $argsFile
if ($rc -ne 0) { throw "test javac failed with exit code $rc" }

$runCp = (@($mainOut, $testOut) + $deps) -join ';'
& $java -jar (Join-Path $libDir 'junit-platform-console-standalone.jar') `
    --class-path $runCp --select-package dev.umb.guimap --details=tree --disable-ansi-colors
if ($LASTEXITCODE -ne 0) { throw "JUnit reported failures (exit $LASTEXITCODE)" }
