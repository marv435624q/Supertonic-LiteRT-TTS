#!/bin/bash
set -euo pipefail

# Qualcomm LiteRT NPU preview setup
# - LiteRT default: CPU/XNNPACK.
# - LiteRT Multi-P FP32/WI8: official LiteRT 2.2 CompiledModel + QAIRT 2.47.
# - ONNX Qualcomm modern devices: ORT QNN HTP.
# - SM6350/lito: ORT QNN HTA compatibility probe using local QAIRT 2.44 HTA libs.

LITERT_NATIVE_VERSION="${LITERT_NATIVE_VERSION:-2.2.0-selected-subgraph-cmake-release}"
LITERT_NPU_RUNTIME_VERSION="${LITERT_NPU_RUNTIME_VERSION:-2.2.0}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
LITERT_DIR="${ROOT}/litert"
QNN_VERSION_FILE="${ROOT}/qnn-version.properties"

# ---------------------------------------------------------------------------
# REV32 custom ORT: preserve the validated REV30 Java/JNI shell, but replace
# its arm64 libonnxruntime.so with a native core rebuilt from upstream v1.28.0
# using BOTH QNN static EP and XNNPACK EP, with the existing HTA patch applied.
# BUILD_ALL.bat invokes BUILD_CUSTOM_ORT_QNN_XNNPACK.bat before this setup.
# ---------------------------------------------------------------------------
ORT_HTA_AAR="${ROOT}/sdk/libs/onnxruntime-android-qnn-xnnpack-1.28.0-hta.aar"
ORT_HTA_SHA256_FILE="${ORT_HTA_AAR}.sha256"
ORT_HTA_BUILDINFO="${ORT_HTA_AAR}.buildinfo.txt"
if [ ! -s "$ORT_HTA_AAR" ]; then
    echo "[ERROR] Custom ORT QNN+XNNPACK AAR missing: $ORT_HTA_AAR" >&2
    echo "        Run BUILD_CUSTOM_ORT_QNN_XNNPACK.bat first." >&2
    exit 1
fi
if [ ! -s "$ORT_HTA_SHA256_FILE" ] || [ ! -s "$ORT_HTA_BUILDINFO" ]; then
    echo "[ERROR] Custom ORT build metadata missing next to AAR." >&2
    exit 1
fi
expected_ort_sha256="$(awk 'NR==1 {print $1}' "$ORT_HTA_SHA256_FILE" | tr 'A-F' 'a-f')"
actual_ort_sha256="$(sha256sum "$ORT_HTA_AAR" | awk '{print $1}')"
if [ -z "$expected_ort_sha256" ] || [ "$actual_ort_sha256" != "$expected_ort_sha256" ]; then
    echo "[ERROR] Custom ORT QNN+XNNPACK AAR checksum mismatch" >&2
    echo "        expected: $expected_ort_sha256" >&2
    echo "        actual  : $actual_ort_sha256" >&2
    exit 1
fi
for required in \
    '^source_tag=v1\.28\.0$' \
    '^use_qnn=static_lib$' \
    '^use_xnnpack=1$' \
    '^hta_patch=1$'; do
    if ! grep -Eq "$required" "$ORT_HTA_BUILDINFO"; then
        echo "[ERROR] Custom ORT build-info verification failed: $required" >&2
        exit 1
    fi
done
if ! unzip -tqq "$ORT_HTA_AAR" >/dev/null 2>&1; then
    echo "[ERROR] Custom ORT QNN+XNNPACK AAR is corrupt" >&2
    exit 1
fi
for entry in jni/arm64-v8a/libonnxruntime.so jni/arm64-v8a/libonnxruntime4j_jni.so classes.jar; do
    if ! unzip -Z1 "$ORT_HTA_AAR" | grep -qx "$entry"; then
        echo "[ERROR] Custom ORT QNN+XNNPACK AAR missing entry: $entry" >&2
        exit 1
    fi
done
echo "Custom ORT QNN+XNNPACK AAR: SHA/build-info/structure OK"

