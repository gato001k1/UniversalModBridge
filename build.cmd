@echo off
setlocal

where java >nul 2>nul
if errorlevel 1 (
  echo build.cmd: Java 25 is required but java was not found on PATH 1>&2
  exit /b 1
)

java -version 2>&1 | findstr /c:"version \"25" >nul
if errorlevel 1 (
  echo build.cmd: Java 25 is required ^(check java -version^) 1>&2
  exit /b 1
)

if "%~1"=="" (
  java tools\UmbBuild.java build
) else (
  java tools\UmbBuild.java %*
)
exit /b %errorlevel%
