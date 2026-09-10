@echo off
setlocal EnableExtensions EnableDelayedExpansion
set "ROOT=%~dp0"
set "APK=%ROOT%app\build\outputs\apk\debug\app-debug.apk"
set "ADB=C:\platform-tools\adb.exe"
set "PACKAGE=com.supertonic.tts"

if not exist "%APK%" (
  echo [ERROR] APK not found: %APK%
  echo Build it first with BUILD_ALL.bat
  pause
  exit /b 1
)
if not exist "%ADB%" (
  echo [ERROR] adb.exe not found: %ADB%
  pause
  exit /b 1
)
"%ADB%" get-state >nul 2>&1
if errorlevel 1 (
  echo [ERROR] No ADB device connected.
  "%ADB%" devices
  pause
  exit /b 1
)

if not defined ANDROID_HOME if exist "%LOCALAPPDATA%\Android\Sdk" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
set "APKSIGNER="
if defined ANDROID_HOME if exist "%ANDROID_HOME%\build-tools" (
  for /f "delims=" %%D in ('dir /b /ad /o-n "%ANDROID_HOME%\build-tools" 2^>nul') do (
    if not defined APKSIGNER if exist "%ANDROID_HOME%\build-tools\%%D\apksigner.bat" set "APKSIGNER=%ANDROID_HOME%\build-tools\%%D\apksigner.bat"
  )
)

if defined APKSIGNER (
  set "NEW_CERT="
  for /f "tokens=2 delims=:" %%H in ('call "!APKSIGNER!" verify --print-certs "%APK%" 2^>nul ^| findstr /c:"Signer #1 certificate SHA-256 digest:"') do set "NEW_CERT=%%H"
  for /f "tokens=*" %%H in ("!NEW_CERT!") do set "NEW_CERT=%%H"
  if not defined NEW_CERT (
    echo [ERROR] Could not read the new APK signing certificate.
    pause
    exit /b 1
  )
  echo New APK signing SHA-256: !NEW_CERT!

  set "REMOTE_APK="
  for /f "tokens=2 delims=:" %%P in ('"%ADB%" shell pm path %PACKAGE% 2^>nul ^| findstr /b "package:"') do if not defined REMOTE_APK set "REMOTE_APK=%%P"
  if defined REMOTE_APK (
    set "INSTALLED_APK=%TEMP%\supertonic-installed-base.apk"
    del /q "!INSTALLED_APK!" >nul 2>&1
    "%ADB%" pull "!REMOTE_APK!" "!INSTALLED_APK!" >nul
    if errorlevel 1 (
      echo [ERROR] Could not pull the installed base APK for signature guard.
      pause
      exit /b 1
    )
    set "OLD_CERT="
    for /f "tokens=2 delims=:" %%H in ('call "!APKSIGNER!" verify --print-certs "!INSTALLED_APK!" 2^>nul ^| findstr /c:"Signer #1 certificate SHA-256 digest:"') do set "OLD_CERT=%%H"
    for /f "tokens=*" %%H in ("!OLD_CERT!") do set "OLD_CERT=%%H"
    del /q "!INSTALLED_APK!" >nul 2>&1
    if not defined OLD_CERT (
      echo [ERROR] Could not read the installed app signing certificate.
      pause
      exit /b 1
    )
    echo Installed signing SHA-256: !OLD_CERT!
    if /i not "!NEW_CERT!"=="!OLD_CERT!" (
      echo.
      echo [BLOCKED] SIGNATURE MISMATCH.
      echo The existing app/data/models will NOT be touched.
      echo Do NOT uninstall the app. Restore the previous signing key and rebuild.
      pause
      exit /b 2
    )
    echo [OK] Signing certificate matches the installed app.
  ) else (
    echo [INFO] %PACKAGE% is not currently installed; signature comparison skipped.
  )
) else (
  echo [WARN] apksigner.bat not found; pre-install certificate comparison skipped.
  echo Android package manager will still reject a mismatched update without deleting app data.
)

echo.
echo Installing Supertonic TTS as an UPDATE ^(app data/models preserved^)...
"%ADB%" install -r "%APK%"
if errorlevel 1 (
  echo.
  echo [ERROR] Install failed.
  echo IMPORTANT: do NOT uninstall the existing app just to bypass a signature mismatch.
  echo Copy the exact adb error and report it so the existing signing setup can be preserved.
  pause
  exit /b 1
)

echo.
echo Installed successfully with adb install -r.
echo Existing app data and downloaded models were preserved by the update path.
pause
