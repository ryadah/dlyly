@echo off
setlocal
where gradle >nul 2>nul
if errorlevel 1 (
  echo Gradle was not found. Open this project in Android Studio, or use GitHub Actions.
  pause
  exit /b 1
)
gradle assembleDebug
if errorlevel 1 (
  echo APK build failed.
  pause
  exit /b 1
)
echo APK created at app\build\outputs\apk\debug\app-debug.apk
pause
