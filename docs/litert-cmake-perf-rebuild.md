# LiteRT 2.2 selected-subgraph CMake performance rebuild

## Goal

Keep `TfLiteInterpreterModifyGraphWithDelegateForSignature` for MultiPreset
models while restoring the normal LiteRT CPU/XNNPACK build path as close as
possible to upstream LiteRT 2.2.0.

## Active builder

`tools/build_litert_2_2_selected_subgraph.sh` is a stable entry point and now
executes `tools/build_litert_2_2_selected_subgraph_cmake.sh`.

The CMake builder:

1. Checks out LiteRT `v2.2.0`.
2. Adds the selected-subgraph declaration to `tflite/core/c/c_api.h`.
3. Adds `litert/supertonic_selected_subgraph_c_api.cc`.
4. Attaches that source to the existing `litert_runtime_c_api_shared_lib` CMake
   target.
5. Builds Android ABIs with `CMAKE_BUILD_TYPE=Release` using the Android NDK
   toolchain and upstream TFLite/XNNPACK CMake configuration.
6. Verifies the selected-subgraph API, XNNPACK delegate API, SignatureRunner
   APIs, DT_SONAME and 16 KB ELF LOAD alignment.

No XNNPACK source revision override or Android Bazel config patch is used.

## A/B baseline

The prior Bazel custom runtime is preserved in `litert-legacy-bazel/`. It is
not accepted by `setup.sh` as the active runtime. This prevents an accidental
performance test against the old binary.

## Runtime validation

After building the new runtime, the marker must contain:

```
Build system: upstream CMake Android cross-compile path
Build type: Release (-O3, NDEBUG)
TFLite/XNNPACK platform config patches: none
```

For performance A/B on SD690 use the same short input, 4 steps, 4 threads and
confirm `chunk_count=1`. Compare stage timings, especially vector estimator,
not only total RTF.

## Chunk Auto fix

`NativeBridge.nativeSetSynthesizerChunkCap(0)` now reaches native code as zero.
The previous setter accidentally clamped zero to 24, causing needless splits on
long text. No fixed 64-character policy is restored.
