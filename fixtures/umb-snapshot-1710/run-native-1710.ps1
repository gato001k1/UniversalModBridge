# Launches the native 1.7.10 + Forge 10.13.4.1614 instance with HBM + the UMB
# snapshot mod, dumping (a) the registry snapshot JSON and (b) every transformed
# class LaunchWrapper sees (LaunchClassLoader DEBUG_SAVE).
#
# Research-only: the instance, the dumped classes and the snapshot are local
# artefacts under research/ and are never committed.
#
# Java 8 is REQUIRED to run 1.7.10 (the portable JDK 21/25 in tools/ cannot).
# The system JAVA_HOME is broken (stray LRM); the JRE path below is the one the
# earlier native control run actually used, per its own FML banner line.
param(
    [int]$MaxSeconds = 900,
    [int]$PollSeconds = 20,
    [switch]$NoDebugSave,
    [string]$RunTag = '',
    # Lane A3: root for the runtime texture-atlas dump (umbsnap.icons). '' turns it off.
    [string]$IconRoot = '<default>',
    [int]$HeapMb = 2560,
    [string]$LogDir = '',
    # Where the registry snapshot goes. Point this somewhere else to leave the shared
    # research\out\legacy\hbm-snapshot.json alone (another lane may be reading it).
    [string]$SnapshotOut = ''
)
$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$inst = Join-Path $root 'research\visual\mc1710-native'
$outDir = Join-Path $root 'research\out\legacy'
if ($RunTag -eq '') { $RunTag = (Get-Date -Format 'yyyyMMdd-HHmmss') }
if ($LogDir -eq '') { $logDir = Join-Path $outDir "logs\run-$RunTag" } else { $logDir = $LogDir }
New-Item -ItemType Directory -Force -Path $logDir, $outDir | Out-Null

if ($IconRoot -eq '<default>') { $IconRoot = Join-Path $outDir 'hbm-assets-runtime' }
$iconIndex = ''
if ($IconRoot -ne '') { $iconIndex = Join-Path $IconRoot 'icon-index.json' }

$javaExe = 'C:\Program Files\Java\jre1.8.0_501\bin\java.exe'
if (-not (Test-Path $javaExe)) { Write-Error "Java 8 not found at $javaExe"; exit 1 }

if ($SnapshotOut -eq '') { $outJson = Join-Path $outDir 'hbm-snapshot.json' } else { $outJson = $SnapshotOut }
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $outJson) | Out-Null
if (Test-Path $outJson) { Remove-Item $outJson -Force }

$cp = (Get-Content (Join-Path $inst 'classpath.txt') -Raw).Trim()

$jvm = @(
    '-Xms512m', "-Xmx${HeapMb}m",
    "-Djava.library.path=$inst\natives",
    "-Dumbsnap.out=$outJson",
    '-Dumbsnap.exit=true',
    '-Dumbsnap.delay=20'
)
if ($IconRoot -ne '') { $jvm += "-Dumbsnap.icons=$IconRoot" }
if (-not $NoDebugSave) {
    $jvm += '-Dlegacy.debugClassLoading=true'
    $jvm += '-Dlegacy.debugClassLoadingSave=true'
}
$gameArgs = @(
    'net.minecraft.launchwrapper.Launch',
    '--username', 'ControlPlayer',
    '--version', '1.7.10-Forge10.13.4.1614-1.7.10',
    '--gameDir', $inst,
    '--assetsDir', (Join-Path $inst 'assets'),
    '--assetIndex', '1.7.10',
    '--uuid', '00000000000000000000000000000000',
    '--accessToken', '0',
    '--userProperties', '{}',
    '--userType', 'legacy',
    '--tweakClass', 'cpw.mods.fml.common.launcher.FMLTweaker',
    '--width', '854', '--height', '480'
)
$argList = $jvm + @('-cp', $cp) + $gameArgs

$stdout = Join-Path $logDir 'stdout.log'
$stderr = Join-Path $logDir 'stderr.log'
($javaExe + "`n" + ($argList -join "`n")) | Out-File -FilePath (Join-Path $logDir 'launch-argv.txt') -Encoding utf8

Write-Output "[run] java     : $javaExe"
Write-Output "[run] workdir  : $inst"
Write-Output "[run] snapshot : $outJson"
Write-Output "[run] icons    : $(if ($IconRoot -eq '') { '<off>' } else { $IconRoot })"
Write-Output "[run] logs     : $logDir"

$p = Start-Process -FilePath $javaExe -ArgumentList $argList -WorkingDirectory $inst `
    -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
$pid_ = $p.Id
Write-Output "[run] PID      : $pid_"
$pid_ | Out-File -FilePath (Join-Path $logDir 'pid.txt') -Encoding ascii

$sw = [System.Diagnostics.Stopwatch]::StartNew()
while (-not $p.HasExited -and $sw.Elapsed.TotalSeconds -lt $MaxSeconds) {
    Start-Sleep -Seconds $PollSeconds
    $jsonLen = 0
    if (Test-Path $outJson) { $jsonLen = (Get-Item $outJson).Length }
    $dumped = 0
    $td = Get-ChildItem -Path $inst -Filter 'CLASSLOADER_TEMP*' -Directory -ErrorAction SilentlyContinue
    if ($td) { $dumped = (Get-ChildItem -Path $td[0].FullName -Recurse -Filter '*.class' -ErrorAction SilentlyContinue).Count }
    $icons = 0
    if ($iconIndex -ne '' -and (Test-Path $iconIndex)) { $icons = (Get-Item $iconIndex).Length }
    $tail = ''
    if (Test-Path $stdout) { $tail = (Get-Content $stdout -Tail 1 -ErrorAction SilentlyContinue) }
    Write-Output ("[{0,4:N0}s] exited={1} json={2}B classes={3} iconIndex={4}B | {5}" -f $sw.Elapsed.TotalSeconds, $p.HasExited, $jsonLen, $dumped, $icons, $tail)
}

if (-not $p.HasExited) {
    Write-Output "[run] timeout after $($sw.Elapsed.TotalSeconds)s -- stopping PID $pid_"
    Stop-Process -Id $pid_ -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 3
}
$p.Refresh()
Write-Output "[run] exited=$($p.HasExited) exitCode=$(try { $p.ExitCode } catch { 'n/a' }) elapsed=$([int]$sw.Elapsed.TotalSeconds)s"

# preserve this run's FML logs alongside our own stdout/stderr
Copy-Item (Join-Path $inst 'logs\*.log') $logDir -Force -ErrorAction SilentlyContinue
if (Test-Path $outJson) {
    Write-Output "[run] snapshot bytes: $((Get-Item $outJson).Length)"
} else {
    Write-Output '[run] snapshot MISSING'
}
if ($iconIndex -ne '') {
    if (Test-Path $iconIndex) {
        $png = (Get-ChildItem -Path $IconRoot -Recurse -Filter '*.png' -ErrorAction SilentlyContinue).Count
        Write-Output "[run] icon-index bytes: $((Get-Item $iconIndex).Length)  pngs: $png"
    } else {
        Write-Output '[run] icon-index MISSING'
    }
}
Select-String -Path $stdout -Pattern '\[umbsnap\]' -ErrorAction SilentlyContinue |
    ForEach-Object { Write-Output "[umbsnap-log] $($_.Line)" }
Get-ChildItem -Path $inst -Filter 'CLASSLOADER_TEMP*' -Directory -ErrorAction SilentlyContinue |
    ForEach-Object { Write-Output "[run] dump dir: $($_.FullName)" }
