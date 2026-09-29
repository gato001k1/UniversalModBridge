# JUnit gate for umb-console (portable JDK 21, JUnit console standalone).
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\windows\run-console-tests.ps1
#
# Never uses the system JAVA_HOME. Builds the main classes first if they are missing.
$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$jdk = Join-Path $repo 'tools\jdk-21.0.12.1+1\bin'
$javac = Join-Path $jdk 'javac.exe'
$java = Join-Path $jdk 'java.exe'
$junit = Join-Path $repo 'tools\junit\junit-platform-console-standalone.jar'
$gson = Join-Path $repo 'tools\junit\gson.jar'
$mainClasses = Join-Path $repo 'build\console\classes'
$testSrc = Join-Path $repo 'umb-console\src\test\java'
$testClasses = Join-Path $repo 'build\console\test-classes'

foreach ($p in @($javac, $java, $junit, $gson, $testSrc)) {
  if (-not (Test-Path $p)) { Write-Error ("missing: " + $p); exit 1 }
}
if (-not (Test-Path $mainClasses)) {
  Write-Output 'build\console\classes missing - building'
  & (Join-Path $PSScriptRoot 'build-console.ps1')
  if ($LASTEXITCODE -ne 0) { Write-Error 'build-console.ps1 failed'; exit 1 }
}

if (Test-Path $testClasses) { Remove-Item -Recurse -Force $testClasses }
New-Item -ItemType Directory -Force $testClasses | Out-Null

$cp = ($mainClasses + ';' + $gson + ';' + $junit)
$sources = @(Get-ChildItem -Recurse -Filter *.java $testSrc | ForEach-Object { $_.FullName })
Write-Output ('test sources: ' + $sources.Count)
$argfile = Join-Path $repo 'build\console\javac-test.args'
($sources | ForEach-Object { '"' + ($_ -replace '\\', '\\\\') + '"' }) -join "`n" | Out-File $argfile -Encoding ascii
& $javac -nowarn -encoding UTF-8 -cp $cp -d $testClasses ('@' + $argfile)
if ($LASTEXITCODE -ne 0) { Write-Error 'test javac failed'; exit 1 }

Push-Location $repo
& $java -jar $junit execute --class-path ($testClasses + ';' + $cp) --scan-class-path $testClasses `
  --details=summary --disable-ansi-colors
$rc = $LASTEXITCODE
Pop-Location
exit $rc
