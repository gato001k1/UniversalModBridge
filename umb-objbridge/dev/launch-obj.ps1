# Boot Minecraft 26.2 windowed with BOTH agents and BOTH packs, so HBM's 1.7.10 OBJ meshes render
# as real geometry.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File umb-objbridge\dev\launch-obj.ps1 -Name obj1
#
# This is Lane D's OWN copy of harness\launch-262-legacy.ps1 (that file is not edited). Differences:
#   * a SECOND -javaagent (umb-objbridge) attached AFTER the host agent
#   * TWO resource packs, with the OBJ overlay LAST in options.txt so it wins - MultiPackResourceManager
#     lets later packs override earlier ones, and the in-game Resource Packs screen is screenshotted
#     to confirm the order empirically
#   * -Xmx4G instead of 2G: ~470k triangles become ~470k BakedQuads during the model bake
[CmdletBinding()]
param(
  [string]$Name = 'obj1',
  [string]$Snapshot = 'research\out\legacy\hbm-snapshot.json',
  [string]$BasePack = 'research\out\legacy\packs\hbm-generated',
  [string]$ObjPack = 'research\out\legacy\packs\hbm-objmodels',
  [string]$RenderMap = 'research\out\legacy\rendermap\hbm-render-map.json',
  [string]$Assets = 'research\out\legacy\hbm-assets',
  [switch]$KeepGameDir,
  # UNIVERSALITY (2026-09-10): the namespace the host agent registers this mod's content
  # under. This used to be the literal 'hbm' baked into the javaagent argument below, so
  # ANY other mod launched through this script was registered under the WRONG namespace.
  # Default keeps the previous behaviour byte-for-byte.
  [string]$Ns = 'hbm'
)
$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$vanilla = Join-Path $repo 'research\visual\mc262-vanilla'
$java = Join-Path $repo 'tools\jdk-25.0.4.1+1\bin\java.exe'
$client = Join-Path $repo 'research\jars\26.2\client.jar'
$hostAgent = Join-Path $repo 'build\hostagent\umb-hostagent.jar'
$objAgent = Join-Path $repo 'build\objbridge\umb-objbridge.jar'
$cpFile = Join-Path $vanilla 'classpath.txt'

foreach ($v in @('Snapshot', 'BasePack', 'ObjPack', 'RenderMap', 'Assets')) {
  $val = Get-Variable -Name $v -ValueOnly
  if (-not [System.IO.Path]::IsPathRooted($val)) { Set-Variable -Name $v -Value (Join-Path $repo $val) }
}
foreach ($p in @($java, $client, $hostAgent, $objAgent, $cpFile, $Snapshot, $BasePack, $ObjPack, $RenderMap, $Assets)) {
  if (-not (Test-Path $p)) { Write-Error ("missing: " + $p); exit 1 }
}

# ---- ONE Minecraft window at a time on this box ----
$busy = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
  Where-Object { $_.CommandLine -like '*legacy\win-*' }
if ($busy) {
  Write-Error ("the windowed slot is busy: pids " + (($busy | ForEach-Object { $_.ProcessId }) -join ','))
  exit 1
}

$gameDir = Join-Path $repo ('research\out\legacy\win-' + $Name)
if ((Test-Path $gameDir) -and (-not $KeepGameDir)) { Remove-Item -Recurse -Force $gameDir }
foreach ($d in @('', 'logs', 'shots', 'resourcepacks', 'saves')) {
  New-Item -ItemType Directory -Force (Join-Path $gameDir $d) | Out-Null
}

# ---- options.txt: base pack first, OBJ overlay LAST (= highest priority) ----
$baseName = Split-Path -Leaf $BasePack
$objName = Split-Path -Leaf $ObjPack
$packList = 'resourcePacks:["file/' + $baseName + '","file/' + $objName + '"]'
$opts = Get-Content (Join-Path $vanilla 'options.txt')
$out = @()
foreach ($line in $opts) {
  switch -Regex ($line) {
    '^resourcePacks:'             { $out += $packList; continue }
    '^incompatibleResourcePacks:' { $out += 'incompatibleResourcePacks:[]'; continue }
    '^pauseOnLostFocus:'          { $out += 'pauseOnLostFocus:false'; continue }
    '^guiScale:'                  { $out += 'guiScale:2'; continue }
    '^renderDistance:'            { $out += 'renderDistance:6'; continue }
    '^simulationDistance:'        { $out += 'simulationDistance:5'; continue }
    '^maxFps:'                    { $out += 'maxFps:60'; continue }
    '^fullscreen:'                { $out += 'fullscreen:false'; continue }
    default                       { $out += $line }
  }
}
$out | Out-File (Join-Path $gameDir 'options.txt') -Encoding ascii
Write-Output ('options resourcePacks -> ' + $packList)

