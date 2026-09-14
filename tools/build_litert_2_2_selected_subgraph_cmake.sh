#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LITERT_TAG="${LITERT_TAG:-v2.2.0}"
LITERT_NDK_VERSION="${LITERT_NDK_VERSION:-27.2.12479018}"
ANDROID_API_LEVEL="${ANDROID_API_LEVEL:-26}"
CMAKE_VERSION="${LITERT_CMAKE_VERSION:-4.0.1}"
OUT_DIR="${1:-$ROOT/litert}"
WORK_ROOT="${LITERT_CMAKE_BUILD_ROOT:-$ROOT/.ci/litert-selected-subgraph-cmake}"
SRC_DIR="$WORK_ROOT/LiteRT"
TOOLS_DIR="$WORK_ROOT/tools"
JOBS="${LITERT_BUILD_JOBS:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 8)}"
ALLOW_DOWNLOADS="${SUPERTONIC_LITERT_ALLOW_DOWNLOADS:-0}"

: "${ANDROID_HOME:?ANDROID_HOME must point to the Android SDK}"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/$LITERT_NDK_VERSION}"
export ANDROID_NDK_HOME
export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
export CMAKE_BUILD_PARALLEL_LEVEL="$JOBS"

if [[ ! -d "$ANDROID_NDK_HOME" ]]; then
  echo "[ERROR] Android NDK not found: $ANDROID_NDK_HOME" >&2
  echo "        Install ndk;$LITERT_NDK_VERSION or set ANDROID_NDK_HOME." >&2
  exit 2
fi
if [[ ! -f "$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" ]]; then
  echo "[ERROR] Android CMake toolchain missing under $ANDROID_NDK_HOME" >&2
  exit 2
fi

mkdir -p "$WORK_ROOT" "$TOOLS_DIR" "$OUT_DIR/arm64-v8a" "$OUT_DIR/x86_64"

version_ge() {
  python3 - "$1" "$2" <<'PY'
import sys
from itertools import zip_longest

def v(s):
    out=[]
    for p in s.split('.'):
        n=''.join(ch for ch in p if ch.isdigit())
        out.append(int(n or 0))
    return out

a,b=map(v,sys.argv[1:3])
for x,y in zip_longest(a,b,fillvalue=0):
    if x != y:
        raise SystemExit(0 if x > y else 1)
raise SystemExit(0)
PY
}

CMAKE_BIN="${LITERT_CMAKE_BIN:-}"
if [[ -z "$CMAKE_BIN" ]]; then
  if command -v cmake >/dev/null 2>&1; then
    installed="$(cmake --version | awk 'NR==1{print $3}')"
    if version_ge "$installed" "$CMAKE_VERSION"; then
      CMAKE_BIN="$(command -v cmake)"
    fi
  fi
fi
if [[ -z "$CMAKE_BIN" ]]; then
  case "$(uname -s)-$(uname -m)" in
    Linux-x86_64|Linux-amd64)
      CMAKE_ROOT="$TOOLS_DIR/cmake-$CMAKE_VERSION-linux-x86_64"
      CMAKE_BIN="$CMAKE_ROOT/bin/cmake"
      if [[ ! -x "$CMAKE_BIN" ]]; then
        archive="$TOOLS_DIR/cmake-$CMAKE_VERSION-linux-x86_64.tar.gz"
        if [[ -s "$archive" ]]; then
          echo "[LITERT] restoring cached CMake $CMAKE_VERSION archive"
          rm -rf "$CMAKE_ROOT"
          tar -xzf "$archive" -C "$TOOLS_DIR"
        elif [[ "$ALLOW_DOWNLOADS" == "1" ]]; then
          echo "[LITERT] downloading CMake $CMAKE_VERSION (explicitly allowed)"
          curl -fL --retry 3 --retry-delay 2 \
            -o "$archive.tmp" \
            "https://github.com/Kitware/CMake/releases/download/v$CMAKE_VERSION/cmake-$CMAKE_VERSION-linux-x86_64.tar.gz"
          mv "$archive.tmp" "$archive"
          rm -rf "$CMAKE_ROOT"
          tar -xzf "$archive" -C "$TOOLS_DIR"
        else
          echo "[ERROR] Cached CMake $CMAKE_VERSION is missing and network downloads are disabled." >&2
          echo "        Expected: $archive" >&2
          echo "        To bootstrap once only: SUPERTONIC_LITERT_ALLOW_DOWNLOADS=1" >&2
          exit 3
        fi
      fi
      ;;
    *)
      echo "[ERROR] CMake >= $CMAKE_VERSION is required." >&2
      echo "        Set LITERT_CMAKE_BIN=/path/to/cmake on this host." >&2
      exit 3
      ;;
  esac
fi

echo "[LITERT] CMake: $($CMAKE_BIN --version | head -n1)"
echo "[LITERT] NDK  : $ANDROID_NDK_HOME"
echo "[LITERT] API  : android-$ANDROID_API_LEVEL"
echo "[LITERT] jobs : $JOBS"
echo "[LITERT] network downloads: $([[ "$ALLOW_DOWNLOADS" == "1" ]] && echo ALLOWED || echo DISABLED)"

if [[ ! -d "$SRC_DIR/.git" ]]; then
  if [[ "$ALLOW_DOWNLOADS" != "1" ]]; then
    echo "[ERROR] Cached LiteRT source is missing and network downloads are disabled." >&2
    echo "        Expected: $SRC_DIR" >&2
    echo "        To bootstrap once only: SUPERTONIC_LITERT_ALLOW_DOWNLOADS=1" >&2
    exit 5
  fi
  echo "[LITERT] cloning $LITERT_TAG (explicitly allowed)"
  rm -rf "$SRC_DIR"
  git clone --depth 1 --branch "$LITERT_TAG" \
    https://github.com/google-ai-edge/LiteRT.git "$SRC_DIR"
else
  echo "[LITERT] resetting existing cached source to $LITERT_TAG (no fetch)"
  if ! git -C "$SRC_DIR" rev-parse -q --verify "refs/tags/$LITERT_TAG^{commit}" >/dev/null 2>&1; then
    if [[ "$ALLOW_DOWNLOADS" == "1" ]]; then
      git -C "$SRC_DIR" fetch --depth 1 origin "refs/tags/$LITERT_TAG:refs/tags/$LITERT_TAG"
    else
      echo "[ERROR] Local LiteRT cache does not contain tag $LITERT_TAG and downloads are disabled." >&2
      exit 5
    fi
  fi
  git -C "$SRC_DIR" reset --hard "$LITERT_TAG"
  # Keep expensive CMake/host-flatc build trees across retries. The source tree
  # itself is reset to the pinned v2.2.0 tag above; CMake will incrementally
  # rebuild only objects affected by the regenerated Supertonic patch.
  git -C "$SRC_DIR" clean -fdx \
    -e host_flatc_build/ \
    -e litert/cmake_build_android_arm64_v8a/ \
    -e litert/cmake_build_android_x86_64/
fi

# Keep the upstream LiteRT/TFLite/XNNPACK build graph intact. Runtime changes
# are limited to the C API declaration, one tiny public Interpreter wrapper for
# delegate removal, one bridge source around XNNPACK's upstream persistent-cache
# lifecycle, and attaching that source to the existing CMake libLiteRt.so target.
echo "[LITERT] applying minimal selected-subgraph C API patch"
python3 - "$SRC_DIR" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])
header = root / "tflite/core/c/c_api.h"
interpreter_header = root / "tflite/core/interpreter.h"
xnnpack_delegate = root / "tflite/delegates/xnnpack/xnnpack_delegate.cc"
selected_source = root / "litert/supertonic_selected_subgraph_c_api.cc"
cmake_file = root / "litert/c/CMakeLists.txt"
root_cmake_file = root / "litert/CMakeLists.txt"

