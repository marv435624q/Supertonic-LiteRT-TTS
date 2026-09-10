#!/bin/bash
set -euo pipefail

# REV33.9 diagnostic setup: exact REV30 custom ORT QNN/HTA AAR.
# This intentionally has NO XNNPACK EP and exists only to isolate whether the
# REV32 QNN+XNNPACK native rebuild changed ORT CPU EP performance.

LITERT_NATIVE_VERSION="${LITERT_NATIVE_VERSION:-2.1.4}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
LITERT_DIR="${ROOT}/litert"
QNN_VERSION_FILE="${ROOT}/qnn-version.properties"
ORT_REV30_AAR="${ROOT}/sdk/libs/onnxruntime-android-qnn-1.28.0-hta.aar"
ORT_REV30_SHA256="1952291600ec5f69f871c4365e75d82be1e28348988f0d8521d5bb2e4e5a93db"

if [ ! -s "$ORT_REV30_AAR" ]; then
    echo "[ERROR] REV30 ORT AAR missing: $ORT_REV30_AAR" >&2
    exit 1
fi
actual_ort_sha256="$(sha256sum "$ORT_REV30_AAR" | awk '{print $1}')"
if [ "$actual_ort_sha256" != "$ORT_REV30_SHA256" ]; then
    echo "[ERROR] REV30 ORT AAR checksum mismatch" >&2
    echo "        expected: $ORT_REV30_SHA256" >&2
    echo "        actual  : $actual_ort_sha256" >&2
    exit 1
fi
if ! unzip -tqq "$ORT_REV30_AAR" >/dev/null 2>&1; then
    echo "[ERROR] REV30 ORT AAR is corrupt" >&2
    exit 1
fi
for entry in jni/arm64-v8a/libonnxruntime.so jni/arm64-v8a/libonnxruntime4j_jni.so classes.jar; do
    if ! unzip -Z1 "$ORT_REV30_AAR" | grep -qx "$entry"; then
        echo "[ERROR] REV30 ORT AAR missing entry: $entry" >&2
        exit 1
    fi
done
echo "REV30 ORT QNN/HTA AAR: SHA/structure OK (XNNPACK intentionally unavailable)"

if [ ! -s "$QNN_VERSION_FILE" ]; then
    echo "[ERROR] Missing qnn-version.properties" >&2
    exit 1
fi
QNN_VERSION="$(sed -n 's/^qnnRuntimeVersion=//p' "$QNN_VERSION_FILE" | tr -d '\r' | head -n1)"
if [ -z "$QNN_VERSION" ]; then
    echo "[ERROR] qnnRuntimeVersion is missing" >&2
    exit 1
fi
if [ "$QNN_VERSION" != "2.44.0" ]; then
    echo "[ERROR] REV30 is pinned to QNN 2.44.0; configured ${QNN_VERSION}" >&2
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
mkdir -p "$CPU_CACHE" "$QNN_CACHE" "${ROOT}/app/libs" \
    "${ROOT}/sdk/src/main/jniLibs/arm64-v8a" "${ROOT}/sdk/src/main/jniLibs/x86_64" \
    "${ROOT}/app/src/main/jniLibs/arm64-v8a"

echo "=== Supertonic REV33.9 REV30-ORT CPU A/B setup ==="
echo "LiteRT CPU runtime : ${LITERT_NATIVE_VERSION}"
echo "QAIRT/QNN runtime  : ${QNN_VERSION}"
echo "LiteRT GPU / NNAPI : removed"
echo "ONNX CPU runtime    : exact REV30 ORT CPU/QNN core (no XNNPACK EP)"
echo "SM6350 accelerator : QNN HTA + patched ORT 1.28 backend recognition"

# ---------------------------------------------------------------------------
# Native LiteRT CPU runtime only.
# ---------------------------------------------------------------------------
LITERT_AAR_URL="https://dl.google.com/dl/android/maven2/com/google/ai/edge/litert/litert/${LITERT_NATIVE_VERSION}/litert-${LITERT_NATIVE_VERSION}.aar"
CACHED_LITERT_AAR="${CPU_CACHE}/litert-${LITERT_NATIVE_VERSION}.aar"
if [ ! -s "$CACHED_LITERT_AAR" ]; then
    echo "Downloading LiteRT ${LITERT_NATIVE_VERSION}..."
    curl --fail --location --retry 3 --retry-delay 2 \
        --output "${CACHED_LITERT_AAR}.part" "$LITERT_AAR_URL"
    mv -f "${CACHED_LITERT_AAR}.part" "$CACHED_LITERT_AAR"
else
    echo "Using cached LiteRT AAR: $CACHED_LITERT_AAR"
fi
if ! unzip -tqq "$CACHED_LITERT_AAR" >/dev/null 2>&1; then
    echo "[ERROR] Corrupt LiteRT AAR: $CACHED_LITERT_AAR" >&2
    exit 1
fi
TMP="${ROOT}/.tmp_rev30_setup"
rm -rf "$TMP"
mkdir -p "$TMP/litert"
unzip -q "$CACHED_LITERT_AAR" -d "$TMP/litert"
for abi in arm64-v8a x86_64; do
    src="${TMP}/litert/jni/${abi}/libLiteRt.so"
    if [ ! -s "$src" ]; then
        echo "[ERROR] ${abi}/libLiteRt.so missing from LiteRT AAR" >&2
        exit 1
    fi
    mkdir -p "${LITERT_DIR}/${abi}"
    cp -f "$src" "${LITERT_DIR}/${abi}/libLiteRt.so"
done

# Remove every old LiteRT accelerator/delegate artifact so an incremental build
# cannot accidentally retain the removed GPU/NNAPI/NPU paths.
rm -f \
    "${ROOT}/app/libs/litert-api.aar" \
    "${ROOT}/app/libs/litert.aar" \
    "${ROOT}/app/libs/litert-gpu-api.aar" \
    "${ROOT}/app/libs/litert-gpu.aar" \
    "${ROOT}/app/libs/qnn-litert-delegate.aar" \
    "${ROOT}/app/libs/qnn-litert-delegate-${QNN_VERSION}.aar" 2>/dev/null || true
for abi in arm64-v8a x86_64; do
    rm -f \
        "${ROOT}/sdk/src/main/jniLibs/${abi}/libLiteRtClGlAccelerator.so" \
        "${ROOT}/sdk/src/main/jniLibs/${abi}/libLiteRtOpenClAccelerator.so" \
        "${ROOT}/sdk/src/main/jniLibs/${abi}/libLiteRtGpuAccelerator.so" \
        "${ROOT}/sdk/src/main/jniLibs/${abi}/libLiteRtCompilerPlugin_Qualcomm.so" \
        "${ROOT}/sdk/src/main/jniLibs/${abi}/libLiteRtDispatch_Qualcomm.so" 2>/dev/null || true
done

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

rm -rf "$TMP"
echo ""
echo "Done."
echo "  LiteRT: CPU/XNNPACK only"
echo "  ONNX CPU: XNNPACK EP compiled into custom ORT native core
  Modern Qualcomm ONNX: QNN HTP (REV25 path preserved)"
echo "  SM6350/lito ONNX: QNN HTA with patched ORT 1.28 backend recognition; stage probe preserved"
echo "  ./gradlew :app:assembleDebug"
