@echo off
setlocal

if "%ANDROID_HOME%"=="" (
  echo ANDROID_HOME is not set. Install Android Studio and its Android SDK first.
  exit /b 1
)

call gradlew.bat clean test lintDebug assembleDebug
if errorlevel 1 exit /b 1

echo.
echo APK created at app\build\outputs\apk\debug\app-debug.apk
endlocal
