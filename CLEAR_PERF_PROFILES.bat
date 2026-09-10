@echo off
setlocal
set "ADB=C:\platform-tools\adb.exe"
set "PACKAGE=com.supertonic.tts"
if not exist "%ADB%" (
  echo [ERROR] adb.exe not found: %ADB%
  pause
  exit /b 1
)
"%ADB%" shell run-as "%PACKAGE%" rm -rf cache/accelerator_cache/perf_profiles
if errorlevel 1 (
  echo [ERROR] Could not clear profiles. Is the debug app installed?
  pause
  exit /b 1
)
echo Cleared app perf_profiles directory. It will be recreated automatically when Deep Profiler is enabled.
pause
