# Supertonic LiteRT TTS for Android

**한국어** | [English](README.md)

**Supertonic-3**를 Android 시스템 TTS 엔진과 독립 실행형 테스트 앱으로 사용할 수 있게 만든 프로젝트입니다. **LiteRT / XNNPACK** 기반 CPU 실행, **ONNX Runtime** CPU 실행, 호환되는 Snapdragon 기기에서의 **Qualcomm QNN NPU 가속**, 커스텀 보이스 JSON 가져오기, 긴 텍스트 스트리밍, 발음/정규식 규칙, 상세 런타임 진단 기능을 제공합니다.

이 프로젝트의 주 목적은 선택한 모델 번들을 내려받은 뒤 Supertonic-3를 **빠르고 완전한 로컬 Android TTS 엔진**으로 실사용할 수 있게 하는 것입니다.

> **상태:** 개발 지향 프로젝트입니다. 일반 LiteRT CPU 경로와 호환 Qualcomm QNN NPU 경로는 모두 실사용 가능한 런타임 경로이며, 일부 기기별 호환 경로와 진단 기능은 개발/검증 목적입니다.

## 주요 기능

- Android **시스템 TextToSpeech 엔진** 연동
- 독립 실행형 합성 / WAV 저장 / 오디오 공유
- 6종 Supertonic-3 모델 선택
- 네이티브 **LiteRT 2.2 + XNNPACK** CPU 실행
- Multi-P용 커스텀 LiteRT Selected-Subgraph 런타임
- **ONNX Runtime 1.28** CPU / Qualcomm QNN 경로
- 지원 기기에서 **Qualcomm NPU 가속**
- 빠른 NPU 시작을 위한 QNN context/cache 사전 생성
- 긴 텍스트 자동 분할 및 T/L bucket 자동 선택
- flow-matching 1~64 step, 기본값 **4 step**
- CPU thread 1~8, 기본값 **4 thread**
- 합성 후 Sonic 처리 방식의 0.25×~3.00× 속도 조절
- 기본 보이스 10종: `F1`~`F5`, `M1`~`M5`
- **커스텀 Supertonic-3 voice JSON** 가져오기
- 모든 활성 모델에서 커스텀 보이스 공유
- 발음 / 정규식 치환 규칙
- CPU 스트리밍용 선택적 pre-generation
- 모델 SHA-256 검증, 런타임 프로파일링, 기기 벤치마크 도구

## 요구 사항 / 런타임 버전

| 항목 | 현재 프로젝트 설정 |
| --- | --- |
| 최소 Android | Android 8.0 / API 26 |
| Target Android | API 34 |
| Compile SDK | 35 |
| ABI | arm64-v8a, x86_64 |
| LiteRT | 2.2 커스텀 Selected-Subgraph 런타임 |
| ONNX Runtime | 1.28 커스텀 Android 런타임 |
| Qualcomm 런타임 | 지원 기기에서 QNN / QAIRT 2.44 |
| Java / Kotlin target | Java 17 |

앱에서 선택 가능한 언어는 Auto/mixed (`na`), 한국어, 영어, 일본어, 중국어, 독일어, 프랑스어, 스페인어, 이탈리아어, 포르투갈어, 러시아어입니다.

---

# 어떤 모델을 쓰면 되나요?

대부분의 경우 아래 기준으로 시작하면 됩니다.

| 목적 | 권장 모델 | 이유 |
| --- | --- | --- |
| **일반 CPU용 최우선 추천** | **LiteRT Multi-P W8-AFP32** | 실측 CPU 성능이 가장 좋고 용량도 줄었으며 T/L preset을 자동 선택 |
| **CPU 품질 / FP32 기준 모델** | **LiteRT Multi-P** | weight quantization 없는 FP32 Multi-P |
| **용량 작은 CPU 모델** | **LiteRT W8-AFP32** | LiteRT FP32보다 훨씬 작으면서 activation은 FP32 유지 |
| **단순 고정 shape 기준 모델** | **LiteRT FP32** | T128/L64 고정 LiteRT/XNNPACK 기준 경로 |
| **원본 ONNX 동작 비교** | **ONNX FP32** | 원본 dynamic ONNX 경로에 가장 가까운 기준 모델 |
| **Qualcomm NPU / 작은 ONNX 번들** | **ONNX W8A16** | 작은 QDQ 모델이며 QNN NPU 성능이 좋고 저장공간 사용량이 적음 |

