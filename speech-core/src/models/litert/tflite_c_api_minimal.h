#pragma once

// Minimal stable TensorFlow Lite C ABI used by the Kokoro wrapper. libLiteRt
// 2.1.x exports these symbols alongside the newer LiteRtCompiledModel API.
// Keeping the declarations private avoids vendoring TFLite's much larger
// public header tree just for fixed-shape interpreter calls.

#include <cstddef>
#include <cstdint>

// TensorFlow Lite public XNNPACK delegate flag:
// TFLITE_XNNPACK_DELEGATE_FLAG_FORCE_FP16 == 1 << 2.
// Keep our private ABI header self-contained instead of importing the full
// TensorFlow Lite header tree.
inline constexpr std::uint32_t kTfLiteXNNPackDelegateFlagForceFp16 = 0x00000004u;

#if defined(_WIN32)
#define SPEECH_TFL_CAPI __declspec(dllimport)
#else
#define SPEECH_TFL_CAPI
#endif

extern "C" {

struct TfLiteModel;
struct TfLiteInterpreterOptions;
struct TfLiteInterpreter;
struct TfLiteSignatureRunner;
struct TfLiteTensor;
struct TfLiteOpaqueDelegate;
struct TfLiteDelegate;
struct TfLiteXNNPackDelegateWeightsCache;

// LiteRT 2.2.0 public XNNPACK delegate options ABI.
struct TfLiteXNNPackDelegateOptions {
    std::int32_t num_threads;
    std::uint32_t runtime_flags;
    std::uint32_t flags;
    TfLiteXNNPackDelegateWeightsCache* weights_cache;
    bool handle_variable_ops;
    const char* weight_cache_file_path;
    int weight_cache_file_descriptor;
    void* weight_cache_provider;
    bool weight_cache_lock_memory;
};

enum TfLiteStatus {
    kTfLiteOk = 0,
    kTfLiteError = 1,
};

SPEECH_TFL_CAPI TfLiteModel* TfLiteModelCreateFromFile(const char* model_path);
SPEECH_TFL_CAPI void TfLiteModelDelete(TfLiteModel* model);

SPEECH_TFL_CAPI TfLiteInterpreterOptions* TfLiteInterpreterOptionsCreate();
SPEECH_TFL_CAPI void TfLiteInterpreterOptionsDelete(
    TfLiteInterpreterOptions* options);
SPEECH_TFL_CAPI void TfLiteInterpreterOptionsSetNumThreads(
    TfLiteInterpreterOptions* options, std::int32_t num_threads);
SPEECH_TFL_CAPI void TfLiteInterpreterOptionsAddDelegate(
    TfLiteInterpreterOptions* options, TfLiteOpaqueDelegate* delegate);

SPEECH_TFL_CAPI TfLiteXNNPackDelegateOptions
TfLiteXNNPackDelegateOptionsDefault();
SPEECH_TFL_CAPI TfLiteOpaqueDelegate* TfLiteXNNPackDelegateCreate(
    const TfLiteXNNPackDelegateOptions* options);
SPEECH_TFL_CAPI void TfLiteXNNPackDelegateDelete(
    TfLiteOpaqueDelegate* delegate);

SPEECH_TFL_CAPI TfLiteInterpreter* TfLiteInterpreterCreate(
    const TfLiteModel* model,
    const TfLiteInterpreterOptions* optional_options);
SPEECH_TFL_CAPI void TfLiteInterpreterDelete(TfLiteInterpreter* interpreter);

// Supertonic LiteRT 2.2.0 shim: apply a delegate to exactly the subgraph
// referenced by one SignatureDef. This is implemented in our pinned custom
// libLiteRt.so and deliberately does not touch the other 48 VE subgraphs.
SPEECH_TFL_CAPI TfLiteStatus TfLiteInterpreterModifyGraphWithDelegateForSignature(
    TfLiteInterpreter* interpreter,
    TfLiteOpaqueDelegate* delegate,
    const char* signature_key);

// Qualcomm's official QNN LiteRT delegate exposes the classic TfLiteDelegate
// ABI rather than TfLiteOpaqueDelegate. Keep a separate bridge so the two
// delegate layouts are never reinterpreted as one another.
SPEECH_TFL_CAPI TfLiteStatus
TfLiteInterpreterModifyGraphWithClassicDelegateForSignature(
    TfLiteInterpreter* interpreter,
    TfLiteDelegate* delegate,
    const char* signature_key);

// Return the post-delegation execution-plan counts for one signature. A QNN
// delegate partition is represented by a DELEGATE node; every other node is a
// CPU remainder. This is diagnostic data, not a synthetic "NPU available"
// flag.
SPEECH_TFL_CAPI TfLiteStatus
SupertonicInterpreterSelectedSignatureDelegationStats(
    const TfLiteInterpreter* interpreter,
    const char* signature_key,
    int* delegate_partitions,
    int* remaining_nodes);

// Supertonic LiteRT extensions used by MultiPreset bucket switching.
// RemoveAllDelegates restores the Interpreter without recreating it; the
// provider wrappers expose XNNPACK's file-backed MMapWeightCacheProvider as
// an opaque C handle so all signatures of one stage/model can share packing.
SPEECH_TFL_CAPI TfLiteStatus TfLiteInterpreterRemoveAllDelegates(
    TfLiteInterpreter* interpreter);
SPEECH_TFL_CAPI void* SupertonicXnnpackWeightCacheProviderCreate();
SPEECH_TFL_CAPI TfLiteStatus
SupertonicXnnpackWeightCacheProviderLoadOrStartBuild(
    void* provider, const char* cache_path);
SPEECH_TFL_CAPI void SupertonicXnnpackWeightCacheProviderStopBuild(void* provider);
SPEECH_TFL_CAPI void SupertonicXnnpackWeightCacheProviderDelete(void* provider);

SPEECH_TFL_CAPI TfLiteSignatureRunner* TfLiteInterpreterGetSignatureRunner(
    const TfLiteInterpreter* interpreter, const char* signature_key);
SPEECH_TFL_CAPI void TfLiteSignatureRunnerDelete(
    TfLiteSignatureRunner* signature_runner);
SPEECH_TFL_CAPI std::size_t TfLiteSignatureRunnerGetInputCount(
    const TfLiteSignatureRunner* signature_runner);
SPEECH_TFL_CAPI const char* TfLiteSignatureRunnerGetInputName(
    const TfLiteSignatureRunner* signature_runner, std::int32_t input_index);
SPEECH_TFL_CAPI TfLiteTensor* TfLiteSignatureRunnerGetInputTensor(
    TfLiteSignatureRunner* signature_runner, const char* input_name);
SPEECH_TFL_CAPI TfLiteStatus TfLiteSignatureRunnerAllocateTensors(
    TfLiteSignatureRunner* signature_runner);
SPEECH_TFL_CAPI TfLiteStatus TfLiteSignatureRunnerInvoke(
    TfLiteSignatureRunner* signature_runner);
SPEECH_TFL_CAPI std::size_t TfLiteSignatureRunnerGetOutputCount(
    const TfLiteSignatureRunner* signature_runner);
SPEECH_TFL_CAPI const char* TfLiteSignatureRunnerGetOutputName(
    const TfLiteSignatureRunner* signature_runner, std::int32_t output_index);
SPEECH_TFL_CAPI const TfLiteTensor* TfLiteSignatureRunnerGetOutputTensor(
    const TfLiteSignatureRunner* signature_runner, const char* output_name);

SPEECH_TFL_CAPI std::int32_t TfLiteInterpreterGetInputTensorCount(
    const TfLiteInterpreter* interpreter);
SPEECH_TFL_CAPI std::int32_t TfLiteInterpreterGetOutputTensorCount(
    const TfLiteInterpreter* interpreter);
SPEECH_TFL_CAPI TfLiteTensor* TfLiteInterpreterGetInputTensor(
    const TfLiteInterpreter* interpreter, std::int32_t input_index);
SPEECH_TFL_CAPI const TfLiteTensor* TfLiteInterpreterGetOutputTensor(
    const TfLiteInterpreter* interpreter, std::int32_t output_index);
SPEECH_TFL_CAPI TfLiteStatus TfLiteInterpreterAllocateTensors(
    TfLiteInterpreter* interpreter);
SPEECH_TFL_CAPI TfLiteStatus TfLiteInterpreterInvoke(
    TfLiteInterpreter* interpreter);

SPEECH_TFL_CAPI std::int32_t TfLiteTensorNumDims(const TfLiteTensor* tensor);
SPEECH_TFL_CAPI std::int32_t TfLiteTensorDim(
    const TfLiteTensor* tensor, std::int32_t dim_index);
SPEECH_TFL_CAPI std::size_t TfLiteTensorByteSize(const TfLiteTensor* tensor);
SPEECH_TFL_CAPI const char* TfLiteTensorName(const TfLiteTensor* tensor);
SPEECH_TFL_CAPI TfLiteStatus TfLiteTensorCopyFromBuffer(
    TfLiteTensor* tensor, const void* input_data, std::size_t input_data_size);
SPEECH_TFL_CAPI TfLiteStatus TfLiteTensorCopyToBuffer(
    const TfLiteTensor* output_tensor, void* output_data,
    std::size_t output_data_size);

}  // extern "C"

#undef SPEECH_TFL_CAPI
