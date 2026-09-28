# Builds fixtures/fixture-1710-srg/build/fixture-1710-srg-0.1.0.jar
#
# B2 packaging discipline (copied from fixture-hello-forge/build.ps1): the
# fixture and its CC0 stubs compile TOGETHER with plain javac, but build/pkg
# stages ONLY the mod's own package subtree (net/umb) plus legacy metadata
# (mcmod.info). Stub trees (cpw/, net/minecraft/) must never ship — a stub
# leak into a jar failed twice before (see BLOCKERS.md B2) and would shadow
# real loader/host classes at runtime. The final step LISTS the jar contents
# so a leak is visible in the build log.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
. ..\..\tools\umb-env.ps1

$javac = Join-Path $env:JAVA_HOME 'bin\javac.exe'
$jar = Join-Path $env:JAVA_HOME 'bin\jar.exe'

New-Item -ItemType Directory -Force -Path build\classes, build\pkg | Out-Null

Write-Output '[build] compiling fixture + era stubs...'
& $javac -encoding UTF-8 --release 21 `
    -d build\classes `
    (Get-ChildItem src -Recurse -Filter *.java | ForEach-Object FullName) `
    (Get-ChildItem stubs -Recurse -Filter *.java | ForEach-Object FullName)
if ($LASTEXITCODE -ne 0) { Write-Error 'compile failed'; exit 1 }

Write-Output '[build] staging shipped files (mod classes + metadata only)...'
Remove-Item build\pkg\* -Recurse -Force -ErrorAction SilentlyContinue
# Copy ONLY the mod's own package subtree — build\classes also holds cpw/ and
# net/minecraft stub classes which must never ship (B2 lesson; verified below).
Copy-Item build\classes\net\umb build\pkg\net\umb -Recurse
Copy-Item src\mcmod.info build\pkg\mcmod.info

Write-Output '[build] packaging jar...'
& $jar --create --file build\fixture-1710-srg-0.1.0.jar -C build\pkg .
if ($LASTEXITCODE -ne 0) { Write-Error 'jar packaging failed'; exit 1 }

Write-Output '[build] verifying package contents:'
& $jar --list --file build\fixture-1710-srg-0.1.0.jar