모든 SoC에서 하나의 모델이 항상 가장 빠른 것은 아닙니다. XNNPACK 성능은 CPU 마이크로아키텍처, thread 수, 텍스트 길이, cache 상태, 발열에 영향을 받고, Qualcomm QNN은 여기에 기기 펌웨어와 지원 backend 및 graph/context 호환성도 영향을 받습니다.

## 모델 비교

아래 용량은 ModelManager에 정의된 다운로드 예상치 기준의 대략적인 decimal MB입니다. config/tokenizer와 기본 보이스 10종을 포함하며, 런타임 cache와 앱/native library 용량은 별도입니다.

| 앱 표시 모델 | 형식 / shape | 정밀도 | 예상 번들 크기 | CPU backend | Qualcomm NPU | 권장 용도 |
| --- | --- | --- | ---: | --- | --- | --- |
| **ONNX FP32** | Dynamic ONNX | FP32 | ~401 MB | ORT CPU | 지원 Qualcomm 기기에서 QNN | 기준 동작, 비교, Qualcomm NPU |
| **ONNX W8A16** | Static QDQ ONNX | W8 / A16 지향 QDQ | ~113 MB | ORT CPU | 지원 Qualcomm 기기에서 QNN | 작은 ONNX 용량, Qualcomm NPU 가속 |
| **LiteRT FP32** | 고정 T128 / L64 | FP32 | ~390 MB | Native XNNPACK | 없음 | 안정적인 고정-shape LiteRT 기준 |
| **LiteRT W8-AFP32** | 고정 T128 / L64 | selective W8, FP32 activation | ~144 MB | Native XNNPACK | 없음 | 작은 CPU 모델 |
| **LiteRT Multi-P** | 7×7 static T/L MultiPreset | FP32 | ~443 MB | Native XNNPACK | 없음 | 유연한 FP32 CPU 기준 모델 |
| **LiteRT Multi-P W8-AFP32** | 7×7 static T/L MultiPreset | selective W8, FP32 activation | ~269 MB | Native XNNPACK | 없음 | 일반 CPU용 최우선 추천 |

### Multi-P / MultiPreset

Multi-P 모델은 다음 T/L preset 조합으로 구성된 **49개의 static signature**를 포함합니다.

```text
T = 32, 48, 64, 80, 96, 112, 128
L = 32, 48, 64, 80, 96, 112, 128
```

하나의 dynamic graph를 모든 길이에 사용하는 방식이 아니라, 입력에 맞는 static signature를 런타임에서 자동으로 선택합니다. 커스텀 LiteRT 런타임은 Selected-Subgraph delegation, signature 전환, XNNPACK packed-weight cache 재사용을 지원합니다.

**초기 사용 시 주의:** Multi-P는 하나의 dynamic graph가 아니라 여러 static T/L preset 묶음입니다. 새 설치나 cache가 비어 있는 상태에서는 실제로 사용하는 signature의 XNNPACK 상태를 준비하고 weight를 pack해야 하므로, **처음 모델을 불러올 때와 처음 만나는 T/L bucket에서는 정상 steady-state 합성보다 훨씬 오래 걸릴 수 있습니다.**

앱은 시작 시 49개 조합을 전부 eager 생성하지 않습니다. 작은 `T32/L32` warm-up만 수행하고 나머지 signature는 lazy하게 준비합니다. 이후 다른 길이의 텍스트가 새 bucket을 선택하면 해당 bucket에 한 번성 준비/packing 비용이 발생할 수 있고, 이후에는 shared/persistent XNNPACK cache의 이점을 받습니다.

### Qualcomm NPU와 QNN cache pre-generation

호환되는 Qualcomm 기기에서 ONNX FP32와 ONNX W8A16의 **QNN NPU backend는 일반적인 고성능 실행 경로**입니다. 단순한 실험용 backend로만 취급하지 않습니다.

다만 QNN에도 중요한 **최초 준비 비용**이 있습니다. 각 graph/shape context를 컴파일하고 cache해야 하므로 앱에는 **QNN cache pre-gen** 기능이 있습니다. ONNX 모델 다운로드 후 NPU를 본격적으로 사용하거나 벤치마크하기 전에 **QNN cache pre-generation을 먼저 실행하는 것을 강하게 권장합니다.**

