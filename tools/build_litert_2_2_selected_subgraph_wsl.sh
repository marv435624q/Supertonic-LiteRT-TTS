#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NDK_VERSION="${LITERT_NDK_VERSION:-27.2.12479018}"
NDK_ARCHIVE_REV="${LITERT_NDK_ARCHIVE_REV:-r27c}"
CACHE_ROOT="${SUPERTONIC_LITERT_WSL_CACHE:-$HOME/.cache/SupertonicLiteRT}"
SDK_ROOT="$CACHE_ROOT/android-sdk-linux"
NDK_DIR="$SDK_ROOT/ndk/$NDK_VERSION"
DL_DIR="$CACHE_ROOT/downloads"
BUILD_ROOT="${LITERT_CMAKE_BUILD_ROOT:-$CACHE_ROOT/litert-selected-subgraph-cmake}"
RUNTIME_CACHE_ROOT="${SUPERTONIC_LITERT_RUNTIME_CACHE:-$CACHE_ROOT/runtime-cache}"
LITERT_TAG="${LITERT_TAG:-v2.2.0}"
ANDROID_API_LEVEL="${ANDROID_API_LEVEL:-26}"
CMAKE_VERSION="${LITERT_CMAKE_VERSION:-4.0.1}"
ALLOW_DOWNLOADS="${SUPERTONIC_LITERT_ALLOW_DOWNLOADS:-0}"

mkdir -p "$DL_DIR" "$SDK_ROOT/ndk" "$BUILD_ROOT" "$RUNTIME_CACHE_ROOT"

need_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "[ERROR] WSL command not found: $1" >&2
    exit 20
  fi
}

need_cmd git
need_cmd python3

download_file() {
  local url="$1"
  local out="$2"
  if command -v curl >/dev/null 2>&1; then
    curl -fL --retry 3 --retry-delay 2 -o "$out.part" "$url"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$out.part" "$url"
  else
    python3 - "$url" "$out.part" <<'PY'
import sys, urllib.request
url, out = sys.argv[1:3]
with urllib.request.urlopen(url) as r, open(out, "wb") as f:
    while True:
        b = r.read(1024 * 1024)
        if not b:
            break
        f.write(b)
PY
  fi
  mv -f "$out.part" "$out"
}

extract_zip() {
  local archive="$1"
  local outdir="$2"
  if command -v unzip >/dev/null 2>&1; then
    unzip -q "$archive" -d "$outdir"
  else
    python3 - "$archive" "$outdir" <<'PY'
import sys, zipfile
archive, outdir = sys.argv[1:3]
with zipfile.ZipFile(archive) as z:
    z.extractall(outdir)
PY
  fi
}

if [[ ! -f "$NDK_DIR/build/cmake/android.toolchain.cmake" ]]; then
  archive="$DL_DIR/android-ndk-${NDK_ARCHIVE_REV}-linux.zip"
  if [[ ! -s "$archive" && "$ALLOW_DOWNLOADS" != "1" ]]; then
    echo "[ERROR] Linux Android NDK $NDK_VERSION is not cached and downloads are disabled." >&2
    echo "        Expected runtime NDK: $NDK_DIR" >&2
    echo "        Or cached archive: $archive" >&2
    echo "        To bootstrap once only: SUPERTONIC_LITERT_ALLOW_DOWNLOADS=1" >&2
    exit 21
  fi
  if [[ ! -s "$archive" ]]; then
    echo "[LITERT-WSL] downloading Linux Android NDK $NDK_ARCHIVE_REV (explicitly allowed)"
    download_file \
      "https://dl.google.com/android/repository/android-ndk-${NDK_ARCHIVE_REV}-linux.zip" \
      "$archive"
  else
    echo "[LITERT-WSL] using cached NDK archive: $archive"
  fi

  stage="$CACHE_ROOT/ndk-extract-${NDK_ARCHIVE_REV}"
  rm -rf "$stage"
  mkdir -p "$stage"
  extract_zip "$archive" "$stage"

  extracted="$stage/android-ndk-${NDK_ARCHIVE_REV}"
  if [[ ! -f "$extracted/build/cmake/android.toolchain.cmake" ]]; then
    echo "[ERROR] Extracted Linux NDK is incomplete: $extracted" >&2
    exit 21
  fi
  rm -rf "$NDK_DIR"
  mkdir -p "$(dirname "$NDK_DIR")"
  mv "$extracted" "$NDK_DIR"
  rm -rf "$stage"
fi

case "$(uname -s)-$(uname -m)" in
  Linux-x86_64|Linux-amd64) ;;
  *)
    echo "[ERROR] Local LiteRT auto-build requires an x86_64 WSL2 Linux distro." >&2
    echo "        Host reported: $(uname -s)-$(uname -m)" >&2
    exit 22
    ;;
esac

export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"
export ANDROID_NDK_HOME="$NDK_DIR"
export ANDROID_NDK_ROOT="$NDK_DIR"
export LITERT_NDK_VERSION="$NDK_VERSION"
export LITERT_CMAKE_BUILD_ROOT="$BUILD_ROOT"
export SUPERTONIC_LITERT_ALLOW_DOWNLOADS="$ALLOW_DOWNLOADS"

