# SAFE RECOVERY build

This build deliberately restores the last on-device-stable LiteRT delegate-switch ABI (`RemoveAllDelegates + delegate recreate`) while keeping shared packed-weight cache and vocoder lookahead overlap. The experimental targeted-subgraph delegate-reuse ABI is removed from libLiteRt.so in this recovery build.

Build caching is split so external dependency sources are preserved, but all compiled objects are forcibly rebuilt when the custom runtime patch changes.

## FIX10 performance-recovery / Multi-P switch work

This source accumulates the previous FIX10/CMake/Multi-P/profiler/backend fixes and adds:

- LiteRT authoritative `[LITERT-SYNTH-END]` and `[UI-SYNTH-PROFILE]` logging (retained from logger V2).
- FIX9.5 vector finite-scan policy: CPU/external vector output is scanned only on the first/final flow step; experimental native NPU validation still scans every required step.
- Multi-P shared `TfLiteModel` lifetime: the four FlatBuffer models are opened once per engine.
- Multi-P stage-shared XNNPACK packed-weight cache: duration / encoder / vector / vocoder each keep one engine-lifetime `MMapWeightCacheProvider`, so T/L signatures of the same stage/model reuse packed weights instead of generating a cache file per bucket. Providers now use XNNPACK's upstream `LoadOrStartBuild()` → `StopBuild()` lifecycle only. A cache loaded in a later process is never reopened for append. Legacy per-bucket cache files for the exact same model metadata/backend are removed during migration.
- Multi-P targeted signature switching: bucket changes remove delegation only from the previously active SignatureDef subgraph, keep the same `TfLiteInterpreter`, XNNPACK delegate and pthreadpool, then apply that same delegate to the next selected signature and rebuild only its SignatureRunner/tensors. Any targeted switch failure falls back to the previous full Graph recreate path.
- `[LITERT-SIGNATURE-SWITCH]` and extended `[LITERT-INIT-TIMING]` report targeted-removal/apply timings plus cache before/after/growth bytes for cold-bucket validation.
- Multi-P small-signature preload uses the existing post-construction hidden one-step `synthesize("a")` warmup to select the cheapest T32/L32 path and populate the stage-shared heavy packed-weight cache without eager graph construction in the native constructor.
- Vocoder/next-preset overlap prepares the next chunk's encoder+VE signature while the current chunk's vocoder is running; vocoder signature mutation is never performed while that vocoder is in-flight.
- `[LITERT-INIT-TIMING]` splits model-open, XNNPACK delegate creation, interpreter creation, selected-subgraph apply, SignatureRunner creation, AllocateTensors, and total init time.
- Duplicate model/backend preload suppression: programmatic spinner restoration no longer invalidates a `model-ready` preload or launches a redundant `backend-change` warm-up.
- Honest ONNX CPU identity: `CPU_ORT` is persisted/displayed as `CPU (ORT)`; historical ONNX `CPU_XNNPACK` settings migrate to `CPU_ORT`. LiteRT keeps `CPU_XNNPACK`.
- SD690 logger package/version collection no longer relies on shell `grep` quoting; filtering is done in PowerShell after `dumpsys package`.
- `COMPARE_LITERT_STOCK_CUSTOM.bat` downloads the official LiteRT 2.2.0 Maven AAR and compares its ARM64 `libLiteRt.so` with the custom runtime (size, SHA-256, selected symbols and ELF dynamic section when NDK `llvm-readelf` is available).

### Stock runtime vs Multi-P custom runtime

The current JNI library has one `DT_NEEDED` LiteRT runtime for the process. The selected-subgraph API is implemented inside the custom `libLiteRt.so` because it needs LiteRT/TFLite internal interpreter access. Therefore switching between a stock `libLiteRt.so` for fixed models and the custom `libLiteRt.so` for Multi-P on a per-model basis is not safely possible with the current single-JNI linkage. This build keeps the custom runtime patch narrowly scoped: selected-subgraph/remove-delegate C ABI plus an opaque bridge to XNNPACK's unmodified upstream persistent-cache provider. XNNPACK `weight_cache.h/.cc`, compute kernels and CPU dispatch/build selection are not modified. It also keeps exact timing/binary comparison instrumentation instead of silently disabling Multi-P.

## Multi-P shared-cache / signature-switch validation

