# UniversalModBridge portable toolchain environment.
# Dot-source this (or have scripts invoke it) so Gradle/Java use the local JDK 21,
# not the broken system JAVA_HOME (JRE 8 with a stray LRM character in the path).
$UMB_ROOT = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$env:JAVA_HOME = Join-Path $UMB_ROOT 'tools\jdk-21.0.12.1+1'
# JDK 25 is required to RUN Minecraft 26.x hosts (javaVersion.majorVersion=25); UMB builds stay on 21.
$script:JAVA25 = Join-Path $UMB_ROOT 'tools\jdk-25.0.4.1+1'
if (Test-Path $script:JAVA25) { $env:UMB_JAVA25 = $script:JAVA25 }
$env:Path = "$env:JAVA_HOME\bin;" + (Join-Path $UMB_ROOT 'tools\gradle-9.7.1\bin') + ';' + $env:Path
$env:ORG_GRADLE_PROJECT_umbRoot = $UMB_ROOT
