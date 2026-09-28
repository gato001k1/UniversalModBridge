# Runs the headless "how far can the isolated loader get against the REAL fetched 1.12.2 jars"
# probe (see Boot1122ProbeMain's javadoc for exactly what it proves and does not attempt).
#   powershell -NoProfile -ExecutionPolicy Bypass -File umb-legacy-1122\run-probe.ps1
$ErrorActionPreference = 'Stop'

$mod   = $PSScriptRoot
$repo  = Split-Path -Parent $mod
$jdk21 = Join-Path $repo 'tools\jdk-21.0.12.1+1\bin'
$java  = Join-Path $jdk21 'java.exe'
$javaArgs = @('--patch-module', '', '--add-opens', 'java.base/java.io=ALL-UNNAMED')
$nativeJava8 = 'C:\Program Files\Java\jre1.8.0_501\bin\java.exe'
if (Test-Path $nativeJava8) {
  $java = $nativeJava8
  $javaArgs = @()
}
$build = Join-Path $mod 'build'
$lwJar = Join-Path $repo 'research\out\legacy-1122\libs\launchwrapper-1.12.jar'

$bootJar = Join-Path $build 'umb-legacy1122-boot.jar'
$apiJar  = Join-Path $build 'umb-legacy1122-api.jar'
$pack200Jar = Join-Path $build 'umb-legacy1122-pack200.jar'
foreach ($p in @($java, $bootJar, $apiJar, $pack200Jar, $lwJar)) {
  if (-not (Test-Path $p)) { Write-Error ("missing: " + $p + " - run build.ps1 first"); exit 1 }
}

$cp = ($bootJar, $apiJar, $lwJar) -join ';'
& $java "-Dumb.repo=$repo" "-Dfml.ignorePatchDiscrepancies=true" -cp $cp dev.umb.legacy1122.boot.Boot1122ProbeMain
$probeExit = $LASTEXITCODE

$transformOut = Join-Path $repo 'research\out\legacy-1122\transform-probe.txt'
$parentJars = Get-Content (Join-Path $mod 'resources\classpath-1122.txt') |
  Where-Object { $_.Trim() -and -not $_.Trim().StartsWith('#') } |
  ForEach-Object { Join-Path $repo $_.Trim() } |
  Where-Object { (Test-Path $_) -and ($_ -notmatch 'forge-1\.12\.2-14\.23\.5\.2860-universal\.jar$') -and ($_ -notmatch '\\client\.jar$') }
$transformCp = (@($bootJar, $apiJar, $lwJar) + $parentJars) -join ';'
if ($javaArgs.Count -eq 0) {
  & $java "-Dfml.debugClassPatchManager=true" "-Dfml.ignorePatchDiscrepancies=true" "-Dumb.repo=$repo" -cp $transformCp dev.umb.legacy1122.boot.Boot1122TransformProbeMain 2>&1 | Tee-Object -FilePath $transformOut
} else {
  & $java "--patch-module" ("java.base=" + $pack200Jar) "--add-opens" "java.base/java.io=ALL-UNNAMED" "-Dfml.debugClassPatchManager=true" "-Dfml.ignorePatchDiscrepancies=true" "-Dumb.repo=$repo" -cp $transformCp dev.umb.legacy1122.boot.Boot1122TransformProbeMain 2>&1 | Tee-Object -FilePath $transformOut
}
$transformExit = $LASTEXITCODE
Write-Output ("BOOT_PROBE_EXIT=" + $probeExit)
Write-Output ("TRANSFORM_PROBE_EXIT=" + $transformExit)
if (($probeExit -ne 0) -or ($transformExit -ne 0)) { exit 1 }
exit 0
