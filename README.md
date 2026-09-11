# Supertonic LiteRT TTS for Android

**한국어** | [English](README.en.md)

**Supertonic-3**를 Android 시스템 TTS 엔진과 독립 실행형 앱에서 사용할 수 있도록 만든 프로젝트입니다. 완전 로컬 환경에서 빠르게 음성을 합성하는 것을 목표로 하며, **LiteRT / XNNPACK 기반 CPU 실행**과 지원되는 Snapdragon 기기에서의 **Qualcomm QNN NPU 가속**을 제공합니다.

여러 Supertonic-3 모델 변형과 **[Soniqo speech-core](https://github.com/soniqo/speech-core)**를 기반으로 확장한 LiteRT 실행 경로, 별도의 ONNX Runtime 경로, 커스텀 보이스 가져오기, 긴 텍스트 스트리밍, 발음 교정 및 정규식 기반 치환 규칙, QNN 컨텍스트 캐시, 벤치마크·진단 기능을 하나의 Android TTS 앱에 통합했습니다.

> **상태:** 현재 실제 사용 가능한 Android TTS 앱입니다. LiteRT CPU 경로와 지원되는 Qualcomm QNN NPU 경로 모두 일반적인 사용을 전제로 제공하며, 성능 최적화와 기기별 호환성, 진단 기능은 계속 개선하고 있습니다.

---

## 주요 기능

- Android **시스템 TextToSpeech 엔진** 지원
- 앱에서 직접 음성 합성, WAV 저장, 오디오 공유
- Supertonic-3 모델 6종 선택 가능
- 네이티브 **LiteRT 2.2 + XNNPACK** CPU 실행
- **Soniqo `speech-core` 기반 LiteRT 실행 경로**, 본 프로젝트에 맞춘 확장 포함
- 별도의 **ONNX Runtime 1.28** CPU / Qualcomm QNN 실행 경로
- 지원되는 최신 Snapdragon 기기에서 **Qualcomm NPU 가속**
- NPU 초기 구동 시간을 줄이기 위한 **QNN 실행 컨텍스트/캐시 사전 생성**
- Multi-P 모델의 static T/L bucket 자동 선택
- flow-matching 1~64 step, 기본값 **4 step**
- CPU thread 1~8, 기본값 **4 thread**
- 합성 후 Sonic 처리를 이용한 0.25×~3.00× 재생 속도 조절
- 기본 보이스 10종: `F1`~`F5`, `M1`~`M5`
- **Supertonic-3 호환 커스텀 보이스 JSON** 가져오기
- 가져온 커스텀 보이스를 6개 모델에서 공통 사용
- 발음 교정 및 정규식 기반 치환 규칙
- 긴 텍스트 CPU 합성을 위한 선택적 pre-generation
- 모델 SHA-256 검증, 런타임 프로파일링, 벤치마크 도구

## 요구 사항 / 런타임 버전

| 항목 | 현재 설정 |
| --- | --- |
| 최소 Android | Android 8.0 / API 26 |
| Target Android | API 34 |
| Compile SDK | 35 |
| ABI | arm64-v8a, x86_64 |
| LiteRT | 2.2 커스텀 Selected-Subgraph 런타임 |
| ONNX Runtime | 1.28 커스텀 Android 런타임 |
| Qualcomm 런타임 | 지원 기기에서 QNN / QAIRT 2.44 |
| Java / Kotlin target | Java 17 |

앱에서 선택할 수 있는 언어는 Auto/mixed (`na`), 한국어, 영어, 일본어, 중국어, 독일어, 프랑스어, 스페인어, 이탈리아어, 포르투갈어, 러시아어입니다.

---

# 어떤 모델을 쓰면 되나요?

가장 먼저 **기기에서 Qualcomm QNN NPU 경로를 사용할 수 있는지**를 확인하는 것이 좋습니다.

| 기기 / 목적 | 권장 모델 | Backend | 설명 |
| --- | --- | --- | --- |
| **QNN/HTP를 지원하는 최신 Snapdragon** — 예: 앱에서 NPU 선택이 가능한 Snapdragon 8 Gen 3급 기기 | **ONNX W8A16** | **NPU / QNN** | Qualcomm NPU를 사용할 때 가장 먼저 권장하는 모델입니다. ONNX FP32보다 훨씬 작고, 높은 QNN 성능을 내면서 TTS 추론 부하를 CPU에서 덜어낼 수 있습니다. |
| 지원되는 Snapdragon에서 FP32 모델을 사용하고 싶은 경우 | **ONNX FP32** | **NPU / QNN** | 원본 FP32 ONNX 모델을 그대로 사용하면서 QNN NPU 가속을 받을 수 있습니다. |
| **Qualcomm NPU를 사용할 수 없는 기기** / MediaTek / 일반 CPU 사용 | **LiteRT Multi-P W8-AFP32** | **CPU / XNNPACK** | 현재 프로젝트에서 가장 우선적으로 권장하는 범용 CPU 모델입니다. |
| 고성능 Snapdragon에서 순수 합성 속도가 가장 중요한 경우 | **ONNX W8A16 NPU와 LiteRT Multi-P W8-AFP32 CPU를 둘 다 비교** | NPU vs CPU | Snapdragon 8 Elite Gen 5 실측에서는 최적화된 LiteRT CPU 경로가 NPU보다 근소하게 빨랐습니다. |
| CPU에서 양자화하지 않은 FP32 모델이 필요한 경우 | **LiteRT Multi-P** | CPU / XNNPACK | FP32 Multi-P 모델이며 입력 길이에 맞춰 T/L bucket을 자동으로 선택합니다. |
| 용량이 작은 고정-shape CPU 모델이 필요한 경우 | **LiteRT W8-AFP32** | CPU / XNNPACK | LiteRT FP32보다 훨씬 작고 Multi-P처럼 새 bucket을 처음 준비하는 과정이 없습니다. |
| 단순한 고정-shape LiteRT 기준 모델이 필요한 경우 | **LiteRT FP32** | CPU / XNNPACK | Soniqo가 제공한 T128/L64 FP32 LiteRT 모델입니다. |
| 원본 ONNX의 CPU 동작을 비교하려는 경우 | **ONNX FP32** | CPU / ORT | ONNX 기준 성능을 확인하기 위한 용도입니다. 고성능 기기에서는 보통 최적화된 LiteRT나 NPU보다 느립니다. |

## NPU를 쓰는 이유

Qualcomm NPU의 장점은 단순히 **RTF가 낮고 합성이 빠르다는 것만이 아닙니다.** ONNX 추론의 주요 계산을 CPU가 아니라 QNN/HTP에서 처리하므로 TTS 때문에 CPU 코어를 계속 높은 부하로 사용할 필요가 줄어듭니다.

그만큼 CPU를 앱 UI, 텍스트 전처리, 오디오 처리, 다른 앱과 백그라운드 작업에 더 여유 있게 사용할 수 있습니다. 특히 시스템 TTS로 소설처럼 긴 글을 계속 읽는 상황에서는 순수 합성 속도가 몇 퍼센트 더 빠른지보다 **CPU 점유를 줄이고 다른 작업과의 자원 경쟁을 낮추는 것**이 실사용에서 더 중요할 수 있습니다.

전용 NPU를 사용하면 기기에 따라 CPU만 사용하는 것보다 전력 효율, 장시간 지속 성능, 발열 면에서 유리할 수 있습니다. 다만 이 부분은 SoC, 펌웨어, QNN 런타임과 기기 전력 정책에 따라 달라지므로 모든 기기에서 동일한 효과를 보장하지는 않습니다.

또한 **ONNX W8A16은 약 113 MB**로 ONNX FP32보다 훨씬 작으면서 QNN NPU 가속을 지원하므로, 지원되는 최신 Snapdragon에서는 속도와 모델 용량, CPU 여유를 함께 고려한 기본 추천 모델입니다.

**다만 NPU를 지원한다고 해서 항상 NPU가 가장 빠른 것은 아닙니다.** CPU 성능이 매우 높은 기기에서는 최적화된 LiteRT/XNNPACK 모델이 QNN과 비슷하거나 조금 더 빠를 수 있습니다. 실제 Snapdragon 8 Elite Gen 5 측정에서도 LiteRT Multi-P W8-AFP32 CPU가 NPU보다 근소하게 빨랐습니다. 최고 속도가 중요하다면 각 경로의 캐시 준비가 끝난 상태에서 직접 비교하는 것이 가장 정확합니다.

---

# 모델 출처와 실행 방식

이 프로젝트에는 공식 Supertonic-3 모델, Soniqo가 제공한 LiteRT 모델, 그리고 본 프로젝트에서 직접 변환하거나 양자화한 모델이 함께 포함되어 있습니다. 모델마다 출처와 변환 과정이 다릅니다.

| 앱 표시 모델 | 출처 / 가공 방식 | 형식 / shape | 정밀도 | 예상 번들 크기 | 실행 경로 | Qualcomm NPU |
| --- | --- | --- | --- | ---: | --- | --- |
| **ONNX FP32** | 공식 Supertonic-3 ONNX 모델 | Dynamic ONNX | FP32 | ~401 MB | ONNX Runtime | 지원 기기에서 QNN |
| **ONNX W8A16** | 공식 ONNX를 바탕으로 **본 프로젝트에서 직접 양자화·교정** | Static QDQ ONNX | W8 / A16 지향 QDQ | ~113 MB | ONNX Runtime | 지원 기기에서 QNN |
| **LiteRT FP32** | **Soniqo가 제공한 LiteRT FP32 모델** | 고정 T128 / L64 | FP32 | ~390 MB | Soniqo `speech-core` LiteRT 경로 | 없음 |
| **LiteRT W8-AFP32** | 고정 LiteRT 계열을 바탕으로 **본 프로젝트에서 직접 양자화** | 고정 T128 / L64 | selective W8, FP32 activation | ~144 MB | 수정·확장된 `speech-core` LiteRT 경로 | 없음 |
| **LiteRT Multi-P** | 공식 Supertonic-3 ONNX에서 **본 프로젝트가 직접 변환**: fixed-shape specialization + GELU fusion + LiteRT 변환 | 7×7 static T/L MultiPreset | FP32 | ~443 MB | 수정·확장된 `speech-core` LiteRT 경로 | 없음 |
| **LiteRT Multi-P W8-AFP32** | 위 Multi-P 계열을 바탕으로 **본 프로젝트에서 직접 양자화** | 7×7 static T/L MultiPreset | selective W8, FP32 activation | ~269 MB | 수정·확장된 `speech-core` LiteRT 경로 | 없음 |

위 용량은 ModelManager에 정의된 다운로드 예상치를 기준으로 한 대략적인 decimal MB입니다. config/tokenizer와 기본 보이스 10종은 포함하지만, 앱 자체와 네이티브 라이브러리, 생성된 런타임 캐시의 용량은 포함하지 않습니다.

## Soniqo `speech-core`와의 관계

네이티브 LiteRT 실행 경로는 **[Soniqo speech-core](https://github.com/soniqo/speech-core)**를 기반으로 합니다. Android JNI는 speech-core의 LiteRT target을 링크하고, 현재 활성화된 LiteRT 4종 모두에 `speech_core::LiteRTSupertonicTts`를 사용합니다.

다만 **LiteRT 모델 4종의 모델 파일이 모두 Soniqo에서 나온 것은 아닙니다.**

- **LiteRT FP32**: Soniqo가 제공한 LiteRT FP32 모델 번들
- **LiteRT W8-AFP32**: 고정 LiteRT 계열을 본 프로젝트에서 직접 양자화한 파생 모델
- **LiteRT Multi-P**: 공식 Supertonic-3 ONNX에서 본 프로젝트가 독립적으로 변환한 모델
- **LiteRT Multi-P W8-AFP32**: 위 Multi-P 모델을 본 프로젝트에서 직접 양자화한 파생 모델

speech-core 기반 LiteRT 실행 경로에는 Multi-P signature 선택, XNNPACK 캐시 처리 등 본 프로젝트에 필요한 기능이 추가되어 있습니다.

반대로 **ONNX FP32와 ONNX W8A16은 추론에 speech-core를 사용하지 않습니다.** 두 모델은 별도의 `OnnxSupertonicRunner` / ONNX Runtime 경로에서 실행됩니다.

---

# Multi-P / MultiPreset 모델

`LiteRT Multi-P`와 `LiteRT Multi-P W8-AFP32`는 하나의 dynamic-shape 모델이 아닙니다. 다음과 같은 **49개의 static T/L signature**를 모델 안에 포함합니다.

```text
T = 32, 48, 64, 80, 96, 112, 128
L = 32, 48, 64, 80, 96, 112, 128
```

런타임은 입력 텍스트와 latent 길이에 맞는 static signature를 자동으로 선택합니다. 커스텀 LiteRT 런타임은 Selected-Subgraph delegation, signature 전환, XNNPACK packed-weight cache 재사용을 지원합니다.

## 실제 한국어 소설을 바탕으로 설계한 bucket

이 T/L grid는 임의로 정한 값이 아닙니다. **실제 한국어 소설 원문 7개**를 모은 코퍼스를 분석해, 장문 TTS에서 자주 나타나는 길이 분포를 기준으로 설계했습니다. 분석에 사용한 원문은 약 **48.3 MB, 2,082만 자**, 초기 분할 기준 약 **65.3만 개 발화 단위** 규모입니다.

한국어 원문을 실제 합성에 사용하는 방식으로 정규화·토큰화한 뒤 T 길이 분포를 통계로 확인했습니다. 대표 백분위 값은 다음과 같습니다.

| 백분위 | T 길이 |
| ---: | ---: |
| P10 | 20 |
| P25 | 35 |
| P50 | 60 |
| P75 | 91 |
| P90 | 120 |
| P95 | 124 |

이 분포를 바탕으로 실제 한국어 소설에서 자주 나타나는 길이를 촘촘하게 커버하면서도 static signature 수가 지나치게 늘어나지 않도록 `32/48/64/80/96/112/128` 구간을 정했습니다. 즉 Multi-P의 T/L preset은 벤치마크용으로 임의 설정한 shape가 아니라 **실제 한국어 장문 읽기 workload를 통계적으로 분석해 정한 bucket 구성**입니다.

## 처음 실행하거나 새 bucket을 만났을 때

Multi-P는 cold-start 특성이 있습니다. **처음 사용하는 T/L bucket을 준비할 때는 평소 합성보다 시간이 눈에 띄게 오래 걸릴 수 있습니다.**

49개의 static signature 자체는 이미 모델 파일 안에 들어 있으므로 실행 중에 새 모델 49개를 만드는 것은 아닙니다. 다만 새 설치 직후나 캐시가 비어 있는 상태에서는 선택된 signature에 필요한 XNNPACK 상태와 weight packing/cache를 준비해야 합니다. 그래서 처음 만나는 T/L bucket은 같은 bucket을 다시 사용할 때보다 훨씬 느릴 수 있습니다.

앱은 시작할 때 49개 조합을 전부 미리 초기화하지 않습니다. 작은 `T32/L32` 조합만 먼저 warm-up하고, 나머지 signature는 실제로 필요해질 때 준비합니다. 이후 다른 길이의 텍스트가 아직 사용하지 않은 bucket을 선택하면 그 시점에 일회성 준비 비용이 발생할 수 있습니다.

Multi-P 성능을 비교할 때는 **해당 T/L bucket이 한 번 이상 준비된 상태**에서 측정하는 것이 맞습니다.

---

# Qualcomm NPU와 QNN 캐시 사전 생성

`ONNX FP32`와 `ONNX W8A16`의 Qualcomm QNN 경로는 지원되는 최신 Snapdragon에서 사용할 수 있는 **정식 고성능 실행 경로**입니다. 단순한 실험 기능으로만 취급하지 않습니다.

NPU를 선택하면 주요 ONNX 추론을 QNN/HTP로 넘길 수 있으므로 CPU가 TTS 추론에 계속 묶이는 것을 줄일 수 있습니다. 이는 시스템 TTS나 장문 읽기처럼 합성을 오래 지속하면서 동시에 UI, 텍스트 처리, 오디오 처리 또는 다른 앱도 함께 사용하는 상황에서 특히 유용합니다.

## QNN도 T/L bucket을 사용합니다

QNN NPU 경로도 Multi-P와 같은 길이 grid를 사용합니다.

```text
T = 32, 48, 64, 80, 96, 112, 128
L = 32, 48, 64, 80, 96, 112, 128
```

다만 **Multi-P의 49개 static LiteRT signature와 QNN cache는 같은 개념이 아닙니다.** Multi-P는 모델 파일 자체에 7×7 static signature가 들어 있는 반면, QNN은 ONNX 그래프를 T/L shape별로 특수화한 **실행 컨텍스트를 persistent cache로 만들어 재사용**합니다.

QNN cache pre-gen에서 생성하는 컨텍스트는 그래프별로 다음과 같습니다.

| ONNX 모델 | 사전 생성되는 QNN 컨텍스트 |
| --- | --- |
| **ONNX FP32** | Duration `T` 7개 + Encoder `T` 7개 + Vector Estimator `T×L` 49개 + Vocoder `L` 7개 = **총 70개** |
| **ONNX W8A16** | Encoder `T` 7개 + Vector Estimator `T×L` 49개 + Vocoder `L` 7개 = **총 63개** |

W8A16의 duration 단계는 현재 QNN이 아니라 **dynamic CPU 경로**를 사용하므로 duration용 7개 QNN 컨텍스트를 만들지 않습니다. FP32와 W8A16이 둘 다 설치되어 있으면 **QNN cache pre-gen 메뉴가 설치된 두 ONNX 모델을 순서대로 처리하므로 총 133개 컨텍스트**를 준비합니다.

## QNN cache pre-gen 실행 방법

1. 사용할 ONNX 모델(`ONNX FP32` 또는 `ONNX W8A16`)을 먼저 다운로드합니다.
2. 앱 메인 화면 오른쪽 위의 **`⋮` 메뉴**를 누릅니다.
3. **`QNN cache pre-gen`**을 선택합니다.
4. 상태 영역에 현재 모델, 그래프, shape와 진행 개수가 표시됩니다.
5. 완료 후에는 만들어진 persistent QNN context cache를 이후 NPU 합성에서 재사용합니다.

이 메뉴는 지원되는 Qualcomm NPU가 감지된 기기에서 표시됩니다. 현재 SM6350/lito 호환 경로에서는 QNN cache pre-gen을 사용하지 않습니다.

NPU도 처음 사용할 때는 QNN graph/shape context를 컴파일하고 준비하는 시간이 필요합니다. 사전 생성 자체에는 시간이 걸릴 수 있지만, 미리 한 번 생성해 두면 일반 합성 중에 새 shape를 만날 때 전체 컨텍스트 컴파일 비용을 다시 치르는 일을 줄일 수 있습니다.

이 기능은 일반 **Pre-generation**과는 목적이 다릅니다.

- **CPU Pre-generation**: 긴 텍스트를 읽을 때 다음 음성 chunk를 미리 만들어 재생과 합성을 겹쳐 처리합니다.
- **QNN cache pre-gen**: NPU에서 사용할 execution context를 T/L shape별로 미리 생성해 persistent cache에 저장합니다.

RTF를 비교할 때는 QNN 캐시가 준비된 상태를 기준으로 해야 합니다. 아래 성능 표에는 최초 QNN context 생성 시간은 포함하지 않습니다.

> NPU 지원 여부는 SoC, 펌웨어, QNN 런타임 호환성, graph/context 지원 상태에 따라 달라질 수 있습니다. 현재 앱은 레거시 SM6350/lito 호환 케이스에서는 일반 NPU 경로를 제공하지 않습니다.

---

# 양자화 모델

`W8-AFP32`는 일부 weight를 8-bit로 양자화하되 activation은 FP32로 유지하는 방식입니다. **완전한 W8A8 모델은 아닙니다.**

`ONNX W8A16`은 Qualcomm QNN 실행을 고려해 본 프로젝트에서 직접 교정·양자화한 static QDQ 모델입니다. ONNX FP32보다 용량이 크게 작으며, 지원되는 최신 Snapdragon에서 NPU를 사용할 때 가장 먼저 권장하는 모델입니다.

양자화는 속도와 용량뿐 아니라 출력 품질에도 영향을 줄 수 있습니다. 실제 차이는 보이스, 텍스트, 기기, 런타임에 따라 달라집니다.

---

# 커스텀 보이스

앱은 **Supertonic-3 호환 voice-style JSON**을 직접 가져올 수 있습니다.

커스텀 보이스 JSON을 만드는 방법 중 하나는 다음 프로젝트를 사용하는 것입니다.

**[saurabhv749/supertonic3-voice-clone](https://github.com/saurabhv749/supertonic3-voice-clone)**

예시:

```bash
python train_style.py \
  --name my-voice \
  --target-wav-path voices/my-voice.wav \
  --num-steps 3000 \
  --learning-rate 0.0002
```

생성된 JSON은 보통 다음과 비슷한 경로에 저장됩니다.

```text
logs/my-voice/my-voice.json
```

Supertonic LiteRT TTS에서 사용하는 방법:

1. 앱을 엽니다.
2. **Voice** 항목에서 **Import**를 누릅니다.
3. 생성된 `.json` 파일을 선택합니다.
4. `Custom · <name>` 형태로 보이스가 추가됩니다.
5. 앱에서 직접 사용하거나 Android 시스템 TextToSpeech 엔진을 통해 사용할 수 있습니다.

가져오기 시 다음 Supertonic-3 style tensor 구조를 확인합니다.

```text
style_ttl: [1, 50, 256]
style_dp : [1, 8, 16]
```

두 필드가 모두 필요합니다. 가져온 커스텀 보이스는 앱 전체에서 공유되므로 **6개 모델 모두에서 같은 보이스를 그대로 사용할 수 있으며**, 모델을 바꿀 때마다 다시 가져올 필요가 없습니다.

Android TTS service에서도 가져온 커스텀 보이스를 일반 Android TextToSpeech API를 통해 사용할 수 있습니다.

> 보이스 복제 품질은 원본 음원의 깨끗함과 학습 조건에 크게 좌우됩니다. 적절한 동의와 고지를 전제로 사용하세요.

자세한 내용은 [`docs/custom-voice-and-regex.md`](docs/custom-voice-and-regex.md)를 참고하세요.

---

# 실측 성능 / RTF

**RTF(Real-Time Factor)**는 `합성 시간 ÷ 생성된 오디오 길이`입니다. **낮을수록 빠릅니다.** 실시간 대비 속도는 대략 `1 / RTF`로 계산할 수 있습니다. 예를 들어 `RTF 0.5 ≈ 2배속`, `RTF 0.1 ≈ 10배속`입니다.

## Snapdragon 8 Elite Gen 5

steady-state 실측값:

| 모델 | Backend | 4-step RTF | 실시간 대비 | 8-step RTF | 실시간 대비 |
| --- | --- | ---: | ---: | ---: | ---: |
| **ONNX FP32** | CPU (ORT) | 0.238 | 약 4.2× | 0.464 | 약 2.2× |
| **ONNX FP32** | NPU / QNN | 0.047 | 약 21.3× | 0.080 | 약 12.5× |
| **ONNX W8A16** | CPU (ORT) | 0.300 | 약 3.3× | 0.557 | 약 1.8× |
| **ONNX W8A16** | NPU / QNN | 0.045 | 약 22.2× | 0.075 | 약 13.3× |
| **LiteRT FP32** | CPU / XNNPACK | 0.077 | 약 13.0× | 0.128 | 약 7.8× |
| **LiteRT W8-AFP32** | CPU / XNNPACK | 0.057 | 약 17.5× | 0.097 | 약 10.3× |
| **LiteRT Multi-P** | CPU / XNNPACK | 0.056 | 약 17.9× | 0.097 | 약 10.3× |
| **LiteRT Multi-P W8-AFP32** | CPU / XNNPACK | **0.042** | **약 23.8×** | **0.070** | **약 14.3×** |

이 측정에서는 **LiteRT Multi-P W8-AFP32 / CPU XNNPACK이 전체에서 가장 빨랐으며**, ONNX W8A16 NPU와 ONNX FP32 NPU보다 근소하게 앞섰습니다. 따라서 지원되는 Snapdragon이라도 순수 합성 속도가 가장 중요하다면 NPU 추천 모델과 최적화된 LiteRT CPU 모델을 둘 다 비교해 볼 가치가 있습니다.

> NPU는 **QNN cache pre-gen을 마친 뒤**, Multi-P는 해당 T/L bucket이 한 번 이상 준비된 뒤 측정하는 것이 맞습니다. 위 표는 steady-state 합성 성능이며 최초 캐시/버킷 준비 시간은 포함하지 않습니다.

## Snapdragon 690 / SM6350

구형 테스트 빌드, **2 thread + big-core affinity** 조건:

| 모델 | Backend | 4-step RTF | 실시간 대비 | 8-step RTF | 실시간 대비 |
| --- | --- | ---: | ---: | ---: | ---: |
| ONNX FP32 | ORT CPU | **0.477** | **약 2.1×** | **0.855** | **약 1.17×** |
| ONNX W8A16 | ORT CPU | 0.546 | 약 1.8× | 0.958 | 약 1.04× |
| LiteRT FP32 | XNNPACK | ~0.59 | 약 1.7× | ~1.05 | 약 0.95× |

이 비교의 LiteRT 값은 이후의 authoritative `SYNTH-END` 지표가 아니라 timestamp를 바탕으로 추정한 구형 수치이므로 대략적인 참고값으로 보는 것이 좋습니다.

현재 앱은 SM6350/lito에서 일반 Qualcomm NPU 경로를 제공하지 않습니다.

## Helio G99

현재 최적화된 CPU 경로에서 관찰된 Helio G99의 실사용 성능은 **대체로 Snapdragon 690과 비슷한 급**입니다.

동일 리비전·동일 텍스트·동일 step 조건으로 남아 있는 정확한 표가 없기 때문에 임의의 세부 RTF 숫자는 적지 않습니다. 과거 런타임/모델 상태에서 기록된 `RTF ~1.0–1.2` 수치는 현재 성능을 대표하지 않습니다.

---

# 런타임 Backend

## LiteRT

현재 Release의 LiteRT 모델 4종은 모두 **CPU / XNNPACK**을 사용합니다.

활성 LiteRT 경로는 speech-core 기반 네이티브 TTS 구현을 사용하며, 현재 모델 계열과 실행 방식에 맞춘 프로젝트별 수정이 포함되어 있습니다. Multi-P에는 추가로 커스텀 Selected-Subgraph/signature 처리와 shared XNNPACK cache 동작이 들어갑니다.

과거 LiteRT GPU/NNAPI 실험 경로는 제거되었습니다. Qualcomm 가속은 현재 LiteRT가 아니라 ONNX/QNN 경로에서 처리합니다.

## ONNX Runtime

현재 Release의 ONNX 경로에서 사용할 수 있는 backend는 다음과 같습니다.

```text
CPU (ORT)
NPU
```

- **CPU (ORT)**: 일반 ONNX Runtime CPU Execution Provider
- **NPU**: 지원되는 Snapdragon에서 Qualcomm QNN 사용

### 현재 Release에는 ONNX XNNPACK이 없음

**현재 배포 APK에는 ONNX Runtime XNNPACK / `CPU XNN`이 포함되어 있지 않습니다.**

저장소에는 커스텀 QNN+XNNPACK ORT 빌드를 위한 스크립트와 과거 개발 코드가 남아 있지만, 이것이 현재 Release APK에서 `CPU XNN`을 사용할 수 있다는 뜻은 아닙니다.

LiteRT는 계속 XNNPACK을 사용합니다. 이 제한은 **ONNX Runtime 경로에만** 해당합니다.

---

# Android 시스템 TTS 사용

1. APK를 설치합니다.
2. **Supertonic LiteRT** 앱을 한 번 실행합니다.
3. 사용할 모델을 선택하고 모델 번들을 다운로드합니다.
4. **Multi-P**를 쓴다면 처음 사용하는 T/L bucket은 준비 시간이 더 걸릴 수 있습니다.
5. **Qualcomm NPU**를 쓴다면 ONNX 모델을 받은 뒤 오른쪽 위 **`⋮` → `QNN cache pre-gen`**을 한 번 실행합니다.
6. 기본 보이스 또는 가져온 커스텀 보이스를 선택합니다.
7. step, thread, 음성 속도를 원하는 대로 설정합니다.
8. Android 설정에서 **Supertonic LiteRT**를 시스템 TTS 엔진으로 선택합니다.

모델 파일은 필요할 때 다운로드되어 로컬에 저장됩니다. 모델 다운로드가 끝난 뒤 실제 음성 합성은 로컬에서 수행됩니다.

음성 속도는 신경망 합성 자체를 변형하는 대신 **합성 후 Sonic 처리**로 적용합니다. 모델 내부 합성은 1.0× 기준으로 수행하고 이후 재생 속도를 조절하는 방식이라, duration prediction을 직접 과하게 가속할 때 생길 수 있는 발음 잘림이나 불안정을 피할 수 있습니다.

Android 시스템 TTS에서 전달되는 **말하기 속도(speech rate)는 현재 지원**합니다. 시스템의 요청 속도와 앱에 저장된 속도 설정을 함께 반영해 최종 속도를 계산합니다. 반면 **피치(pitch) 요청값은 현재 엔진에서 적용하지 않으므로 시스템 TTS의 피치 조절은 지원하지 않습니다.**

---

# 발음 교정 및 정규식 치환 규칙

앱 자체에서 규칙을 편집할 수 있으며, 이 규칙은 **독립 실행형 합성뿐 아니라 Android 시스템 TTS로 들어오는 텍스트에도 합성 전에 자동 적용**됩니다.

## 앱에서 규칙 추가하기

1. 메인 화면 오른쪽 위의 **`⋮` 메뉴**를 누릅니다.
2. **`Regex Editor`**를 선택합니다.
3. 위쪽의 **`+ ADD RULE`**을 누릅니다.
4. **Pattern**에 찾을 문자열 또는 정규식을 입력합니다.
5. **Replace with**에 바꿀 문자열을 입력합니다. 비워 두면 매칭된 부분을 삭제합니다.
6. 정규식을 사용할 경우 **Regex**를 체크합니다. 체크를 끄면 Pattern을 일반 문자열 그대로 찾아 바꿉니다.
7. 대소문자를 구분하지 않으려면 **Ignore case**를 체크합니다.
8. **SAVE**를 누릅니다.

규칙은 **목록 위에서 아래 순서대로** 적용됩니다. 앞 규칙의 치환 결과가 다음 규칙의 입력이 되므로 순서가 중요합니다.

각 규칙 카드에서 다음 작업을 할 수 있습니다.

- `↑` / `↓`: 규칙 적용 순서 변경
- `Enabled` / `Disabled`: 규칙을 삭제하지 않고 일시적으로 켜거나 끄기
- `EDIT`: Pattern, replacement, Regex/Ignore case 설정 수정
- `DELETE`: 규칙 삭제
- `RESET`: 현재 규칙 전체를 지우고 앱 기본 규칙으로 되돌림

> `RESET`은 현재 저장된 규칙 전체를 기본값으로 교체하므로 필요한 규칙은 먼저 Export하는 것이 좋습니다.

## JSON 가져오기 / 내보내기

메인 화면 오른쪽 위 **`⋮` 메뉴**에서 바로 사용할 수 있습니다.

- **`Import Regex`**: JSON 규칙 파일을 기존 규칙에 병합하여 가져옵니다.
- **`Export Regex`**: 현재 규칙 전체를 `supertonic-pronunciation-rules.json`으로 저장합니다.
- `Regex Editor` 화면 안의 **EXPORT** 버튼으로도 같은 형식의 JSON을 저장할 수 있습니다.

기본 JSON 형식은 다음과 같습니다.

```json
[
  {
    "term": "LLMs",
    "replacement": "L L Ems",
    "ignoreCase": true,
    "isRegex": false,
    "enabled": true
  },
  {
    "term": "RTX\\s*(\\d+)",
    "replacement": "알티엑스 $1",
    "ignoreCase": true,
    "isRegex": true,
    "enabled": true
  }
]
```

배열 자체뿐 아니라 `{ "rules": [...] }` 형식도 읽을 수 있습니다. 호환성을 위해 `term` 대신 `word`, `replacement` 대신 `pronunciation` 또는 `ipa` 키도 인식합니다. 잘못된 정규식이나 잘못된 replacement backreference는 TTS 전체를 중단시키지 않고 해당 규칙만 건너뜁니다.

자세한 내용은 [`docs/custom-voice-and-regex.md`](docs/custom-voice-and-regex.md)와 [`examples/pronunciation_rules_example.json`](examples/pronunciation_rules_example.json)을 참고하세요.

---

# 빌드

## 일반 Windows 빌드

필요한 환경:

- Git for Windows
- JDK 17
- Android SDK / platform 35
- CMake 3.22.1
- NDK `29.0.14206865`

전체 로컬 설정 및 빌드:

```powershell
.\BUILD_ALL.bat
```

런타임이 이미 준비된 상태에서 APK만 다시 빌드하려면:

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

커스텀 LiteRT 빌드는 Multi-P의 Selected-Subgraph/signature 실행과 XNNPACK 캐시 처리에 필요한 기능을 추가합니다.

## Qualcomm / ONNX Runtime 개발 빌드

저장소에는 Qualcomm/QNN용 커스텀 ORT 빌드 도구도 포함되어 있습니다.

```text
BUILD_CUSTOM_ORT_QNN_XNNPACK.bat
tools/build_custom_ort_qnn_xnnpack.ps1
third_party/onnxruntime/ORT-1.28.0-QNN-HTA.patch
```

이 파일들은 개발/빌드용입니다. 위에서 설명했듯 **현재 Release APK에는 ONNX `CPU XNN`이 제공되지 않습니다.**

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

설치 스크립트는 기존 앱을 교체하기 전에 서명 인증서를 확인합니다. 서명이 같으면 기존에 내려받은 모델과 앱 데이터를 유지한 채 업데이트할 수 있습니다.

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

`tools/` 아래에는 커스텀 LiteRT 런타임, 네이티브 라이브러리 식별, ELF/ZIP 16 KB 정렬, 프로파일링 결과 등을 검증하는 도구가 추가로 있습니다.

Deep Profiler는 프로파일링 자체의 오버헤드가 있으므로 **기본 OFF**이며, 일반 RTF 측정에서는 켜지 않는 것이 좋습니다.

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
- **Soniqo speech-core**: [soniqo/speech-core](https://github.com/soniqo/speech-core) — 본 프로젝트에서 사용하고 확장한 네이티브 LiteRT TTS 실행 기반
- **커스텀 보이스 학습**: [saurabhv749/supertonic3-voice-clone](https://github.com/saurabhv749/supertonic3-voice-clone)

번들된 의존성의 고지 내용은 [`THIRD-PARTY-SONIC-NOTICE.txt`](THIRD-PARTY-SONIC-NOTICE.txt)와 저장소의 third-party notice를 참고하세요.

## 참고

- 모델 weight는 이 저장소에 직접 포함하지 않으며 앱이 선택한 모델 번들을 필요할 때 다운로드합니다.
- 모델 용량은 대략적인 값이며 생성된 캐시 용량은 포함하지 않습니다.
- Qualcomm NPU 사용 가능 여부는 기기와 런타임 호환성에 따라 달라집니다.
- Multi-P 최초 bucket 준비 시간과 QNN context 생성 시간은 steady-state RTF와 별개입니다.
- 커스텀 보이스 학습은 외부에서 수행합니다. 이 Android 앱은 호환되는 Supertonic-3 voice-style JSON을 가져와 사용하지만 기기에서 직접 보이스를 학습하지는 않습니다.
