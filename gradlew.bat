@echo off
setlocal

set "APP_HOME=%~dp0"
set "GRADLE_VERSION=8.9"
set "GRADLE_HOME=%USERPROFILE%\.gradle\opentasker\gradle-%GRADLE_VERSION%"
set "GRADLE_BIN=%GRADLE_HOME%\bin\gradle.bat"
set "GRADLE_ZIP=%TEMP%\opentasker-gradle-%GRADLE_VERSION%.zip"
set "GRADLE_URL=https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip"

if not exist "%GRADLE_BIN%" (
    echo Downloading Gradle %GRADLE_VERSION%...
    powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $url='%GRADLE_URL%'; $zip='%GRADLE_ZIP%'; $dest='%USERPROFILE%\.gradle\opentasker'; New-Item -ItemType Directory -Force -Path $dest | Out-Null; [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $zip; Add-Type -AssemblyName System.IO.Compression.FileSystem; if (Test-Path '%GRADLE_HOME%') { Remove-Item -LiteralPath '%GRADLE_HOME%' -Recurse -Force }; [System.IO.Compression.ZipFile]::ExtractToDirectory($zip, $dest); Remove-Item -LiteralPath $zip -Force"
    if errorlevel 1 exit /b %errorlevel%
)

call "%GRADLE_BIN%" %*
exit /b %errorlevel%