if [ ! -s "$QNN_VERSION_FILE" ]; then
    echo "[ERROR] Missing qnn-version.properties" >&2
    exit 1
fi
QNN_VERSION="$(sed -n 's/^qnnRuntimeVersion=//p' "$QNN_VERSION_FILE" | tr -d '\r' | head -n1)"
if [ -z "$QNN_VERSION" ]; then
    echo "[ERROR] qnnRuntimeVersion is missing" >&2
    exit 1
fi
if [ "$QNN_VERSION" != "2.47.0" ]; then
    echo "[ERROR] Qualcomm LiteRT preview requires QNN 2.47.0; configured ${QNN_VERSION}" >&2
    exit 1
fi

if [ -n "${SUPERTONIC_CACHE_ROOT:-}" ]; then
    CACHE_ROOT="${SUPERTONIC_CACHE_ROOT}"
elif [ -n "${LOCALAPPDATA:-}" ] && command -v cygpath >/dev/null 2>&1; then
    CACHE_ROOT="$(cygpath -u "$LOCALAPPDATA")/SupertonicLiteRT/native-cache"
elif [ -n "${XDG_CACHE_HOME:-}" ]; then
    CACHE_ROOT="${XDG_CACHE_HOME}/SupertonicLiteRT"
else
    CACHE_ROOT="${HOME}/.cache/SupertonicLiteRT"
fi
CPU_CACHE="${CACHE_ROOT}/${LITERT_NATIVE_VERSION}"
QNN_CACHE="${CACHE_ROOT}/qnn-${QNN_VERSION}"
LITERT_NPU_CACHE="${CACHE_ROOT}/litert-npu-${LITERT_NPU_RUNTIME_VERSION}"
mkdir -p "$CPU_CACHE" "$QNN_CACHE" "$LITERT_NPU_CACHE" "${ROOT}/app/libs" \
    "${ROOT}/sdk/src/main/jniLibs/arm64-v8a" "${ROOT}/sdk/src/main/jniLibs/x86_64" \
    "${ROOT}/app/src/main/jniLibs/arm64-v8a"

echo "=== Supertonic REV32 QNN+XNNPACK CPU-engine setup ==="
echo "LiteRT CPU runtime : ${LITERT_NATIVE_VERSION}"
echo "QAIRT/QNN runtime  : ${QNN_VERSION}"
echo "LiteRT Qualcomm NPU: CompiledModel 2.2 + QAIRT 2.47, Multi-P FP32/WI8"
echo "LiteRT GPU / NNAPI : removed"
echo "ONNX CPU runtime    : ORT XNNPACK EP + CPU fallback"
echo "SM6350 accelerator : QNN HTA + patched ORT 1.28 backend recognition"

# ---------------------------------------------------------------------------
# Native LiteRT runtime. The custom build preserves the verified CPU/XNNPACK
# selected-signature and persistent-cache extensions. Qualcomm NPU uses the
# official 2.2 CompiledModel API and vendor compiler/dispatch plugins.
#
# GitHub Actions builds and packages this runtime automatically. The default
# builder now uses upstream LiteRT CMake Android Release (-O3/NDEBUG) and only
# adds the selected-subgraph C ABI; the old Bazel-patched builder is retained as
# tools/build_litert_2_2_selected_subgraph_bazel_legacy.sh for A/B diagnostics.
# For a local BUILD_ALL.bat, extract the Custom-LiteRT-2.2-Selected-Subgraph-CMake-Release artifact so
# these files already exist under ./litert/.
# ---------------------------------------------------------------------------
LITERT_MARKER="${LITERT_DIR}/SELECTED_SUBGRAPH_RUNTIME.txt"
if [ ! -s "$LITERT_MARKER" ]; then
    echo "[ERROR] Custom LiteRT 2.2 selected-subgraph runtime is missing." >&2
    echo "        Required marker: $LITERT_MARKER" >&2
    echo "        BUILD_ALL.bat normally creates this automatically through WSL." >&2
    echo "        Manual Windows entry point: BUILD_CUSTOM_LITERT_CMAKE.bat" >&2
    echo "        CI alternative: Custom-LiteRT-2.2-Selected-Subgraph-CMake-Release artifact" >&2
    exit 1
