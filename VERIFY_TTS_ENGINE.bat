@echo off
setlocal
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" (
  where adb >nul 2>nul
  if errorlevel 1 (
    echo [ERROR] adb.exe not found.
    pause
    exit /b 1
  )
  set "ADB=adb"
)
echo === Connected device ===
"%ADB%" devices
for /f "tokens=1" %%D in ('"%ADB%" devices ^| findstr /R /C:"[0-9A-Za-z].*device$"') do set "DEVICE=%%D"
if not defined DEVICE (
  echo [ERROR] No Android device with USB debugging found.
  pause
  exit /b 1
)
echo.
echo === TTS services reported by Package Manager ===
"%ADB%" shell pm query-services -a android.intent.action.TTS_SERVICE
if errorlevel 1 (
  echo pm query-services is unavailable; falling back to dumpsys.
  "%ADB%" shell dumpsys package com.supertonic.tts ^| findstr /I "android.intent.action.TTS_SERVICE SpeechTextToSpeechService supertonic.tts"
)
echo.
echo === Installed package ===
"%ADB%" shell dumpsys package com.supertonic.tts ^| findstr /I "versionName versionCode enabled exported permission"
echo.
echo If the TTS service is listed here, Android can see the engine. If it is not listed, the APK manifest/service registration is still wrong.
pause
