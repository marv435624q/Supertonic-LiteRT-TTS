@echo off
setlocal EnableExtensions
set "ROOT=%~dp0"
set "FAILED=0"

echo === Source tree preflight ===
for %%F in (
  "speech-core\src\models\litert\litert_silero_vad.cpp"
  "speech-core\src\models\litert\litert_supertonic_tts.cpp"
  "speech-core\src\models\litert\supertonic_tokenizer.cpp"
  "speech-core\src\models\litert\supertonic_c.cpp"
  "speech-core\examples\litert\kokoro_tts_bench.cpp"
  "speech-core\examples\litert\tensor_bench.cpp"
  "speech-core\CMakeLists.txt"
  "sdk\src\main\cpp\CMakeLists.txt"
  "sdk\src\main\cpp\jni_bridge.cpp"
  "sdk\src\main\kotlin\audio\soniqo\speech\SpeechConfig.kt"
  "sdk\src\main\kotlin\audio\soniqo\speech\OnnxSupertonicRunner.kt"
  "setup.sh"
  "setup_rev30_ort_cpu_ab.sh"
  "sdk\libs\onnxruntime-android-qnn-1.28.0-hta.aar"
  "third_party\onnxruntime\ORT-1.28.0-QNN-HTA.patch"
  "BUILD_CUSTOM_ORT_QNN_XNNPACK.bat"
  "tools\build_custom_ort_qnn_xnnpack.ps1"
  "tools\build_litert_2_2_selected_subgraph.sh"
  "tools\build_litert_2_2_selected_subgraph_cmake.sh"
  "tools\build_litert_2_2_selected_subgraph_wsl.sh"
  "BUILD_CUSTOM_LITERT_CMAKE.bat"
  "tools\build_litert_2_2_selected_subgraph_bazel_legacy.sh"
  "tools\verify_elf_16k.py"
  "PULL_PERF_PROFILES.bat"
  "CLEAR_PERF_PROFILES.bat"
  "tools\analyze_deep_profiles.py"
) do (
  if not exist "%ROOT%%%~F" (
    echo [ERROR] Missing source file: %%~F
    set "FAILED=1"
  )
)


findstr /C:"Deep Profiler: ON" "%ROOT%app\src\main\kotlin\com\supertonic\tts\MainActivity.kt" >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Deep Profiler menu restore is missing.
  set "FAILED=1"
)
findstr /C:"enableDeepProfiler" "%ROOT%sdk\src\main\kotlin\audio\soniqo\speech\SpeechConfig.kt" >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Deep Profiler SDK wiring is missing.
  set "FAILED=1"
)
findstr /I /C:"HTA stage probe" "%ROOT%app\src\main\kotlin\com\supertonic\tts\MainActivity.kt" "%ROOT%sdk\src\main\kotlin\audio\soniqo\speech\SpeechConfig.kt" "%ROOT%sdk\src\main\kotlin\audio\soniqo\speech\OnnxSupertonicRunner.kt" >nul 2>&1
if not errorlevel 1 (
  echo [ERROR] Obsolete HTA stage probe diagnostic code is still present.
  set "FAILED=1"
)

if "%FAILED%"=="1" (
  echo.
  echo [ERROR] Source ZIP is incomplete. Build was not started.
  exit /b 1
)

echo Source tree OK.
exit /b 0