fi
for abi in arm64-v8a x86_64; do
    if [ ! -s "${LITERT_DIR}/${abi}/libLiteRt.so" ]; then
        echo "[ERROR] Custom LiteRT missing: ${LITERT_DIR}/${abi}/libLiteRt.so" >&2
        exit 1
    fi
done
for required_symbol in \
    TfLiteInterpreterModifyGraphWithDelegateForSignature \
    TfLiteInterpreterRemoveAllDelegates \
    LrtCreateRuntimeOptions \
    LrtGetOpaqueRuntimeOptionsData \
    LrtSetRuntimeOptionsSelectedSignatures \
    SupertonicXnnpackWeightCacheProviderCreate \
    SupertonicXnnpackWeightCacheProviderLoadOrStartBuild \
    SupertonicXnnpackWeightCacheProviderStopBuild \
    SupertonicXnnpackWeightCacheProviderDelete; do
    if ! grep -Fq "$required_symbol" "$LITERT_MARKER"; then
        echo "[ERROR] Custom LiteRT marker is missing required ABI: $required_symbol" >&2
        echo "        Rebuild it with BUILD_CUSTOM_LITERT_CMAKE.bat." >&2
        exit 1
    fi
done
if ! grep -Fq "ELF LOAD alignment: 16384 bytes minimum" "$LITERT_MARKER"; then
    echo "[ERROR] Custom LiteRT runtime predates the Android 16 KB-page fix." >&2
    echo "        Rebuild it with tools/build_litert_2_2_selected_subgraph.sh." >&2
    exit 1
fi
if ! grep -Fq "Build system: upstream CMake Android cross-compile path" "$LITERT_MARKER"; then
    echo "[ERROR] Active custom LiteRT is not the CMake Release performance rebuild." >&2
    echo "        The previous Bazel runtime is kept under ./litert-legacy-bazel for A/B only." >&2
    echo "        Rebuild ./litert with tools/build_litert_2_2_selected_subgraph.sh." >&2
    exit 1
fi
if ! grep -Fq "Build type: Release (-O3, NDEBUG)" "$LITERT_MARKER"; then
    echo "[ERROR] Active custom LiteRT is not a verified Release build." >&2
    exit 1
fi
if ! grep -Fq "Persistent cache preload: xnn-initialize-before-load" "$LITERT_MARKER"; then
    echo "[ERROR] Active custom LiteRT predates the XNNPACK persistent-cache restart fix." >&2
    echo "        Rebuild it with BUILD_CUSTOM_LITERT_CMAKE.bat." >&2
    exit 1
fi
echo "Custom LiteRT 2.2 selected-subgraph runtime: OK (upstream CMake Release)"

# Remove every old LiteRT accelerator/delegate artifact so an incremental build
# cannot accidentally retain the removed GPU/NNAPI/NPU paths.
rm -f \
    "${ROOT}/app/libs/litert-api.aar" \
    "${ROOT}/app/libs/litert.aar" \
    "${ROOT}/app/libs/litert-gpu-api.aar" \
    "${ROOT}/app/libs/litert-gpu.aar" \
    "${ROOT}/app/libs/qnn-litert-delegate.aar" 2>/dev/null || true
for abi in arm64-v8a x86_64; do
    rm -f \
        "${ROOT}/sdk/src/main/jniLibs/${abi}/libLiteRtClGlAccelerator.so" \
        "${ROOT}/sdk/src/main/jniLibs/${abi}/libLiteRtOpenClAccelerator.so" \
        "${ROOT}/sdk/src/main/jniLibs/${abi}/libLiteRtGpuAccelerator.so" \
        "${ROOT}/sdk/src/main/jniLibs/${abi}/libLiteRtCompilerPlugin_Qualcomm.so" \
        "${ROOT}/sdk/src/main/jniLibs/${abi}/libLiteRtDispatch_Qualcomm.so" 2>/dev/null || true