This source changes the custom `libLiteRt.so` ABI. **Run `BUILD_ALL.bat`** so `BUILD_CUSTOM_LITERT_CMAKE.bat` rebuilds LiteRT v2.2.0 with the required `RemoveAllDelegates` and shared XNNPACK cache-provider bridge symbols. An APK-only rebuild against an older custom `libLiteRt.so` is not sufficient.

Recommended SD690 validation order after installing the build:

1. Install the APK over the existing app with the project's existing signing identity; do **not** clear app data/model downloads. Then start `CAPTURE_SD690_RTF_LOG.bat`.
2. Exercise Multi-P WI8 buckets in this order: `T32/L32 -> T80/L96 -> T48/L64 -> T80/L96`.
3. Confirm `[AUTO-BUCKET][SHARED-XNN-CACHE] providers=4` and `[MODEL-PRELOAD][SMALL-SIGNATURE]`, then check `[LITERT-SIGNATURE-SWITCH] ... interpreter=reused delegate=reused threadpool=reused removal=targeted-subgraph`.
4. `cache_grow` should be large only when a genuinely new packed representation is needed and should be near zero on T80/L96 re-entry.
5. `[AUTO-BUCKET][SIGNATURE-FALLBACK]` means the safe recreate path was used and must be investigated before calling the optimization validated.
6. `[AUTO-BUCKET][XNN-CACHE-MIGRATION]` reports how many old per-bucket cache files/bytes were removed.

The earlier `LoadOrStartBuildForAppend()` extension is removed. Cache ABI v3 uses a new directory, so files produced by the broken v2 resume/append path are ignored automatically. On the first v3 process a missing cache is built through upstream XNNPACK; `StopBuild()` is called before provider destruction. On later processes a valid cache is loaded as finalized, while misses remain process-local according to upstream behavior.

Optimization #3 (delegate/threadpool reuse) and #4 (vocoder/next-preset overlap) are **not** part of this source yet.

## SD690 logger v2

Standalone **Generate** actions now emit `[UI-SYNTH-PROFILE]` with the exact applied model/backend/steps/threads/chunk value, native total time, native audio duration, native RTF, and the complete engine profile. The capture script writes these to `UI_SYNTH_PROFILES.txt`. Native LiteRT also emits `[LITERT-SYNTH-END]` after its measured total is finalized. This fixes the previous logger gap where only Android TTS-service `SYNTH_PROFILE` lines were selected. Raw `settings_*.xml` may still contain the retired legacy `chunk_cap` preference; effective LiteRT chunking is reported by the profile marker (`chunk=0` means Auto).

## SD690 test loggers

- `CAPTURE_SD690_RTF_LOG.bat`: low-interference capture for final RTF comparison.
- `CAPTURE_SD690_FULL_DIAG.bat`: adds 2-second CPU-frequency / thermal telemetry for throttling diagnosis.
- Both save logs before their final prompt, create a ZIP, write `LAST_SD690_LOG.txt`,
  capture model/backend settings before+after, and automatically pull Deep Profiler
  files when the profiler is enabled.

## Backend selection persistence / SD690 UI policy

- ONNX `CPU` is now strictly user-selected ORT CPU EP. The old automatic
  CPU-vs-CPU-XNN benchmark/cache that could silently change the spinner has
  been removed.
- Backend preferences are persisted per TTS model instead of one global value,
  so ONNX FP32 / ONNX W8A16 / each LiteRT model keep independent choices.
- ONNX W8A16 no longer defaults/promotes itself to NPU.
- SM6350/lito (Snapdragon 690) never exposes the NPU backend or QNN cache
  pre-generation menu. A stale persisted NPU choice is normalized to CPU.
- The underlying QNN/HTA implementation is retained for source compatibility
  and other supported Qualcomm devices; it is simply not user-selectable on
  SM6350.

## Android native build fix

- Fixes the pre-generation worker constructor after Deep Profiler restoration:
  the new `enable_deep_profiler` constructor slot is explicitly passed as
  `false`, matching REV34 behavior for worker engines.
- Restricts AGP externalNativeBuild to `speech_android` only. The Kokoro/tensor
  benchmark executables are development tools and are no longer compiled or
  linked during APK builds.

## Model picker display order

The user-facing model picker is ordered and labeled as:

1. ONNX FP32
2. ONNX W8A16
3. LiteRT FP32
4. LiteRT W8-AFP32
5. LiteRT Multi-P
6. LiteRT Multi-P W8-AFP32

