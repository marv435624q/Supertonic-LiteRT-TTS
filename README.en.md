# Supertonic LiteRT TTS for Android

[한국어](README.md) | **English**

Android system TTS engine and standalone test app for **Supertonic-3**, focused on fast fully local synthesis with optimized **LiteRT / XNNPACK** CPU execution and **Qualcomm QNN NPU acceleration** on supported Snapdragon devices.

The project combines multiple Supertonic-3 model variants, a LiteRT runtime path based on and extended from **[Soniqo speech-core](https://github.com/soniqo/speech-core)**, a separate ONNX Runtime path, custom voice import, long-text streaming, pronunciation rules, QNN context caching, and benchmark/diagnostic tools.

> **Status:** This is a usable Android TTS app, not just a prototype. Both the LiteRT CPU path and supported Qualcomm QNN NPU path are intended for normal use; performance tuning, device compatibility, and diagnostic features continue to improve.

---

## Highlights

- Android **system TextToSpeech engine** integration
- Standalone synthesis, WAV export, and audio sharing
- Six selectable Supertonic-3 model variants
- Native **LiteRT 2.2 + XNNPACK** CPU execution
- LiteRT execution/orchestration based on **Soniqo `speech-core`**, extended for this project
- Separate **ONNX Runtime 1.28** CPU and Qualcomm QNN paths
- **Qualcomm NPU acceleration** on supported modern Snapdragon devices
- QNN context/cache pre-generation to reduce NPU cold-start overhead
- Multi-P models with automatic static T/L bucket selection
- 1–64 flow-matching steps; default **4 steps**
- 1–8 CPU threads in the UI; default **4 threads**
- 0.25×–3.00× speech-rate control using post-synthesis Sonic processing
- 10 built-in voices: `F1`–`F5`, `M1`–`M5`
- Importable **custom Supertonic-3 voice JSONs**
- Shared custom voices across all six model variants
- Pronunciation correction and regex-based replacement rules
- Optional long-text CPU pre-generation
- SHA-256 model verification, runtime profiling, and benchmark tools

## Requirements / runtime versions

| Item | Current project setting |
| --- | --- |
| Minimum Android | Android 8.0 / API 26 |
| Target Android | API 34 |
| Compile SDK | 35 |
| Architectures | arm64-v8a, x86_64 |
| LiteRT | 2.2 custom Selected-Subgraph runtime |
| ONNX Runtime | 1.28 custom Android runtime |
| Qualcomm runtime | QNN / QAIRT 2.44 where supported |
| Java / Kotlin target | Java 17 |

Supported language selections in the app are Auto/mixed (`na`), Korean, English, Japanese, Chinese, German, French, Spanish, Italian, Portuguese, and Russian.

---

# Which model should I use?

Choose based first on whether the device has a supported Qualcomm NPU path.

| Device / goal | Recommended model | Backend | Why |
| --- | --- | --- | --- |
| **Modern Snapdragon with supported QNN/HTP** — for example Snapdragon 8 Gen 3-class devices where the app exposes NPU | **ONNX W8A16** | **NPU / QNN** | Recommended first choice for using the Qualcomm NPU; much smaller than ONNX FP32 and measured QNN performance is excellent |
| Supported Snapdragon, but FP32 is preferred | **ONNX FP32** | **NPU / QNN** | Uses the original FP32 ONNX model while still taking advantage of QNN acceleration |
| **No supported Qualcomm NPU** / MediaTek / general CPU use | **LiteRT Multi-P W8-AFP32** | **CPU / XNNPACK** | Best general-purpose optimized CPU model in this project |
| High-end Snapdragon where absolute speed matters | **Benchmark ONNX W8A16 NPU and LiteRT Multi-P W8-AFP32 CPU** | NPU vs CPU | On Snapdragon 8 Elite Gen 5, the optimized LiteRT CPU path was slightly faster than the measured NPU path |
| CPU FP32 / non-quantized comparison | **LiteRT Multi-P** | CPU / XNNPACK | FP32 Multi-P model with automatic T/L buckets |
| Smaller fixed-shape CPU model | **LiteRT W8-AFP32** | CPU / XNNPACK | Much smaller than LiteRT FP32 and does not have Multi-P bucket warm-up behavior |
| Simple fixed-shape LiteRT baseline | **LiteRT FP32** | CPU / XNNPACK | Soniqo-provided T128/L64 FP32 LiteRT model |
| Original/reference ONNX CPU behavior | **ONNX FP32** | CPU / ORT | Useful as an ONNX reference path, but usually much slower than optimized LiteRT or NPU on high-end devices |

**NPU support does not guarantee that NPU will always be the fastest option.** On a very fast CPU, an aggressively optimized LiteRT/XNNPACK model can match or slightly outperform QNN. If maximum throughput matters, benchmark both paths after their caches are prepared.

---

# Model lineage, format, and runtime

The project mixes upstream models, Soniqo-provided LiteRT assets, and model variants converted or quantized specifically for this project. Their origins are intentionally documented separately.

| Model shown in app | Model origin / conversion | Format / shape | Precision | Est. bundle size | Runtime path | Qualcomm NPU |
| --- | --- | --- | --- | ---: | --- | --- |
| **ONNX FP32** | Original/upstream Supertonic-3 ONNX model | Dynamic ONNX | FP32 | ~401 MB | ONNX Runtime | QNN on supported devices |
| **ONNX W8A16** | **Quantized/calibrated for this project** from the ONNX model | Static QDQ ONNX | W8 / A16-oriented QDQ | ~113 MB | ONNX Runtime | QNN on supported devices |
| **LiteRT FP32** | **Soniqo-provided LiteRT FP32 model** | Fixed T128 / L64 | FP32 | ~390 MB | Soniqo `speech-core` LiteRT path | No |
| **LiteRT W8-AFP32** | **Quantized for this project** from the fixed Soniqo-compatible LiteRT family | Fixed T128 / L64 | selective W8, FP32 activations | ~144 MB | Modified/extended `speech-core` LiteRT path | No |
| **LiteRT Multi-P** | **Converted for this project** from official Supertonic-3 ONNX using fixed-shape specialization + GELU fusion + LiteRT conversion | 7×7 static T/L MultiPreset | FP32 | ~443 MB | Modified/extended `speech-core` LiteRT path | No |
| **LiteRT Multi-P W8-AFP32** | **Quantized for this project** from the Multi-P model family | 7×7 static T/L MultiPreset | selective W8, FP32 activations | ~269 MB | Modified/extended `speech-core` LiteRT path | No |

Bundle sizes are approximate ModelManager download estimates in decimal MB. They include configuration/tokenizer assets and the ten built-in voice-style JSONs, but not app/native-library size or generated runtime caches.

## Soniqo `speech-core` relationship

The native LiteRT execution path is based on **[Soniqo speech-core](https://github.com/soniqo/speech-core)**. The Android JNI layer links the LiteRT speech-core target and creates `speech_core::LiteRTSupertonicTts` for all four active LiteRT variants.

That does **not** mean all four LiteRT model files came from Soniqo:

- **LiteRT FP32** uses the Soniqo-provided LiteRT FP32 model bundle.
- **LiteRT W8-AFP32** is a project-produced quantized derivative of the fixed LiteRT family.
- **LiteRT Multi-P** is an independent project conversion from the official Supertonic-3 ONNX model.
- **LiteRT Multi-P W8-AFP32** is the project-produced quantized derivative of that Multi-P family.

The speech-core-based LiteRT path has been extended for these model variants and runtime behavior, including Multi-P signature selection and XNNPACK cache handling.

The two ONNX models do **not** use speech-core for inference. They run through the separate `OnnxSupertonicRunner` / ONNX Runtime path.

---

# Multi-P / MultiPreset models

`LiteRT Multi-P` and `LiteRT Multi-P W8-AFP32` are not single dynamic-shape models. Each contains **49 static T/L signatures** from the following grid:

```text
T = 32, 48, 64, 80, 96, 112, 128
L = 32, 48, 64, 80, 96, 112, 128
```

The runtime automatically selects a suitable static signature for the current text/latent shape. The custom LiteRT runtime adds Selected-Subgraph delegation, signature switching, and XNNPACK packed-weight cache reuse.

## Data-driven bucket design

The T/L grid was **not chosen arbitrarily**. It was designed after analyzing the original text of **seven Korean novels**, totaling about **48.3 MB / 20.82 million characters** and roughly **653,000 utterance units** before the final T128-oriented re-splitting stage.

The Korean corpus was normalized and tokenized using the same type of preprocessing used for synthesis, and the resulting T-length distribution was measured statistically. Representative percentiles were:

| Percentile | T length |
| ---: | ---: |
| P10 | 20 |
| P25 | 35 |
| P50 | 60 |
| P75 | 91 |
| P90 | 120 |
| P95 | 124 |

The `32/48/64/80/96/112/128` preset spacing was selected to cover the high-frequency regions of that real Korean novel distribution while keeping the number of static signatures manageable. The paired T/L grid therefore reflects actual long-form Korean reading workloads rather than an arbitrary set of benchmark-only shapes.

## First-run / new-bucket cost

Multi-P has an important cold-start characteristic: **initial T/L bucket preparation can take noticeably longer than steady-state synthesis**.

The 49 static signatures already exist in the model files; the app is not generating 49 new model files. However, a fresh runtime/cache still needs to prepare the selected signature and XNNPACK state, including weight packing/cache work. The first encounter with a T/L bucket can therefore be much slower than later synthesis using the same prepared/cached bucket.

The app intentionally does **not** eagerly initialize all 49 combinations at startup. It performs a small `T32/L32` warm-up and leaves the remaining signatures lazy. As different text lengths select previously unused buckets, one-time preparation work can occur.

For benchmarking Multi-P, compare **warmed** runs after the relevant T/L bucket has already been prepared.

---

# Qualcomm NPU / QNN cache pre-generation

For `ONNX FP32` and `ONNX W8A16`, Qualcomm QNN is a normal supported high-performance backend on compatible modern Snapdragon devices. It is **not treated as merely experimental**.

The NPU path has a cold-start cost because QNN graph/shape contexts must first be compiled and prepared. The app therefore provides **QNN cache pre-gen**.

After downloading an ONNX model, run **QNN cache pre-generation** before benchmarking or regular NPU use. The pre-generation pass may take some time because it prepares the supported execution contexts, but later starts can reuse the persistent cache instead of paying the full compilation cost during ordinary synthesis.

This is separate from normal **Pre-generation**:

- **CPU Pre-generation** overlaps generation of future speech chunks during long-text playback.
- **QNN cache pre-gen** prepares persistent NPU execution contexts ahead of time.

When comparing RTF values, use cache-ready NPU runs. Cold QNN context compilation is intentionally not represented by the steady-state benchmark table below.

> Device support still depends on the SoC, firmware, QNN runtime compatibility, and graph/context support. The current app does not expose the normal NPU path on the legacy SM6350/lito compatibility case.

---

# Quantized variants

`W8-AFP32` means selected weights are quantized to 8-bit while activations remain FP32. It is **not** a full W8A8 model.

`ONNX W8A16` is a separate static QDQ model family calibrated/quantized for the Qualcomm QNN path. It is substantially smaller than ONNX FP32 and is the default recommendation when using a supported modern Snapdragon NPU.

Quantization can affect performance, size, and output quality. Exact differences depend on the voice, text, device, and runtime.

---

# Custom voices

The app can directly import **Supertonic-3-compatible voice-style JSON files**.

A convenient way to create one is:

**[saurabhv749/supertonic3-voice-clone](https://github.com/saurabhv749/supertonic3-voice-clone)**

Example:

```bash
python train_style.py \
  --name my-voice \
  --target-wav-path voices/my-voice.wav \
  --num-steps 3000 \
  --learning-rate 0.0002
```

The generated JSON is normally written under a path similar to:

```text
logs/my-voice/my-voice.json
```

To use it in Supertonic LiteRT TTS:

1. Open the app.
2. Under **Voice**, tap **Import**.
3. Select the generated `.json` file.
4. The voice appears as `Custom · <name>`.
5. Use it in the standalone app or through Android's system TextToSpeech engine.

The importer expects the normal Supertonic-3 style tensors:

```text
style_ttl: [1, 50, 256]
style_dp : [1, 8, 16]
```

Both are required. Imported custom voices are stored in one shared app-wide library and can be used across **all six model variants** without importing the same voice again for each model.

The Android TTS service also exposes imported voices through the normal Android TextToSpeech API.

> Voice-clone quality depends heavily on source-audio cleanliness and training conditions. Use voice cloning only with appropriate permission and disclosure.

See [`docs/custom-voice-and-regex.md`](docs/custom-voice-and-regex.md) for additional details.

---

# Measured performance / RTF

**RTF (Real-Time Factor)** = synthesis time ÷ generated audio duration. **Lower is faster.** The speed multiplier is approximately `1 / RTF`, so `RTF 0.5 ≈ 2× real-time`, `RTF 0.1 ≈ 10×`, and so on.

## Snapdragon 8 Elite Gen 5

Measured steady-state values:

| Model | Backend | 4-step RTF | 4-step speed | 8-step RTF | 8-step speed |
| --- | --- | ---: | ---: | ---: | ---: |
| **ONNX FP32** | CPU (ORT) | 0.238 | ~4.2× | 0.464 | ~2.2× |
| **ONNX FP32** | NPU / QNN | 0.047 | ~21.3× | 0.080 | ~12.5× |
| **ONNX W8A16** | CPU (ORT) | 0.300 | ~3.3× | 0.557 | ~1.8× |
| **ONNX W8A16** | NPU / QNN | 0.045 | ~22.2× | 0.075 | ~13.3× |
| **LiteRT FP32** | CPU / XNNPACK | 0.077 | ~13.0× | 0.128 | ~7.8× |
| **LiteRT W8-AFP32** | CPU / XNNPACK | 0.057 | ~17.5× | 0.097 | ~10.3× |
| **LiteRT Multi-P** | CPU / XNNPACK | 0.056 | ~17.9× | 0.097 | ~10.3× |
| **LiteRT Multi-P W8-AFP32** | CPU / XNNPACK | **0.042** | **~23.8×** | **0.070** | **~14.3×** |

In this measurement set, **LiteRT Multi-P W8-AFP32 on CPU/XNNPACK is the fastest result overall**, narrowly ahead of ONNX W8A16 NPU and ONNX FP32 NPU. This is why supported Snapdragon users who care about absolute speed may want to benchmark both the NPU recommendation and the optimized LiteRT CPU model.

> NPU results should be measured after **QNN cache pre-gen**. Multi-P results should be measured after the relevant T/L bucket has been warmed. The table represents steady-state synthesis, not first-use cache-building time.

## Snapdragon 690 / SM6350

Older test build, measured with **2 threads and big-core affinity**:

| Model | Backend | 4-step RTF | 4-step speed | 8-step RTF | 8-step speed |
| --- | --- | ---: | ---: | ---: | ---: |
| ONNX FP32 | ORT CPU | **0.477** | **~2.1×** | **0.855** | **~1.17×** |
| ONNX W8A16 | ORT CPU | 0.546 | ~1.8× | 0.958 | ~1.04× |
| LiteRT FP32 | XNNPACK | ~0.59 | ~1.7× | ~1.05 | ~0.95× |

The LiteRT values in this older comparison were estimated from timestamps rather than the later authoritative `SYNTH-END` metric, so they should be treated as approximate.

The current app does not expose the normal Qualcomm NPU path for SM6350/lito.

## Helio G99

Current practical CPU performance observed on Helio G99 hardware is **roughly in the same performance class as Snapdragon 690** with the current optimized CPU path.

An exact same-revision, same-text, same-step retained table is not available, so this README intentionally does not invent a precise G99 RTF number. The much slower old `RTF ~1.0–1.2` figure from an earlier runtime/model state is not representative of current behavior.

---

# Runtime backends

## LiteRT

All current LiteRT model variants use **CPU / XNNPACK** in the release runtime.

The active LiteRT path uses the speech-core-based native TTS implementation plus project-specific changes for the current model families and runtime behavior. Multi-P additionally depends on custom Selected-Subgraph/signature handling and shared XNNPACK cache work.

Older LiteRT GPU/NNAPI experiments are retired. Qualcomm acceleration is handled by the ONNX/QNN path, not the current LiteRT path.

## ONNX Runtime

The current release exposes:

```text
CPU (ORT)
NPU
```

- **CPU (ORT)**: normal ONNX Runtime CPU Execution Provider
- **NPU**: Qualcomm QNN on supported Snapdragon devices

### No ONNX XNNPACK in the current release

**ONNX Runtime XNNPACK / `CPU XNN` is not included in the current released APK.**

The repository still contains scripts and historical development code for a custom QNN+XNNPACK ORT build, but those files should not be interpreted as indicating that `CPU XNN` is available in the current release runtime.

LiteRT still uses XNNPACK; this restriction applies specifically to the **ONNX Runtime** path.

---

# Android system TTS usage

1. Install the APK.
2. Open **Supertonic LiteRT** once.
3. Select a model and download its model bundle.
4. If using **Multi-P**, expect first-time preparation of a new T/L bucket to take longer than warmed synthesis.
5. If using **Qualcomm NPU**, run **QNN cache pre-gen** after downloading the ONNX model.
6. Select a built-in or imported custom voice.
7. Configure steps, threads, and speech speed as desired.
8. In Android settings, select **Supertonic LiteRT** as the system Text-to-speech engine.

Model files are downloaded on demand and stored locally. Once the required model is present, synthesis itself is local.

Speech speed is applied **after neural synthesis** using Sonic. The neural model synthesizes internally at 1.0×; post-processing avoids clipping/instability that can occur when duration prediction itself is aggressively accelerated.

---

# Pronunciation and regex replacement rules

The standalone app and Android system TTS service can both apply reusable JSON rules before synthesis.

Example:

```json
[
  {
    "term": "LLMs",
    "replacement": "L L Ems",
    "ignoreCase": true,
    "isRegex": false
  },
  {
    "word": "RTX\\s*(\\d+)",
    "pronunciation": "알티엑스 $1",
    "ignoreCase": true,
    "isRegex": true
  }
]
```

See [`docs/custom-voice-and-regex.md`](docs/custom-voice-and-regex.md) and [`examples/pronunciation_rules_example.json`](examples/pronunciation_rules_example.json).

---

# Building

## Normal Windows build

Typical requirements:

- Git for Windows
- JDK 17
- Android SDK / platform 35
- CMake 3.22.1
- NDK `29.0.14206865`

Full local setup/build:

```powershell
.\BUILD_ALL.bat
```

Repeated APK build after runtimes are prepared:

```powershell
.\build_apk.bat
```

or:

```powershell
.\gradlew.bat :app:assembleDebug
```

Debug APK output:

```text
app\build\outputs\apk\debug\app-debug.apk
```

## Custom LiteRT runtime

Relevant entry points:

```text
BUILD_CUSTOM_LITERT_CMAKE.bat
tools/build_litert_2_2_selected_subgraph.sh
tools/build_litert_2_2_selected_subgraph_cmake.sh
```

The custom LiteRT build adds the runtime surface used by the Multi-P Selected-Subgraph/signature path and XNNPACK cache handling.

## Qualcomm / ONNX Runtime development builds

The repository also contains tooling for custom Qualcomm/QNN ORT builds, including:

```text
BUILD_CUSTOM_ORT_QNN_XNNPACK.bat
tools/build_custom_ort_qnn_xnnpack.ps1
third_party/onnxruntime/ORT-1.28.0-QNN-HTA.patch
```

These are development/build tools. As noted above, **the current released APK does not expose ONNX `CPU XNN`** even though a QNN+XNNPACK ORT build path exists in the repository.

---

# Install on a connected device

Enable USB debugging and verify ADB:

```powershell
adb devices
```

Then run:

```powershell
.\INSTALL_ON_PHONE.bat
```

The installer checks the signing certificate before replacing an existing installation so downloaded models/app data can be preserved when the signing identity matches.

---

# Verification / diagnostics

Useful scripts include:

```text
VERIFY_SOURCE_TREE.bat
VERIFY_TTS_ENGINE.bat
CAPTURE_SD690_RTF_LOG.bat
CAPTURE_SD690_FULL_DIAG.bat
PULL_PERF_PROFILES.bat
CLEAR_PERF_PROFILES.bat
COMPARE_LITERT_STOCK_CUSTOM.bat
```

Additional tools under `tools/` verify the custom LiteRT runtime, native-library identity, ELF/ZIP 16 KB alignment, and profiling output.

Deep Profiler is **OFF by default** because profiling adds overhead and should not be enabled for normal RTF measurements.

---

# Repository layout

```text
app/          Android application / settings UI
sdk/          Android TTS service, Kotlin runtime glue, ONNX runner, JNI bridge
speech-core/  Soniqo speech-core-based native LiteRT TTS source/path
third_party/  LiteRT and ONNX Runtime patches / pinned support files
tools/        Runtime builders, verification, profiling, conversion helpers
docs/         Build, runtime, custom-voice, and diagnostic notes
.github/      CI / release workflows
```

Important implementation areas include:

```text
app/src/main/kotlin/com/supertonic/tts/MainActivity.kt
sdk/src/main/kotlin/audio/soniqo/speech/ModelManager.kt
sdk/src/main/kotlin/audio/soniqo/speech/OnnxSupertonicRunner.kt
sdk/src/main/kotlin/audio/soniqo/speech/TtsSettings.kt
sdk/src/main/kotlin/audio/soniqo/speech/service/SpeechTextToSpeechService.kt
sdk/src/main/cpp/jni_bridge.cpp
```

---

# Credits / related projects

- **Supertonic-3** upstream model: [Supertone/supertonic-3](https://huggingface.co/Supertone/supertonic-3)
- **Soniqo speech-core**: [soniqo/speech-core](https://github.com/soniqo/speech-core) — base of the native LiteRT TTS execution/orchestration path used and extended by this project
- **Custom voice training**: [saurabhv749/supertonic3-voice-clone](https://github.com/saurabhv749/supertonic3-voice-clone)

See [`THIRD-PARTY-SONIC-NOTICE.txt`](THIRD-PARTY-SONIC-NOTICE.txt) and third-party notices in the repository for bundled dependencies.

## Notes

- Model weights are not committed directly to this repository; the app downloads the selected bundle on demand.
- Bundle sizes are approximate and do not include generated caches.
- Qualcomm NPU availability is device/runtime dependent.
- Multi-P first-use bucket preparation and QNN context generation are separate from steady-state RTF.
- Custom voice training happens externally; this Android app imports compatible Supertonic-3 voice-style JSON files but does not train a voice on-device.
