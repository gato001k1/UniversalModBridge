# Low-memory test loop: bypasses Gradle (daemon+worker ~1.4GB commit) with a single
# javac+JUnit JVM (~300MB). Usage: .\tools\windows\run-tests.ps1 -Module umb-cache [-Xmx 256m]
#
# Module dependency graph (compile classpath is assembled from the prebuilt classes dirs
# of upstream modules, matching the Gradle project deps):
#   umb-core    : gson, asm, asm-tree
#   umb-mappings: gson, asm, asm-tree, asm-commons (graph-driven remapper)
#   umb-pipeline: umb-core, gson, asm, asm-tree (SmokeLoader tests build classfixtures)
#   umb-cache   : gson
#   umb-cli     : umb-core, umb-mappings, umb-cache, umb-pipeline, gson, asm, asm-tree,
#                 asm-commons, picocli
param(
    [Parameter(Mandatory=$true)][string]$Module,
    [string[]]$ExtraCpJars = @(),
    [string]$Xmx = '160m'
)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
. .\tools\windows\umb-env.ps1

$jdk = Join-Path $env:JAVA_HOME 'bin'
$out = Join-Path $env:TEMP "umb-$Module-classes"
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force -Path $out | Out-Null

# Shared lib jars.
$cp = "tools\junit\gson.jar"
$asmJars = @('tools\junit\asm-9.9.jar', 'tools\junit\asm-tree-9.9.jar')
if (-not (Test-Path 'tools\junit\asm-9.9.jar')) { $asmJars = @('tools\junit\asm.jar', 'tools\junit\asm-tree.jar') }

switch ($Module) {
    'umb-core'     { $cp = "$cp;$($asmJars -join ';')" }
    'umb-mappings' {
        # SelectorResolver (M8-2) imports umb-core selector IR.
        $upOut = Join-Path $env:TEMP "umb-umb-core-classes"
        if (-not (Test-Path $upOut)) { Write-Error "run umb-core tests first (need its classes)"; exit 1 }
        $cp = "tools\junit\gson.jar;$upOut;$($asmJars -join ';');tools\junit\asm-commons-9.9.jar"
    }
    'umb-pipeline' {
        # SmokeLoader (M3) tests generate classfiles with ASM; main imports umb-core.
        $upOut = Join-Path $env:TEMP "umb-umb-core-classes"
        if (-not (Test-Path $upOut)) { Write-Error "run umb-core tests first (need its classes)"; exit 1 }
        $cp = "$cp;$upOut;$($asmJars -join ';')"
    }
    'umb-cache'    { }
    'umb-cli'      {
        # Upstream module classes first, then libs. umb-pipeline joins the graph for
        # umb smoke (M3), umb-cache joins for umb translate (M4) — matches build.gradle.
        foreach ($up in 'umb-core', 'umb-mappings', 'umb-cache', 'umb-pipeline') {
            $upOut = Join-Path $env:TEMP "umb-$up-classes"
            if (-not (Test-Path $upOut)) { Write-Error "run umb-$up tests first (need its classes)"; exit 1 }
            $cp = "$cp;$upOut"
        }
        $cp = "$cp;$($asmJars -join ';');tools\junit\asm-commons-9.9.jar;tools\junit\picocli-4.7.6.jar"
    }
    default        { Write-Error "unknown module '$Module'"; exit 1 }
}
foreach ($j in $ExtraCpJars) { $cp = "$cp;$j" }

Write-Output "[run-tests] compiling $Module main..."
& "$jdk\javac.exe" -encoding UTF-8 --release 21 -cp $cp -d $out `
    (Get-ChildItem "$Module\src\main\java" -Recurse -Filter *.java | ForEach-Object FullName) | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Error "main compile failed"; exit 1 }

$testSrc = "$Module\src\test\java"
if (Test-Path $testSrc) {
    Write-Output "[run-tests] compiling tests..."
    & "$jdk\javac.exe" -encoding UTF-8 --release 21 -cp "$out;$cp;tools\junit\junit-platform-console-standalone.jar" -d $out `
        (Get-ChildItem $testSrc -Recurse -Filter *.java | ForEach-Object FullName) | Out-Null
    if ($LASTEXITCODE -ne 0) { Write-Error "test compile failed"; exit 1 }

    Write-Output "[run-tests] running JUnit..."
    # Commit-charge-constrained host: run with a minimal-footprint JVM.
    & "$jdk\java.exe" "-Xmx$Xmx" -Xss512k -XX:MaxMetaspaceSize=96m -XX:ReservedCodeCacheSize=24m -XX:-UsePerfData `
        -jar tools\junit\junit-platform-console-standalone.jar `
        execute --class-path "$out;$cp" --scan-classpath --fail-if-no-tests `
        --details=tree
    exit $LASTEXITCODE
} else {
    Write-Output "[run-tests] no tests in $Module"
    exit 0
}
