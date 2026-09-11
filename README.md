# Supertonic LiteRT TTS for Android

Android-focused Supertonic TTS implementation with optimized **LiteRT / XNNPACK** execution, optional **ONNX Runtime / Qualcomm QNN** paths, Android system TTS integration, and extensive runtime diagnostics.

This repository is primarily focused on making Supertonic practical for **fast local TTS inference on Android devices**, especially CPU-oriented mobile and e-reader use cases.

> **Status:** Experimental / development-oriented.
> This repository contains custom runtime changes, diagnostic code, and device-specific optimization work. Some paths are intended for benchmarking and development rather than general production use.

---

## Features

* Android **system TTS engine** integration
* Fully local speech synthesis
* LiteRT-based Supertonic inference
* XNNPACK CPU acceleration
* LiteRT **Selected-Subgraph** runtime support
* Multi-model / multi-preset runtime work
* Custom LiteRT builds
* ONNX Runtime fallback / comparison path
* Experimental Qualcomm **QNN / HTP / HTA** runtime work
* Persistent XNNPACK cache handling
* Runtime preload and delegate reuse optimizations
* Dynamic Depthwise reference / compatibility fixes
* Custom voice support
* Pronunciation replacement rules
* Speech speed processing
* Performance profiling and A/B comparison tools
* Android device diagnostic scripts

The project is designed to make it easy to compare runtime implementations and investigate regressions in startup time, inference time, delegate behavior, caching, and model execution.

---

## Project Structure

```text
.
├── app/
│   └── Android TTS application / settings UI
│
├── sdk/
│   ├── Android TTS service
│   ├── Supertonic runners
│   ├── JNI bridge
│   ├── pronunciation rules
│   └── audio processing
│
├── speech-core/
│   └── Native speech / LiteRT support code
│
├── third_party/
│   ├── litert/
│   │   └── LiteRT 2.2 Selected-Subgraph patch
│   └── onnxruntime/
│       └── ORT QNN / HTA patch
│
├── litert-legacy-bazel/
│   └── Legacy prebuilt LiteRT runtime
│
├── tools/
│   ├── LiteRT build scripts
│   ├── ORT/QNN build scripts
│   ├── profiling tools
│   └── APK/runtime verification tools
│
├── docs/
│   └── Runtime, benchmark and build notes
│
├── BUILD_ALL.bat
├── BUILD_CUSTOM_LITERT_CMAKE.bat
├── BUILD_CUSTOM_ORT_QNN_XNNPACK.bat
├── INSTALL_ON_PHONE.bat
├── VERIFY_SOURCE_TREE.bat
└── VERIFY_TTS_ENGINE.bat
```

---

## Runtime Paths

### LiteRT + XNNPACK

This is the main optimization path of the project.

The repository contains work related to:

* LiteRT 2.2
* Selected-Subgraph execution
* XNNPACK delegation
* custom CMake builds
* persistent cache initialization / reload
* delegate reuse
* preload behavior
* Dynamic Depthwise compatibility
* profiling of remaining non-delegated operations

Relevant files include:

```text
third_party/litert/LiteRT-2.2.0-selected-subgraph-capi.patch
tools/build_litert_2_2_selected_subgraph.sh
tools/build_litert_2_2_selected_subgraph_cmake.sh
tools/build_litert_2_2_selected_subgraph_wsl.sh
REMAINING_XNNPACK_OPS_DIAG.txt
XNNPACK_PERSISTENT_CACHE_PREINIT_FIX.txt
XNNPACK_PERSISTENT_CACHE_RELOAD_FIX.txt
DYNAMIC_DEPTHWISE_V3_REFERENCE_NOTES.txt
```

### ONNX Runtime

An ONNX Runtime path is also retained for comparison, fallback, and runtime experiments.

The project includes CPU reference / A-B testing infrastructure and custom ONNX Runtime build support.

See:

```text
docs/custom-ort-build.md
docs/ort-runtime-ab.md
docs/cpu-reference-ab.md
BUILD_REV33.10_REV40_CPU_REFERENCE.bat
```

### Qualcomm QNN / HTA

Experimental Qualcomm acceleration work is included through a custom ONNX Runtime build.

```text
third_party/onnxruntime/ORT-1.28.0-QNN-HTA.patch
tools/build_custom_ort_qnn_xnnpack.ps1
BUILD_CUSTOM_ORT_QNN_XNNPACK.bat
```

This path is highly dependent on SoC, Qualcomm runtime availability, QNN version, model partitioning, and device firmware.

It should be treated as an **experimental backend**, not as a universally supported Android acceleration path.

---

## Android TTS Engine

The application registers itself as an Android TTS engine and provides the standard Android TTS service interface.

Important components:

```text
app/src/main/
sdk/src/main/kotlin/audio/soniqo/speech/service/
```

Core runtime classes include:

```text
ModelManager.kt
NativeBridge.kt
OnnxSupertonicRunner.kt
SupertonicRunnerBridge.kt
SpeechConfig.kt
TtsSettings.kt
SpeechTextToSpeechService.kt
```

After installing the APK, select the engine from Android's **Text-to-speech output** settings.

---

## Building

### Normal Android build

On Windows:

```powershell
.\build_apk.bat
```

or build directly with Gradle:

```powershell
.\gradlew.bat :app:assembleDebug
```

For the complete repository build path:

```powershell
.\BUILD_ALL.bat
```

### Install on a connected Android device

Enable USB debugging and verify that the device is visible:

```powershell
adb devices
```

Then:

```powershell
.\INSTALL_ON_PHONE.bat
```

---

## Rebuilding the Custom LiteRT Runtime

The repository contains several build paths because the custom runtime has been tested through both CMake and legacy Bazel-based setups.

Primary scripts:

```text
BUILD_CUSTOM_LITERT_CMAKE.bat

tools/build_litert_2_2_selected_subgraph.sh
tools/build_litert_2_2_selected_subgraph_cmake.sh
tools/build_litert_2_2_selected_subgraph_wsl.sh
tools/build_litert_2_2_selected_subgraph_bazel_legacy.sh
```

For implementation notes, see:

```text
docs/litert-cmake-perf-rebuild.md
FIX10_CMAKE_LITERT_PERF_NOTES.txt
```

---

## Verification

Before testing a modified build:

```powershell
.\VERIFY_SOURCE_TREE.bat
.\VERIFY_TTS_ENGINE.bat
```

Additional verification utilities are available under `tools/`:

```text
verify_apk_litert.ps1
verify_dynamic_depthwise_v3_reference_source.py
verify_elf_16k.py
verify_fix10_cmake_litert_source.sh
```

These are useful when checking whether the APK actually contains the expected custom runtime rather than silently falling back to a stock library.

---

