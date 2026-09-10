#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LITERT_TAG="${LITERT_TAG:-v2.2.0}"
LITERT_NDK_VERSION="${LITERT_NDK_VERSION:-26.3.11579264}"
ANDROID_API_LEVEL="${ANDROID_API_LEVEL:-23}"
OUT_DIR="${1:-$ROOT/litert}"
WORK_ROOT="${LITERT_BUILD_ROOT:-$ROOT/.ci/litert-selected-subgraph}"
SRC_DIR="$WORK_ROOT/LiteRT"
XNN_SRC="$WORK_ROOT/XNNPACK"
XNN_COMMIT="${XNN_COMMIT:-53a1797ba4360cbde068f2a984652be0f0b7b6fe}"
BAZELISK_VERSION="${BAZELISK_VERSION:-1.27.0}"
BAZELISK="$WORK_ROOT/bin/bazel"
BAZEL_STARTUP_ARGS=()
if [[ "${LITERT_BAZEL_BATCH:-0}" == "1" ]]; then
  # Some nested/containerized build hosts cannot keep Bazel's background
  # server PID alive. Batch mode is slower across ABIs but deterministic.
  BAZEL_STARTUP_ARGS+=(--batch)
fi
if [[ -n "${LITERT_BAZEL_SERVER_JAVABASE:-}" ]]; then
  # Use the host JDK trust store when the embedded Bazel JDK cannot validate
  # the build host's HTTPS interception/root certificate chain.
  BAZEL_STARTUP_ARGS+=(--server_javabase="$LITERT_BAZEL_SERVER_JAVABASE")
fi

: "${ANDROID_HOME:?ANDROID_HOME must point to the Android SDK}"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/$LITERT_NDK_VERSION}"
export ANDROID_NDK_HOME
export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"

if [[ ! -d "$ANDROID_NDK_HOME" ]]; then
  echo "[ERROR] Android NDK not found: $ANDROID_NDK_HOME" >&2
  exit 2
fi
mkdir -p "$WORK_ROOT/bin" "$OUT_DIR/arm64-v8a" "$OUT_DIR/x86_64"

if [[ ! -x "$BAZELISK" ]]; then
  echo "[LITERT] downloading Bazelisk $BAZELISK_VERSION"
  curl -fL --retry 3 --retry-delay 2 \
    -o "$BAZELISK.tmp" \
    "https://github.com/bazelbuild/bazelisk/releases/download/v$BAZELISK_VERSION/bazelisk-linux-amd64"
  mv "$BAZELISK.tmp" "$BAZELISK"
  chmod +x "$BAZELISK"
fi

if [[ ! -d "$SRC_DIR/.git" ]]; then
  echo "[LITERT] cloning $LITERT_TAG"
  rm -rf "$SRC_DIR"
  git clone --depth 1 --branch "$LITERT_TAG" \
    https://github.com/google-ai-edge/LiteRT.git "$SRC_DIR"
else
  echo "[LITERT] resetting existing source to $LITERT_TAG"
  git -C "$SRC_DIR" fetch --depth 1 origin "refs/tags/$LITERT_TAG:refs/tags/$LITERT_TAG"
  git -C "$SRC_DIR" reset --hard "$LITERT_TAG"
  git -C "$SRC_DIR" clean -fdx
fi

echo "[LITERT] injecting selected-subgraph C API shim into pinned v2.2.0"
python3 - "$SRC_DIR" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])
header = root / "tflite/core/c/c_api.h"
selected_source = root / "litert/c/supertonic_selected_subgraph_c_api.cc"
litert_c_build = root / "litert/c/BUILD"
litert_build = root / "litert/BUILD"
profiling_build = root / "tflite/profiling/BUILD"
special_rules = root / "tflite/special_rules.bzl"
workspace = root / "WORKSPACE"