# Project folders are disposable; the custom LiteRT runtime is not. Cache the
# completed runtime globally by the exact builder script + toolchain identity.
# If a newly extracted source folder contains the same LiteRT patch, simply
# copy the two already-built DSOs instead of re-running CMake/FetchContent.
BUILDER_SCRIPT="$ROOT/tools/build_litert_2_2_selected_subgraph_cmake.sh"
BUILDER_SHA256="$(sha256sum "$BUILDER_SCRIPT" | awk '{print $1}')"
RUNTIME_KEY_INPUT="tag=$LITERT_TAG|ndk=$NDK_VERSION|api=$ANDROID_API_LEVEL|cmake=$CMAKE_VERSION|builder=$BUILDER_SHA256"
RUNTIME_KEY="$(printf '%s' "$RUNTIME_KEY_INPUT" | sha256sum | awk '{print $1}')"
RUNTIME_CACHE_DIR="$RUNTIME_CACHE_ROOT/$RUNTIME_KEY"
RUNTIME_MANIFEST="$RUNTIME_CACHE_DIR/CACHE_MANIFEST.txt"

runtime_cache_valid() {
  [[ -s "$RUNTIME_CACHE_DIR/arm64-v8a/libLiteRt.so" ]] || return 1
  [[ -s "$RUNTIME_CACHE_DIR/x86_64/libLiteRt.so" ]] || return 1
  [[ -s "$RUNTIME_CACHE_DIR/SELECTED_SUBGRAPH_RUNTIME.txt" ]] || return 1
  [[ -f "$RUNTIME_MANIFEST" ]] || return 1
  grep -Fqx "key_input=$RUNTIME_KEY_INPUT" "$RUNTIME_MANIFEST" || return 1
  local expected_arm64 expected_x86 actual_arm64 actual_x86
  expected_arm64="$(awk -F= '$1=="arm64_sha256"{print $2}' "$RUNTIME_MANIFEST")"
  expected_x86="$(awk -F= '$1=="x86_64_sha256"{print $2}' "$RUNTIME_MANIFEST")"
  [[ -n "$expected_arm64" && -n "$expected_x86" ]] || return 1
  actual_arm64="$(sha256sum "$RUNTIME_CACHE_DIR/arm64-v8a/libLiteRt.so" | awk '{print $1}')"
  actual_x86="$(sha256sum "$RUNTIME_CACHE_DIR/x86_64/libLiteRt.so" | awk '{print $1}')"
  [[ "$actual_arm64" == "$expected_arm64" ]] || return 1
  [[ "$actual_x86" == "$expected_x86" ]] || return 1
  return 0
}

restore_runtime_cache() {
  mkdir -p "$ROOT/litert/arm64-v8a" "$ROOT/litert/x86_64"
  cp -f "$RUNTIME_CACHE_DIR/arm64-v8a/libLiteRt.so" "$ROOT/litert/arm64-v8a/libLiteRt.so"
  cp -f "$RUNTIME_CACHE_DIR/x86_64/libLiteRt.so" "$ROOT/litert/x86_64/libLiteRt.so"
  cp -f "$RUNTIME_CACHE_DIR/SELECTED_SUBGRAPH_RUNTIME.txt" "$ROOT/litert/SELECTED_SUBGRAPH_RUNTIME.txt"
  echo "[LITERT-CACHE] HIT: $RUNTIME_CACHE_DIR"
  echo "[LITERT-CACHE] restored arm64-v8a/x86_64 libLiteRt.so; CMake and dependency downloads skipped"
}

save_runtime_cache() {
  local stage="$RUNTIME_CACHE_ROOT/.stage-$RUNTIME_KEY-$$"
  rm -rf "$stage"
  mkdir -p "$stage/arm64-v8a" "$stage/x86_64"
  cp -f "$ROOT/litert/arm64-v8a/libLiteRt.so" "$stage/arm64-v8a/libLiteRt.so"
  cp -f "$ROOT/litert/x86_64/libLiteRt.so" "$stage/x86_64/libLiteRt.so"
  cp -f "$ROOT/litert/SELECTED_SUBGRAPH_RUNTIME.txt" "$stage/SELECTED_SUBGRAPH_RUNTIME.txt"
  cat > "$stage/CACHE_MANIFEST.txt" <<EOF_CACHE
key=$RUNTIME_KEY
key_input=$RUNTIME_KEY_INPUT
builder_sha256=$BUILDER_SHA256
arm64_sha256=$(sha256sum "$stage/arm64-v8a/libLiteRt.so" | awk '{print $1}')
x86_64_sha256=$(sha256sum "$stage/x86_64/libLiteRt.so" | awk '{print $1}')
EOF_CACHE
  rm -rf "$RUNTIME_CACHE_DIR"
  mv "$stage" "$RUNTIME_CACHE_DIR"
  echo "[LITERT-CACHE] SAVED: $RUNTIME_CACHE_DIR"
}

echo "============================================================"
echo " Supertonic LiteRT 2.2 selected-subgraph CMake auto-build"
echo "============================================================"
echo "Project     : $ROOT"
echo "WSL cache   : $CACHE_ROOT"
echo "Linux NDK   : $NDK_DIR"
echo "Build cache : $BUILD_ROOT"
echo "Runtime cache: $RUNTIME_CACHE_ROOT"
echo "Runtime key : ${RUNTIME_KEY:0:16}..."
echo "Network     : $([[ "$ALLOW_DOWNLOADS" == "1" ]] && echo ALLOWED || echo DISABLED)"
echo "Output      : $ROOT/litert"
echo

if [[ "${LITERT_FORCE_REBUILD:-0}" != "1" ]] && runtime_cache_valid; then
  restore_runtime_cache
  exit 0
fi

if [[ "${LITERT_FORCE_REBUILD:-0}" == "1" ]]; then
  echo "[LITERT-CACHE] forced rebuild requested; ignoring completed runtime cache"
else
  echo "[LITERT-CACHE] MISS: building once, then storing globally for future source folders"
fi

"$ROOT/tools/build_litert_2_2_selected_subgraph_cmake.sh" "$ROOT/litert"
save_runtime_cache