QNN cache pre-gen은 지원되는 모델 context를 미리 순회하면서 accelerator cache에 저장합니다. 사전 생성 과정 자체는 시간이 걸릴 수 있지만, 이후 NPU 시작과 추론에서 매번 전체 컴파일 비용을 지불하지 않고 저장된 context를 재사용할 수 있습니다.

이 기능은 일반 CPU용 **Pre-generation**과 별개입니다. CPU Pre-generation은 다음 음성 chunk 생성을 겹쳐 처리하는 기능이고, **QNN cache pre-gen은 지속적으로 재사용할 NPU execution context를 미리 만드는 기능**입니다. NPU backend를 선택하면 일반 CPU pre-generation 옵션은 비활성화됩니다.

> 참고: 레거시 SM6350/lito HTA 호환 경로에서는 현재 NPU 선택 및 QNN cache pre-gen이 비활성화되어 있습니다. 최신 지원 QNN/HTP 기기는 일반 NPU 경로를 사용합니다.

### 양자화 모델

`W8-AFP32`는 일부 weight를 8-bit로 양자화하면서 activation은 FP32로 유지하는 방식입니다. **완전한 W8A8 모델이 아닙니다.**

`ONNX W8A16`은 Qualcomm QNN 경로를 중심으로 만든 별도 QDQ 모델 계열입니다. 양자화 모델은 용량이 크게 줄지만 실제 음질/성능 차이는 보이스, 텍스트, 기기에 따라 달라질 수 있습니다.

---

# 커스텀 보이스

앱은 **Supertonic-3 호환 voice-style JSON**을 직접 가져올 수 있습니다.

커스텀 보이스를 만드는 방법 중 하나는 다음 프로젝트입니다.