h = header.read_text(encoding="utf-8")
h_anchor = """TFL_CAPI_EXPORT extern void TfLiteInterpreterDelete(
    TfLiteInterpreter* interpreter);

/// Returns the number of input tensors associated with the model.
"""
h_insert = """TFL_CAPI_EXPORT extern void TfLiteInterpreterDelete(
    TfLiteInterpreter* interpreter);

/// Applies `delegate` only to the subgraph referenced by `signature_key`.
///
/// Supertonic extension for LiteRT 2.2.0. This exposes the existing C++
/// Interpreter selected-subgraph delegate path through the C ABI so a
/// multi-signature model can prepare only the active fixed T/L graph.
///
/// Returns kTfLiteError for null arguments or an unknown signature.
TFL_CAPI_EXPORT extern TfLiteStatus
TfLiteInterpreterModifyGraphWithDelegateForSignature(
    TfLiteInterpreter* interpreter,
    TfLiteOpaqueDelegate* delegate,
    const char* signature_key);

/// Applies a classic TfLiteDelegate (used by Qualcomm's QNN LiteRT delegate)
/// only to the subgraph referenced by `signature_key`.
TFL_CAPI_EXPORT extern TfLiteStatus
TfLiteInterpreterModifyGraphWithClassicDelegateForSignature(
    TfLiteInterpreter* interpreter,
    TfLiteDelegate* delegate,
    const char* signature_key);

/// Counts delegated partitions and remaining CPU nodes in one signature.
TFL_CAPI_EXPORT extern TfLiteStatus
SupertonicInterpreterSelectedSignatureDelegationStats(
    const TfLiteInterpreter* interpreter,
    const char* signature_key,
    int* delegate_partitions,
    int* remaining_nodes);

/// Restores all delegated subgraphs without recreating the Interpreter.
TFL_CAPI_EXPORT extern TfLiteStatus TfLiteInterpreterRemoveAllDelegates(
    TfLiteInterpreter* interpreter);

/// Opaque XNNPACK file-cache provider wrappers used to share packed weights
/// across SignatureDefs of the same stage/model.
TFL_CAPI_EXPORT extern void* SupertonicXnnpackWeightCacheProviderCreate();
TFL_CAPI_EXPORT extern TfLiteStatus SupertonicXnnpackWeightCacheProviderLoadOrStartBuild(
    void* provider, const char* cache_path);
TFL_CAPI_EXPORT extern void SupertonicXnnpackWeightCacheProviderStopBuild(
    void* provider);
TFL_CAPI_EXPORT extern void SupertonicXnnpackWeightCacheProviderDelete(
    void* provider);

/// Build marker for the hybrid DEPTHWISE_CONV_2D XNNPACK patch.
TFL_CAPI_EXPORT extern const char*
SupertonicXnnpackDynamicDepthwisePatchVersion();

/// Returns the number of input tensors associated with the model.
"""
if h.count(h_anchor) != 1:
    raise SystemExit("c_api.h anchor mismatch; refusing to patch unknown LiteRT source")
header.write_text(h.replace(h_anchor, h_insert), encoding="utf-8")

# Interpreter::RemoveAllDelegates() exists in upstream v2.2.0 but is private.
# Do not duplicate its logic in the bridge. Expose one tiny public wrapper that
# calls the existing private method from inside the class, preserving upstream
# semantics (all subgraphs restored, interpreter invokable afterwards).
ih = interpreter_header.read_text(encoding="utf-8")
ih_anchor = r"""  /// \warning Experimental interface, subject to change. \n
  /// \brief Get the error reporter associated with this interpreter.
  ErrorReporter* error_reporter() const { return error_reporter_; }

 private:
"""
ih_insert = r"""  /// \warning Experimental interface, subject to change. \n
  /// \brief Get the error reporter associated with this interpreter.
  ErrorReporter* error_reporter() const { return error_reporter_; }

  // Supertonic v2.2.0 extension: public bridge to the existing private
  // RemoveAllDelegates() implementation. No delegate-removal logic is copied.
  TfLiteStatus SupertonicRemoveAllDelegatesForSignatureSwitch() {
    return RemoveAllDelegates();
  }

 private:
"""
if ih.count(ih_anchor) != 1:
    raise SystemExit("interpreter.h public-wrapper anchor mismatch")
interpreter_header.write_text(ih.replace(ih_anchor, ih_insert), encoding="utf-8")