h = header.read_text(encoding="utf-8")
h_anchor = """TFL_CAPI_EXPORT extern void TfLiteInterpreterDelete(
    TfLiteInterpreter* interpreter);

/// Returns the number of input tensors associated with the model.
"""
h_insert = """TFL_CAPI_EXPORT extern void TfLiteInterpreterDelete(
    TfLiteInterpreter* interpreter);

/// Applies `delegate` only to the subgraph referenced by `signature_key`.
TFL_CAPI_EXPORT extern TfLiteStatus
TfLiteInterpreterModifyGraphWithDelegateForSignature(
    TfLiteInterpreter* interpreter,
    TfLiteOpaqueDelegate* delegate,
    const char* signature_key);

/// Restores delegated subgraphs without recreating the Interpreter.
TFL_CAPI_EXPORT extern TfLiteStatus TfLiteInterpreterRemoveAllDelegates(
    TfLiteInterpreter* interpreter);
TFL_CAPI_EXPORT extern void* SupertonicXnnpackWeightCacheProviderCreate();
TFL_CAPI_EXPORT extern TfLiteStatus SupertonicXnnpackWeightCacheProviderLoadOrStartBuild(
    void* provider, const char* cache_path);
TFL_CAPI_EXPORT extern void SupertonicXnnpackWeightCacheProviderStopBuild(
    void* provider);
TFL_CAPI_EXPORT extern void SupertonicXnnpackWeightCacheProviderDelete(
    void* provider);

/// Returns the number of input tensors associated with the model.
"""
if h.count(h_anchor) != 1:
    raise SystemExit("c_api.h anchor mismatch; refusing to patch unknown LiteRT source")
header.write_text(h.replace(h_anchor, h_insert), encoding="utf-8")

selected_source.write_text(r'''#include "tflite/core/c/c_api.h"

#include <new>
#include <vector>

#include "tflite/c/c_api_internal.h"
#include "tflite/core/interpreter.h"
#include "tflite/delegates/xnnpack/weight_cache.h"

extern "C" TFL_CAPI_EXPORT
#if defined(__clang__) || defined(__GNUC__)
__attribute__((used, retain, visibility("default")))
#endif
TfLiteStatus TfLiteInterpreterModifyGraphWithDelegateForSignature(
    TfLiteInterpreter* interpreter,
    TfLiteOpaqueDelegate* delegate,
    const char* signature_key) {
  if (interpreter == nullptr || interpreter->impl == nullptr ||
      delegate == nullptr || signature_key == nullptr) return kTfLiteError;
  const int subgraph_index =
      interpreter->impl->GetSubgraphIndexFromSignature(signature_key);
  if (subgraph_index < 0) return kTfLiteError;
  return interpreter->impl->ModifyGraphWithDelegate(
      delegate, std::vector<int>{subgraph_index});
}

extern "C" TFL_CAPI_EXPORT
#if defined(__clang__) || defined(__GNUC__)
__attribute__((used, retain, visibility("default")))
#endif
TfLiteStatus TfLiteInterpreterRemoveAllDelegates(TfLiteInterpreter* interpreter) {
  if (interpreter == nullptr || interpreter->impl == nullptr) return kTfLiteError;
  return interpreter->impl->RemoveAllDelegates();
}

struct SupertonicXnnpackWeightCacheProvider {
  tflite::xnnpack::MMapWeightCacheProvider impl;
};

extern "C" TFL_CAPI_EXPORT
#if defined(__clang__) || defined(__GNUC__)
__attribute__((used, retain, visibility("default")))
#endif
void* SupertonicXnnpackWeightCacheProviderCreate() {
  return new (std::nothrow) SupertonicXnnpackWeightCacheProvider();
}

extern "C" TFL_CAPI_EXPORT
#if defined(__clang__) || defined(__GNUC__)
__attribute__((used, retain, visibility("default")))
#endif
TfLiteStatus SupertonicXnnpackWeightCacheProviderLoadOrStartBuild(
    void* provider, const char* cache_path) {
  if (!provider || !cache_path) return kTfLiteError;
  auto* p = static_cast<SupertonicXnnpackWeightCacheProvider*>(provider);
  return p->impl.LoadOrStartBuild(cache_path) ? kTfLiteOk : kTfLiteError;
}

extern "C" TFL_CAPI_EXPORT
#if defined(__clang__) || defined(__GNUC__)
__attribute__((used, retain, visibility("default")))
#endif
void SupertonicXnnpackWeightCacheProviderStopBuild(void* provider) {
  if (provider) static_cast<SupertonicXnnpackWeightCacheProvider*>(provider)->impl.StopBuild();
}

extern "C" TFL_CAPI_EXPORT
#if defined(__clang__) || defined(__GNUC__)
__attribute__((used, retain, visibility("default")))
#endif
void SupertonicXnnpackWeightCacheProviderDelete(void* provider) {
  delete static_cast<SupertonicXnnpackWeightCacheProvider*>(provider);
}
''', encoding="utf-8")

