# Supertonic LiteRT TTS for Android

Android system TTS engine and standalone test app for **Supertonic-3**, with optimized **LiteRT / XNNPACK** CPU execution, optional **ONNX Runtime** backends, experimental **Qualcomm QNN** acceleration, custom voice-style import, long-text streaming, pronunciation rules, and detailed runtime diagnostics.

The main goal of this project is to make Supertonic-3 practical as a **fast, fully local Android TTS engine** after the selected model bundle has been downloaded.

> **Status:** experimental / development-oriented. The normal LiteRT CPU path is the main focus; some ONNX/QNN paths and diagnostic features are device-specific.

## Highlights

- Android **system TextToSpeech engine** integration
- Standalone synthesis / WAV export / audio sharing
- Six selectable Supertonic-3 model variants
- Native **LiteRT 2.2 + XNNPACK** CPU path
- Custom LiteRT Selected-Subgraph runtime for Multi-P models
- **ONNX Runtime 1.28** CPU / optional XNNPACK / Qualcomm QNN paths
- Automatic long-text chunking and T/L bucket selection
- 1–64 flow-matching steps; default is **4 steps**
- 1–8 CPU threads; default is **4 threads**
- 0.25×–3.00× speech-rate control using post-synthesis Sonic processing
- 10 built-in voices: `F1`–`F5`, `M1`–`M5`
- Importable **custom Supertonic-3 voice JSONs**
- Shared custom voices across every active model variant
- Pronunciation / regex replacement rules
- Optional pre-generation for CPU streaming
- Model SHA-256 verification, runtime profiling and device benchmark tools

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

Supported language selections in the app are Auto/mixed (`na`), Korean, English, Japanese, Chinese, German, French, Spanish, Italian, Portuguese and Russian.

---

# Which model should I use?

For most users, start here:

| Goal | Recommended model | Why |
| --- | --- | --- |
| **Best overall CPU starting point** | **LiteRT Multi-P W8-AFP32** | Good size/performance balance, automatic T/L presets, XNNPACK CPU path |
| **CPU quality / FP32 reference** | **LiteRT Multi-P** | FP32 Multi-P graphs without weight quantization |
| **Small CPU model** | **LiteRT W8-AFP32** | Much smaller than LiteRT FP32 while keeping FP32 activations |
| **Simple fixed-shape baseline** | **LiteRT FP32** | Straightforward T128/L64 LiteRT/XNNPACK reference path |
| **Original/reference ONNX behavior** | **ONNX FP32** | Closest to the original dynamic ONNX model path; useful for comparisons |
| **Qualcomm QNN / small ONNX bundle** | **ONNX W8A16** | Small QDQ model intended for Qualcomm QNN experiments and lower storage use |

There is no single model that wins on every SoC. XNNPACK performance depends heavily on CPU microarchitecture, thread count, text length, cache state and thermal conditions; Qualcomm QNN performance additionally depends on device firmware, supported backend, graph partitioning and runtime compatibility.

## Model matrix

Estimated sizes below are the model-manager download estimates in decimal MB and include config/tokenizer files plus the ten built-in voice-style JSONs. Runtime caches and app/native libraries are additional.

| Model shown in app | Format / shape | Precision | Est. bundle size | CPU backend | Qualcomm NPU | Best suited for |
| --- | --- | --- | ---: | --- | --- | --- |
| **ONNX FP32** | Dynamic ONNX | FP32 | ~401 MB | ORT CPU; optional ORT XNNPACK | QNN on supported Qualcomm devices | Reference behavior, comparisons, QNN experiments |
| **ONNX W8A16** | Static QDQ ONNX | W8 / A16-oriented QDQ | ~113 MB | ORT CPU; optional ORT XNNPACK | QNN on supported Qualcomm devices | Small ONNX footprint, Qualcomm acceleration |
| **LiteRT FP32** | Fixed T128 / L64 | FP32 | ~390 MB | Native XNNPACK | No | Stable fixed-shape LiteRT baseline |
| **LiteRT W8-AFP32** | Fixed T128 / L64 | selective W8, FP32 activations | ~144 MB | Native XNNPACK | No | Smaller CPU model |
| **LiteRT Multi-P** | 7×7 static T/L MultiPreset | FP32 | ~443 MB | Native XNNPACK | No | Flexible FP32 CPU reference |
| **LiteRT Multi-P W8-AFP32** | 7×7 static T/L MultiPreset | selective W8, FP32 activations | ~269 MB | Native XNNPACK | No | Recommended general-purpose CPU model |

