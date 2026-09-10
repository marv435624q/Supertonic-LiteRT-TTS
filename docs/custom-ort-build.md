# REV32 — Custom ORT QNN + XNNPACK CPU engine fix

## Why REV32 exists
REV31 called `SessionOptions.addXnnpack()` but the bundled custom ORT 1.28.0 native core had been built with QNN/HTA support and **without the XNNPACK execution provider**. The Java API existed, so compile-time inspection was not sufficient. On-device ORT correctly failed with `XNNPACK execution provider is not supported in this build`.

REV32 fixes the actual native runtime rather than silently falling back to the old CPU EP.

## What changed
- Builds ONNX Runtime **v1.28.0** for Android arm64-v8a with both:
  - `--use_qnn static_lib`
  - `--use_xnnpack`
- Applies the existing REV30 `ORT-1.28.0-QNN-HTA.patch` before building so SM6350/HTA behavior is retained.
- Reuses the validated REV30 custom AAR's `classes.jar` and `libonnxruntime4j_jni.so`, replacing only the same-version `jni/arm64-v8a/libonnxruntime.so` with the QNN+XNNPACK build.
- Produces `sdk/libs/onnxruntime-android-qnn-xnnpack-1.28.0-hta.aar` plus SHA-256/build-info files.
- `sdk/build.gradle.kts` now consumes that generated AAR.
- `BUILD_ALL.bat` builds the custom ORT first, then performs LiteRT/QNN runtime setup, then assembles the APK.
- Before a CPU_XNNPACK or QNN session is created, the app checks `OrtEnvironment.getAvailableProviders()` and logs `[ORT-PROVIDERS] ... verified=1`. This is the definitive check that the native EP is actually compiled in.

## One-click build
Run:

    BUILD_ALL.bat

The first stage invokes:

    BUILD_CUSTOM_ORT_QNN_XNNPACK.bat

The custom ORT builder looks for:
- Android SDK: `ANDROID_SDK_ROOT`, `ANDROID_HOME`, or `%LOCALAPPDATA%\Android\Sdk`
- Android NDK: prefers `29.0.14206865`, otherwise an installed NDK
- QAIRT 2.44 full SDK: `SUPERTONIC_QAIRT_ROOT`, `QNN_SDK_ROOT`, `QAIRT_SDK_ROOT`, or `C:\platform-tools\qairt-2.44\qairt\2.44.0.260225`
- Git for Windows
- Windows Python 3

CMake >=3.28 and Ninja are installed into an isolated cache venv. ORT v1.28.0 is cloned into `%LOCALAPPDATA%\SupertonicLiteRT\ort128-qnn-xnnpack-hta` and built through a short SUBST drive to reduce Windows path-length failures.

Force a clean custom ORT rebuild with:

    BUILD_CUSTOM_ORT_QNN_XNNPACK.bat -Force

## Expected runtime log
For ONNX CPU_XNNPACK, a working REV32 build should contain lines similar to:

    [ORT-PROVIDERS] required=XNNPACK ... verified=1
    [ORT-XNNPACK] graph=duration registered=1 ...

For QNN/NPU:

    [ORT-PROVIDERS] required=QNN ... verified=1

If the new native library somehow does not contain the requested EP, REV32 fails with an explicit provider-list error before session creation instead of claiming XNNPACK is active.

## Files involved

- `BUILD_ALL.bat` and `BUILD_CUSTOM_ORT_QNN_XNNPACK.bat`
- `setup.sh` and `VERIFY_SOURCE_TREE.bat`
- `sdk/build.gradle.kts` and `sdk/src/main/kotlin/audio/soniqo/speech/OnnxSupertonicRunner.kt`
- `tools/build_custom_ort_qnn_xnnpack.ps1`
- `third_party/onnxruntime/ORT-1.28.0-QNN-HTA.patch`

## Validation performed in the packaged source
- `bash -n setup.sh` passes.
- The original validated custom AAR is present and structurally valid.
- `javap` against its `classes.jar` confirms `OrtEnvironment.getAvailableProviders()`, `OrtProvider.XNNPACK`, `OrtProvider.QNN`, `SessionOptions.addXnnpack(...)`, `addQnn(...)`, and the thread/memory APIs used by REV32.
- The custom builder verifies the generated ORT CMake cache contains `onnxruntime_USE_QNN=ON`, `onnxruntime_BUILD_QNN_EP_STATIC_LIB=ON`, and `onnxruntime_USE_XNNPACK=ON` before repacking the AAR.
- The builder writes SHA-256 and build-info metadata; `setup.sh` refuses an AAR that does not match those records.

## Environment limitation here
The new QNN+XNNPACK `libonnxruntime.so` itself is **not precompiled in this package**, because this execution environment does not contain the user's Android SDK/NDK and full QAIRT 2.44 SDK. `BUILD_ALL.bat` performs that native build on the Windows machine where those SDKs are installed.