b = litert_c_build.read_text(encoding="utf-8")
b_anchor = """cc_library(
    name = "litert_tflite_runtime_c_api_so_shim",
    linkopts = select({
        "//litert:macos": [],
        "//litert:ios": [],
        "//conditions:default": ["-Wl,--undefined-glob=LiteRt*"],
    }),
    deps = LITERT_C_API_COMMON_DEPS + [
        "//tflite/c:c_api",
        "//tflite/c:c_api_experimental",
    ],
)
"""
b_insert = """cc_library(
    name = "litert_tflite_runtime_c_api_so_shim",
    linkopts = select({
        "//litert:macos": [],
        "//litert:ios": [],
        "//conditions:default": ["-Wl,--undefined-glob=LiteRt*"],
    }) + [
        # Supertonic calls the public XNNPACK delegate entry point directly.
        "-Wl,--undefined=TfLiteXNNPackDelegateCreate",
    ],
    deps = LITERT_C_API_COMMON_DEPS + [
        "//tflite/c:c_api",
        "//tflite/c:c_api_experimental",
        "//tflite/delegates/xnnpack:xnnpack_delegate",
    ],
)

# Keep the extension as its own direct cc_shared_library dependency. rules_cc
# documents direct deps as unconditionally statically linked after whole-archive,
# which is the supported mechanism for retaining an otherwise unreferenced C API.
cc_library(
    name = "supertonic_selected_subgraph_c_api",
    srcs = ["supertonic_selected_subgraph_c_api.cc"],
    alwayslink = True,
    deps = [
        "//tflite/c:c_api",
        "//tflite/delegates/xnnpack:xnnpack_delegate",
    ],
)
"""
if b.count(b_anchor) != 1:
    raise SystemExit("litert/c/BUILD anchor mismatch; refusing to patch unknown LiteRT source")
b = b.replace(b_anchor, b_insert)

so_anchor = '''cc_shared_library(
    name = "litert_tflite_runtime_c_api_so",
    additional_linker_inputs = export_lrt_tflite_runtime_script(),
    shared_lib_name = "libLiteRt.so",
    tags = ["manual"],  # To prevent build together with `litert_runtime_c_api_so`.
    target_compatible_with = exclude_windows_target_compatible_with(),
    user_link_flags = export_lrt_tflite_runtime_linkopt() + litert_android_linkopts(),
    deps = [":litert_tflite_runtime_c_api_so_shim"],
)
'''
so_insert = '''cc_shared_library(
    name = "litert_tflite_runtime_c_api_so",
    additional_linker_inputs = export_lrt_tflite_runtime_script(),
    shared_lib_name = "libLiteRt.so",
    tags = ["manual"],  # To prevent build together with `litert_runtime_c_api_so`.
    target_compatible_with = exclude_windows_target_compatible_with(),
    user_link_flags = export_lrt_tflite_runtime_linkopt() + litert_android_linkopts() + [
        # Android API 23+ honors DT_NEEDED paths literally. Give the runtime an
        # explicit SONAME so downstream CMake/clang links record "libLiteRt.so"
        # instead of the build-host absolute path.
        "-Wl,-soname,libLiteRt.so",
        "-Wl,--undefined=TfLiteXNNPackDelegateCreate",
        "-Wl,--undefined=TfLiteInterpreterModifyGraphWithDelegateForSignature",
        "-Wl,--undefined=TfLiteInterpreterRemoveAllDelegates",
        "-Wl,--undefined=SupertonicXnnpackWeightCacheProviderCreate",
        "-Wl,--undefined=SupertonicXnnpackWeightCacheProviderLoadOrStartBuild",
        "-Wl,--undefined=SupertonicXnnpackWeightCacheProviderStopBuild",
        "-Wl,--undefined=SupertonicXnnpackWeightCacheProviderDelete",
    ],
    deps = [
        ":litert_tflite_runtime_c_api_so_shim",
        ":supertonic_selected_subgraph_c_api",
    ],
)
'''
if b.count(so_anchor) != 1:
    raise SystemExit("litert/c/BUILD tflite runtime shared target anchor mismatch")
litert_c_build.write_text(b.replace(so_anchor, so_insert), encoding="utf-8")

