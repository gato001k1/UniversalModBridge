# Builds fixtures/fixture-hello-forge/build/fixture-hello-forge-0.1.0.jar
#
# Compile classpath: forge stubs + host slf4j-api. The @Mod stub and slf4j are
# compile-time-only: build/pkg must contain ONLY the mod's own classes + mods.toml
# (stub classes leaked into a jar once before — see BLOCKERS.md B2 — they would
# shadow real loader interfaces at runtime).
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
. ..\..\tools\umb-env.ps1

$javac = Join-Path $env:JAVA_HOME 'bin\javac.exe'
$jar = Join-Path $env:JAVA_HOME 'bin\jar.exe'

$slf4j = Get-ChildItem '..\..\research\jars\26.2\libraries' -Recurse -Filter 'slf4j-api-*.jar' |
    Select-Object -First 1 -ExpandProperty FullName
if (-not $slf4j) { Write-Error 'slf4j-api jar not found under research/jars/26.2/libraries (run tools/fetch-host.ps1 first)'; exit 1 }

New-Item -ItemType Directory -Force -Path build\classes, build\pkg | Out-Null

Write-Output '[build] compiling fixture + stubs...'
& $javac -encoding UTF-8 --release 21 `
    -cp "$slf4j" `
    -d build\classes `
    (Get-ChildItem src -Recurse -Filter *.java | ForEach-Object FullName) `
    (Get-ChildItem ..\forge-stubs -Recurse -Filter *.java | ForEach-Object FullName)
if ($LASTEXITCODE -ne 0) { Write-Error 'compile failed'; exit 1 }

Write-Output '[build] staging shipped files (mod classes + metadata only)...'
Remove-Item build\pkg\* -Recurse -Force -ErrorAction SilentlyContinue
# Copy ONLY the mod's own package subtree — build\classes also holds net\minecraftforge
# stub classes which must never ship (B2 lesson; verified by the listing below).
Copy-Item build\classes\net\umb build\pkg\net\umb -Recurse
Copy-Item src\META-INF build\pkg\META-INF -Recurse

Write-Output '[build] packaging jar...'
& $jar --create --file build\fixture-hello-forge-0.1.0.jar -C build\pkg .
if ($LASTEXITCODE -ne 0) { Write-Error 'jar packaging failed'; exit 1 }

Write-Output '[build] verifying package contents:'
& $jar --list --file build\fixture-hello-forge-0.1.0.jar
