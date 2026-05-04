@echo off
setlocal enabledelayedexpansion

REM Get the directory where this script is located
for %%A in ("%~dp0") do set "scriptDir=%%~dpA"
cd /d "!scriptDir!"

REM Set environment variables
set "ANDROID_HOME=C:\Users\--\AppData\Local\Android\Sdk"
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"

REM Run the build
echo Building OpenTasker...
call gradlew.bat clean assembleDebug
exit /b !errorlevel!
