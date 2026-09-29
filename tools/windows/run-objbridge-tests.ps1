# JUnit gate for umb-objbridge. JDK 25 only (client.jar is classfile major 69).
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\windows\run-objbridge-tests.ps1
#
# Run tools\windows\build-objbridge.ps1 first. Tests run with the repo root as cwd because several of them
# assert against the real asset tree (research\out\legacy\hbm-assets), the real client jar
# (research\jars\26.2\client.jar) and the generated overlay pack.
#
# build\hostagent\umb-hostagent.jar is put on the TEST classpath only, so one test can prove that our
# duplicated path sanitizer agrees byte for byte with dev.umb.hostagent.content.LegacyIds. It is read
# and never written; if it is absent that single test self-skips.
$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$jdk25 = Join-Path $repo 'tools\jdk-25.0.4.1+1\bin'
$javac = Join-Path $jdk25 'javac.exe'
$java = Join-Path $jdk25 'java.exe'
$junit = Join-Path $repo 'tools\junit\junit-platform-console-standalone.jar'
$cpFile = Join-Path $repo 'research\visual\mc262-vanilla\classpath.txt'
$mainClasses = Join-Path $repo 'build\objbridge\classes'
$testSrc = Join-Path $repo 'umb-objbridge\src\test\java'
$testClasses = Join-Path $repo 'build\objbridge\test-classes'

foreach ($p in @($javac, $java, $junit, $cpFile, $mainClasses)) {
  if (-not (Test-Path $p)) { Write-Error ("missing: " + $p + " (run tools\windows\build-objbridge.ps1 first)"); exit 1 }
}

$rawGameCp = (Get-Content $cpFile -Raw).Trim()
$gameCpParts = $rawGameCp -split ';'
if ($gameCpParts.Count -eq 1 -and $rawGameCp -match '/mnt/[A-Za-z]/') {
  $gameCpParts = $rawGameCp -split ':'
}
$gameCp = (($gameCpParts | ForEach-Object {
  $entry = $_
  if ($entry -match '^/mnt/([A-Za-z])/(.*)$') {
    $entry = ($matches[1].ToUpper() + ':\' + ($matches[2] -replace '/', '\'))
  }
  $entry
}) -join ';')
$asm = @(
  (Join-Path $repo 'tools\junit\asm-9.9.jar'),
  (Join-Path $repo 'tools\junit\asm-tree-9.9.jar'),
  (Join-Path $repo 'tools\junit\asm-commons-9.9.jar')
)
$hostAgent = Join-Path $repo 'build\hostagent\umb-hostagent.jar'
$extra = @()
if (Test-Path $hostAgent) { $extra += $hostAgent; Write-Output ('hostagent (read-only, for the LegacyIds cross-check): ' + $hostAgent) }
else { Write-Output 'hostagent jar absent - the LegacyIds cross-check test will self-skip' }

if (Test-Path $testClasses) { Remove-Item -Recurse -Force $testClasses }
New-Item -ItemType Directory -Force $testClasses | Out-Null

$cp = ((@($mainClasses, $junit) + $asm + $extra + @($gameCp)) -join ';')
$sources = Get-ChildItem -Recurse -Filter *.java $testSrc | ForEach-Object { $_.FullName }
Write-Output ("test sources: " + $sources.Count)

$argfile = Join-Path $repo 'build\objbridge\javac-test.args'
($sources | ForEach-Object { '"' + ($_ -replace '\\', '\\\\') + '"' }) -join "`n" | Out-File $argfile -Encoding ascii
& $javac -nowarn -encoding UTF-8 -cp $cp -d $testClasses ('@' + $argfile)
if ($LASTEXITCODE -ne 0) { Write-Error 'test javac failed'; exit 1 }

Push-Location $repo
& $java -jar $junit execute --class-path ($testClasses + ';' + $cp) --scan-class-path $testClasses --details=summary --disable-ansi-colors
$rc = $LASTEXITCODE
Pop-Location
exit $rc