### Multi-P / MultiPreset

The Multi-P models contain **49 static signatures** made from the T/L preset grid:

```text
T = 32, 48, 64, 80, 96, 112, 128
L = 32, 48, 64, 80, 96, 112, 128
```

The runtime selects an appropriate signature automatically rather than forcing every utterance through the same fixed T/L shape. The custom LiteRT runtime adds Selected-Subgraph delegation, signature switching and XNNPACK packed-weight cache reuse so the application does not have to create an entirely separate process/runtime for every preset.

### Quantized variants

`W8-AFP32` means the LiteRT model uses selective 8-bit weight quantization while keeping activations in FP32. It is **not** a full W8A8 model.

`ONNX W8A16` is a separate QDQ model family tuned around the Qualcomm QNN path. Quantized models are substantially smaller, but exact quality/performance differences are voice-, text- and device-dependent.

---

# Custom voices

The app can directly import **Supertonic-3 voice-style JSON files**.

A convenient way to create one is:

**[saurabhv749/supertonic3-voice-clone](https://github.com/saurabhv749/supertonic3-voice-clone)**

That project trains a Supertonic-3 style JSON from a target WAV. Example from its CLI:

```bash
python train_style.py \
  --name my-voice \
  --target-wav-path voices/my-voice.wav \
  --num-steps 3000 \
  --learning-rate 0.0002
```

The resulting file is normally written under:

```text
logs/my-voice/my-voice.json
```

In Supertonic LiteRT TTS:

1. Open the app.
2. In **Voice**, tap **Import**.
3. Select the generated `.json` file.
4. The voice appears as `Custom · <name>`.
5. Select it and use the app normally, or select Supertonic LiteRT as the Android system TTS engine.

The importer validates the normal Supertonic-3 style contract:

```text
style_ttl: [1, 50, 256]
style_dp : [1, 8, 16]
```

Both fields are required. Imported custom voices are kept in one shared app-wide library and synchronized across **all six active model variants**, so the same custom voice can be used with ONNX FP32, ONNX W8A16, every LiteRT model and both Multi-P models without re-importing it per model.

The Android TTS service also exposes imported voices to applications using the normal Android TextToSpeech API.

> Voice cloning quality depends strongly on source-audio cleanliness and the training setup. The linked voice-clone project notes that its current optimization emphasizes speaker identity more than emotional/prosodic reproduction. Use voice cloning only with appropriate permission and disclosure.

See [`docs/custom-voice-and-regex.md`](docs/custom-voice-and-regex.md) for additional details.

---

# Measured performance / RTF

**RTF (real-time factor)** is synthesis time divided by generated audio duration:

- `RTF 1.0` = exactly real-time
- `RTF 0.5` = roughly 2× faster than real-time
- `RTF 0.25` = roughly 4× faster than real-time

These are **real device measurements from development**, not vendor benchmark numbers. They are snapshots from different revisions and test conditions, so use them as orientation rather than guaranteed performance.

## OnePlus 15 — Snapdragon 8 Elite Gen 5

Recent 8-step measurements:

| Model | Backend | Steps | RTF | Approx. real-time speed |
| --- | --- | ---: | ---: | ---: |
| ONNX FP32 | CPU (ORT) | 8 | ~0.475 | ~2.1× |
| LiteRT Multi-P W8-AFP32 | CPU / XNNPACK | 8 | **~0.412 median** | **~2.4×** |

In this test the Multi-P W8-AFP32 CPU path was faster than the ONNX FP32 / ORT CPU path while also using a smaller model bundle.

## Snapdragon 690 / SM6350

Measured with **2 threads and big-core affinity** in the SD690 test build:

| Model | Backend | 4 steps | 8 steps |
| --- | --- | ---: | ---: |
| ONNX FP32 | ORT CPU | **0.477** | **0.855** |
| ONNX FP32 | ORT XNNPACK | 0.516 | 0.884 |
| ONNX W8A16 | ORT CPU | 0.546 | 0.958 |
| LiteRT FP32 | XNNPACK | ~0.59 | ~1.05 |

The LiteRT values in this older comparison were estimated from timestamps rather than the later authoritative `SYNTH-END` metric, so they are approximate. The table also shows why `CPU XNN` should not automatically be assumed faster than plain ORT CPU on every SoC.

The current app intentionally does **not** expose Qualcomm NPU selection on SM6350/lito devices; historical HTA work remains in the source for development/reference purposes.

## Helio G99

Earlier LiteRT CPU testing on Helio G99 hardware was approximately:

```text
RTF ~1.0–1.2
```

That is around real-time synthesis. This was an earlier fixed-model test and is not directly comparable to the OnePlus 15 table because text, revision and exact step configuration differed.

### Removed accelerator experiments

Older LiteRT GPU/NNAPI experiments were removed from the active app after driver/numerical problems and inconsistent performance. For example, an earlier Snapdragon 8 Elite Gen 5 experiment measured the LiteRT GPU path slower than CPU (`RTF 0.260` GPU vs `0.188` CPU), while a Helio G99 NNAPI experiment reached `RTF 2.148` but produced numerically invalid output. The current LiteRT path is therefore deliberately **CPU/XNNPACK-only**.

### Benchmark caveats

RTF can change substantially with:

- flow-matching step count
- text and language
- T/L bucket selected by Multi-P
- number of CPU threads
- warm vs cold XNNPACK/ORT cache
- pre-generation setting
- SoC scheduler / affinity
- device temperature and throttling
- app/runtime revision

For meaningful comparisons, use the same text, voice, steps, threads and thermal state.

---

# Backends

## LiteRT

All active LiteRT variants use the native **CPU / XNNPACK** path.

The previous LiteRT GPU and NNAPI paths are retired. Qualcomm acceleration is handled by the ONNX/QNN path rather than LiteRT.

The Multi-P runtime includes custom support for:

- Selected-Subgraph / SignatureDef delegation
- signature switching
- XNNPACK delegate/thread-pool reuse work
- stage-shared packed-weight cache
- persistent-cache reload handling
- dynamic-depthwise compatibility work
- preload / long-text overlap experiments

## ONNX Runtime

Depending on the build and device, ONNX models can expose:

```text
CPU (ORT)
CPU XNN
NPU
```

`CPU (ORT)` is the normal ONNX Runtime CPU execution provider.

`CPU XNN` is available only when the APK contains the custom ORT QNN+XNNPACK runtime. Diagnostic/CI builds made with `-PsupertonicOrtRev30=true` intentionally use the older QNN-only AAR and therefore omit the `CPU XNN` choice.

`NPU` uses Qualcomm QNN where supported. Availability is device-specific, and the current UI deliberately excludes SM6350/lito from NPU selection.

---

# Android system TTS usage

1. Install the APK.
2. Open **Supertonic LiteRT** once.
3. Select a model and allow its model bundle to download.
4. Select a built-in or imported custom voice.
5. Configure steps / threads / speed if desired.
6. In Android settings, select **Supertonic LiteRT** as the Text-to-speech engine.

Model files are downloaded on demand from the configured Hugging Face mirrors and stored locally. Once the required model is present, synthesis itself is local.

Speech rate is intentionally applied **after** neural synthesis using Sonic. The neural model always synthesizes at 1.0× internally; this avoids the word/syllable clipping that occurred when model duration prediction itself was accelerated.

---

# Pronunciation / regex rules

The app supports reusable JSON pronunciation rules applied before synthesis in both the standalone app and Android system TTS service.

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

Required for the Android project itself:

- Git for Windows
- JDK 17
- Android SDK / platform 35
- CMake 3.22.1
- NDK `29.0.14206865`

For the complete local runtime setup/build:

```powershell
.\BUILD_ALL.bat
```

The normal full build prepares the custom LiteRT runtime, custom ONNX Runtime configuration and Android APK.

For repeated APK builds after the runtimes have already been prepared:

```powershell
.\build_apk.bat
```

or:

```powershell
.\gradlew.bat :app:assembleDebug
```

The debug APK is written to:

```text
app\build\outputs\apk\debug\app-debug.apk
```

## Qualcomm / custom ORT build

The full local QNN+XNNPACK ORT build additionally requires the matching **QAIRT 2.44.0.260225** SDK.

Relevant entry points:

```text
BUILD_CUSTOM_ORT_QNN_XNNPACK.bat
tools/build_custom_ort_qnn_xnnpack.ps1
third_party/onnxruntime/ORT-1.28.0-QNN-HTA.patch
```

## Custom LiteRT build

Primary entry points:

```text
BUILD_CUSTOM_LITERT_CMAKE.bat
tools/build_litert_2_2_selected_subgraph.sh
tools/build_litert_2_2_selected_subgraph_cmake.sh
```

The custom runtime is based on LiteRT 2.2 and adds the small ABI surface required for Selected-Subgraph delegation and shared XNNPACK cache handling.

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

The installer checks the signing certificate before replacing an existing installation so downloaded models and app data can be preserved when the signing identity matches.

---

# Verification / diagnostics

Useful top-level scripts:

```text
VERIFY_SOURCE_TREE.bat
VERIFY_TTS_ENGINE.bat
CAPTURE_SD690_RTF_LOG.bat
CAPTURE_SD690_FULL_DIAG.bat
PULL_PERF_PROFILES.bat
CLEAR_PERF_PROFILES.bat
COMPARE_LITERT_STOCK_CUSTOM.bat
```

Additional tools under `tools/` verify the custom LiteRT runtime, ELF/ZIP 16 KB alignment, native library identity and deep-profiler output.

Deep Profiler is **OFF by default** because profiling itself adds overhead and should not be enabled for normal RTF measurements.

---

# Repository layout

```text
app/          Android application / settings UI
sdk/          Android TTS service, Kotlin runtime glue and JNI bridge
speech-core/  Native speech / LiteRT implementation
third_party/  LiteRT and ONNX Runtime patches / pinned support files
tools/        Runtime builders, verification and profiling utilities
docs/         Build, runtime, custom-voice and diagnostic notes
.github/      CI / release workflows
```

Key implementation files include:

```text
app/src/main/kotlin/com/supertonic/tts/MainActivity.kt
sdk/src/main/kotlin/audio/soniqo/speech/ModelManager.kt
sdk/src/main/kotlin/audio/soniqo/speech/TtsSettings.kt
sdk/src/main/kotlin/audio/soniqo/speech/service/SpeechTextToSpeechService.kt
```

---

# Notes

- Model weights are **not** committed to this repository; the app downloads the selected bundle on demand.
- Model size estimates above are approximate and do not include runtime caches.
- ONNX/QNN availability is not uniform across Qualcomm devices.
- Performance measurements are development snapshots, not guaranteed device specifications.
- Custom voice generation is performed externally; this Android app imports and uses compatible Supertonic-3 style JSONs but does not train a voice on-device.

## Related project

- Custom voice-style training: **[saurabhv749/supertonic3-voice-clone](https://github.com/saurabhv749/supertonic3-voice-clone)**
- Upstream model: **[Supertone/supertonic-3](https://huggingface.co/Supertone/supertonic-3)**

## Third-party notices

See [`THIRD-PARTY-SONIC-NOTICE.txt`](THIRD-PARTY-SONIC-NOTICE.txt) and notices in the bundled third-party sources.
