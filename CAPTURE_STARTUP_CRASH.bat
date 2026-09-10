@echo off
setlocal EnableExtensions
set "ADB=C:\platform-tools\adb.exe"
set "PKG=com.supertonic.tts"
set "OUT=%~dp0STARTUP-CRASH-LOG.txt"
if not exist "%ADB%" (
  echo [ERROR] ADB not found: %ADB%
  pause
  exit /b 1
)
"%ADB%" wait-for-device
"%ADB%" logcat -c
"%ADB%" shell am force-stop %PKG%
"%ADB%" shell monkey -p %PKG% -c android.intent.category.LAUNCHER 1 >nul 2>&1
ping 127.0.0.1 -n 6 >nul
"%ADB%" logcat -d -v threadtime > "%OUT%"
echo.
echo === Crash lines ===
findstr /I /C:"FATAL EXCEPTION" /C:"Fatal signal" /C:"SIGSEGV" /C:"SIGABRT" /C:"linker" /C:"dlopen failed" /C:"UnsatisfiedLinkError" /C:"Abort message" "%OUT%"
echo.
echo Saved: %OUT%
pause