Only display labels/order changed. Model enums, storage directories, download URLs,
and compatibility identities are unchanged.

## Deep Profiler (restored from REV34 FIX9.5)

The Advanced menu again includes `Deep Profiler: OFF/ON`. It is **OFF by default** and should stay OFF for normal RTF measurements because profiling adds overhead. Enabling it invalidates/recreates the synthesizer and records diagnostic output under `cache/accelerator_cache/perf_profiles`:

- ONNX Runtime node profile JSON
- Qualcomm QNN `optrace` profiler output when QNN is used
- LiteRT graph-invoke CSV for fixed XNNPACK and MultiPreset selected-signature XNNPACK paths

After one diagnostic synthesis, run `PULL_PERF_PROFILES.bat`; it pulls the profile directory and runs `tools/analyze_deep_profiles.py`. `CLEAR_PERF_PROFILES.bat` clears old profiler files.

The obsolete `HTA stage probe` diagnostic menu/API has been removed. This does **not** remove the SM6350 HTA/QNN execution backend itself.

## Local custom LiteRT auto-build

`BUILD_ALL.bat` now builds the selected-subgraph LiteRT runtime automatically when
`litert/SELECTED_SUBGRAPH_RUNTIME.txt` is absent. On Windows this step runs inside
WSL2, downloads the Linux Android NDK r27c (`27.2.12479018`) into
`~/.cache/SupertonicLiteRT`, builds upstream LiteRT 2.2.0 through the CMake
Android Release path, and writes the arm64-v8a/x86_64 `libLiteRt.so` files back
under `./litert/`.

The Windows-installed NDK is intentionally not reused from WSL because it
contains Windows-host toolchain binaries. All heavyweight LiteRT build state is
project-folder independent under `~/.cache/SupertonicLiteRT`:

- `runtime-cache/<key>/` stores completed arm64-v8a/x86_64 `libLiteRt.so` files keyed by the exact LiteRT patch/toolchain identity. A newly extracted source folder with the same custom LiteRT patch restores these files and skips CMake/TensorFlow/XNNPACK downloads entirely.
- `litert-selected-subgraph-cmake/LiteRT/litert/cmake_build_android_*` keeps the CMake object and dependency trees. If the custom runtime patch changes and a rebuild is required, these trees are reused instead of deleting and re-downloading TensorFlow/XNNPACK/KleidiAI/Abseil/FlatBuffers.
- `LITERT_FORCE_REBUILD=1` bypasses only the completed-runtime cache. `LITERT_FORCE_CLEAN=1` is the explicit emergency switch that deletes an ABI CMake tree; normal builds never do this.

So extracting each cumulative source ZIP into a new Windows folder no longer
causes the heavyweight LiteRT dependencies to be downloaded again.

# Supertonic LiteRT + ONNX Android TTS

An Android text-to-speech engine and standalone test app for Supertonic-3. The current source is version `0.1.38` (`versionCode 46`).

## Runtime matrix

| Model | Runtime | Available backends |
| --- | --- | --- |
| Supertonic-3 LiteRT | Native LiteRT 2.2 | CPU/XNNPACK |
| Supertonic-3 LiteRT WI8-AFP32 | Native LiteRT 2.2 | CPU/XNNPACK |
| Supertonic-3 LiteRT MultiPreset GELU | Native LiteRT 2.2 selected-subgraph | CPU/XNNPACK |
| Supertonic-3 LiteRT MultiPreset GELU WI8-AFP32 | Native LiteRT 2.2 selected-subgraph | CPU/XNNPACK |
| Supertonic-3 FP32 ONNX | ONNX Runtime 1.28 | CPU, CPU/XNNPACK, Qualcomm QNN HTP/HTA |
| Supertonic-3 W8A16 QDQ | ONNX Runtime 1.28 | CPU duration + Qualcomm QNN HTP/HTA encoder, vector estimator and vocoder |

The old broken ONNX FP16 path and the LiteRT GPU/NNAPI/NPU experiment are not part of the active app. Existing FP16 selections are migrated to the original FP32 model. Custom Supertonic voice-style JSON files are shared by all active variants.

## Build on Windows

