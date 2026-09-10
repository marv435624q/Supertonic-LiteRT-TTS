@echo off
setlocal EnableExtensions
set "ROOT=%~dp0"
cd /d "%ROOT%"
echo ============================================================
echo  REV33.10 exact SM6350/HTA REV40 CPU reference A/B
echo  CPU XNN is intentionally hidden in this diagnostic build.
echo ============================================================
set "AAR=%ROOT%sdk\libs\onnxruntime-android-qnn-1.28.0-hta-rev40-cpuref.aar"
if not exist "%AAR%" (
  echo [ERROR] Missing REV40 CPU reference AAR: %AAR%
  exit /b 1
)
for /f %%H in ('powershell -NoProfile -Command "(Get-FileHash -Algorithm SHA256 -LiteralPath ''%AAR%'').Hash.ToLowerInvariant()"') do set "SHA=%%H"
if /I not "%SHA%"=="cd37083d5d1d12ccb08ef7bf23166269c507f078dd07c5da386a601149c1f1f2" (
  echo [ERROR] REV40 CPU reference AAR checksum mismatch
  echo expected cd37083d5d1d12ccb08ef7bf23166269c507f078dd07c5da386a601149c1f1f2
  echo actual   %SHA%
  exit /b 1
)
set "GRADLE_EXTRA_ARGS=-PsupertonicOrtRev40CpuRef=true"
set "APK_OUTPUT_NAME=Supertonic-REV33.10-REV40-CPU-REFERENCE-debug.apk"
call "%ROOT%build_apk.bat"
exit /b %errorlevel%
