@echo off
REM Build debug APK
cd /d C:\Users\--\repos\OpenTasker
echo Building OpenTasker debug APK...
echo.
call gradlew.bat clean assembleDebug
echo.
echo Build completed with exit code: %ERRORLEVEL%
if exist app\build\outputs\apk\debug\app-debug.apk (
    echo.
    echo SUCCESS: APK built at:
    echo app\build\outputs\apk\debug\app-debug.apk
    dir app\build\outputs\apk\debug\app-debug.apk
) else (
    echo.
    echo FAILED: APK not found
)
pause