lb = litert_build.read_text(encoding="utf-8")
litert_replacements = {
'''config_setting(
    name = "android",
    constraint_values = if_google(
        ["@platforms//os:android"],
        [],
    ),
    values = if_oss(
        {"crosstool_top": "//external:android/crosstool"},
        {},
    ),
    visibility = ["//visibility:public"],
)
''': '''config_setting(
    name = "android",
    constraint_values = ["@platforms//os:android"],
    visibility = ["//visibility:public"],
)
''',
'''config_setting(
    name = "android_x86_64",
    constraint_values = [
        "@platforms//cpu:x86_64",
        "@platforms//os:android",
    ],
    values = if_oss(
        {"crosstool_top": "//external:android/crosstool"},
        {},
    ),
    visibility = ["//visibility:public"],
)
''': '''config_setting(
    name = "android_x86_64",
    constraint_values = [
        "@platforms//cpu:x86_64",
        "@platforms//os:android",
    ],
    visibility = ["//visibility:public"],
)
''',
'''config_setting(
    name = "android_arm64",
    constraint_values = [
        "@platforms//cpu:aarch64",
        "@platforms//os:android",
    ],
    values = if_oss(
        {"crosstool_top": "//external:android/crosstool"},
        {},
    ),
    visibility = ["//visibility:public"],
)
''': '''config_setting(
    name = "android_arm64",
    constraint_values = [
        "@platforms//cpu:arm64",
        "@platforms//os:android",
    ],
    visibility = ["//visibility:public"],
)
''',
}
for old, new in litert_replacements.items():
    if lb.count(old) != 1:
        raise SystemExit("litert/BUILD Android config anchor mismatch; refusing to patch unknown LiteRT source")
    lb = lb.replace(old, new)
litert_build.write_text(lb, encoding="utf-8")

pb = profiling_build.read_text(encoding="utf-8")
pb_anchor = '''        "@org_tensorflow//tensorflow:android": [":atrace_profiler"],
'''
pb_insert = '''        "//litert:android": [":atrace_profiler"],
'''
if pb.count(pb_anchor) != 1:
    raise SystemExit("tflite/profiling/BUILD Android select anchor mismatch")
profiling_build.write_text(pb.replace(pb_anchor, pb_insert), encoding="utf-8")

sr = special_rules.read_text(encoding="utf-8")
sr_anchor = '''        clean_dep("@org_tensorflow//tensorflow:android"): supported_android,
'''
sr_insert = '''        "//litert:android": supported_android,
'''
if sr.count(sr_anchor) != 1:
    raise SystemExit("tflite/special_rules.bzl Android select anchor mismatch")
special_rules.write_text(sr.replace(sr_anchor, sr_insert), encoding="utf-8")

w = workspace.read_text(encoding="utf-8")
w_anchor = """android_ndk_repository(
    name = "androidndk",
    api_level = 26,
)

load("//:android_ndk_env.bzl", "check_android_ndk_env")

check_android_ndk_env(name = "android_ndk_env")

load("@android_ndk_env//:current_android_ndk_env.bzl", "ANDROID_NDK_HOME_IS_SET")

register_toolchains("@androidndk//:all" if ANDROID_NDK_HOME_IS_SET else "@android_ndk_env//:all")
"""
w_insert = """android_ndk_repository(
    name = "supertonic_androidndk",
    api_level = 26,
)

load("//:android_ndk_env.bzl", "check_android_ndk_env")

check_android_ndk_env(name = "android_ndk_env")

load("@android_ndk_env//:current_android_ndk_env.bzl", "ANDROID_NDK_HOME_IS_SET")

register_toolchains("@supertonic_androidndk//:all" if ANDROID_NDK_HOME_IS_SET else "@android_ndk_env//:all")
"""
if w.count(w_anchor) != 1:
    raise SystemExit("WORKSPACE android NDK anchor mismatch; refusing to patch unknown LiteRT source")
workspace.write_text(w.replace(w_anchor, w_insert), encoding="utf-8")
PY

git -C "$SRC_DIR" diff --check
git -C "$SRC_DIR" add -N litert/c/supertonic_selected_subgraph_c_api.cc
git -C "$SRC_DIR" diff -- tflite/core/c/c_api.h litert/c/supertonic_selected_subgraph_c_api.cc litert/c/BUILD litert/BUILD tflite/profiling/BUILD tflite/special_rules.bzl WORKSPACE \
  > "$WORK_ROOT/LiteRT-2.2.0-selected-subgraph.generated.patch"

