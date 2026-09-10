@echo off
setlocal EnableExtensions
set "ROOT=%~dp0"
echo Starting SD690 RTF logger...
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%ROOT%tools\capture_sd690_test.ps1" -Mode RTF
set "RC=%ERRORLEVEL%"
echo.
echo Recorder exit code: %RC%
echo Last output directory is recorded in:
echo   %ROOT%LAST_SD690_LOG.txt
exit /b %RC%