done

# ---------------------------------------------------------------------------
# Official LiteRT 2.2 Qualcomm CompiledModel JIT/AOT plugins.
# Keep these exactly version-matched with libLiteRt.so.
# ---------------------------------------------------------------------------
LITERT_NPU_ZIP="litert_npu_runtime_libraries_jit.zip"
LITERT_NPU_ZIP_CACHE="${LITERT_NPU_CACHE}/${LITERT_NPU_ZIP}"
LITERT_NPU_URL="https://github.com/google-ai-edge/LiteRT/releases/download/v${LITERT_NPU_RUNTIME_VERSION}/${LITERT_NPU_ZIP}"
if [ ! -s "$LITERT_NPU_ZIP_CACHE" ]; then
    curl --fail --location --retry 3 --retry-delay 2 \
        --output "${LITERT_NPU_ZIP_CACHE}.part" "$LITERT_NPU_URL"
    mv -f "${LITERT_NPU_ZIP_CACHE}.part" "$LITERT_NPU_ZIP_CACHE"
fi
unzip -tqq "$LITERT_NPU_ZIP_CACHE"
LITERT_NPU_EXTRACT="${LITERT_NPU_CACHE}/extracted"
rm -rf "$LITERT_NPU_EXTRACT"
mkdir -p "$LITERT_NPU_EXTRACT"
unzip -q "$LITERT_NPU_ZIP_CACHE" -d "$LITERT_NPU_EXTRACT"
unzip -Z1 "$LITERT_NPU_ZIP_CACHE" | sort > "${LITERT_NPU_CACHE}/entries.txt"
for lib in libLiteRtCompilerPlugin_Qualcomm.so libLiteRtDispatch_Qualcomm.so; do
    src="$(find "$LITERT_NPU_EXTRACT" -type f -name "$lib" -print -quit)"
    if [ -z "$src" ] || [ ! -s "$src" ]; then
        echo "[ERROR] Official LiteRT NPU archive missing $lib" >&2
        cat "${LITERT_NPU_CACHE}/entries.txt" >&2
        exit 1
    fi
    cp -f "$src" "${ROOT}/sdk/src/main/jniLibs/arm64-v8a/$lib"
done
for lib in libQnnIr.so libQnnSaver.so; do
    src="$(find "$LITERT_NPU_EXTRACT" -type f -name "$lib" -print -quit)"
    if [ -n "$src" ] && [ -s "$src" ]; then
        cp -f "$src" "${ROOT}/sdk/src/main/jniLibs/arm64-v8a/$lib"
    fi
done
echo "Official LiteRT 2.2 Qualcomm compiler/dispatch plugins: ready"

# ---------------------------------------------------------------------------
# Pinned Qualcomm runtime AAR for ORT QNN HTP on modern Snapdragon devices.
# ---------------------------------------------------------------------------
QNN_AAR_NAME="qnn-runtime-${QNN_VERSION}.aar"
QNN_AAR_CACHE="${QNN_CACHE}/${QNN_AAR_NAME}"
QNN_AAR_SHA1="${QNN_AAR_CACHE}.sha1"
QNN_AAR_URL="https://repo.maven.apache.org/maven2/com/qualcomm/qti/qnn-runtime/${QNN_VERSION}/${QNN_AAR_NAME}"
if [ ! -s "$QNN_AAR_CACHE" ]; then
    echo "Downloading Qualcomm ${QNN_AAR_NAME}..."
    curl --fail --location --retry 3 --retry-delay 2 --output "${QNN_AAR_CACHE}.part" "$QNN_AAR_URL"
    mv -f "${QNN_AAR_CACHE}.part" "$QNN_AAR_CACHE"
else
    echo "Using cached Qualcomm AAR: $QNN_AAR_CACHE"
fi
if [ ! -s "$QNN_AAR_SHA1" ]; then
    curl --fail --location --retry 3 --retry-delay 2 --output "${QNN_AAR_SHA1}.part" "${QNN_AAR_URL}.sha1"
    mv -f "${QNN_AAR_SHA1}.part" "$QNN_AAR_SHA1"