# XNNPACK's pinned Android architecture config still keys off Bazel's removed
# legacy crosstool_top. Keep the exact pinned XNNPACK revision, but make only
# the Android CPU config_settings platform-constraint based so rules_android_ndk
# can drive the build without selecting x86 microkernels for arm64.
if [[ ! -d "$XNN_SRC/.git" ]]; then
  echo "[XNNPACK] cloning pinned $XNN_COMMIT"
  git clone --no-checkout https://github.com/google/XNNPACK.git "$XNN_SRC"
fi
git -C "$XNN_SRC" fetch --depth 1 origin "$XNN_COMMIT"
git -C "$XNN_SRC" reset --hard FETCH_HEAD
git -C "$XNN_SRC" clean -fdx

python3 - "$XNN_SRC" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1]) / "build_config/BUILD.bazel"
s = p.read_text(encoding="utf-8")

replacements = {
'''config_setting(
    name = "linux_k8",
    values = {"cpu": "k8"},
)
''': '''config_setting(
    name = "linux_k8",
    values = {"cpu": "k8"},
    constraint_values = ["@platforms//os:linux"],
)
''',
'''config_setting(
    name = "android",
    values = {"crosstool_top": "//external:android/crosstool"},
)
''': '''config_setting(
    name = "android",
    constraint_values = ["@platforms//os:android"],
)
''',
'''config_setting(
    name = "android_arm64",
    values = {
        "crosstool_top": "//external:android/crosstool",
        "cpu": "arm64-v8a",
    },
)
''': '''config_setting(
    name = "android_arm64",
    constraint_values = [
        "@platforms//os:android",
        "@platforms//cpu:arm64",
    ],
)
''',
'''config_setting(
    name = "android_x86_64",
    values = {
        "crosstool_top": "//external:android/crosstool",
        "cpu": "x86_64",
    },
)
''': '''config_setting(
    name = "android_x86_64",
    constraint_values = [
        "@platforms//os:android",
        "@platforms//cpu:x86_64",
    ],
)
''',
}
for old, new in replacements.items():
    if s.count(old) != 1:
        raise SystemExit("XNNPACK config anchor mismatch; refusing to patch unknown revision")
    s = s.replace(old, new)
p.write_text(s, encoding="utf-8")
PY

git -C "$XNN_SRC" diff --check
git -C "$XNN_SRC" diff -- build_config/BUILD.bazel \
  > "$WORK_ROOT/XNNPACK-android-platform-config.generated.patch"

# Match Google's Android release configuration. The explicit action_env is
# needed by LiteRT's Android toolchain discovery.
echo "build --action_env ANDROID_NDK_HOME=$ANDROID_NDK_HOME" >> "$SRC_DIR/.bazelrc"

