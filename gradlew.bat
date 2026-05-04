@echo off
setlocal enabledelayedexpansion
set GRADLE_HOME=%APPDATA%\gradle
if not exist "!GRADLE_HOME!\bin\gradle.bat" (
    powershell -NoProfile -Command "
    `$gradleUrl = 'https://services.gradle.org/distributions/gradle-8.9-bin.zip'
    `$dest = `"`$env:TEMP\gradle-8.9.zip`"
    `$home = `"`$env:APPDATA\gradle`"
    if (-Not (Test-Path `$home)) {
        [System.Net.ServicePointManager]::SecurityProtocol = [System.Net.ServicePointManager]::SecurityProtocol -bor [System.Net.SecurityProtocolType]::Tls12
        (New-Object System.Net.WebClient).DownloadFile(`$gradleUrl, `$dest)
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        [System.IO.Compression.ZipFile]::ExtractToDirectory(`$dest, `$env:APPDATA)
        Remove-Item `$dest
    }
    "
)
call "%GRADLE_HOME%\bin\gradle.bat" %*
