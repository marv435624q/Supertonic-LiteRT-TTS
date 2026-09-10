@echo off
setlocal EnableExtensions EnableDelayedExpansion
set "ROOT=%~dp0"
set "BUILD_DRIVE="
set "DRIVE_MAPPED=0"

rem Build through a short SUBST drive so CMake/Ninja dependency paths stay below Windows MAX_PATH.
rem Do NOT assume Z: is free. If a previous build was interrupted, its SUBST mapping can remain
rem behind. Try several letters and use the first one Windows accepts. Existing user drives and
rem stale mappings are left untouched.
for %%D in (Z Y X W V U T S R Q P O N M L K J I H G) do (
    if not defined BUILD_DRIVE (
        subst %%D: "%ROOT%" >nul 2>&1
        if not errorlevel 1 (
            set "BUILD_DRIVE=%%D:"
            set "DRIVE_MAPPED=1"
        )
    )
)

if not defined BUILD_DRIVE (
    echo [ERROR] Could not create a short SUBST build drive.
    echo         Tried Z: through G:. Existing drives were not modified.
    echo         You can list current SUBST mappings with: subst
    pause
    exit /b 1
)

echo Using short build drive !BUILD_DRIVE! ^-^> %ROOT%
cd /d "!BUILD_DRIVE!\"
if errorlevel 1 (
    echo [ERROR] Could not enter short build drive !BUILD_DRIVE!.
    if "!DRIVE_MAPPED!"=="1" subst !BUILD_DRIVE! /d >nul 2>&1
    pause
    exit /b 1
)

if not defined ANDROID_HOME if exist "%LOCALAPPDATA%\Android\Sdk" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
if not defined ANDROID_SDK_ROOT if defined ANDROID_HOME set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
if not defined ANDROID_HOME (
    echo [ERROR] Android SDK was not found.
    echo Expected: %LOCALAPPDATA%\Android\Sdk or ANDROID_HOME.
    if "!DRIVE_MAPPED!"=="1" subst !BUILD_DRIVE! /d >nul 2>&1
    pause
    exit /b 1
)

rem ================================================================
rem Use a JDK 17 already installed on Windows. This project NEVER downloads JDK.
rem Search order: JAVA17_HOME -^> JAVA_HOME -^> javac.exe on PATH -^> Temurin folder.
rem ================================================================
set "FOUND_JAVA_HOME="

if defined JAVA17_HOME if exist "%JAVA17_HOME%\bin\javac.exe" set "FOUND_JAVA_HOME=%JAVA17_HOME%"
if not defined FOUND_JAVA_HOME if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "FOUND_JAVA_HOME=%JAVA_HOME%"

if not defined FOUND_JAVA_HOME (
    for /f "delims=" %%J in ('where javac.exe 2^>nul') do if not defined FOUND_JAVA_HOME (
        for %%D in ("%%~dpJ..") do set "FOUND_JAVA_HOME=%%~fD"
    )
)

if not defined FOUND_JAVA_HOME (
    for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\jdk-17*") do if not defined FOUND_JAVA_HOME if exist "%%~fD\bin\javac.exe" set "FOUND_JAVA_HOME=%%~fD"
)

if not defined FOUND_JAVA_HOME (
    echo.
    echo [ERROR] JDK was not found.
    echo Install JDK 17 and set JAVA_HOME, then open a new terminal.
    if "!DRIVE_MAPPED!"=="1" subst !BUILD_DRIVE! /d >nul 2>&1
    pause
    exit /b 1
)

set "JAVA_HOME=%FOUND_JAVA_HOME%"
set "PATH=%JAVA_HOME%\bin;%PATH%"

echo JAVA_HOME=%JAVA_HOME%
"%JAVA_HOME%\bin\java.exe" -version
if errorlevel 1 (
    echo [ERROR] Java could not be started from JAVA_HOME.
    if "!DRIVE_MAPPED!"=="1" subst !BUILD_DRIVE! /d >nul 2>&1
    pause
    exit /b 1
)
"%JAVA_HOME%\bin\javac.exe" -version
if errorlevel 1 (
    echo [ERROR] javac could not be started. A full JDK 17 is required, not a JRE.
    if "!DRIVE_MAPPED!"=="1" subst !BUILD_DRIVE! /d >nul 2>&1
    pause
    exit /b 1
)

echo ANDROID_HOME=%ANDROID_HOME%
echo.
echo === Building APK ===
call gradlew.bat --stop >nul 2>&1
call gradlew.bat :app:assembleDebug %GRADLE_EXTRA_ARGS%
if errorlevel 1 (
    echo.
    echo [ERROR] Build failed.
    echo.
    echo For a detailed diagnostic, run:
    echo   gradlew.bat :app:assembleDebug --stacktrace
    if "!DRIVE_MAPPED!"=="1" subst !BUILD_DRIVE! /d >nul 2>&1
    pause
    exit /b 1
)

set "BUILT_APK=%ROOT%app\build\outputs\apk\debug\app-debug.apk"
set "ZIPALIGN_EXE=%ANDROID_HOME%\build-tools\35.0.0\zipalign.exe"
if not exist "%ZIPALIGN_EXE%" (
    echo [ERROR] Android Build Tools 35.0.0 zipalign.exe was not found.
    if "!DRIVE_MAPPED!"=="1" subst !BUILD_DRIVE! /d >nul 2>&1
    pause
    exit /b 1
)
"%ZIPALIGN_EXE%" -c -P 16 4 "%BUILT_APK%"
if errorlevel 1 (
    echo [ERROR] APK failed Android 16 KB ZIP alignment verification.
    if "!DRIVE_MAPPED!"=="1" subst !BUILD_DRIVE! /d >nul 2>&1
    pause
    exit /b 1
)

set "READELF_EXE=%ANDROID_HOME%\ndk\29.0.14206865\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%ROOT%tools\verify_apk_litert.ps1" -ApkPath "%BUILT_APK%" -RuntimePath "%ROOT%litert\arm64-v8a\libLiteRt.so" -ReadElfPath "%READELF_EXE%"
if errorlevel 1 (
    echo [ERROR] APK native runtime verification failed.
    if "!DRIVE_MAPPED!"=="1" subst !BUILD_DRIVE! /d >nul 2>&1
    pause
    exit /b 1
)

echo.
echo BUILD SUCCESSFUL
if not defined APK_OUTPUT_NAME set "APK_OUTPUT_NAME=Supertonic-0.1.38-debug.apk"
copy /y "%BUILT_APK%" "%ROOT%%APK_OUTPUT_NAME%" >nul
echo APK: %ROOT%%APK_OUTPUT_NAME%
echo.
echo Next: run INSTALL_ON_PHONE.bat
if "!DRIVE_MAPPED!"=="1" subst !BUILD_DRIVE! /d >nul 2>&1
pause
exit /b 0
