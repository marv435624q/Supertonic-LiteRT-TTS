@echo off
setlocal EnableExtensions
set "ROOT=%~dp0"
cd /d "%ROOT%"

call VERIFY_SOURCE_TREE.bat
if errorlevel 1 (
  pause
  exit /b 1
)

echo === 1/4: preparing custom LiteRT 2.2 CMake Release ===
call "%ROOT%BUILD_CUSTOM_LITERT_CMAKE.bat"
if errorlevel 1 (
  echo [ERROR] Custom LiteRT CMake Release build failed.
  pause
  exit /b 1
)

echo.
echo === 2/4: preparing custom ORT QNN + XNNPACK ===
call "%ROOT%BUILD_CUSTOM_ORT_QNN_XNNPACK.bat"
if errorlevel 1 (
  echo [ERROR] Custom ORT QNN+XNNPACK build failed.
  pause
  exit /b 1
)

echo.
echo === 3/4: preparing native runtimes ===

set "BASH_EXE="
if exist "%ProgramFiles%\Git\bin\bash.exe" set "BASH_EXE=%ProgramFiles%\Git\bin\bash.exe"
if not defined BASH_EXE if exist "%ProgramFiles%\Git\usr\bin\bash.exe" set "BASH_EXE=%ProgramFiles%\Git\usr\bin\bash.exe"
if not defined BASH_EXE if defined ProgramFiles(x86) if exist "%ProgramFiles(x86)%\Git\bin\bash.exe" set "BASH_EXE=%ProgramFiles(x86)%\Git\bin\bash.exe"
if not defined BASH_EXE if defined ProgramFiles(x86) if exist "%ProgramFiles(x86)%\Git\usr\bin\bash.exe" set "BASH_EXE=%ProgramFiles(x86)%\Git\usr\bin\bash.exe"

if not defined BASH_EXE (
  echo [ERROR] Git for Windows bash.exe could not be found.
  echo Please install Git for Windows.
  pause
  exit /b 1
)

echo Using Git Bash: %BASH_EXE%
"%BASH_EXE%" setup.sh
if errorlevel 1 (
  echo [ERROR] setup.sh failed.
  pause
  exit /b 1
)

echo.
echo === Android SDK setup ===
if not defined ANDROID_HOME if exist "%LOCALAPPDATA%\Android\Sdk" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
if not defined ANDROID_SDK_ROOT if defined ANDROID_HOME set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
if not defined ANDROID_HOME (
  echo [ERROR] Android SDK not found. Expected %%LOCALAPPDATA%%\Android\Sdk or ANDROID_HOME.
  pause
  exit /b 1
)
echo ANDROID_HOME=%ANDROID_HOME%
>local.properties echo sdk.dir=%ANDROID_HOME:\=/%

echo === 4/4: building APK ===
call build_apk.bat
exit /b %errorlevel%