fi
expected_sha1="$(tr -d '[:space:]' < "$QNN_AAR_SHA1")"
actual_sha1="$(sha1sum "$QNN_AAR_CACHE" | awk '{print $1}')"
if [ -z "$expected_sha1" ] || [ "$actual_sha1" != "$expected_sha1" ]; then
    echo "[ERROR] ${QNN_AAR_NAME} checksum mismatch" >&2
    exit 1
fi
cp -f "$QNN_AAR_CACHE" "${ROOT}/app/libs/${QNN_AAR_NAME}"
QNN_RUNTIME_AAR="${ROOT}/app/libs/${QNN_AAR_NAME}"
for lib in libQnnSystem.so libQnnHtp.so libQnnHtpPrepare.so; do
    if ! unzip -Z1 "$QNN_RUNTIME_AAR" | grep -q "/${lib}$"; then
        echo "[ERROR] qnn-runtime AAR does not contain ${lib}" >&2
        exit 1
    fi
done


# ---------------------------------------------------------------------------
# SM6350 HTA libraries are not present in the Maven qnn-runtime AAR used by the
# app. Copy the exact QAIRT 2.44 files from a locally installed full SDK.
# The user's validated SDK path is auto-detected, while env overrides keep the
# source portable.
# ---------------------------------------------------------------------------
find_qairt_root() {
    local candidates=(
        "${SUPERTONIC_QAIRT_ROOT:-}"
        "${QNN_SDK_ROOT:-}"
        "${QAIRT_SDK_ROOT:-}"
        "/mnt/c/platform-tools/qairt-2.44/qairt/2.44.0.260225"
        "/c/platform-tools/qairt-2.44/qairt/2.44.0.260225"
        "${HOME}/qairt/2.44.0.260225"
    )
    local c
    for c in "${candidates[@]}"; do
        [ -n "$c" ] || continue
        if [ -s "$c/lib/aarch64-android/libQnnHta.so" ]; then
            printf '%s\n' "$c"
            return 0
        fi
    done
    return 1
}

QAIRT_ROOT="$(find_qairt_root || true)"
if [ -z "$QAIRT_ROOT" ]; then
    echo "[ERROR] Full QAIRT 2.44.0.260225 SDK not found." >&2
    echo "        Set SUPERTONIC_QAIRT_ROOT or QNN_SDK_ROOT to the SDK root." >&2
    echo "        Expected file: lib/aarch64-android/libQnnHta.so" >&2
    exit 1
fi
HTA_SRC="${QAIRT_ROOT}/lib/aarch64-android"
HTA_DST="${ROOT}/app/src/main/jniLibs/arm64-v8a"
for lib in libQnnHta.so libQnnHtaNetRunExtensions.so libhta_hexagon_runtime_qnn.so; do
    if [ ! -s "${HTA_SRC}/${lib}" ]; then
        echo "[ERROR] QAIRT HTA library missing: ${HTA_SRC}/${lib}" >&2
        exit 1
    fi
    cp -f "${HTA_SRC}/${lib}" "${HTA_DST}/${lib}"
done

echo "QAIRT HTA source    : ${QAIRT_ROOT}"
echo "Packaged HTA libs   : libQnnHta.so, libQnnHtaNetRunExtensions.so, libhta_hexagon_runtime_qnn.so"

# Do not delete $TMP here. Under Git for Windows, TMP is inherited as /tmp
# and may contain live files owned by ADB, WSL, drivers, and other processes.
# This script does not create a private temp directory, so there is nothing to clean.
echo ""
echo "Done."
echo "  LiteRT: CPU/XNNPACK + official CompiledModel NPU for Multi-P FP32/WI8"
echo "  ONNX CPU: XNNPACK EP compiled into custom ORT native core
  Modern Qualcomm ONNX: QNN HTP (REV25 path preserved)"
echo "  SM6350/lito ONNX: QNN HTA with patched ORT 1.28 backend recognition; stage probe preserved"
echo "  ./gradlew :app:assembleDebug"