# Keep the target configuration aligned with LiteRT v2.2.0's own Android
# Bazel configuration. LiteRT explicitly sets both --cpu and --fat_apk_cpu
# together with --platforms so legacy/transitive config_settings resolve as
# Android rather than host Linux. It also disables host platform-specific
# auto-config while cross compiling.
# Reference: google-ai-edge/LiteRT v2.2.0 .bazelrc (build:android*).
#
# Do not synthesize libpthread for Android. The Android NDK documents that
# pthread functionality is part of Bionic libc and there is no libpthread.
build_one() {
  local abi="$1"
  local cpu="$2"
  local platform="$3"
  local out="$OUT_DIR/$abi/libLiteRt.so"

  echo "[LITERT] building $abi with registered rules_android_ndk toolchain"
  (
    cd "$SRC_DIR"
    "$BAZELISK" "${BAZEL_STARTUP_ARGS[@]}" build \
      -c opt \
      --platforms="$platform" \
      --cpu="$cpu" \
      --fat_apk_cpu="$cpu" \
      --noenable_platform_specific_config \
      --override_repository="XNNPACK=$XNN_SRC" \
      --extra_toolchains=@supertonic_androidndk//:all \
      --incompatible_enable_cc_toolchain_resolution \
      --incompatible_enable_android_toolchain_resolution \
      --repo_env=USE_HERMETIC_CC_TOOLCHAIN="${LITERT_HERMETIC_CC_TOOLCHAIN:-1}" \
      --dynamic_mode=off \
      --define=xnn_enable_avxvnniint8=false \
      --define=with_xla_support=false \
      --cxxopt=-std=c++17 \
      --host_cxxopt=-std=c++17 \
      --linkopt=-Wl,-z,max-page-size=16384 \
      --linkopt=-Wl,-z,common-page-size=16384 \
      --verbose_failures \
      --action_env "ANDROID_NDK_HOME=$ANDROID_NDK_HOME" \
      --action_env "ANDROID_NDK_API_LEVEL=$ANDROID_API_LEVEL" \
      //litert/c:litert_tflite_runtime_c_api_so
  )

  local built="$SRC_DIR/bazel-bin/litert/c/libLiteRt.so"
  if [[ ! -s "$built" ]]; then
    built="$(find "$SRC_DIR/bazel-bin/litert/c" -type f -name 'libLiteRt.so' -print -quit 2>/dev/null || true)"
  fi
  if [[ -z "$built" || ! -s "$built" ]]; then
    echo "[ERROR] native libLiteRt.so missing after $abi build" >&2
    find "$SRC_DIR/bazel-bin/litert/c" -maxdepth 3 -type f -print 2>/dev/null | head -100 >&2 || true
    exit 10
  fi
  cp -f "$built" "$out"

  local readelf_bin
  readelf_bin="$(command -v llvm-readelf || command -v readelf || true)"
  if [[ -z "$readelf_bin" ]]; then
    echo "[ERROR] llvm-readelf/readelf is required for symbol verification" >&2
    exit 12
  fi

  # Verify the runtime ABI from .dynsym, not merely from the full symbol
  # table. Do not use `readelf | grep -q` under `set -o pipefail`: grep -q
  # closes the pipe as soon as it finds a match, which can make readelf exit
  # with SIGPIPE (141) and turn a successful lookup into a false failure.
  local dynsym="$WORK_ROOT/${abi//\//_}.libLiteRt.dynsym.txt"
  local dynamic="$WORK_ROOT/${abi//\//_}.libLiteRt.dynamic.txt"
  local phdr="$WORK_ROOT/${abi//\//_}.libLiteRt.phdr.txt"
  "$readelf_bin" --wide --dyn-syms "$out" > "$dynsym"
  "$readelf_bin" --wide --dynamic "$out" > "$dynamic"
  "$readelf_bin" -lW "$out" > "$phdr"

  # NDK r26 does not emit flexible-page-size ELF files by default. Every LOAD
  # segment must be aligned to at least 2**14 or libLiteRt cannot be loaded on
  # native 16 KB-page Android devices.
  python3 "$ROOT/tools/verify_elf_16k.py" \
    "$out" "$abi/libLiteRt.so" "$readelf_bin"

  if ! grep -Fq 'Library soname: [libLiteRt.so]' "$dynamic"; then
    echo "[ERROR] $abi custom LiteRT missing required DT_SONAME=libLiteRt.so" >&2
    grep -F 'SONAME' "$dynamic" >&2 || true
    exit 14
  fi

  for symbol in \
    TfLiteInterpreterModifyGraphWithDelegateForSignature \
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
      echo "[ERROR] $abi custom LiteRT missing defined dynamic symbol: $symbol" >&2
      echo "[DEBUG] matching symbol-table rows:" >&2
      grep -F "$symbol" "$dynsym" >&2 || true
      exit 13
    fi
  done

  echo "[LITERT] $abi OK bytes=$(stat -c %s "$out") sha256=$(sha256sum "$out" | awk '{print $1}')"
}

build_one arm64-v8a arm64-v8a @org_tensorflow//tensorflow/tools/toolchains/android:arm64-v8a
build_one x86_64 x86_64 @org_tensorflow//tensorflow/tools/toolchains/android:x86_64

cat > "$OUT_DIR/SELECTED_SUBGRAPH_RUNTIME.txt" <<EOF
LiteRT tag: $LITERT_TAG
Android API: $ANDROID_API_LEVEL
NDK: $LITERT_NDK_VERSION
Patch: generated from pinned v2.2.0 anchors
Required selected-subgraph symbol:
  TfLiteInterpreterModifyGraphWithDelegateForSignature
ELF LOAD alignment: 16384 bytes minimum
EOF

echo "[PASS] custom LiteRT selected-subgraph runtime built"