# LiteRT v2.2.0 delegates dynamic-weight CONV_2D (F32 activation + INT8
# per-channel weights) through XNNPACK, but DEPTHWISE_CONV_2D still rejects
# the same mixed types before subgraph construction. Mirror the upstream
# dynamic Conv2D lowering here, while preserving the native Depthwise subgraph
# API and TFLite's [1,H,W,C*depth_multiplier] filter layout (channel_dim=3).
xd = xnnpack_delegate.read_text(encoding="utf-8")
if "SUPERTONIC_DYNAMIC_DEPTHWISE_QD8_V3" not in xd:
    mixed_anchor = """    if (input_tensor.type != output_tensor.type ||
        input_tensor.type != filter_tensor.type) {
      TF_LITE_MAYBE_KERNEL_LOG(
          logging_context,
          \"unsupported mixed types in DEPTHWISE_CONV_2D operator #%d\",
          node_index);
      return kTfLiteError;
    }
"""
    mixed_insert = """    // SUPERTONIC_DYNAMIC_DEPTHWISE_QD8_V3
    // Match the existing dynamic Conv2D path: quantize one NHWC batch to QD8
    // at runtime while keeping the original INT8 channelwise filter and FP32
    // bias/output. This prevents WI8-AFP32 Depthwise nodes from falling back
    // to the builtin TFLite kernel solely because their activation is FP32.
    const bool dynamically_quantized =
        (!delegate.disable_dynamically_quantized_ops() &&
         input_tensor.type == kTfLiteFloat32 &&
         filter_tensor.type == kTfLiteInt8 &&
         bias_tensor.type == kTfLiteFloat32 &&
         output_tensor.type == kTfLiteFloat32);
    if (input_tensor.type != output_tensor.type ||
        ((input_tensor.type != filter_tensor.type) && !dynamically_quantized)) {
      TF_LITE_MAYBE_KERNEL_LOG(
          logging_context,
          \"unsupported mixed types in DEPTHWISE_CONV_2D operator #%d\",
          node_index);
      return kTfLiteError;
    }
"""
    if xd.count(mixed_anchor) != 1:
        raise SystemExit("xnnpack_delegate.cc dynamic-depthwise mixed-type anchor mismatch")
    xd = xd.replace(mixed_anchor, mixed_insert)

    call_anchor = """      const xnn_status status = xnn_define_depthwise_convolution_2d(
          subgraph,
          /*input_padding_top=*/0,
          /*input_padding_right=*/0,
          /*input_padding_bottom=*/0,
          /*input_padding_left=*/0, static_cast<uint32_t>(kernel_height),
          static_cast<uint32_t>(kernel_width),
          static_cast<uint32_t>(dwconv_params->stride_height),
          static_cast<uint32_t>(dwconv_params->stride_width),
          static_cast<uint32_t>(dwconv_params->dilation_height_factor),
          static_cast<uint32_t>(dwconv_params->dilation_width_factor),
          static_cast<uint32_t>(dwconv_params->depth_multiplier),
          /*input_channels=*/
          static_cast<uint32_t>(output_channels /
                                dwconv_params->depth_multiplier),
          output_min, output_max,
          /*input_id=*/input_output_tensors.at(node->inputs->data[0]),
          /*filter_id=*/filter_xnn_id,
          /*bias_id=*/bias_xnn_id,
          /*output_id=*/input_output_tensors.at(node->outputs->data[0]), flags);
"""
    call_insert = """      xnn_status status = xnn_status_invalid_state;
      if (dynamically_quantized) {
        TF_LITE_KERNEL_LOG(
            logging_context,
            "[SUPERTONIC-XNN-DW-QD8-V3] delegated DEPTHWISE_CONV_2D node #%d",
            node_index);
        TfLiteAffineQuantization* filter_params =
            reinterpret_cast<TfLiteAffineQuantization*>(
                filter_tensor.quantization.params);
        if (filter_params == nullptr || filter_params->scale == nullptr ||
            filter_params->scale->size <= 0 ||
            filter_params->zero_point == nullptr ||
            filter_params->zero_point->size <= 0) {
          TF_LITE_KERNEL_LOG(
              logging_context,
              \"invalid quantization parameters for DEPTHWISE_CONV_2D node #%d\",
              node_index);
          return kTfLiteError;
        }
        for (int i = 0; i < filter_params->zero_point->size; ++i) {
          if (filter_params->zero_point->data[i] != 0) {
            TF_LITE_KERNEL_LOG(
                logging_context,
                \"non-zero QC8W zero point %d in DEPTHWISE_CONV_2D node #%d is unsupported\",
                filter_params->zero_point->data[i], node_index);
            return kTfLiteError;
          }
        }

        // QD8/F32/QC8W consumes one scale per output channel. Keep valid
        // per-channel scales; expand a legal per-tensor WI8 scale exactly once.
        if (filter_params->scale->size != output_channels) {
          if (filter_params->scale->size != 1) {
            TF_LITE_KERNEL_LOG(
                logging_context,
                \"unexpected DEPTHWISE_CONV_2D scale count %d, expected 1 or %d\",
                filter_params->scale->size, output_channels);
            return kTfLiteError;
          }
          const float per_tensor_scale = filter_params->scale->data[0];
          TfLiteFloatArrayFree(filter_params->scale);
          filter_params->scale = TfLiteFloatArrayCreate(output_channels);
          for (int i = 0; i < output_channels; ++i) {
            filter_params->scale->data[i] = per_tensor_scale;
          }
          filter_params->quantized_dimension = 3;
        }

        uint32_t dq_quantized_id = XNN_INVALID_VALUE_ID;
        std::vector<size_t> input_dims(
            &input_tensor.dims->data[0],
            &input_tensor.dims->data[NumDimensions(&input_tensor)]);
        status = xnn_define_dynamically_quantized_tensor_value(
            subgraph, xnn_datatype_qdint8, input_dims.size(),
            /*num_nonbatch_dims=*/3, input_dims.data(), XNN_INVALID_VALUE_ID,
            /*flags=*/0, &dq_quantized_id);
        if (status != xnn_status_success) {
          TF_LITE_KERNEL_LOG(
              logging_context,
              \"failed to create QD8 value for DEPTHWISE_CONV_2D node #%d\",
              node_index);
          return kTfLiteError;
        }

        status = xnn_define_unary(
            subgraph, xnn_unary_convert, /*params=*/nullptr,
            /*input_id=*/input_output_tensors.at(node->inputs->data[0]),
            /*output_id=*/dq_quantized_id, /*flags=*/0);
        if (status != xnn_status_success) {
          TF_LITE_KERNEL_LOG(
              logging_context,
              \"failed to define F32->QD8 conversion for DEPTHWISE_CONV_2D node #%d\",
              node_index);
          return kTfLiteError;
        }

        std::vector<size_t> filter_dims(
            &filter_tensor.dims->data[0],
            &filter_tensor.dims->data[NumDimensions(&filter_tensor)]);
        uint32_t kernel_id = XNN_INVALID_VALUE_ID;
        status = xnn_define_channelwise_quantized_tensor_value(
            subgraph, xnn_datatype_qcint8, filter_params->scale->data,
            filter_dims.size(), /*channel_dim=*/3, filter_dims.data(),
            GetTensorData<int8_t>(&filter_tensor), XNN_INVALID_VALUE_ID,
            /*flags=*/0, &kernel_id);
        if (status != xnn_status_success) {
          TF_LITE_KERNEL_LOG(
              logging_context,
              \"failed to create QC8W filter for DEPTHWISE_CONV_2D node #%d\",
              node_index);
          return kTfLiteError;
        }

        status = xnn_define_depthwise_convolution_2d(
            subgraph,
            /*input_padding_top=*/0,
            /*input_padding_right=*/0,
            /*input_padding_bottom=*/0,
            /*input_padding_left=*/0, static_cast<uint32_t>(kernel_height),
            static_cast<uint32_t>(kernel_width),
            static_cast<uint32_t>(dwconv_params->stride_height),
            static_cast<uint32_t>(dwconv_params->stride_width),
            static_cast<uint32_t>(dwconv_params->dilation_height_factor),
            static_cast<uint32_t>(dwconv_params->dilation_width_factor),
            static_cast<uint32_t>(dwconv_params->depth_multiplier),
            /*input_channels=*/
            static_cast<uint32_t>(output_channels /
                                  dwconv_params->depth_multiplier),
            output_min, output_max,
            /*input_id=*/dq_quantized_id,
            /*filter_id=*/kernel_id,
            /*bias_id=*/bias_xnn_id,
            /*output_id=*/input_output_tensors.at(node->outputs->data[0]), flags);
      } else {
        status = xnn_define_depthwise_convolution_2d(
            subgraph,
            /*input_padding_top=*/0,
            /*input_padding_right=*/0,
            /*input_padding_bottom=*/0,
            /*input_padding_left=*/0, static_cast<uint32_t>(kernel_height),
            static_cast<uint32_t>(kernel_width),
            static_cast<uint32_t>(dwconv_params->stride_height),
            static_cast<uint32_t>(dwconv_params->stride_width),
            static_cast<uint32_t>(dwconv_params->dilation_height_factor),
            static_cast<uint32_t>(dwconv_params->dilation_width_factor),
            static_cast<uint32_t>(dwconv_params->depth_multiplier),
            /*input_channels=*/
            static_cast<uint32_t>(output_channels /
                                  dwconv_params->depth_multiplier),
            output_min, output_max,
            /*input_id=*/input_output_tensors.at(node->inputs->data[0]),
            /*filter_id=*/filter_xnn_id,
            /*bias_id=*/bias_xnn_id,
            /*output_id=*/input_output_tensors.at(node->outputs->data[0]), flags);
      }
"""
    if xd.count(call_anchor) != 1:
        raise SystemExit("xnnpack_delegate.cc dynamic-depthwise call anchor mismatch")
    xd = xd.replace(call_anchor, call_insert)
    xnnpack_delegate.write_text(xd, encoding="utf-8")

