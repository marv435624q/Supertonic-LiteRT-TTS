# Supertonic LiteRT TTS for Android

**한국어** | [English](README.md)

**Supertonic-3**를 Android 시스템 TTS 엔진과 독립 실행형 테스트 앱으로 사용할 수 있게 만든 프로젝트입니다. 빠른 완전 로컬 합성을 목표로 **LiteRT / XNNPACK CPU 실행**과 지원되는 Snapdragon 기기에서의 **Qualcomm QNN NPU 가속**을 제공합니다.

이 프로젝트는 여러 Supertonic-3 모델 변형, **[Soniqo speech-core](https://github.com/soniqo/speech-core)**를 기반으로 확장한 LiteRT 실행 경로, 별도의 ONNX Runtime 경로, 커스텀 보이스 가져오기, 긴 텍스트 스트리밍, 발음 규칙, QNN context cache, 벤치마크/진단 도구를 하나의 Android TTS 앱으로 통합합니다.

> **상태:** 개발 지향 프로젝트이지만 일반 LiteRT CPU 경로와 지원되는 Qualcomm QNN NPU 경로는 모두 실사용을 목표로 합니다. 일부 기기별 호환 경로와 진단 기능은 계속 변경될 수 있습니다.

---

## 주요 기능

- Android **시스템 TextToSpeech 엔진** 연동
- 독립 실행형 합성, WAV 저장, 오디오 공유
- 6종 Supertonic-3 모델 선택
- 네이티브 **LiteRT 2.2 + XNNPACK** CPU 실행
- **Soniqo `speech-core` 기반 LiteRT 실행/오케스트레이션**, 본 프로젝트용 확장 포함
- 별도의 **ONNX Runtime 1.28** CPU / Qualcomm QNN 경로
- 지원되는 최신 Snapdragon에서 **Qualcomm NPU 가속**
- NPU 시작 비용을 줄이기 위한 QNN context/cache 사전 생성
- Multi-P 모델의 static T/L bucket 자동 선택
- flow-matching 1~64 step, 기본값 **4 step**
- UI에서 CPU thread 1~8, 기본값 **4 thread**
- 합성 후 Sonic 처리 방식의 0.25×~3.00× 속도 조절
- 기본 보이스 10종: `F1`~`F5`, `M1`~`M5`
- **커스텀 Supertonic-3 voice JSON** 가져오기
- 6개 모델 전체에서 커스텀 보이스 공유
- 발음 / 정규식 치환 규칙
- 긴 텍스트 CPU 스트리밍용 선택적 pre-generation
- 모델 SHA-256 검증, 런타임 프로파일링, 벤치마크 도구

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

가장 먼저 **기기가 지원되는 Qualcomm NPU 경로를 사용할 수 있는지**를 기준으로 고르는 것이 좋습니다.

| 기기 / 목적 | 권장 모델 | Backend | 이유 |
| --- | --- | --- | --- |
| **QNN/HTP를 지원하는 최신 Snapdragon** — 예: 앱에서 NPU 선택이 가능한 Snapdragon 8 Gen 3급 기기 | **ONNX W8A16** | **NPU / QNN** | 모델이 작고 QNN 성능이 매우 좋으며 Qualcomm NPU를 활용하기 위한 기본 추천 |
| 지원 Snapdragon에서 FP32 기준 모델을 쓰고 싶은 경우 | **ONNX FP32** | **NPU / QNN** | 원본 FP32 ONNX 모델을 NPU로 실행 |
| **지원 Qualcomm NPU가 없는 기기** / MediaTek / 일반 CPU 사용 | **LiteRT Multi-P W8-AFP32** | **CPU / XNNPACK** | 이 프로젝트의 범용 CPU 최우선 추천 모델 |
| 고성능 Snapdragon에서 절대적인 최고 속도가 중요한 경우 | **ONNX W8A16 NPU와 LiteRT Multi-P W8-AFP32 CPU를 둘 다 벤치** | NPU vs CPU | Snapdragon 8 Elite Gen 5 실측에서는 최적화된 LiteRT CPU가 NPU보다 근소하게 빨랐음 |
| CPU FP32 / 비양자화 비교 | **LiteRT Multi-P** | CPU / XNNPACK | FP32 Multi-P + 자동 T/L bucket |
| 작고 단순한 고정-shape CPU 모델 | **LiteRT W8-AFP32** | CPU / XNNPACK | LiteRT FP32보다 훨씬 작고 Multi-P의 bucket warm-up 특성이 없음 |
| 단순 고정-shape LiteRT 기준 모델 | **LiteRT FP32** | CPU / XNNPACK | Soniqo 제공 T128/L64 FP32 LiteRT 모델 |
| 원본 ONNX CPU 동작 비교 | **ONNX FP32** | CPU / ORT | ONNX 기준 경로 확인용. 고성능 기기에서는 보통 최적화 LiteRT나 NPU보다 느림 |

**중요:** NPU를 지원한다고 해서 항상 NPU가 가장 빠른 것은 아닙니다. 매우 빠른 CPU에서는 최적화된 LiteRT/XNNPACK 모델이 QNN과 비슷하거나 더 빠를 수 있습니다. 최고 속도가 목적이면 각 경로의 cache 준비가 끝난 상태에서 직접 비교하는 것이 가장 정확합니다.

---

# 모델 출처, 변환 방식, 런타임

이 프로젝트에는 공식 모델, Soniqo 제공 LiteRT 모델, 그리고 본 프로젝트에서 직접 변환/양자화한 모델이 함께 들어갑니다. 모든 모델을 같은 출처로 설명하면 정확하지 않습니다.

| 앱 표시 모델 | 모델 출처 / 가공 방식 | 형식 / shape | 정밀도 | 예상 번들 크기 | 실행 경로 | Qualcomm NPU |
| --- | --- | --- | --- | ---: | --- | --- |
| **ONNX FP32** | 원본/공식 Supertonic-3 ONNX 모델 | Dynamic ONNX | FP32 | ~401 MB | ONNX Runtime | 지원 기기에서 QNN |
| **ONNX W8A16** | 공식 ONNX에서 **본 프로젝트용으로 직접 양자화/교정** | Static QDQ ONNX | W8 / A16 지향 QDQ | ~113 MB | ONNX Runtime | 지원 기기에서 QNN |
| **LiteRT FP32** | **Soniqo 제공 LiteRT FP32 모델** | 고정 T128 / L64 | FP32 | ~390 MB | Soniqo `speech-core` LiteRT 경로 | 없음 |
| **LiteRT W8-AFP32** | 고정 Soniqo 호환 LiteRT 모델에서 **본 프로젝트용으로 직접 양자화** | 고정 T128 / L64 | selective W8, FP32 activation | ~144 MB | 수정/확장된 `speech-core` LiteRT 경로 | 없음 |
| **LiteRT Multi-P** | 공식 Supertonic-3 ONNX에서 **본 프로젝트용으로 직접 변환**: fixed-shape specialization + GELU fusion + LiteRT 변환 | 7×7 static T/L MultiPreset | FP32 | ~443 MB | 수정/확장된 `speech-core` LiteRT 경로 | 없음 |
| **LiteRT Multi-P W8-AFP32** | 위 Multi-P 계열에서 **본 프로젝트용으로 직접 양자화** | 7×7 static T/L MultiPreset | selective W8, FP32 activation | ~269 MB | 수정/확장된 `speech-core` LiteRT 경로 | 없음 |

위 용량은 ModelManager의 다운로드 예상치를 기준으로 한 대략적인 decimal MB입니다. config/tokenizer와 기본 보이스 10종을 포함하지만 앱/native library와 런타임 cache 용량은 별도입니다.

## Soniqo `speech-core`와의 관계

네이티브 LiteRT 실행 경로는 **[Soniqo speech-core](https://github.com/soniqo/speech-core)**를 기반으로 합니다. Android JNI는 speech-core의 LiteRT target을 링크하고, 현재 활성화된 LiteRT 4종 모두에 대해 `speech_core::LiteRTSupertonicTts`를 생성합니다.

다만 이것이 **LiteRT 4종의 모델 파일 자체가 모두 Soniqo에서 왔다는 뜻은 아닙니다.**

- **LiteRT FP32**: Soniqo에서 제공한 LiteRT FP32 모델 번들을 사용합니다.
- **LiteRT W8-AFP32**: 고정 LiteRT 계열을 본 프로젝트에서 직접 양자화한 파생 모델입니다.
- **LiteRT Multi-P**: 공식 Supertonic-3 ONNX에서 본 프로젝트가 독립적으로 변환한 모델입니다.
- **LiteRT Multi-P W8-AFP32**: 위 Multi-P 모델을 본 프로젝트에서 직접 양자화한 파생 모델입니다.

speech-core 기반 LiteRT 경로는 본 프로젝트의 모델 변형과 실행 방식에 맞춰 확장되어 있으며, Multi-P signature 선택과 XNNPACK cache 처리 등이 추가되어 있습니다.

반면 **ONNX FP32와 ONNX W8A16은 speech-core를 추론 런타임으로 사용하지 않습니다.** 두 모델은 별도의 `OnnxSupertonicRunner` / ONNX Runtime 경로로 실행됩니다.

---

# Multi-P / MultiPreset 모델

`LiteRT Multi-P`와 `LiteRT Multi-P W8-AFP32`는 하나의 dynamic-shape 모델이 아닙니다. 다음 preset 조합으로 구성된 **49개의 static T/L signature**를 포함합니다.

```text
T = 32, 48, 64, 80, 96, 112, 128
L = 32, 48, 64, 80, 96, 112, 128
```

런타임은 현재 텍스트/latent shape에 맞는 static signature를 자동으로 선택합니다. 커스텀 LiteRT 런타임은 Selected-Subgraph delegation, signature 전환, XNNPACK packed-weight cache 재사용을 지원합니다.

## 최초 실행 / 새 bucket 준비 비용

Multi-P에는 중요한 cold-start 특성이 있습니다. **처음 T/L bucket을 준비할 때는 steady-state 합성보다 시간이 꽤 오래 걸릴 수 있습니다.**

49개 static signature 자체는 이미 모델 파일 안에 들어 있으므로 매번 49개의 새 모델 파일을 생성하는 것은 아닙니다. 하지만 새 설치나 비어 있는 cache에서는 선택된 signature의 런타임 상태와 XNNPACK weight packing/cache를 준비해야 합니다. 따라서 처음 만나는 T/L bucket은 이후 같은 bucket을 사용할 때보다 훨씬 느릴 수 있습니다.

앱은 시작할 때 49개 조합을 전부 eager 초기화하지 않습니다. 작은 `T32/L32` warm-up만 수행하고 나머지 signature는 lazy하게 준비합니다. 이후 다른 길이의 텍스트가 아직 사용하지 않은 bucket을 선택하면 일회성 준비 비용이 발생할 수 있습니다.

Multi-P 벤치마크는 **해당 T/L bucket이 이미 한 번 준비된 warm 상태**에서 비교하는 것이 맞습니다.

---

# Qualcomm NPU / QNN cache pre-generation

`ONNX FP32`와 `ONNX W8A16`의 Qualcomm QNN은 지원되는 최신 Snapdragon에서 사용하는 **정상적인 고성능 backend**입니다. 단순한 실험용 backend로 취급하지 않습니다.

다만 NPU에도 cold-start 비용이 있습니다. QNN graph/shape context는 효율적으로 재사용하기 전에 컴파일/준비 과정이 필요합니다. 앱에는 이를 미리 수행하는 **QNN cache pre-gen** 기능이 있습니다.

ONNX 모델을 다운로드한 뒤 NPU를 본격적으로 사용하거나 벤치마크하기 전에는 **QNN cache pre-generation을 먼저 실행하는 것을 권장합니다.** 사전 생성 과정 자체는 지원 context들을 준비하므로 시간이 걸릴 수 있지만, 이후에는 지속 cache를 재사용해서 일반 합성 중 context 컴파일 비용을 줄일 수 있습니다.

일반 **Pre-generation**과는 다른 기능입니다.

- **CPU Pre-generation**: 긴 텍스트 재생 중 다음 음성 chunk 생성을 미리 겹쳐 처리합니다.
- **QNN cache pre-gen**: 지속적으로 재사용할 NPU execution context를 미리 준비합니다.

RTF를 비교할 때는 QNN cache가 준비된 상태를 기준으로 해야 합니다. 아래 steady-state 벤치마크에는 최초 QNN context 컴파일 시간이 포함되지 않습니다.

> 지원 여부는 SoC, 펌웨어, QNN runtime 호환성, graph/context 지원 상태에 따라 달라질 수 있습니다. 현재 앱은 레거시 SM6350/lito 호환 케이스에서는 일반 NPU 경로를 노출하지 않습니다.

---

# 양자화 모델

`W8-AFP32`는 선택된 weight를 8-bit로 양자화하면서 activation은 FP32로 유지하는 방식입니다. **완전한 W8A8 모델이 아닙니다.**

`ONNX W8A16`은 Qualcomm QNN 경로를 중심으로 본 프로젝트에서 직접 교정/양자화한 static QDQ 모델 계열입니다. ONNX FP32보다 훨씬 작으며, 지원되는 최신 Snapdragon NPU를 사용할 때의 기본 추천 모델입니다.

양자화는 속도와 용량뿐 아니라 출력 품질에도 영향을 줄 수 있습니다. 실제 차이는 보이스, 텍스트, 기기, 런타임에 따라 달라집니다.

---

# 커스텀 보이스

앱은 **Supertonic-3 호환 voice-style JSON**을 직접 가져올 수 있습니다.

커스텀 보이스를 만드는 편리한 방법 중 하나는 다음 프로젝트입니다.

**[saurabhv749/supertonic3-voice-clone](https://github.com/saurabhv749/supertonic3-voice-clone)**

예시:

```bash
python train_style.py \
  --name my-voice \
  --target-wav-path voices/my-voice.wav \
  --num-steps 3000 \
  --learning-rate 0.0002
```

생성된 JSON은 일반적으로 다음과 비슷한 경로에 저장됩니다.

```text
logs/my-voice/my-voice.json
```

Supertonic LiteRT TTS에서 사용하는 방법:

1. 앱을 엽니다.
2. **Voice** 항목에서 **Import**를 누릅니다.
3. 생성된 `.json` 파일을 선택합니다.
4. `Custom · <name>` 형태로 보이스가 추가됩니다.
5. 독립 실행형 앱 또는 Android 시스템 TextToSpeech 엔진에서 사용합니다.

가져오기 시 일반 Supertonic-3 style tensor 구조를 확인합니다.

```text
style_ttl: [1, 50, 256]
style_dp : [1, 8, 16]
```

두 필드가 모두 필요합니다. 가져온 커스텀 보이스는 앱 전체의 공용 라이브러리에 저장되며 **6개 모델 모두에서 공유**되므로 모델별로 같은 보이스를 다시 가져올 필요가 없습니다.

Android TTS service에서도 가져온 커스텀 보이스를 일반 Android TextToSpeech API를 통해 노출합니다.

> 보이스 복제 품질은 원본 음원의 깨끗함과 학습 조건에 크게 좌우됩니다. 적절한 동의와 고지를 전제로 사용하세요.

추가 내용은 [`docs/custom-voice-and-regex.md`](docs/custom-voice-and-regex.md)를 참고하세요.

---

# 실측 성능 / RTF

**RTF(Real-Time Factor)** = 합성 시간 ÷ 생성된 오디오 길이이며, **낮을수록 빠릅니다.**

- `RTF 1.0` = 실시간
- `RTF 0.5` = 약 2배 빠름
- `RTF 0.10` = 약 10배 빠름
- `RTF 0.05` = 약 20배 빠름

아래 값은 제조사 수치가 아니라 실제 개발 중 실측값입니다. 텍스트, 언어, 보이스, step 수, thread 수, T/L bucket, cache 상태, 스케줄러, 발열, 앱/런타임 리비전에 따라 달라질 수 있습니다.

## Snapdragon 8 Elite Gen 5

steady-state 실측 RTF:

| 모델 | Backend | 4-step RTF | 8-step RTF |
| --- | --- | ---: | ---: |
| **ONNX FP32** | CPU (ORT) | 0.238 | 0.464 |
| **ONNX FP32** | NPU / QNN | 0.047 | 0.080 |
| **ONNX W8A16** | CPU (ORT) | 0.300 | 0.557 |
| **ONNX W8A16** | NPU / QNN | 0.045 | 0.075 |
| **LiteRT FP32** | CPU / XNNPACK | 0.077 | 0.128 |
| **LiteRT W8-AFP32** | CPU / XNNPACK | 0.057 | 0.097 |
| **LiteRT Multi-P** | CPU / XNNPACK | 0.056 | 0.097 |
| **LiteRT Multi-P W8-AFP32** | CPU / XNNPACK | **0.042** | **0.070** |

이 측정에서는 **LiteRT Multi-P W8-AFP32 / CPU XNNPACK이 전체에서 가장 빠른 결과**였고, ONNX W8A16 NPU와 ONNX FP32 NPU보다 근소하게 빨랐습니다. 따라서 지원되는 Snapdragon이라도 최고 속도가 중요하면 NPU 추천 모델과 최적화 LiteRT CPU 모델을 둘 다 비교할 가치가 있습니다.

> NPU는 **QNN cache pre-gen 후**, Multi-P는 해당 T/L bucket을 한 번 준비한 뒤 측정하는 것이 맞습니다. 위 표는 steady-state 합성값이며 최초 cache/bucket 생성 비용은 포함하지 않습니다.

## Snapdragon 690 / SM6350

구형 테스트 빌드, **2 thread + big-core affinity** 조건:

| 모델 | Backend | 4-step RTF | 8-step RTF |
| --- | --- | ---: | ---: |
| ONNX FP32 | ORT CPU | **0.477** | **0.855** |
| ONNX W8A16 | ORT CPU | 0.546 | 0.958 |
| LiteRT FP32 | XNNPACK | ~0.59 | ~1.05 |

이 비교의 LiteRT 값은 이후의 authoritative `SYNTH-END` 지표가 아니라 timestamp를 바탕으로 추정한 구형 수치이므로 대략적인 참고값으로 보는 것이 좋습니다.

현재 앱은 SM6350/lito에서 일반 Qualcomm NPU 경로를 노출하지 않습니다.

## Helio G99

현재 최적화된 CPU 경로에서 관찰된 Helio G99 실사용 성능은 **대체로 Snapdragon 690과 비슷한 급**입니다.

동일 리비전·동일 텍스트·동일 step 조건으로 남아 있는 정확한 표가 없기 때문에 임의의 세부 RTF 숫자는 적지 않습니다. 예전 런타임/모델 상태에서 기록된 훨씬 느린 `RTF ~1.0–1.2` 수치는 현재 성능을 대표하지 않습니다.

---

# 런타임 Backend

## LiteRT

현재 Release의 LiteRT 모델 4종은 모두 **CPU / XNNPACK**을 사용합니다.

활성 LiteRT 경로는 speech-core 기반 네이티브 TTS 구현을 사용하며, 현재 모델 계열과 실행 방식에 맞춘 프로젝트별 수정이 포함되어 있습니다. Multi-P는 추가로 커스텀 Selected-Subgraph/signature 처리와 shared XNNPACK cache 동작에 의존합니다.

과거 LiteRT GPU/NNAPI 실험 경로는 제거되었습니다. Qualcomm 가속은 현재 LiteRT가 아니라 ONNX/QNN 경로에서 처리합니다.

## ONNX Runtime

현재 Release의 ONNX 경로에서 사용 가능한 backend는 다음과 같습니다.

```text
CPU (ORT)
NPU
```

- **CPU (ORT)**: 일반 ONNX Runtime CPU Execution Provider
- **NPU**: 지원되는 Snapdragon에서 Qualcomm QNN 사용

### 현재 Release에는 ONNX XNNPACK이 없음

**현재 배포 APK에는 ONNX Runtime XNNPACK / `CPU XNN`이 포함되어 있지 않습니다.**

저장소에는 커스텀 QNN+XNNPACK ORT 빌드를 위한 스크립트와 과거 개발 코드가 남아 있지만, 이것이 현재 Release APK에서 `CPU XNN`을 사용할 수 있다는 의미는 아닙니다.

LiteRT는 계속 XNNPACK을 사용합니다. 이 제한은 **ONNX Runtime 경로에만** 해당합니다.

---

# Android 시스템 TTS 사용

1. APK를 설치합니다.
2. **Supertonic LiteRT** 앱을 한 번 실행합니다.
3. 사용할 모델을 선택하고 모델 번들을 다운로드합니다.
4. **Multi-P**를 쓰는 경우 첫 모델/bucket 준비가 warm 상태보다 오래 걸릴 수 있습니다.
5. **Qualcomm NPU**를 쓰는 경우 ONNX 모델 다운로드 후 **QNN cache pre-gen**을 실행합니다.
6. 기본 보이스 또는 가져온 커스텀 보이스를 선택합니다.
7. step, thread, speech speed를 원하는 대로 설정합니다.
8. Android 설정에서 **Supertonic LiteRT**를 시스템 TTS 엔진으로 선택합니다.

모델 파일은 필요할 때 다운로드되어 로컬에 저장됩니다. 필요한 모델을 받은 이후 실제 합성은 로컬에서 수행됩니다.

음성 속도는 신경망 합성 자체를 변형하는 대신 **합성 후 Sonic 처리**로 적용됩니다. 모델 내부 합성은 1.0× 기준으로 수행하고, 이후 속도를 바꾸는 방식으로 duration prediction을 과하게 가속할 때 생길 수 있는 발음 잘림/불안정을 피합니다.

---

# 발음 / 정규식 규칙

독립 실행형 앱과 Android 시스템 TTS service 모두에서 합성 전에 재사용 가능한 JSON 발음 규칙을 적용할 수 있습니다.

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

일반적인 요구 사항:

- Git for Windows
- JDK 17
- Android SDK / platform 35
- CMake 3.22.1
- NDK `29.0.14206865`

전체 로컬 설정/빌드:

```powershell
.\BUILD_ALL.bat
```

런타임이 이미 준비된 뒤 APK만 다시 빌드:

```powershell
.\build_apk.bat
```

또는:

```powershell
.\gradlew.bat :app:assembleDebug
```

Debug APK 출력 경로:

```text
app\build\outputs\apk\debug\app-debug.apk
```

## 커스텀 LiteRT 런타임

주요 진입점:

```text
BUILD_CUSTOM_LITERT_CMAKE.bat
tools/build_litert_2_2_selected_subgraph.sh
tools/build_litert_2_2_selected_subgraph_cmake.sh
```

커스텀 LiteRT 빌드는 Multi-P Selected-Subgraph/signature 실행과 XNNPACK cache 처리에 필요한 런타임 기능을 추가합니다.

## Qualcomm / ONNX Runtime 개발 빌드

저장소에는 Qualcomm/QNN용 커스텀 ORT 빌드 도구도 포함되어 있습니다.

```text
BUILD_CUSTOM_ORT_QNN_XNNPACK.bat
tools/build_custom_ort_qnn_xnnpack.ps1
third_party/onnxruntime/ORT-1.28.0-QNN-HTA.patch
```

이 파일들은 개발/빌드 도구입니다. 위에서 설명했듯 **현재 Release APK에는 ONNX `CPU XNN`이 노출되지 않습니다.**

---

# 연결된 기기에 설치

USB 디버깅을 켜고 ADB 연결을 확인합니다.

```powershell
adb devices
```

그다음:

```powershell
.\INSTALL_ON_PHONE.bat
```

설치 스크립트는 기존 앱을 교체하기 전에 signing certificate를 확인해서 서명 identity가 같은 경우 기존 다운로드 모델/앱 데이터를 유지할 수 있도록 합니다.

---

# 검증 / 진단

주요 스크립트:

```text
VERIFY_SOURCE_TREE.bat
VERIFY_TTS_ENGINE.bat
CAPTURE_SD690_RTF_LOG.bat
CAPTURE_SD690_FULL_DIAG.bat
PULL_PERF_PROFILES.bat
CLEAR_PERF_PROFILES.bat
COMPARE_LITERT_STOCK_CUSTOM.bat
```

`tools/` 아래에는 커스텀 LiteRT 런타임, native library identity, ELF/ZIP 16 KB alignment, profiling output 등을 검증하는 도구가 추가로 있습니다.

Deep Profiler는 프로파일링 자체의 overhead가 있으므로 **기본 OFF**이며, 일반 RTF 측정 때는 켜지 않는 것이 좋습니다.

---

# 저장소 구조

```text
app/          Android 앱 / 설정 UI
sdk/          Android TTS service, Kotlin 런타임 glue, ONNX runner, JNI bridge
speech-core/  Soniqo speech-core 기반 네이티브 LiteRT TTS 소스/경로
third_party/  LiteRT / ONNX Runtime patch 및 고정 지원 파일
tools/        런타임 빌더, 검증, 프로파일링, 변환 보조 도구
docs/         빌드, 런타임, 커스텀 보이스, 진단 문서
.github/      CI / Release workflow
```

주요 구현 파일:

```text
app/src/main/kotlin/com/supertonic/tts/MainActivity.kt
sdk/src/main/kotlin/audio/soniqo/speech/ModelManager.kt
sdk/src/main/kotlin/audio/soniqo/speech/OnnxSupertonicRunner.kt
sdk/src/main/kotlin/audio/soniqo/speech/TtsSettings.kt
sdk/src/main/kotlin/audio/soniqo/speech/service/SpeechTextToSpeechService.kt
sdk/src/main/cpp/jni_bridge.cpp
```

---

# 출처 / 관련 프로젝트

- **Supertonic-3** 원본 모델: [Supertone/supertonic-3](https://huggingface.co/Supertone/supertonic-3)
- **Soniqo speech-core**: [soniqo/speech-core](https://github.com/soniqo/speech-core) — 본 프로젝트에서 사용 및 확장한 네이티브 LiteRT TTS 실행/오케스트레이션 기반
- **커스텀 보이스 학습**: [saurabhv749/supertonic3-voice-clone](https://github.com/saurabhv749/supertonic3-voice-clone)

번들된 의존성의 고지 내용은 [`THIRD-PARTY-SONIC-NOTICE.txt`](THIRD-PARTY-SONIC-NOTICE.txt) 및 저장소의 third-party notice를 참고하세요.

## 참고

- 모델 weight는 이 저장소에 직접 포함하지 않으며 앱이 선택한 모델 번들을 필요할 때 다운로드합니다.
- 모델 용량은 대략적인 값이며 생성된 cache 용량은 포함하지 않습니다.
- Qualcomm NPU 사용 가능 여부는 기기와 런타임 호환성에 따라 달라집니다.
- Multi-P 최초 bucket 준비 시간과 QNN context 생성 시간은 steady-state RTF와 별개입니다.
- 커스텀 보이스 학습은 외부에서 수행합니다. 이 Android 앱은 호환되는 Supertonic-3 voice-style JSON을 가져와 사용하지만 기기에서 직접 보이스를 학습하지는 않습니다.
