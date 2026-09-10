@echo off
setlocal EnableExtensions EnableDelayedExpansion
set "ROOT=%~dp0"
set "ROOT_NO_SLASH=%ROOT:~0,-1%"

echo ============================================================
echo  LiteRT 2.2 selected-subgraph CMake Release local builder
echo ============================================================

rem Fast path: do not rebuild only if the local runtime is the exact V3
rem hybrid-Depthwise build. Older SAFE binaries do not contain the fallback fix.
set "MARKER=%ROOT%litert\SELECTED_SUBGRAPH_RUNTIME.txt"
set "RUNTIME_VALID=1"
if not exist "%MARKER%" set "RUNTIME_VALID=0"
if "%RUNTIME_VALID%"=="1" (
  findstr /C:"Build system: upstream CMake Android cross-compile path" "%MARKER%" >nul 2>&1
  if errorlevel 1 set "RUNTIME_VALID=0"
)
if "%RUNTIME_VALID%"=="1" (
  findstr /C:"Build type: Release (-O3, NDEBUG)" "%MARKER%" >nul 2>&1
  if errorlevel 1 set "RUNTIME_VALID=0"
)
if "%RUNTIME_VALID%"=="1" (
  findstr /C:"Runtime profile: SAFE-RECOVERY-NO-TARGETED-REMOVE" "%MARKER%" >nul 2>&1
  if errorlevel 1 set "RUNTIME_VALID=0"
)
if "%RUNTIME_VALID%"=="1" (
  findstr /C:"Dynamic Depthwise patch: dedicated-subgraph-QD8-F32-QC8W-v3" "%MARKER%" >nul 2>&1
  if errorlevel 1 set "RUNTIME_VALID=0"
)
if "%RUNTIME_VALID%"=="1" (
  findstr /C:"Persistent cache preload: xnn-initialize-before-load" "%MARKER%" >nul 2>&1
  if errorlevel 1 set "RUNTIME_VALID=0"
)
if "%RUNTIME_VALID%"=="1" (
  findstr /C:"XNNPACK revision: ae746db8255aa93704012a98b4b030eefd17357d" "%MARKER%" >nul 2>&1
  if errorlevel 1 set "RUNTIME_VALID=0"
)
if "%RUNTIME_VALID%"=="1" (
  findstr /C:"SupertonicXnnpackDynamicDepthwisePatchVersion" "%MARKER%" >nul 2>&1
  if errorlevel 1 set "RUNTIME_VALID=0"
)
if "%RUNTIME_VALID%"=="1" if exist "%ROOT%litert\arm64-v8a\libLiteRt.so" if exist "%ROOT%litert\x86_64\libLiteRt.so" (
  echo [OK] Validated LiteRT 2.2 SAFE + dynamic Depthwise V3 + cache-init runtime already exists.
  exit /b 0
)

where wsl.exe >nul 2>&1
if errorlevel 1 (
  echo [ERROR] WSL is required for the local LiteRT CMake build.
  echo         Install/enable WSL2, or place the CI-built runtime under .\litert\.
  exit /b 1
)

set "WSL_ROOT="
for /f "usebackq delims=" %%I in (`wsl.exe wslpath -a "%ROOT_NO_SLASH%" 2^>nul`) do (
  if not defined WSL_ROOT set "WSL_ROOT=%%I"
)
if not defined WSL_ROOT (
  echo [ERROR] Could not translate project path into WSL.
  wsl.exe --status
  exit /b 1
)

echo Windows root: %ROOT_NO_SLASH%
echo WSL root    : !WSL_ROOT!
echo.
echo Network downloads are DISABLED by default.
echo Existing WSL cache under ~/.cache/SupertonicLiteRT is reused.
echo If a required first-time bootstrap cache is missing, the build FAILS instead of downloading.
echo Set SUPERTONIC_LITERT_ALLOW_DOWNLOADS=1 manually only when you explicitly want a bootstrap download.
echo.

wsl.exe bash -lc "cd '!WSL_ROOT!' && chmod +x tools/build_litert_2_2_selected_subgraph*.sh && ./tools/build_litert_2_2_selected_subgraph_wsl.sh"
if errorlevel 1 (
  echo.
  echo [ERROR] Custom LiteRT CMake Release build failed.
  echo         The WSL build cache is under ~/.cache/SupertonicLiteRT.
  exit /b 1
)

if not exist "%ROOT%litert\SELECTED_SUBGRAPH_RUNTIME.txt" (
  echo [ERROR] LiteRT build returned success but marker was not produced.
  exit /b 1
)
if not exist "%ROOT%litert\arm64-v8a\libLiteRt.so" (
  echo [ERROR] arm64-v8a libLiteRt.so was not produced.
  exit /b 1
)
if not exist "%ROOT%litert\x86_64\libLiteRt.so" (
  echo [ERROR] x86_64 libLiteRt.so was not produced.
  exit /b 1
)

echo.
echo [SUCCESS] Custom LiteRT CMake Release runtime is ready.
exit /b 0