**[saurabhv749/supertonic3-voice-clone](https://github.com/saurabhv749/supertonic3-voice-clone)**

이 프로젝트는 대상 WAV에서 Supertonic-3 style JSON을 학습합니다. 예시 명령은 다음과 같습니다.

```bash
python train_style.py \
  --name my-voice \
  --target-wav-path voices/my-voice.wav \
  --num-steps 3000 \
  --learning-rate 0.0002
```

결과 파일은 보통 다음 경로에 생성됩니다.

```text
logs/my-voice/my-voice.json
```

Supertonic LiteRT TTS에서는:

1. 앱을 엽니다.
2. **Voice**에서 **Import**를 누릅니다.
3. 생성된 `.json` 파일을 선택합니다.
4. `Custom · <name>` 형태로 보이스가 추가됩니다.
5. 앱에서 직접 사용하거나 Android 시스템 TTS 엔진으로 Supertonic LiteRT를 선택해 사용할 수 있습니다.

가져오기 시 다음 Supertonic-3 style 구조를 검증합니다.

```text
style_ttl: [1, 50, 256]
style_dp : [1, 8, 16]
```

두 필드가 모두 필요합니다. 가져온 커스텀 보이스는 앱 전체에서 공유되며 **6개 활성 모델 모두에서 동일하게 사용**할 수 있습니다. ONNX FP32, ONNX W8A16, 모든 LiteRT 모델과 두 Multi-P 모델마다 다시 가져올 필요가 없습니다.

Android TTS service에서도 가져온 커스텀 보이스를 일반 Android TextToSpeech API를 통해 노출합니다.

> 보이스 복제 품질은 원본 음원의 깨끗함과 학습 설정에 크게 좌우됩니다. 연결된 voice-clone 프로젝트는 현재 감정/운율 재현보다 화자 정체성 유사도에 더 초점을 둔다고 명시하고 있습니다. 적절한 동의와 고지를 전제로 사용하세요.

추가 내용은 [`docs/custom-voice-and-regex.md`](docs/custom-voice-and-regex.md)를 참고하세요.

---

# 실측 성능 / RTF

**RTF(Real-Time Factor)**는 합성 시간 ÷ 생성된 오디오 길이입니다.

- `RTF 1.0` = 실시간과 동일한 속도
- `RTF 0.5` = 약 2배 빠름
- `RTF 0.25` = 약 4배 빠름
- `RTF 0.10` = 약 10배 빠름

아래 값은 제조사 수치가 아니라 **실제 기기에서 측정한 개발 중 실측값**입니다. 서로 다른 리비전과 테스트 조건의 결과가 섞여 있을 수 있으므로, SoC별 대략적인 성능 등급을 보는 용도로 해석하는 것이 좋습니다.

## Snapdragon 8 Elite Gen 5

4-step / 8-step 실측 RTF:

| 모델 | Backend | 4-step RTF | 8-step RTF |
| --- | --- | ---: | ---: |
| **ONNX FP32** | CPU (ORT) | 0.238 | 0.464 |
| **ONNX FP32** | NPU | 0.047 | 0.080 |
| **ONNX W8A16** | CPU (ORT) | 0.300 | 0.557 |
| **ONNX W8A16** | NPU | 0.045 | 0.075 |
| **LiteRT FP32** | CPU / XNNPACK | 0.077 | 0.128 |
| **LiteRT W8-AFP32** | CPU / XNNPACK | 0.057 | 0.097 |
| **LiteRT Multi-P** | CPU / XNNPACK | 0.056 | 0.097 |
| **LiteRT Multi-P W8-AFP32** | CPU / XNNPACK | **0.042** | **0.070** |

이 측정에서는 **LiteRT Multi-P W8-AFP32 / CPU XNNPACK이 전체에서 가장 빠른 결과**였습니다. 4-step `0.042`, 8-step `0.070`으로 ONNX W8A16 NPU(`0.045 / 0.075`)와 ONNX FP32 NPU(`0.047 / 0.080`)보다도 약간 빨랐습니다.

또한 이 결과는 동일 SoC에서 일반 ONNX Runtime CPU 경로와 최적화된 LiteRT/XNNPACK 또는 Qualcomm NPU 사이의 성능 차이가 매우 클 수 있음을 보여줍니다. 실제 RTF는 텍스트, T/L bucket, thread 수, cache 상태, 발열에 따라 달라질 수 있습니다.

> NPU 성능은 **QNN cache pre-gen을 완료한 뒤**, Multi-P 성능은 관련 T/L bucket을 한 번 warm-up한 뒤 비교하는 것이 맞습니다. 위 표는 steady-state RTF이므로 cold-start/cache 생성 시간은 포함하지 않습니다.

## Snapdragon 690 / SM6350

SD690 테스트 빌드에서 **2 threads + big-core affinity** 조건으로 측정한 값입니다.

| 모델 | Backend | 4-step | 8-step |
| --- | --- | ---: | ---: |
| ONNX FP32 | ORT CPU | **0.477** | **0.855** |
| ONNX W8A16 | ORT CPU | 0.546 | 0.958 |
| LiteRT FP32 | XNNPACK | ~0.59 | ~1.05 |

위 LiteRT 값은 이후 사용한 공식 `SYNTH-END` metric이 아니라 당시 timestamp에서 추정한 값이므로 대략적인 수치입니다.

현재 앱에서는 SM6350/lito 기기에 Qualcomm NPU 선택을 노출하지 않으며, 과거 HTA 작업은 개발/참고용 코드로 남아 있습니다.

## Helio G99

현재 Helio G99에서 관찰된 실사용 CPU 성능은 **대략 Snapdragon 690과 비슷한 등급**입니다. 과거 README에 있던 `RTF ~1.0–1.2` 수치는 이전 고정 모델/런타임 상태에서 얻은 값이라 현재 앱 성능을 대표하지 않습니다.

동일 리비전·동일 텍스트·동일 step 조건의 완전한 비교표가 남아 있지 않아 임의의 정확한 숫자는 기재하지 않습니다.

### 벤치마크 주의사항

RTF는 다음 조건에 따라 크게 달라질 수 있습니다.

- flow-matching step 수
- 텍스트와 언어
- Multi-P에서 선택된 T/L bucket
- CPU thread 수
- XNNPACK/ORT cache의 cold/warm 상태
- NPU의 QNN context cache 상태
- pre-generation 설정
- SoC scheduler / affinity
- 기기 온도와 thermal throttling
- 앱/런타임 리비전

의미 있는 비교를 하려면 같은 텍스트, 보이스, step, thread, 발열 상태를 맞추고 cache가 준비된 상태끼리 비교해야 합니다.

---

# Backend

## LiteRT

모든 활성 LiteRT 모델은 네이티브 **CPU / XNNPACK** 경로를 사용합니다.

이전 LiteRT GPU와 NNAPI 경로는 제거되었습니다. Qualcomm 가속은 LiteRT가 아니라 ONNX/QNN 경로에서 처리합니다.

Multi-P 런타임에는 다음 작업이 포함되어 있습니다.

- Selected-Subgraph / SignatureDef delegation
- signature 전환
- XNNPACK delegate/thread-pool 재사용
- stage-shared packed-weight cache
- persistent-cache reload 처리
- dynamic-depthwise 호환 작업
- preload / 긴 텍스트 overlap 최적화

## ONNX Runtime

현재 Release 런타임에서 노출되는 backend는 다음과 같습니다.

```text
CPU (ORT)
NPU
```

`CPU (ORT)`는 일반 ONNX Runtime CPU execution provider입니다.

`NPU`는 지원되는 Snapdragon 기기에서 Qualcomm QNN을 사용합니다. 최신 QNN/HTP 호환 기기에서는 정상적인 고성능 backend이며, 최초 graph/context 컴파일 비용은 **QNN cache pre-gen**을 먼저 실행해 줄이는 것을 권장합니다. 실제 사용 가능 여부는 기기의 Qualcomm runtime/firmware 및 graph context 지원 여부에 따라 달라질 수 있습니다.

> **현재 Release 기준:** ONNX Runtime XNNPACK / `CPU XNN`은 **현재 배포 APK에 포함되어 있지 않습니다.** 저장소의 일부 스크립트와 과거 진단 자료에는 커스텀 QNN+XNNPACK ORT 빌드가 남아 있지만, 이는 별도의 개발 경로이며 현재 Release 런타임 기능으로 보면 안 됩니다.

---

# Android 시스템 TTS 사용법

1. APK를 설치합니다.
2. **Supertonic LiteRT** 앱을 한 번 실행합니다.
3. 모델을 선택하고 해당 모델 번들을 다운로드합니다.
4. LiteRT Multi-P를 쓸 경우 처음 선택되는 T/L bucket의 warm-up/준비가 끝나도록 둡니다.
5. Qualcomm NPU를 쓸 경우 ONNX 모델 다운로드 후 **QNN cache pre-gen**을 한 번 실행합니다.
6. 기본 보이스 또는 가져온 커스텀 보이스를 선택합니다.
7. 필요하면 step / thread / speed를 설정합니다.
8. Android 설정에서 **Supertonic LiteRT**를 시스템 TTS 엔진으로 선택합니다.

모델 파일은 설정된 Hugging Face mirror에서 필요한 번들만 on-demand로 다운로드하고 로컬에 저장합니다. 필요한 모델이 준비된 뒤의 음성 합성 자체는 로컬에서 수행됩니다.

음성 속도는 신경망 합성 이후 **Sonic**으로 적용합니다. 신경망 모델 내부 합성은 항상 1.0×로 수행하며, 이렇게 하면 duration prediction 자체를 빠르게 했을 때 발생할 수 있는 단어/음절 잘림을 피할 수 있습니다.

---

# 발음 / 정규식 규칙

앱과 Android 시스템 TTS service 모두에서 합성 전에 적용되는 재사용 가능한 JSON 발음 규칙을 지원합니다.

예시:

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

자세한 내용은 [`docs/custom-voice-and-regex.md`](docs/custom-voice-and-regex.md)와 [`examples/pronunciation_rules_example.json`](examples/pronunciation_rules_example.json)을 참고하세요.

---

# 빌드

## 일반 Windows 빌드

Android 프로젝트 빌드에 필요한 주요 항목:

- Git for Windows
- JDK 17
- Android SDK / platform 35
- CMake 3.22.1
- NDK `29.0.14206865`

전체 로컬 런타임 준비/빌드:

```powershell
.\BUILD_ALL.bat
```

런타임을 이미 준비한 뒤 APK만 다시 빌드할 때:

```powershell
.\build_apk.bat
```

또는:

```powershell
.\gradlew.bat :app:assembleDebug
```

debug APK 출력 위치:

```text
app\build\outputs\apk\debug\app-debug.apk
```

## Qualcomm / 커스텀 ORT 개발용 빌드 도구

저장소에는 과거 개발 및 A/B 테스트에 사용한 커스텀 QNN+XNNPACK ORT 빌드 경로가 남아 있습니다.

```text
BUILD_CUSTOM_ORT_QNN_XNNPACK.bat
tools/build_custom_ort_qnn_xnnpack.ps1
third_party/onnxruntime/ORT-1.28.0-QNN-HTA.patch
```

이 개발 경로는 matching **QAIRT 2.44.0.260225** SDK가 필요합니다. **현재 Release workflow가 APK에 넣는 ONNX Runtime과는 별개**입니다. 현재 Release는 ONNX XNNPACK이 없는 QNN-capable ORT 런타임을 사용합니다.

## 커스텀 LiteRT 빌드

주요 진입점:

```text
BUILD_CUSTOM_LITERT_CMAKE.bat
tools/build_litert_2_2_selected_subgraph.sh
tools/build_litert_2_2_selected_subgraph_cmake.sh
```

커스텀 런타임은 LiteRT 2.2 기반이며 Selected-Subgraph delegation과 shared XNNPACK cache 처리를 위한 ABI를 추가합니다.

---

# 연결된 기기에 설치

USB 디버깅을 활성화하고 ADB 연결을 확인합니다.

```powershell
adb devices
```

그다음:

```powershell
.\INSTALL_ON_PHONE.bat
```

설치 스크립트는 기존 설치를 교체하기 전에 서명 인증서를 확인해, 서명이 같을 경우 기존에 다운로드한 모델과 앱 데이터를 유지할 수 있게 합니다.

---

# 검증 / 진단

주요 최상위 스크립트:

```text
VERIFY_SOURCE_TREE.bat
VERIFY_TTS_ENGINE.bat
CAPTURE_SD690_RTF_LOG.bat
CAPTURE_SD690_FULL_DIAG.bat
PULL_PERF_PROFILES.bat
CLEAR_PERF_PROFILES.bat
COMPARE_LITERT_STOCK_CUSTOM.bat
```

`tools/` 아래에는 커스텀 LiteRT 런타임, ELF/ZIP 16 KB alignment, native library identity, Deep Profiler 출력을 검증하는 도구가 있습니다.

Deep Profiler는 프로파일링 자체의 오버헤드가 있기 때문에 기본적으로 **OFF**이며, 일반 RTF 측정에서는 켜지 않는 것이 좋습니다.

---

# 저장소 구조

```text
app/          Android 앱 / 설정 UI
sdk/          Android TTS service, Kotlin runtime glue, JNI bridge
speech-core/  Native speech / LiteRT 구현
third_party/  LiteRT 및 ONNX Runtime patch / 고정 support 파일
tools/        런타임 빌드, 검증, 프로파일링 도구
docs/         빌드, 런타임, 커스텀 보이스, 진단 문서
.github/      CI / Release workflow
```

주요 구현 파일:

```text
app/src/main/kotlin/com/supertonic/tts/MainActivity.kt
sdk/src/main/kotlin/audio/soniqo/speech/ModelManager.kt
sdk/src/main/kotlin/audio/soniqo/speech/TtsSettings.kt
sdk/src/main/kotlin/audio/soniqo/speech/service/SpeechTextToSpeechService.kt
```

---

# 참고

- 모델 weight는 저장소에 커밋하지 않으며, 앱이 선택한 모델 번들을 on-demand로 다운로드합니다.
- 위 모델 용량은 대략적인 값이며 런타임 cache는 포함하지 않습니다.
- Qualcomm QNN NPU는 최신 호환 Snapdragon 기기에서 지원되는 가속 경로이지만 실제 사용 가능 여부는 기기 runtime/firmware에 따라 달라질 수 있습니다.
- Multi-P와 QNN 모두 첫 사용 시 한 번성 cache/준비 비용이 있으므로 steady-state RTF와 분리해서 봐야 합니다.
- ONNX `CPU XNN`은 현재 Release 런타임에 포함되어 있지 않습니다.
- 성능 수치는 개발 중 실측값이며 모든 기기에서 동일한 성능을 보장하지 않습니다.
- 커스텀 보이스 학습은 외부에서 수행하며, 이 Android 앱은 호환되는 Supertonic-3 style JSON을 가져와 사용하는 기능만 제공합니다.

## 관련 프로젝트

- 커스텀 voice-style 학습: **[saurabhv749/supertonic3-voice-clone](https://github.com/saurabhv749/supertonic3-voice-clone)**
- 원본 모델: **[Supertone/supertonic-3](https://huggingface.co/Supertone/supertonic-3)**

## Third-party notice

[`THIRD-PARTY-SONIC-NOTICE.txt`](THIRD-PARTY-SONIC-NOTICE.txt) 및 bundled third-party source의 notice를 참고하세요.
