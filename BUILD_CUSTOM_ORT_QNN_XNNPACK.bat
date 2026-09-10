@echo off
setlocal EnableExtensions
cd /d "%~dp0"
echo ============================================================
echo  ORT 1.28.0 Android arm64: QNN/HTA + XNNPACK native rebuild
echo ============================================================
echo.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\build_custom_ort_qnn_xnnpack.ps1" %*
if errorlevel 1 (
  echo.
  echo [ERROR] Custom ORT QNN+XNNPACK build failed.
  echo The PowerShell output above prints the authoritative Native build log path.
  pause
  exit /b 1
)
echo.
echo [OK] Custom ORT QNN+XNNPACK AAR is ready.
exit /b 0
