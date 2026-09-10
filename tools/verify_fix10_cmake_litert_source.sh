#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

CM="$ROOT/tools/build_litert_2_2_selected_subgraph_cmake.sh"
WRAP="$ROOT/tools/build_litert_2_2_selected_subgraph.sh"
JNI="$ROOT/sdk/src/main/cpp/jni_bridge.cpp"
SETUP="$ROOT/setup.sh"
WF="$ROOT/.github/workflows/selected-xnnpack.yml"

for f in "$CM" "$WRAP" "$JNI" "$SETUP" "$WF"; do test -s "$f"; done

grep -Fq 'exec "$ROOT/tools/build_litert_2_2_selected_subgraph_cmake.sh" "$@"' "$WRAP"
grep -Fq 'requested_chunk_cap <= 0' "$JNI"
grep -Fq 'Build system: upstream CMake Android cross-compile path' "$SETUP"
grep -Fq 'Build type: Release (-O3, NDEBUG)' "$SETUP"
grep -Fq 'LITERT_NDK_VERSION: 27.2.12479018' "$WF"

# The active builder must not mutate the old Bazel platform-selection files.
for forbidden in \
  'litert/BUILD' \
  'tflite/profiling/BUILD' \
  'tflite/special_rules.bzl' \
  'build_config/BUILD.bazel' \
  'override_repository="XNNPACK' \
  'WORKSPACE android NDK anchor'; do
  if grep -Fq "$forbidden" "$CM"; then
    echo "[ERROR] active CMake builder still contains legacy Bazel patch logic: $forbidden" >&2
    exit 1
  fi
done

grep -Fq 'litert/supertonic_selected_subgraph_c_api.cc' "$CM"
grep -Fq 'CMAKE_BUILD_TYPE=Release' "$CM"
grep -Fq 'litert_runtime_c_api_shared_lib' "$CM"

grep -Fq 'TfLiteInterpreterRemoveAllDelegates' "$CM"
grep -Fq 'SupertonicRemoveAllDelegatesForSignatureSwitch' "$CM"
grep -Fq 'tflite/core/interpreter.h' "$CM"
grep -Fq -- '-e litert/cmake_build_android_arm64_v8a/' "$CM"
grep -Fq 'SupertonicXnnpackWeightCacheProviderCreate' "$CM"
grep -Fq 'SupertonicXnnpackWeightCacheProviderLoadOrStartBuild' "$CM"
grep -Fq 'p->impl.LoadOrStartBuild(cache_path)' "$CM"
grep -Fq 'MMapWeightCacheProvider' "$CM"
grep -Fq 'shared_vector_cache_provider' "$ROOT/speech-core/src/models/litert/litert_supertonic_tts.cpp"
grep -Fq 'switch_cpu_signature' "$ROOT/speech-core/src/models/litert/litert_supertonic_tts.cpp"
grep -Fq '[AUTO-BUCKET][SHARED-XNN-CACHE]' "$ROOT/speech-core/src/models/litert/litert_supertonic_tts.cpp"
grep -Fq '[AUTO-BUCKET][XNN-CACHE-MIGRATION]' "$ROOT/speech-core/src/models/litert/litert_supertonic_tts.cpp"
grep -Fq '[LITERT-SIGNATURE-SWITCH]' "$ROOT/speech-core/src/models/litert/litert_supertonic_tts.cpp"
grep -Fq 'cache_grow=' "$ROOT/speech-core/src/models/litert/litert_supertonic_tts.cpp"
grep -Fq 'SupertonicXnnpackWeightCacheProviderStopBuild' "$CM"

# Persistent cache files must only use XNNPACK's upstream build-or-load state
# machine. Reopening a loaded cache for append caused cross-process corruption.
for forbidden in \
  'LoadOrStartBuildForAppend' \
  'WeightCacheBuilder::Resume' \
  'bool Resume(const char* path' \
  'OpenForAppend'; do
  if grep -Fq "$forbidden" "$CM"; then
    echo "[ERROR] non-upstream XNNPACK cache-resume extension is still present: $forbidden" >&2
    exit 1
  fi
done
if grep -Fq 'weight_cache_header.write_text' "$CM" ||
   grep -Fq 'weight_cache_source.write_text' "$CM"; then
  echo '[ERROR] active builder still patches upstream XNNPACK weight_cache sources' >&2
  exit 1
fi
grep -Fq 'xnncache-v3-upstream-lifecycle' \
  "$ROOT/speech-core/src/models/litert/litert_supertonic_tts.cpp"
grep -Fq 'no_resume_append=1' \
  "$ROOT/speech-core/src/models/litert/litert_supertonic_tts.cpp"

echo '[PASS] FIX10 CMake LiteRT source invariants verified'

# Local Windows/WSL auto-build wiring

grep -Fq 'BUILD_CUSTOM_LITERT_CMAKE.bat' "$ROOT/BUILD_ALL.bat"
grep -Fq 'build_litert_2_2_selected_subgraph_wsl.sh' "$ROOT/BUILD_CUSTOM_LITERT_CMAKE.bat"
grep -Fq 'android-ndk-${NDK_ARCHIVE_REV}-linux.zip' "$ROOT/tools/build_litert_2_2_selected_subgraph_wsl.sh"
grep -Fq 'LITERT_CMAKE_BUILD_ROOT="$BUILD_ROOT"' "$ROOT/tools/build_litert_2_2_selected_subgraph_wsl.sh"
