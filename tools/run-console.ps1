# Start the umb-console web UI (and optionally open it in the default browser).
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\run-console.ps1 [-Port 8765] [-Open]
#                                                                            [-Background] [-Stop]
#
# Binds 127.0.0.1 ONLY. -Xmx128m: RAM on this box is tight and three other lanes may be running a
# Minecraft client. Portable JDK 21 - never the system JAVA_HOME.
[CmdletBinding()]
param(
  [int]$Port = 8765,
  [switch]$Open,
  [switch]$Background,
  [switch]$Stop,
  [string[]]$ModsDir = @()
)
$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent $PSScriptRoot
$java = Join-Path $repo 'tools\jdk-21.0.12.1+1\bin\java.exe'
$jarFile = Join-Path $repo 'build\console\umb-console.jar'
$gson = Join-Path $repo 'tools\junit\gson.jar'
$logDir = Join-Path $repo 'research\out\legacy\console'
$pidFile = Join-Path $logDir 'console.pid'
$stdoutLog = Join-Path $logDir 'console-stdout.log'
$stderrLog = Join-Path $logDir 'console-stderr.log'
New-Item -ItemType Directory -Force $logDir | Out-Null

if ($Stop) {
  if (-not (Test-Path $pidFile)) { Write-Output 'no console.pid - nothing to stop'; exit 0 }
  $p = [int]((Get-Content $pidFile -Raw).Trim())
  try {
    $proc = Get-Process -Id $p -ErrorAction Stop
    if ($proc.ProcessName -notlike 'java*') { Write-Error ("pid " + $p + " is " + $proc.ProcessName + ", not java - refusing to stop it"); exit 1 }
    Stop-Process -Id $p -Force
    Write-Output ('stopped console pid ' + $p)
  } catch { Write-Output ('console pid ' + $p + ' is not running') }
  Remove-Item -Force $pidFile -ErrorAction SilentlyContinue
  exit 0
}

foreach ($p in @($java, $gson)) { if (-not (Test-Path $p)) { Write-Error ("missing: " + $p); exit 1 } }
if (-not (Test-Path $jarFile)) {
  Write-Output 'build\console\umb-console.jar missing - building'
  & (Join-Path $PSScriptRoot 'build-console.ps1')
  if ($LASTEXITCODE -ne 0) { Write-Error 'build-console.ps1 failed'; exit 1 }
}

$argv = @('-Xmx128m', '-XX:+UseSerialGC', '-cp', ($jarFile + ';' + $gson),
          'dev.umb.console.ConsoleServer', '--port', ('' + $Port), '--repo', $repo)
foreach ($d in $ModsDir) { $argv += @('--mods', $d) }
$url = 'http://127.0.0.1:' + $Port + '/'

Write-Output ('CMD: "' + $java + '" ' + ($argv -join ' '))
if ($Background) {
  if (Test-Path $stdoutLog) { Remove-Item -Force $stdoutLog }
  if (Test-Path $stderrLog) { Remove-Item -Force $stderrLog }
  $proc = Start-Process -FilePath $java -ArgumentList $argv -WorkingDirectory $repo `
    -RedirectStandardOutput $stdoutLog -RedirectStandardError $stderrLog -PassThru -WindowStyle Hidden
  $proc.Id | Out-File $pidFile -Encoding ascii -NoNewline
  Write-Output ('console pid=' + $proc.Id + '  log=' + $stdoutLog)
  # The server walks forward from -Port when a port is taken (this box has a tunnel-client on
  # 8765), so the real URL is whatever it printed - never assume it.
  $url = $null
  for ($i = 0; $i -lt 50; $i++) {
    Start-Sleep -Milliseconds 400
    if ($proc.HasExited) { break }
    if (Test-Path $stdoutLog) {
      $m = Select-String -Path $stdoutLog -Pattern 'listening on (http://\S+)' -ErrorAction SilentlyContinue |
        Select-Object -First 1
      if ($m) { $url = $m.Matches[0].Groups[1].Value; break }
    }
  }
  if (-not $url) {
    Write-Output '--- stderr ---'
    if (Test-Path $stderrLog) { Get-Content $stderrLog }
    Write-Error ('the console never reported a listening URL - see ' + $stderrLog)
    exit 1
  }
  Write-Output ('UMB CONSOLE: ' + $url)
  if ($Open) { Start-Process $url; Write-Output ('opened ' + $url + ' in the default browser') }
  exit 0
}

if ($Open) { Start-Process $url }
Write-Output ('UMB CONSOLE: ' + $url + '   (Ctrl+C to stop)')
& $java @argv
exit $LASTEXITCODE