selected_source.write_text(r'''#include "tflite/core/c/c_api.h"

#include <new>
#include <map>
#include <string>
#include <vector>

#include <android/log.h>

#include "tflite/c/c_api_internal.h"
#include "tflite/core/interpreter.h"
#include "tflite/delegates/xnnpack/weight_cache.h"
#include "tflite/schema/schema_generated.h"
#include "xnnpack.h"

extern "C" TFL_CAPI_EXPORT
#if defined(__clang__) || defined(__GNUC__)
__attribute__((used, visibility("default")))
#endif
TfLiteStatus TfLiteInterpreterModifyGraphWithDelegateForSignature(
    TfLiteInterpreter* interpreter,
    TfLiteOpaqueDelegate* delegate,
    const char* signature_key) {
  if (interpreter == nullptr || interpreter->impl == nullptr ||
      delegate == nullptr || signature_key == nullptr) {
    return kTfLiteError;
  }

  const int subgraph_index =
      interpreter->impl->GetSubgraphIndexFromSignature(signature_key);
  if (subgraph_index < 0) {
    return kTfLiteError;
  }

  const TfLiteStatus status = interpreter->impl->ModifyGraphWithDelegate(
      delegate, std::vector<int>{subgraph_index});
  if (status != kTfLiteOk) return status;

  // Diagnostic-only: enumerate the post-delegation execution plan. Delegate
  // partitions appear as DELEGATE nodes; every other entry is a builtin/custom
  // node that still executes outside XNNPACK. This runs only when a signature
  // graph is (re)delegated, never inside Invoke(), so it does not contaminate
  // steady-state RTF measurements.
  const tflite::Subgraph* subgraph = interpreter->impl->subgraph(subgraph_index);
  if (subgraph != nullptr) {
    std::map<std::string, int> counts;
    int remaining = 0;
    int delegate_partitions = 0;
    for (const int node_index : subgraph->execution_plan()) {
      const auto* nr = subgraph->node_and_registration(node_index);
      if (nr == nullptr) continue;
      const TfLiteRegistration& reg = nr->second;
      const int builtin_code = reg.builtin_code;
      if (builtin_code == static_cast<int>(tflite::BuiltinOperator_DELEGATE)) {
        ++delegate_partitions;
        continue;
      }

      std::string op_name;
      if (builtin_code == static_cast<int>(tflite::BuiltinOperator_CUSTOM)) {
        op_name = reg.custom_name != nullptr ? reg.custom_name : "CUSTOM";
      } else if (builtin_code >= 0 &&
                 builtin_code <= static_cast<int>(tflite::BuiltinOperator_MAX)) {
        const char* enum_name = tflite::EnumNameBuiltinOperator(
            static_cast<tflite::BuiltinOperator>(builtin_code));
        op_name = enum_name != nullptr ? enum_name : "UNKNOWN";
      } else {
        op_name = "UNKNOWN";
      }
      ++remaining;
      ++counts[op_name];
      __android_log_print(ANDROID_LOG_ERROR, "tflite",
          "[SUPERTONIC-XNN-REMAIN] signature=%s node=%d op=%s builtin_code=%d",
          signature_key, node_index, op_name.c_str(), builtin_code);
    }

    __android_log_print(ANDROID_LOG_ERROR, "tflite",
        "[SUPERTONIC-XNN-REMAIN-SUMMARY] signature=%s remaining=%d delegate_partitions=%d op_types=%zu",
        signature_key, remaining, delegate_partitions, counts.size());
    for (const auto& kv : counts) {
      __android_log_print(ANDROID_LOG_ERROR, "tflite",
          "[SUPERTONIC-XNN-REMAIN-COUNT] signature=%s op=%s count=%d",
          signature_key, kv.first.c_str(), kv.second);
    }
  }

  return status;
}

extern "C" TFL_CAPI_EXPORT
#if defined(__clang__) || defined(__GNUC__)
__attribute__((used, visibility("default")))
#endif
TfLiteStatus TfLiteInterpreterModifyGraphWithClassicDelegateForSignature(
    TfLiteInterpreter* interpreter,
    TfLiteDelegate* delegate,
    const char* signature_key) {
  if (interpreter == nullptr || interpreter->impl == nullptr ||
      delegate == nullptr || signature_key == nullptr) {
    return kTfLiteError;
  }
  const int subgraph_index =
      interpreter->impl->GetSubgraphIndexFromSignature(signature_key);
  if (subgraph_index < 0) return kTfLiteError;
  return interpreter->impl->ModifyGraphWithDelegate(
      delegate, std::vector<int>{subgraph_index});
}

extern "C" TFL_CAPI_EXPORT
#if defined(__clang__) || defined(__GNUC__)
__attribute__((used, visibility("default")))
#endif
TfLiteStatus SupertonicInterpreterSelectedSignatureDelegationStats(
    const TfLiteInterpreter* interpreter,
    const char* signature_key,
    int* delegate_partitions,
    int* remaining_nodes) {
  if (interpreter == nullptr || interpreter->impl == nullptr ||
      signature_key == nullptr || delegate_partitions == nullptr ||
      remaining_nodes == nullptr) {
    return kTfLiteError;
  }
  const int subgraph_index =
      interpreter->impl->GetSubgraphIndexFromSignature(signature_key);
  if (subgraph_index < 0) return kTfLiteError;
  const tflite::Subgraph* subgraph = interpreter->impl->subgraph(subgraph_index);
  if (subgraph == nullptr) return kTfLiteError;

  int delegated = 0;
  int remaining = 0;
  for (const int node_index : subgraph->execution_plan()) {
    const auto* nr = subgraph->node_and_registration(node_index);
    if (nr == nullptr) continue;
    if (nr->second.builtin_code ==
        static_cast<int>(tflite::BuiltinOperator_DELEGATE)) {
      ++delegated;
    } else {
      ++remaining;
    }
  }
  *delegate_partitions = delegated;
  *remaining_nodes = remaining;
  return kTfLiteOk;
}

extern "C" TFL_CAPI_EXPORT
#if defined(__clang__) || defined(__GNUC__)
__attribute__((used, visibility("default")))
#endif
TfLiteStatus TfLiteInterpreterRemoveAllDelegates(
    TfLiteInterpreter* interpreter) {
  if (interpreter == nullptr || interpreter->impl == nullptr) {
    return kTfLiteError;
  }
  return interpreter->impl->SupertonicRemoveAllDelegatesForSignatureSwitch();
}

struct SupertonicXnnpackWeightCacheProvider {
  tflite::xnnpack::MMapWeightCacheProvider impl;
};

extern "C" TFL_CAPI_EXPORT
#if defined(__clang__) || defined(__GNUC__)
__attribute__((used, visibility("default")))
#endif
void* SupertonicXnnpackWeightCacheProviderCreate() {
  return new (std::nothrow) SupertonicXnnpackWeightCacheProvider();
}

extern "C" TFL_CAPI_EXPORT
#if defined(__clang__) || defined(__GNUC__)
__attribute__((used, visibility("default")))
#endif
TfLiteStatus SupertonicXnnpackWeightCacheProviderLoadOrStartBuild(
    void* provider, const char* cache_path) {
  if (provider == nullptr || cache_path == nullptr) return kTfLiteError;

  // Match the upstream XNNPACK delegate ordering exactly: the public
  // TfLiteXNNPackDelegateCreateWithThreadpool() entry point calls
  // xnn_initialize() before Delegate construction, and Delegate construction
  // is where MMapWeightCacheProvider::LoadOrStartBuild() normally runs.
  //
  // Supertonic intentionally owns one stage-shared provider outside the
  // delegate, so this C ABI can be called before the first delegate exists.
  // A persisted cache invokes CheckFingerprints() during Load(); fingerprint
  // dispatch requires XNNPACK initialization. Without this guard, first-run
  // cache creation works but the next process can branch through an
  // uninitialized fingerprint function pointer and crash at PC=0.
  if (xnn_initialize(/*allocator=*/nullptr) != xnn_status_success) {
    return kTfLiteError;
  }

  auto* p = static_cast<SupertonicXnnpackWeightCacheProvider*>(provider);
  // Keep the upstream state machine intact. A new/invalid file starts a clean
  // build; an existing valid file is loaded as finalized/read-only. In
  // particular, never reopen a loaded cache and graft a builder onto it.
  return p->impl.LoadOrStartBuild(cache_path) ? kTfLiteOk : kTfLiteError;
}

extern "C" TFL_CAPI_EXPORT
#if defined(__clang__) || defined(__GNUC__)
__attribute__((used, visibility("default")))
#endif
void SupertonicXnnpackWeightCacheProviderStopBuild(void* provider) {
  if (provider == nullptr) return;
  static_cast<SupertonicXnnpackWeightCacheProvider*>(provider)->impl.StopBuild();
}

extern "C" TFL_CAPI_EXPORT
#if defined(__clang__) || defined(__GNUC__)
__attribute__((used, visibility("default")))
#endif
void SupertonicXnnpackWeightCacheProviderDelete(void* provider) {
  delete static_cast<SupertonicXnnpackWeightCacheProvider*>(provider);
}

extern "C" TFL_CAPI_EXPORT
#if defined(__clang__) || defined(__GNUC__)
__attribute__((used, visibility("default")))
#endif
const char* SupertonicXnnpackDynamicDepthwisePatchVersion() {
  return "dedicated-depthwise-QD8-F32-QC8W-v3";
}
''', encoding="utf-8")

c = cmake_file.read_text(encoding="utf-8")
anchor = """# C API shared library
add_library(litert_runtime_c_api_shared_lib SHARED empty.cc)
set_target_properties(litert_runtime_c_api_shared_lib PROPERTIES
    OUTPUT_NAME "LiteRt"
    POSITION_INDEPENDENT_CODE ON
)
"""
insert = """# C API shared library
add_library(litert_runtime_c_api_shared_lib SHARED empty.cc)

# Supertonic extension: compile one small bridge directly into the upstream
# CMake shared runtime. Do not alter TFLite, XNNPACK, Android platform selects,
# or the toolchain configuration.
target_sources(litert_runtime_c_api_shared_lib PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../supertonic_selected_subgraph_c_api.cc
)

set_target_properties(litert_runtime_c_api_shared_lib PROPERTIES
    OUTPUT_NAME "LiteRt"
    POSITION_INDEPENDENT_CODE ON
)
"""
if c.count(anchor) != 1:
    raise SystemExit("litert/c/CMakeLists.txt shared-library anchor mismatch")
c = c.replace(anchor, insert)

