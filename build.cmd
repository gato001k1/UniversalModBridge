@echo off
setlocal
where java >nul 2>nul || (echo Java 25 is required & exit /b 2)
for /f "tokens=3" %%V in ('java -version 2^>^&1 ^| findstr /r /c:"version"') do set "JV=%%~V"
echo %JV% | findstr /b "25." >nul || (echo Java 25 is required; check java -version & exit /b 2)
if "%~1"=="" (java tools/UmbBuild.java build) else (java tools/UmbBuild.java %*)
exit /b %ERRORLEVEL%
