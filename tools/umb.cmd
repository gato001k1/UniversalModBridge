@echo off
rem umb launcher - runs the UniversalModBridge CLI.
rem Honors JAVA_HOME when set (JDK 25 is required for MC 26.x hosts: classfile
rem major 69 cannot be defined on JDK 21); falls back to the portable JDK 21.
rem Usage: tools\umb.cmd analyze <mod.jar> [--json]
rem        tools\umb.cmd check-linkage <mod.jar> <host-client.jar>
rem        tools\umb.cmd graph-audit --tiny <file=verA:nsA:verB:nsB> [--limit N] [--json]
setlocal enabledelayedexpansion
set "ROOT=%~dp0.."
set "UMB_JAVA=%ROOT%\tools\jdk-21.0.12.1+1"
if defined JAVA_HOME set "UMB_JAVA=%JAVA_HOME%"
set "T=%TEMP%"

set "CP="
for %%M in (umb-core umb-mappings umb-cache umb-pipeline umb-cli) do set "CP=!CP!;%T%\umb-%%M-classes"
for %%J in (gson.jar asm-9.9.jar asm-tree-9.9.jar asm-commons-9.9.jar picocli-4.7.6.jar) do (
  if not exist "%ROOT%\tools\junit\%%J" set "MISSING=1"
  set "CP=!CP!;%ROOT%\tools\junit\%%J"
)
if defined MISSING (
  echo missing jars under tools\junit - see tools\windows\run-tests.ps1 header for the list >&2
  exit /b 1
)

if not exist "%UMB_JAVA%\bin\java.exe" (
  echo umb: no java at "%UMB_JAVA%\bin\java.exe" ^(JAVA_HOME=%JAVA_HOME%^) >&2
  exit /b 1
)

"%UMB_JAVA%\bin\java.exe" -Xmx768m -Xss4m -cp "!CP:~1!" dev.umb.cli.UmbCli %*
exit /b %ERRORLEVEL%