# Retain the two direct APIs Supertonic resolves from libLiteRt.so and make the
# resulting DSO compatible with Android 16 KB page-size devices. These are link
# properties only; they do not change XNNPACK compilation or CPU dispatch.
anchor2 = """target_compile_options(litert_runtime_c_api_shared_lib PRIVATE
    -fno-exceptions -fno-unwind-tables -fno-asynchronous-unwind-tables
    -ffunction-sections -fdata-sections
)
"""
insert2 = """target_compile_options(litert_runtime_c_api_shared_lib PRIVATE
    -fno-exceptions -fno-unwind-tables -fno-asynchronous-unwind-tables
    -ffunction-sections -fdata-sections
)
if(CMAKE_SYSTEM_NAME STREQUAL "Android")
  target_link_options(litert_runtime_c_api_shared_lib PRIVATE
      -Wl,-z,max-page-size=16384
      -Wl,-z,common-page-size=16384
      -Wl,--undefined=TfLiteXNNPackDelegateCreate
      -Wl,--undefined=TfLiteInterpreterModifyGraphWithDelegateForSignature
      -Wl,--undefined=TfLiteInterpreterRemoveAllDelegates
      -Wl,--undefined=SupertonicXnnpackWeightCacheProviderCreate
      -Wl,--undefined=SupertonicXnnpackWeightCacheProviderLoadOrStartBuild
      -Wl,--undefined=SupertonicXnnpackWeightCacheProviderStopBuild
      -Wl,--undefined=SupertonicXnnpackWeightCacheProviderDelete
      -Wl,--undefined=SupertonicXnnpackDynamicDepthwisePatchVersion
  )
endif()
"""
if c.count(anchor2) != 1:
    raise SystemExit("litert/c/CMakeLists.txt compile-options anchor mismatch")
cmake_file.write_text(c.replace(anchor2, insert2), encoding="utf-8")

# Keep the v2.2.0 core CMake/link graph intact. Only suppress two unrelated
# top-level configure branches that are not dependencies of libLiteRt.so:
# vendor SDK dispatches (which download NeuroPilot/QAIRT/LiteCore at configure
# time) and tensor examples (which download accelerator/example artifacts).
# The LITERT_SKIP_VENDORS name deliberately matches the upstream CMake issue
# proposal instead of pretending the runtime itself is built with GPU code off.
rc = root_cmake_file.read_text(encoding="utf-8")
rc_anchor = (
    "add_subdirectory(compiler)\n"
    "add_subdirectory(vendors)\n"
    "add_subdirectory(tools)\n\n"
    "if(EXISTS \"${CMAKE_CURRENT_SOURCE_DIR}/../tensor/CMakeLists.txt\")\n"
    "  add_subdirectory(\"${CMAKE_CURRENT_SOURCE_DIR}/../tensor\"\n"
    "                   \"${CMAKE_CURRENT_BINARY_DIR}/tensor\")\n"
    "endif()\n"
)
rc_insert = (
    "add_subdirectory(compiler)\n"
    "option(LITERT_SKIP_VENDORS \"Skip built-in vendor SDK dispatch configuration\" OFF)\n"
    "if(LITERT_SKIP_VENDORS)\n"
    "  message(STATUS \"Supertonic: vendor SDK configuration skipped; core LiteRT link graph unchanged\")\n"
    "else()\n"
    "  add_subdirectory(vendors)\n"
    "endif()\n"
    "add_subdirectory(tools)\n\n"
    "option(LITERT_SKIP_TENSOR_EXAMPLES \"Skip tensor examples/artifact downloads\" OFF)\n"
    "if(NOT LITERT_SKIP_TENSOR_EXAMPLES AND EXISTS \"${CMAKE_CURRENT_SOURCE_DIR}/../tensor/CMakeLists.txt\")\n"
    "  add_subdirectory(\"${CMAKE_CURRENT_SOURCE_DIR}/../tensor\"\n"
    "                   \"${CMAKE_CURRENT_BINARY_DIR}/tensor\")\n"
    "endif()\n"
)
if rc.count(rc_anchor) != 1:
    raise SystemExit("litert/CMakeLists.txt vendor/tensor anchor mismatch")
rc = rc.replace(rc_anchor, rc_insert)

# If host flatc is not already cached, normal upstream CMake would bootstrap it
# with FetchContent. In offline mode fail explicitly instead of using network.
hf_anchor = (
    "    else()\n"
    "      file(MAKE_DIRECTORY \"${_root_host_flatc_dir}\")\n"
)
hf_insert = (
    "    else()\n"
    "      if(FETCHCONTENT_FULLY_DISCONNECTED)\n"
    "        message(FATAL_ERROR \"Cached host flatc is missing at ${_root_host_flatc_dir}/_deps/flatbuffers-build/flatc; offline build refuses to download it\")\n"
    "      endif()\n"
    "      file(MAKE_DIRECTORY \"${_root_host_flatc_dir}\")\n"
)
if rc.count(hf_anchor) != 1:
    raise SystemExit("litert/CMakeLists.txt host-flatc anchor mismatch")
root_cmake_file.write_text(rc.replace(hf_anchor, hf_insert), encoding="utf-8")
PY

git -C "$SRC_DIR" diff --check
git -C "$SRC_DIR" add -N litert/supertonic_selected_subgraph_c_api.cc
git -C "$SRC_DIR" diff -- \
  tflite/core/c/c_api.h \
  tflite/core/interpreter.h \
  tflite/delegates/xnnpack/xnnpack_delegate.cc \
  tflite/delegates/xnnpack/weight_cache.h \
  tflite/delegates/xnnpack/weight_cache.cc \
  litert/supertonic_selected_subgraph_c_api.cc \
  litert/c/CMakeLists.txt \
  litert/CMakeLists.txt \
  > "$WORK_ROOT/LiteRT-2.2.0-selected-subgraph-cmake.generated.patch"

READELF="$(command -v llvm-readelf || command -v readelf || true)"
if [[ -z "$READELF" ]]; then
  # NDK llvm-readelf is always available and understands Android ELF files.
  READELF="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
fi
if [[ ! -x "$READELF" ]]; then
  echo "[ERROR] llvm-readelf/readelf is required" >&2
  exit 4
fi

EXPECTED_XNNPACK_REV="ae746db8255aa93704012a98b4b030eefd17357d"

