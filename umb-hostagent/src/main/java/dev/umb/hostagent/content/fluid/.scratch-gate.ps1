$ErrorActionPreference = 'Stop'
$repo = (Get-Location).Path
$scratch = Join-Path $PSScriptRoot '.scratch'
if (Test-Path $scratch) { Remove-Item -Recurse -Force $scratch }
New-Item -ItemType Directory -Force (Join-Path $scratch 'classes-bridge'), (Join-Path $scratch 'classes'), (Join-Path $scratch 'test-classes') | Out-Null
$jdk25 = Join-Path $repo 'tools/jdk-25.0.4.1+1/bin'
$jdk21 = Join-Path $repo 'tools/jdk-21.0.12.1+1/bin'
$gameCp = (Get-Content (Join-Path $repo 'research/visual/mc262-vanilla/classpath.txt') -Raw).Trim()
$asm = @('tools/junit/asm-9.9.jar','tools/junit/asm-tree-9.9.jar','tools/junit/asm-commons-9.9.jar')
$legacy = @('build/legacy/umb-legacy-boot.jar','build/legacy/umb-legacy-api.jar',
    'research/visual/mc1710-native/libraries/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar',
    (Get-ChildItem -Recurse -Filter 'jopt-simple-*.jar' research/visual/mc1710-native/libraries | Select-Object -First 1).FullName)
$canonical = 'umb-legacy/src/bridge-api/java/dev/umb/bridge/api'
$apiSources = Get-ChildItem -Filter *.java $canonical | ForEach-Object { $_.FullName }
$apiArgs = Join-Path $scratch 'api.args'
($apiSources | ForEach-Object { '"' + ($_ -replace '\\', '\\\\') + '"' }) -join "`n" | Out-File $apiArgs -Encoding ascii
& (Join-Path $jdk21 'javac.exe') -nowarn -encoding UTF-8 --release 8 -d (Join-Path $scratch 'classes-bridge') ('@' + $apiArgs)
$builtClasses = Join-Path $repo 'build/hostagent/classes'
if (-not (Test-Path $builtClasses)) { throw "missing prebuilt hostagent classes: $builtClasses (run the owning lane's build first)" }
Copy-Item -Recurse -Force (Join-Path $builtClasses '*') (Join-Path $scratch 'classes')
$sources = Get-ChildItem -Filter *.java (Join-Path $repo 'umb-hostagent/src/main/java/dev/umb/hostagent/content/fluid') |
    Where-Object { $_.Name -notlike '.scratch-gate.ps1' } | ForEach-Object { $_.FullName }
$sources += Join-Path $repo 'umb-hostagent/src/main/java/dev/umb/hostagent/content/LegacyIds.java'
$mainArgs = Join-Path $scratch 'main.args'
($sources | ForEach-Object { '"' + ($_ -replace '\\', '\\\\') + '"' }) -join "`n" | Out-File $mainArgs -Encoding ascii
$compileCp = ((@((Join-Path $scratch 'classes')) + @($gameCp) + $asm + $legacy + @(Join-Path $scratch 'classes-bridge')) -join ';')
& (Join-Path $jdk25 'javac.exe') -nowarn -encoding UTF-8 -source 21 -target 21 -cp $compileCp -d (Join-Path $scratch 'classes') ('@' + $mainArgs)
if ($LASTEXITCODE -ne 0) { throw 'scratch fluid javac failed' }
Copy-Item -Recurse -Force (Join-Path $scratch 'classes-bridge/dev') (Join-Path $scratch 'classes')
$testSources = Get-ChildItem -Recurse -Filter *.java (Join-Path $repo 'umb-hostagent/src/test/java/dev/umb/hostagent/content/fluid') | ForEach-Object { $_.FullName }
$testArgs = Join-Path $scratch 'test.args'
($testSources | ForEach-Object { '"' + ($_ -replace '\\', '\\\\') + '"' }) -join "`n" | Out-File $testArgs -Encoding ascii
$testCp = ((@((Join-Path $scratch 'classes'),'tools/junit/junit-platform-console-standalone.jar') + $asm +
    @('tools/junit/asm-analysis-9.9.jar','tools/junit/asm-util-9.9.jar') + $legacy + @($gameCp)) -join ';')
& (Join-Path $jdk25 'javac.exe') -nowarn -encoding UTF-8 -cp $testCp -d (Join-Path $scratch 'test-classes') ('@' + $testArgs)
if ($LASTEXITCODE -ne 0) { throw 'scratch fluid test javac failed' }
Write-Output ('SCRATCH_COMPILE_OK mainSources=' + $sources.Count + ' testSources=' + $testSources.Count)
& (Join-Path $jdk25 'java.exe') -jar tools/junit/junit-platform-console-standalone.jar execute `
    --class-path (($scratch + '/test-classes;' + $testCp)) `
    --scan-class-path (Join-Path $scratch 'test-classes') --exclude-classname 'dev\.umb\.hostagent\.content\.UmbLegacyBlockTest' `
    --exclude-classname 'dev\.umb\.hostagent\.content\.UmbMenuAdapterCrossLoaderTest' `
    --exclude-classname 'dev\.umb\.hostagent\.content\.UmbMenuContentParityTest' --details=summary --disable-ansi-colors
exit $LASTEXITCODE
