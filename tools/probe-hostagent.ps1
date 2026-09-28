# Headless proof: bootstrap the REAL 26.2 registries with the agent attached and verify that
# the 1.7.10 snapshot content is present before they freeze. No window, no GL.
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\probe-hostagent.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\probe-hostagent.ps1 -Namespace ironchest
# Exit code 0 = PROBE-OK, 1 = PROBE-FAIL.
#
# UNIVERSALITY (harness-purge, headline finding 3): this used to hardcode
# research\out\legacy\hbm-snapshot.json and ns=hbm with no parameters at all, so this - the
# project's own headless "prove the agent works" gate - could only ever prove that for hbm.
# dev.umb.hostagent.probe.BootstrapProbe itself is already fully generic (reads HostAgent's own
# namespace/snapshot at runtime; verified by reading BootstrapProbe.java in full); only this
# wrapper's paths were the bottleneck. Defaults reproduce today's exact hbm invocation.
param(
  [string]$Namespace = 'hbm',
  [string]$Snapshot,
  [string]$Log
)
$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent $PSScriptRoot
$java = Join-Path $repo 'tools\jdk-25.0.4.1+1\bin\java.exe'
$agent = Join-Path $repo 'build\hostagent\umb-hostagent.jar'
$launchwrapper = Join-Path $repo 'research\visual\mc1710-native\libraries\net\minecraft\launchwrapper\1.12\launchwrapper-1.12.jar'
$classes = Join-Path $repo 'build\hostagent\classes'
$cpFile = Join-Path $repo 'research\visual\mc262-vanilla\classpath.txt'
if ($Snapshot) { $snapshot = $Snapshot } else { $snapshot = Join-Path $repo ('research\out\legacy\' + $Namespace + '-snapshot.json') }
if ($Log) { $log = $Log } else { $log = Join-Path $repo 'research\out\legacy\hostagent-probe.log' }

foreach ($p in @($java, $agent, $launchwrapper, $classes, $cpFile, $snapshot)) {
  if (-not (Test-Path $p)) { Write-Error ("missing: " + $p); exit 1 }
}
if (Test-Path $log) { Remove-Item -Force $log }

$gameCp = (Get-Content $cpFile -Raw).Trim()
# NOTE: the agent argument string is ';'-separated, which PowerShell would otherwise split -
# it is passed as ONE argument here.
$agentArg = '-javaagent:' + $agent + '=snapshot=' + $snapshot + ';log=' + $log + ';ns=' + $Namespace + ';launchwrapper=' + $launchwrapper

$argv = @(
  '-Xmx1200m',
  '--sun-misc-unsafe-memory-access=allow',
  '--enable-native-access=ALL-UNNAMED',
  $agentArg,
  '-cp', ($gameCp + ';' + $classes),
  'dev.umb.hostagent.probe.BootstrapProbe',
  $snapshot
)
Write-Output ('CMD: "' + $java + '" ' + ($argv -join ' '))
& $java @argv
$rc = $LASTEXITCODE
Write-Output ('exit=' + $rc + '  agent log: ' + $log)
exit $rc