discover_xnnpack_root() {
  local build_dir="$1"
  local candidate=""
  local cache=""

  # 1) Prefer CMake's own resolved path. Depending on the TensorFlow/LiteRT
  # CMake helper version this may be XNNPACK_SOURCE_DIR or xnnpack_SOURCE_DIR.
  for cache in     "$build_dir/CMakeCache.txt"     "$build_dir/tflite_build/CMakeCache.txt"; do
    [[ -f "$cache" ]] || continue
    candidate="$(sed -n -E 's#^(XNNPACK_SOURCE_DIR|xnnpack_SOURCE_DIR):[^=]*=(.*)$#\2#p' "$cache" | tail -n1)"
    if [[ -n "$candidate" && -f "$candidate/src/subgraph/depthwise-convolution-2d.c" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done

  # 2) TFLite's classic CMake dependency downloader uses *-source names,
  # while FetchContent uses _deps/*-src. Support both and nested tflite_build.
  for candidate in     "$build_dir/xnnpack-source"     "$build_dir/XNNPACK-source"     "$build_dir/tflite_build/xnnpack-source"     "$build_dir/tflite_build/XNNPACK-source"     "$build_dir/_deps/xnnpack-src"     "$build_dir/tflite_build/_deps/xnnpack-src"; do
    if [[ -f "$candidate/src/subgraph/depthwise-convolution-2d.c" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done

  # 3) Last-resort bounded discovery. Match the actual source file rather than
  # assuming a dependency directory naming convention.
  candidate="$(find "$build_dir" -maxdepth 6 -type f     -path '*/src/subgraph/depthwise-convolution-2d.c' -print -quit 2>/dev/null || true)"
  if [[ -n "$candidate" ]]; then
    candidate="${candidate%/src/subgraph/depthwise-convolution-2d.c}"
    if [[ -f "$candidate/CMakeLists.txt" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  fi
  return 1
}

patch_xnnpack_dynamic_depthwise() {
  local build_dir="$1"
  local xnn_root
  xnn_root="$(discover_xnnpack_root "$build_dir" || true)"
  local target="${xnn_root:+$xnn_root/src/subgraph/depthwise-convolution-2d.c}"

  if [[ -z "$xnn_root" || ! -f "$target" ]]; then
    echo "[ERROR] CMake configured without a discoverable XNNPACK source tree" >&2
    echo "        searched CMakeCache + xnnpack-source + _deps/xnnpack-src under:" >&2
    echo "        $build_dir" >&2
    echo "        candidates found:" >&2
    find "$build_dir" -maxdepth 5 -type d       \( -iname '*xnnpack*' -o -iname '*XNNPACK*' \) -print >&2 2>/dev/null || true
    exit 13
  fi
  echo "[LITERT] XNNPACK source: $xnn_root"

  # This build intentionally requires a git-backed XNNPACK dependency so the
  # exact LiteRT v2.2.0 pin can be verified cryptographically by commit ID.
  # The user's working CMake cache resolves this form at <build>/xnnpack. Do not
  # silently accept an unversioned archive and merely assume it is equivalent.
  if [[ ! -d "$xnn_root/.git" ]]; then
    echo "[ERROR] XNNPACK source is not a git checkout; exact revision cannot be verified" >&2
    echo "        resolved: $xnn_root" >&2
    echo "        required commit: $EXPECTED_XNNPACK_REV" >&2
    exit 14
  fi
  local actual
  actual="$(git -C "$xnn_root" rev-parse HEAD)"
  if [[ "$actual" != "$EXPECTED_XNNPACK_REV" ]]; then
    echo "[ERROR] LiteRT v2.2.0 resolved unexpected XNNPACK revision" >&2
    echo "        expected: $EXPECTED_XNNPACK_REV" >&2
    echo "        actual  : $actual" >&2
    exit 14
  fi
  git -C "$xnn_root" reset --hard "$EXPECTED_XNNPACK_REV" >/dev/null
  git -C "$xnn_root" clean -fd >/dev/null
  echo "[LITERT] XNNPACK revision verified by git: $actual"

  # Retries reuse dependency source trees. If a previous attempt already
  # applied the complete V3 source patch, do not patch it twice.
  if grep -Fq 'SUPERTONIC_DYNAMIC_DEPTHWISE_QD8_V3' "$target" && \
     grep -Fq 'xnn_create_convolution2d_nhwc_qd8_f32_qc8w' "$target" && \
     grep -Fq 'xnn_reshape_convolution2d_nhwc_qd8_f32_qc8w' "$target" && \
     grep -Fq 'xnn_setup_convolution2d_nhwc_qd8_f32_qc8w' "$target"; then
    echo "[LITERT] XNNPACK dynamic Depthwise v3 already patched; reusing source tree"
    return 0
  fi

  python3 - "$target" <<'PY_XNN'
from pathlib import Path
import sys

p = Path(sys.argv[1])
s = p.read_text(encoding="utf-8")

# create(): know runtime input datatype as well as filter/output datatypes.
old = """  enum xnn_status status;\n  const enum xnn_datatype filter_datatype = values[filter_id].datatype;\n"""
new = """  enum xnn_status status;\n  // SUPERTONIC_DYNAMIC_DEPTHWISE_QD8_V3\n  const enum xnn_datatype input_datatype = values[input_id].datatype;\n  const enum xnn_datatype filter_datatype = values[filter_id].datatype;\n"""
if s.count(old) != 1:
    raise SystemExit("XNNPACK depthwise create datatype anchor mismatch")
s = s.replace(old, new)

# create(): replace only the NHWC QCINT8 block. Static QS8/QC8W remains;
# dynamic QD8/F32/QC8W uses the existing operator API plus the Depthwise flag.
nhwc = s.index("  } else {\n    assert((values[input_id].flags & XNN_VALUE_FLAG_LAYOUT_NCHW) == 0);")
q8 = s.index("      case xnn_datatype_qcint8:\n      {", nhwc)
qu8 = s.index("      case xnn_datatype_quint8:", q8)
new_block = """      case xnn_datatype_qcint8:\n      {\n        switch (input_datatype) {\n          case xnn_datatype_qdint8:\n            status = xnn_create_convolution2d_nhwc_qd8_f32_qc8w(\n              node->params.depthwise_convolution_2d.input_padding_top,\n              node->params.depthwise_convolution_2d.input_padding_right,\n              node->params.depthwise_convolution_2d.input_padding_bottom,\n              node->params.depthwise_convolution_2d.input_padding_left,\n              node->params.depthwise_convolution_2d.kernel_height,\n              node->params.depthwise_convolution_2d.kernel_width,\n              node->params.depthwise_convolution_2d.subsampling_height,\n              node->params.depthwise_convolution_2d.subsampling_width,\n              node->params.depthwise_convolution_2d.dilation_height,\n              node->params.depthwise_convolution_2d.dilation_width,\n              node->params.depthwise_convolution_2d.input_channels /* groups */,\n              1 /* group_input_channels */,\n              node->params.depthwise_convolution_2d.depth_multiplier /* group_output_channels */,\n              node->params.depthwise_convolution_2d.input_channels /* input_channel_stride */,\n              node->params.depthwise_convolution_2d.input_channels * node->params.depthwise_convolution_2d.depth_multiplier /* output_channel_stride */,\n              values[filter_id].quantization.channelwise_scale,\n              filter_data, bias_data,\n              node->activation.output_min,\n              node->activation.output_max,\n              node->flags | XNN_FLAG_DEPTHWISE_CONVOLUTION,\n              weights_cache,\n              &opdata->operator_objects[0]);\n            break;\n          case xnn_datatype_qint8:\n          {\n            const float output_scale = values[output_id].quantization.scale;\n            const int32_t output_zero_point = values[output_id].quantization.zero_point;\n            const int8_t output_min = xnn_qs8_quantize(node->activation.output_min, output_scale, output_zero_point);\n            const int8_t output_max = xnn_qs8_quantize(node->activation.output_max, output_scale, output_zero_point);\n            status = xnn_create_convolution2d_nhwc_qs8_qc8w(\n              node->params.depthwise_convolution_2d.input_padding_top,\n              node->params.depthwise_convolution_2d.input_padding_right,\n              node->params.depthwise_convolution_2d.input_padding_bottom,\n              node->params.depthwise_convolution_2d.input_padding_left,\n              node->params.depthwise_convolution_2d.kernel_height,\n              node->params.depthwise_convolution_2d.kernel_width,\n              node->params.depthwise_convolution_2d.subsampling_height,\n              node->params.depthwise_convolution_2d.subsampling_width,\n              node->params.depthwise_convolution_2d.dilation_height,\n              node->params.depthwise_convolution_2d.dilation_width,\n              node->params.depthwise_convolution_2d.input_channels /* groups */,\n              1 /* group_input_channels */,\n              node->params.depthwise_convolution_2d.depth_multiplier /* group_output_channels */,\n              node->params.depthwise_convolution_2d.input_channels /* input_channel_stride */,\n              node->params.depthwise_convolution_2d.input_channels * node->params.depthwise_convolution_2d.depth_multiplier /* output_channel_stride */,\n              (int8_t) values[input_id].quantization.zero_point,\n              values[input_id].quantization.scale,\n              values[filter_id].quantization.channelwise_scale,\n              filter_data, bias_data,\n              (int8_t) output_zero_point,\n              output_scale, output_min, output_max,\n              node->flags | XNN_FLAG_DEPTHWISE_CONVOLUTION,\n              weights_cache,\n              &opdata->operator_objects[0]);\n            break;\n          }\n          default:\n            XNN_UNREACHABLE;\n        }\n        break;\n      }\n"""
s = s[:q8] + new_block + s[qu8:]

# reshape(): connect the existing dynamic operator reshape API.
old = """    case xnn_operator_type_convolution_nhwc_qc8:\n      status = xnn_reshape_convolution2d_nhwc_qs8_qc8w(\n"""
new = """    case xnn_operator_type_convolution_nhwc_qd8_f32_qc8w:\n      status = xnn_reshape_convolution2d_nhwc_qd8_f32_qc8w(\n        opdata->operator_objects[0], batch_size, input_height, input_width,\n        &opdata->workspace_size, &output_height, &output_width, threadpool);\n      break;\n    case xnn_operator_type_convolution_nhwc_qc8:\n      status = xnn_reshape_convolution2d_nhwc_qs8_qc8w(\n"""
if s.count(old) != 1:
    raise SystemExit("XNNPACK depthwise reshape anchor mismatch")
s = s.replace(old, new)

# setup(): consume the per-batch dynamic params created by xnn_unary_convert.
old = """    case xnn_operator_type_convolution_nhwc_qc8:\n      return xnn_setup_convolution2d_nhwc_qs8_qc8w(\n"""
new = """    case xnn_operator_type_convolution_nhwc_qd8_f32_qc8w: {\n      const void* quantization_params = input_value->quantization.dynamic_params;\n      assert(quantization_params != NULL);\n      return xnn_setup_convolution2d_nhwc_qd8_f32_qc8w(\n        opdata->operator_objects[0], opdata->workspace, input_data,\n        output_data, quantization_params);\n    }\n    case xnn_operator_type_convolution_nhwc_qc8:\n      return xnn_setup_convolution2d_nhwc_qs8_qc8w(\n"""
if s.count(old) != 1:
    raise SystemExit("XNNPACK depthwise setup anchor mismatch")
s = s.replace(old, new)

# validation with FP32 bias/output for the hybrid path.
old = """    case xnn_datatype_qcint8:\n      if (input_datatype == xnn_datatype_qint8 &&\n          bias_datatype == xnn_datatype_qcint32 &&\n          output_datatype == xnn_datatype_qint8)\n      {\n        return true;\n      }\n      break;\n"""
new = """    case xnn_datatype_qcint8:\n      if (input_datatype == xnn_datatype_qint8 &&\n          bias_datatype == xnn_datatype_qcint32 &&\n          output_datatype == xnn_datatype_qint8)\n      {\n        return true;\n      } else if (input_datatype == xnn_datatype_qdint8 &&\n                 bias_datatype == xnn_datatype_fp32 &&\n                 output_datatype == xnn_datatype_fp32) {\n        return true;\n      }\n      break;\n"""
if s.count(old) != 1:
    raise SystemExit("XNNPACK depthwise validate-with-bias anchor mismatch")
s = s.replace(old, new)

old = """    case xnn_datatype_qcint8:\n      if (input_datatype == xnn_datatype_qint8 && output_datatype == xnn_datatype_qint8) {\n        return true;\n      }\n      break;\n"""
new = """    case xnn_datatype_qcint8:\n      if (input_datatype == xnn_datatype_qint8 && output_datatype == xnn_datatype_qint8) {\n        return true;\n      } else if (input_datatype == xnn_datatype_qdint8 &&\n                 output_datatype == xnn_datatype_fp32) {\n        return true;\n      }\n      break;\n"""
if s.count(old) != 1:
    raise SystemExit("XNNPACK depthwise validate-without-bias anchor mismatch")
s = s.replace(old, new)

# xnn_define_depthwise_convolution_2d(): admit QDINT8 with legal batch granularity.
old = """  switch (input_value->datatype) {\n    case xnn_datatype_fp16:\n    case xnn_datatype_fp32:\n    case xnn_datatype_qint8:\n    case xnn_datatype_quint8:\n      break;\n    default:\n"""
new = """  switch (input_value->datatype) {\n    case xnn_datatype_fp16:\n    case xnn_datatype_fp32:\n    case xnn_datatype_qint8:\n    case xnn_datatype_quint8:\n      break;\n    case xnn_datatype_qdint8:\n      if (input_value->quantization.num_nonbatch_dims >=\n          input_value->shape.num_dims) {\n        xnn_log_error(\n          \"failed to define %s operator with input ID #%\" PRIu32\n          \": num_nonbatch_dims (%zu) must be < num_dims (%zu)\",\n          xnn_node_type_to_string(xnn_node_type_depthwise_convolution_2d),\n          input_id, input_value->quantization.num_nonbatch_dims,\n          input_value->shape.num_dims);\n        return xnn_status_invalid_parameter;\n      }\n      break;\n    default:\n"""
if s.count(old) != 1:
    raise SystemExit("XNNPACK depthwise input-datatype anchor mismatch")
s = s.replace(old, new)

# Static QCINT8 uses QCINT32 bias metadata; dynamic QD8 uses plain FP32 bias.
old = """    if (bias_value != NULL) {\n      assert(bias_value->datatype == xnn_datatype_qcint32);\n      if (bias_value->quantization.channel_dimension != 0) {\n        xnn_log_error(\n          \"failed to define %s operator with bias ID #%\" PRIu32 \": invalid channel dimension %zu\",\n          xnn_node_type_to_string(xnn_node_type_depthwise_convolution_2d), bias_id, bias_value->quantization.channel_dimension);\n        return xnn_status_invalid_parameter;\n      }\n    }\n"""
new = """    if (bias_value != NULL) {\n      if (input_value->datatype == xnn_datatype_qdint8) {\n        assert(bias_value->datatype == xnn_datatype_fp32);\n      } else {\n        assert(bias_value->datatype == xnn_datatype_qcint32);\n        if (bias_value->quantization.channel_dimension != 0) {\n          xnn_log_error(\n            \"failed to define %s operator with bias ID #%\" PRIu32 \": invalid channel dimension %zu\",\n            xnn_node_type_to_string(xnn_node_type_depthwise_convolution_2d), bias_id, bias_value->quantization.channel_dimension);\n          return xnn_status_invalid_parameter;\n        }\n      }\n    }\n"""
if s.count(old) != 1:
    raise SystemExit("XNNPACK depthwise QCINT8 bias anchor mismatch")
s = s.replace(old, new)

p.write_text(s, encoding="utf-8")
PY_XNN

  grep -Fq 'SUPERTONIC_DYNAMIC_DEPTHWISE_QD8_V3' "$target"
  grep -Fq 'xnn_create_convolution2d_nhwc_qd8_f32_qc8w' "$target"
  grep -Fq 'xnn_reshape_convolution2d_nhwc_qd8_f32_qc8w' "$target"
  grep -Fq 'xnn_setup_convolution2d_nhwc_qd8_f32_qc8w' "$target"
  echo "[LITERT] XNNPACK dynamic Depthwise v3 patched at pinned $EXPECTED_XNNPACK_REV"
}

build_one() {
  local abi="$1"
  local build_dir="$SRC_DIR/litert/cmake_build_android_${abi//-/_}"
  local out="$OUT_DIR/$abi/libLiteRt.so"

  # Keep the persistent CMake/dependency/object tree. Source reset + patching
  # changes only the touched source/header mtimes; Make/CMake then rebuilds the
  # exact dependent objects. Do not erase every .o merely because this wrapper
  # script changed.
  local builder_sha stamp
  builder_sha="$(sha256sum "${BASH_SOURCE[0]}" | awk '{print $1}')"
  stamp="$build_dir/.supertonic_builder_sha256"
  mkdir -p "$build_dir"

  # Reuse the already-populated TensorFlow checkout explicitly. This avoids
  # both network access and the misleading upstream "Downloading TensorFlow"
  # status line on retries/new project folders that share the global cache.
  local -a cached_source_args=()
  local cached_tf="$build_dir/tflite_build/tensorflow-src"
  if [[ -f "$cached_tf/tensorflow/lite/CMakeLists.txt" ]]; then
    cached_source_args+=("-DTENSORFLOW_SOURCE_DIR=$cached_tf")
    echo "[LITERT] TensorFlow source cache: $cached_tf"
  fi

  echo "[LITERT] configuring official CMake Android Release path for $abi"
  "$CMAKE_BIN" \
    -S "$SRC_DIR/litert" \
    -B "$build_dir" \
    -G "Unix Makefiles" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DCMAKE_SYSTEM_NAME=Android \
    -DANDROID_ABI="$abi" \
    -DANDROID_PLATFORM="android-$ANDROID_API_LEVEL" \
    -DCMAKE_BUILD_TYPE=Release \
    -DLITERT_AUTO_BUILD_TFLITE=ON \
    -DLITERT_ENABLE_GPU=ON \
    -DLITERT_ENABLE_NPU=ON \
    -DTFLITE_ENABLE_GPU=ON \
    -DLITERT_SKIP_VENDORS=ON \
    -DLITERT_SKIP_TENSOR_EXAMPLES=ON \
    "${cached_source_args[@]}" \
    -DFETCHCONTENT_UPDATES_DISCONNECTED=ON \
    -DFETCHCONTENT_FULLY_DISCONNECTED=$([[ "$ALLOW_DOWNLOADS" == "1" ]] && echo OFF || echo ON) \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5

  # OFFLINE2 incorrectly forced TFLITE_ENABLE_GPU=OFF. LiteRT v2.2's C API
  # shared target still links litert_tflite_gpu_gl_core, so that setting removes
  # common GPU definitions while retaining their consumers. Explicit ON values
  # both reproduce the known-good SAFE link graph and repair a polluted cache.
  local cache_file="$build_dir/CMakeCache.txt"
  for expected in \
    'LITERT_ENABLE_GPU:BOOL=ON' \
    'LITERT_ENABLE_NPU:BOOL=ON' \
    'TFLITE_ENABLE_GPU:BOOL=ON' \
    'LITERT_SKIP_VENDORS:BOOL=ON' \
    'LITERT_SKIP_TENSOR_EXAMPLES:BOOL=ON'; do
    if ! grep -Fqx "$expected" "$cache_file"; then
      echo "[ERROR] CMake cache contract mismatch for $abi: expected $expected" >&2
      grep -E '^(LITERT_ENABLE_GPU|LITERT_ENABLE_NPU|TFLITE_ENABLE_GPU|LITERT_SKIP_VENDORS|LITERT_SKIP_TENSOR_EXAMPLES):' "$cache_file" >&2 || true
      exit 16
    fi
  done

  local target_help="$WORK_ROOT/${abi//\//_}.targets.txt"
  "$CMAKE_BIN" --build "$build_dir" --target help > "$target_help"
  if grep -Eq 'dispatch_api_(Qualcomm|MediaTek|Samsung)|litert_tensor_.*example' "$target_help"; then
    echo "[ERROR] vendor/tensor targets leaked into the supposedly skipped configuration for $abi" >&2
    grep -E 'dispatch_api_(Qualcomm|MediaTek|Samsung)|litert_tensor_.*example' "$target_help" >&2 || true
    exit 17
  fi
  echo "[LITERT] configure contract OK: upstream GPU/TFLite link graph ON; vendor/tensor configure skipped"

  patch_xnnpack_dynamic_depthwise "$build_dir"

  echo "[LITERT] building $abi libLiteRt.so (Release)"
  "$CMAKE_BIN" --build "$build_dir" \
    --target litert_runtime_c_api_shared_lib \
    --parallel "$JOBS"

  local built="$build_dir/c/libLiteRt.so"
  if [[ ! -s "$built" ]]; then
    built="$(find "$build_dir" -type f -name 'libLiteRt.so' -print -quit 2>/dev/null || true)"
  fi
  if [[ -z "$built" || ! -s "$built" ]]; then
    echo "[ERROR] libLiteRt.so missing after CMake $abi build" >&2
    find "$build_dir" -maxdepth 4 -type f -name '*.so' -print >&2 || true
    exit 10
  fi
  cp -f "$built" "$out"

  local dynsym="$WORK_ROOT/${abi//\//_}.cmake.libLiteRt.dynsym.txt"
  local dynamic="$WORK_ROOT/${abi//\//_}.cmake.libLiteRt.dynamic.txt"
  "$READELF" --wide --dyn-syms "$out" > "$dynsym"
  "$READELF" --wide --dynamic "$out" > "$dynamic"

  python3 "$ROOT/tools/verify_elf_16k.py" "$out" "$abi/libLiteRt.so" "$READELF"

  if ! grep -Fq 'Library soname: [libLiteRt.so]' "$dynamic"; then
    echo "[ERROR] $abi CMake LiteRT missing DT_SONAME=libLiteRt.so" >&2
    grep -F SONAME "$dynamic" >&2 || true
    exit 11
  fi

  for symbol in \
    TfLiteInterpreterModifyGraphWithDelegateForSignature \
    TfLiteInterpreterModifyGraphWithClassicDelegateForSignature \
    SupertonicInterpreterSelectedSignatureDelegationStats \
    TfLiteInterpreterRemoveAllDelegates \
    SupertonicXnnpackWeightCacheProviderCreate \
    SupertonicXnnpackWeightCacheProviderLoadOrStartBuild \
    SupertonicXnnpackWeightCacheProviderStopBuild \
    SupertonicXnnpackWeightCacheProviderDelete \
    SupertonicXnnpackDynamicDepthwisePatchVersion \
    TfLiteXNNPackDelegateCreate \
    TfLiteInterpreterGetSignatureRunner \
    TfLiteSignatureRunnerAllocateTensors \
    TfLiteSignatureRunnerInvoke; do
    if ! awk -v wanted="$symbol" '
      $7 != "UND" {
        name = $8
        sub(/@.*/, "", name)
        if (name == wanted) found = 1
      }
      END { exit(found ? 0 : 1) }
    ' "$dynsym"; then
      echo "[ERROR] $abi CMake LiteRT missing defined dynamic symbol: $symbol" >&2
      grep -F "$symbol" "$dynsym" >&2 || true
      exit 12
    fi
  done

  # The original user-provided SAFE runtime defines these helpers. Their
  # absence is the exact OFFLINE2 final-link regression (gpu_gl_core consumers
  # retained while TFLite GPU common definitions were disabled). Use raw ELF
  # names so validation does not depend on a host c++filt installation.
  local fullsym="$WORK_ROOT/${abi//\//_}.cmake.libLiteRt.fullsym.txt"
  "$READELF" --wide --syms "$out" > "$fullsym"
  for required_elf in \
    '_ZNK6tflite3gpu7GpuInfo9IsPowerVREv' \
    '_ZN6tflite3gpu6SizeOfENS0_8DataTypeE' \
    '_ZN6tflite3gpu31GetGpuInfoFromDeviceDescription'; do
    if ! awk -v wanted="$required_elf" '$7 != "UND" && index($8, wanted) { found=1 } END { exit(found ? 0 : 1) }' "$fullsym"; then
      echo "[ERROR] $abi upstream link-contract regression: missing defined ELF symbol containing: $required_elf" >&2
      exit 18
    fi
  done

  local machine
  machine="$($READELF -h "$out" | awk -F: '/Machine:/{gsub(/^[[:space:]]+/,"",$2); print $2; exit}')"
  echo "$builder_sha" > "$stamp"
  echo "[LITERT] $abi OK machine=$machine bytes=$(stat -c %s "$out") sha256=$(sha256sum "$out" | awk '{print $1}')"
}

build_one arm64-v8a
build_one x86_64

cat > "$OUT_DIR/SELECTED_SUBGRAPH_RUNTIME.txt" <<EOF_MARKER
LiteRT tag: $LITERT_TAG
Build system: upstream CMake Android cross-compile path
Build type: Release (-O3, NDEBUG)
Runtime profile: SAFE-RECOVERY-NO-TARGETED-REMOVE
Dynamic Depthwise patch: dedicated-subgraph-QD8-F32-QC8W-v3
Persistent cache preload: xnn-initialize-before-load
XNNPACK revision: $EXPECTED_XNNPACK_REV
Android API: $ANDROID_API_LEVEL
NDK: $LITERT_NDK_VERSION
Patch scope: c_api.h + interpreter.h public delegate-removal wrapper + tflite/delegates/xnnpack/xnnpack_delegate.cc + litert/supertonic_selected_subgraph_c_api.cc + litert/c/CMakeLists.txt + pinned XNNPACK src/subgraph/depthwise-convolution-2d.c (weight_cache.h/.cc unchanged)
CMake link graph: upstream-compatible (LiteRT/TFLite GPU support ON as in known-good SAFE runtime; TTS path remains CPU/XNNPACK)
Vendor/tensor configuration: skipped without altering core runtime link graph
TFLite/XNNPACK platform config patches: none
Required Supertonic extension symbols:
  TfLiteInterpreterModifyGraphWithDelegateForSignature
  TfLiteInterpreterModifyGraphWithClassicDelegateForSignature
  SupertonicInterpreterSelectedSignatureDelegationStats
  TfLiteInterpreterRemoveAllDelegates
  SupertonicXnnpackWeightCacheProviderCreate
  SupertonicXnnpackWeightCacheProviderLoadOrStartBuild
  SupertonicXnnpackWeightCacheProviderStopBuild
  SupertonicXnnpackWeightCacheProviderDelete
  SupertonicXnnpackDynamicDepthwisePatchVersion
ELF LOAD alignment: 16384 bytes minimum
EOF_MARKER

echo "[PASS] CMake Release custom LiteRT selected-subgraph runtime built"