Install Git for Windows, JDK 17, Android SDK platform 35, CMake 3.22.1, and NDK `29.0.14206865`. The SM6350 HTA path additionally requires the full QAIRT `2.44.0.260225` SDK. Set `SUPERTONIC_QAIRT_ROOT`, `QNN_SDK_ROOT`, or `QAIRT_SDK_ROOT` if it is not at the auto-detected path.

From the repository root, run:

```bat
BUILD_ALL.bat
```

This verifies the source tree, builds the custom ORT 1.28.0 QNN+XNNPACK native core, downloads and verifies LiteRT/QNN runtimes, copies the local HTA libraries, and assembles the debug APK. `build_apk.bat` can be used after runtime setup for a repeat build.

The custom LiteRT build enforces 16 KB ELF LOAD alignment for Android 15+ devices. The APK verification also checks `libLiteRt.so`, `libspeech_android.so`, `libc++_shared.so`, the selected-subgraph symbols, the LiteRT SONAME/DT_NEEDED contract, and 16 KB ZIP alignment. LiteRT libraries are loaded explicitly before the JNI bridge so a linker failure remains retryable and reports the original `dlopen` reason.

The APK is written to `app\build\outputs\apk\debug\app-debug.apk`. Run `INSTALL_ON_PHONE.bat` to install it with `adb install -r`; the script checks the signing certificate before replacing an existing installation so app data and downloaded models are preserved.

## Diagnostic builds

- `BUILD_REV33.9_REV30_ORT_CPU_AB.bat` builds the exact REV30 ORT native core for the CPU A/B comparison.
- `BUILD_REV33.10_REV40_CPU_REFERENCE.bat` builds the exact REV40 CPU-reference AAR and SessionOptions policy used for the SM6350/HTA comparison.
- `RUN_REV33.9_ONNX_CPU_AB_LOG_CAPTURE.bat` and `RUN_REV33.10_REV40_CPU_REFERENCE_LOG.bat` capture the corresponding device logs.

The diagnostic builds are intentionally separate from the normal QNN+XNNPACK build. They do not change the model files or the LiteRT source.

## Repository layout

- `app/` — Android application and TTS service.
- `sdk/` — Kotlin/JNI bridge and Android library.
- `speech-core/` — native LiteRT/audio/model implementation.
- `tools/` — custom ORT build helper.
- `docs/` — current custom-voice, ORT, and CPU-reference notes.

Model weights are downloaded by the app from the configured Hugging Face mirrors; they are not committed to this repository. See `docs/custom-voice-and-regex.md` for custom voices and pronunciation rules.

## Third-party code

See `THIRD-PARTY-SONIC-NOTICE.txt` and the notices in the bundled `speech-core/third_party` sources.

## FIX10 CMake LiteRT performance rebuild

This source keeps the MultiPreset selected-signature ABI but replaces the active
custom LiteRT build route. `tools/build_litert_2_2_selected_subgraph.sh` now
invokes `tools/build_litert_2_2_selected_subgraph_cmake.sh`, which starts from
LiteRT `v2.2.0` and uses the upstream Android CMake Release path. The patch is
limited to the C API declaration, one selected-subgraph bridge source, and
attaching that source to the existing `libLiteRt.so` CMake target. It does not
patch LiteRT Android config settings, TFLite platform selects, XNNPACK build
configuration, profiling BUILD files, special rules, or WORKSPACE toolchain
selection.

The previous Bazel-patched runtime and builder are retained only for A/B:

- `litert-legacy-bazel/`
- `tools/build_litert_2_2_selected_subgraph_bazel_legacy.sh`

The active `litert/` directory intentionally contains no legacy `libLiteRt.so`.
Build the CMake Release runtime first, or use the GitHub Actions artifact
`Custom-LiteRT-2.2-Selected-Subgraph-CMake-Release`.

The JNI runtime setter also preserves `chunkCap=0` as Auto instead of clamping
it to 24. This fixes the long-text chunking regression without restoring the
old fixed 64-character policy.


### Shared-cache/signature-switch build fix

LiteRT 2.2.0 keeps both `Interpreter::RemoveAllDelegates()` and `Subgraph::RemoveAllDelegates()` private. The targeted path now exposes only a tiny public in-class `Subgraph::SupertonicRemoveAllDelegatesForSignatureSwitch()` wrapper and keeps the actual upstream delegate-removal implementation unchanged. Local WSL rebuilds also
preserve the expensive CMake object trees across retries.
