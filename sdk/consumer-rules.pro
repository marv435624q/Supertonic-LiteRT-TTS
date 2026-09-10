# Keep JNI callback interfaces — native code looks up methods by name
-keep class audio.soniqo.speech.NativeBridge { *; }
-keep class audio.soniqo.speech.NativeBridge$EventCallback { *; }
-keep class audio.soniqo.speech.NativeBridge$SynthesisCallback { *; }
-keep class audio.soniqo.speech.NativeBridge$LlmCallback { *; }

# Keep all public SDK classes
-keep class audio.soniqo.speech.SpeechPipeline { *; }
-keep class audio.soniqo.speech.SpeechSynthesizer { *; }
-keep class audio.soniqo.speech.SpeechSynthesizerConfig { *; }
-keep class audio.soniqo.speech.SpeechSynthesisResult { *; }
-keep class audio.soniqo.speech.SpeechConfig { *; }
-keep class audio.soniqo.speech.SpeechEvent { *; }
-keep class audio.soniqo.speech.SpeechEvent$* { *; }
-keep class audio.soniqo.speech.ModelManager { *; }
-keep class audio.soniqo.speech.ModelPrecision { *; }
-keep class audio.soniqo.speech.PipelineState { *; }
-keep class audio.soniqo.speech.service.SpeechTextToSpeechService { *; }

# Qualcomm QNN delegate creates native-backed classes/reflection entry points.
-keep class com.qualcomm.qti.** { *; }

# JNI runner ABI: native code looks up these exact class/method names
-keep class audio.soniqo.speech.SupertonicRunnerBridge { *; }

-keep class ai.onnxruntime.** { *; }