# ---- both packs ----
foreach ($pack in @($BasePack, $ObjPack)) {
  $dst = Join-Path $gameDir ('resourcepacks\' + (Split-Path -Leaf $pack))
  $null = robocopy $pack $dst /E /NFL /NDL /NJH /NJS /NP /R:1 /W:1
  if ($LASTEXITCODE -gt 7) { Write-Error ('robocopy failed rc=' + $LASTEXITCODE); exit 1 }
  $global:LASTEXITCODE = 0
  Write-Output ((Split-Path -Leaf $pack) + ' files: ' + (Get-ChildItem -Recurse -File $dst).Count)
}

# ---- natives (reuse the vanilla instance's extracted natives) ----
$natives = Join-Path $vanilla 'natives'
foreach ($d in @('java', 'jna', 'lwjgl', 'netty')) { New-Item -ItemType Directory -Force (Join-Path $natives $d) | Out-Null }

$cp = (Get-Content $cpFile -Raw).Trim()
$hostLog = Join-Path $gameDir 'logs\hostagent.log'
$objLog = Join-Path $gameDir 'logs\objbridge.log'
# ONE argument each - the ';' separators must not be split by PowerShell
$hostArg = '-javaagent:' + $hostAgent + '=snapshot=' + $Snapshot + ';log=' + $hostLog + ';ns=' + $Ns
# BUGFIX (2026-09-09): the objbridge agent's `transforms` option is OPTIONAL and defaults to the
# RELATIVE path 'research/out/legacy/rendermap/renderer-transforms.json'. The game runs with its own
# gameDir as the working directory, so that relative default resolves to
# the game directory instead of the repo root, so it can never be found. The agent then logged
# "renderer transforms missing ... every model auto-fits" and EVERY model fell back to auto-fit
# (SCALE-FIX transformResolved=0 autoFitFallback=162) -- i.e. every machine crushed into one block.
# Pass it explicitly, rooted, derived from the render map's own directory.
$transformsPath = Join-Path (Split-Path -Parent $RenderMap) 'renderer-transforms.json'
if (-not (Test-Path $transformsPath)) {
  Write-Warning ("renderer transforms not found next to the render map: " + $transformsPath + " - models will auto-fit")
}
$objArg = '-javaagent:' + $objAgent + '=rendermap=' + $RenderMap + ';assets=' + $Assets + ';transforms=' + $transformsPath + ';log=' + $objLog

$jvm = @(
  '-Xmx4G',
  # BUGFIX (2026-09-09): these 8 --add-opens are REQUIRED AT JVM STARTUP by the legacy universe and
  # cannot be added once the JVM is running. harness/launch-262-legacy-m1.ps1 has always passed them;
  # this launcher never did, so every -Obj launch booted with 3D models but a DEAD legacy bridge:
  # "UMB-BRIDGE FAILED to boot ... missing --add-opens", every tile entity poisoned, and therefore no
  # GUI, no block interaction, no item interaction and no machine logic anywhere in the game.
  '--add-opens', 'java.base/java.lang=ALL-UNNAMED',
  '--add-opens', 'java.base/java.lang.reflect=ALL-UNNAMED',
  '--add-opens', 'java.base/java.util=ALL-UNNAMED',
  '--add-opens', 'java.base/java.util.concurrent=ALL-UNNAMED',
  '--add-opens', 'java.base/java.net=ALL-UNNAMED',
  '--add-opens', 'java.base/java.nio=ALL-UNNAMED',
  '--add-opens', 'java.base/java.io=ALL-UNNAMED',
  '--add-opens', 'java.base/java.text=ALL-UNNAMED',
  '-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump',
  '--sun-misc-unsafe-memory-access=allow',
  '--enable-native-access=ALL-UNNAMED',
  ('-Djava.library.path=' + (Join-Path $natives 'java')),
  ('-Djna.tmpdir=' + (Join-Path $natives 'jna')),
  ('-Dorg.lwjgl.system.SharedLibraryExtractPath=' + (Join-Path $natives 'lwjgl')),
  ('-Dio.netty.native.workdir=' + (Join-Path $natives 'netty')),
  # SAFETY (2026-09-09): this used to read 'umb-objbridge-probe'. That is also how the short-lived
  # objbridge PROBE processes are described, so a cleanup step that killed 'leftover
  # umb-objbridge-probe java processes' matched the USER'S LIVE GAME and killed it repeatedly.
  # The brand for a real, user-facing game must be unmistakably distinct from any disposable
  # tooling process.
  '-Dminecraft.launcher.brand=umb-game',
  '-Dminecraft.launcher.version=1.0',
  ('-Dlog4j.configurationFile=' + (Join-Path $vanilla 'log4j-client.xml')),
  $hostArg,
  $objArg
)
$game = @(
  'net.minecraft.client.main.Main',
  '--username', 'UmbObj',
  '--version', '26.2',
  '--gameDir', $gameDir,
  '--assetsDir', (Join-Path $vanilla 'assets'),
  '--assetIndex', '26.2',
  '--uuid', '00000000000000000000000000000000',
  '--accessToken', '0',
  '--clientId', '0',
  '--xuid', '0',
  '--versionType', 'release',
  '--userType', 'legacy',
  '--width', '854',
  '--height', '480'
)
$full = $jvm + @('-cp', $cp) + $game
('"' + $java + '" ' + (($full | ForEach-Object { '"' + ($_ -replace '"', '""') + '"' }) -join ' ')) |
  Out-File (Join-Path $gameDir 'logs\launch-cmd.txt') -Encoding ascii

$p = Start-Process -FilePath $java -ArgumentList $full `
  -WorkingDirectory $gameDir `
  -RedirectStandardOutput (Join-Path $gameDir 'logs\stdout.log') `
  -RedirectStandardError (Join-Path $gameDir 'logs\stderr.log') `
  -PassThru
$p.Id | Out-File (Join-Path $gameDir 'logs\pid.txt') -Encoding ascii -NoNewline
Write-Output ('launched pid=' + $p.Id)
Write-Output ('gameDir=' + $gameDir)
Write-Output ('hostLog=' + $hostLog)
Write-Output ('objLog=' + $objLog)
