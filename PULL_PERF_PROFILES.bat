@echo off
setlocal EnableExtensions EnableDelayedExpansion
set "ROOT=%~dp0"
set "ADB=C:\platform-tools\adb.exe"
set "PACKAGE=com.supertonic.tts"

if not exist "%ADB%" (
  echo [ERROR] adb.exe not found: %ADB%
  pause
  exit /b 1
)
"%ADB%" get-state >nul 2>&1
if errorlevel 1 (
  echo [ERROR] No ONLINE ADB device connected.
  "%ADB%" devices
  pause
  exit /b 1
)

for /f %%T in ('powershell.exe -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set "STAMP=%%T"
set "OUT=%ROOT%PERF-PROFILES-!STAMP!"
set "TAR=%TEMP%\supertonic-perf-!RANDOM!-!RANDOM!.tar"
mkdir "!OUT!" >nul 2>&1

echo === Supertonic FIX10 Deep Profiler pull ===
echo Device path: cache/accelerator_cache/perf_profiles

echo Checking app/debug run-as access...
"%ADB%" shell run-as "%PACKAGE%" sh -c "test -d cache/accelerator_cache/perf_profiles" >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Profile directory is missing or run-as is unavailable.
  echo Enable Deep Profiler in the app, recreate/use the engine, and run one synthesis first.
  pause
  exit /b 1
)

if exist "!TAR!" del /q "!TAR!" >nul 2>&1
"%ADB%" exec-out run-as "%PACKAGE%" tar -C cache/accelerator_cache/perf_profiles -cf - . > "!TAR!"
if errorlevel 1 (
  echo [ERROR] Could not archive profile files with run-as/tar.
  if exist "!TAR!" del /q "!TAR!" >nul 2>&1
  pause
  exit /b 1
)

where tar.exe >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Windows tar.exe was not found.
  echo Raw archive kept at: !TAR!
  pause
  exit /b 1
)

tar.exe -xf "!TAR!" -C "!OUT!"
if errorlevel 1 (
  echo [ERROR] Failed to extract profile archive.
  echo Raw archive kept at: !TAR!
  pause
  exit /b 1
)
del /q "!TAR!" >nul 2>&1

echo.
echo Profiles: !OUT!
where python.exe >nul 2>&1
if not errorlevel 1 (
  python.exe "%ROOT%tools\analyze_deep_profiles.py" "!OUT!"
) else (
  where py.exe >nul 2>&1
  if not errorlevel 1 py.exe -3 "%ROOT%tools\analyze_deep_profiles.py" "!OUT!"
)
if exist "!OUT!\SUMMARY.txt" (
  echo Summary : !OUT!\SUMMARY.txt
)
echo.
echo Done. Deep Profiler can now be turned OFF for normal RTF tests.
pause
