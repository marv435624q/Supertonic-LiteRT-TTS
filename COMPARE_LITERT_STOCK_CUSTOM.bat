@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\compare_litert_stock_custom.ps1" -ProjectRoot "%~dp0"
exit /b %ERRORLEVEL%
