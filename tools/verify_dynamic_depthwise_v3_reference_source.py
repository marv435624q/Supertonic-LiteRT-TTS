#!/usr/bin/env python3
from __future__ import annotations
from pathlib import Path
import hashlib
import sys

ROOT = Path(__file__).resolve().parents[1]
BUILDER = ROOT / "tools/build_litert_2_2_selected_subgraph_cmake.sh"
WRAPPER = ROOT / "tools/build_litert_2_2_selected_subgraph_wsl.sh"
ENGINE = ROOT / "speech-core/src/models/litert/litert_supertonic_tts.cpp"
CAPI = ROOT / "speech-core/src/models/litert/tflite_c_api_minimal.h"

BASE_ENGINE_SHA256 = "27f550341b35c36ac5a0f10ce54655001cbdedce0bb04d46a85eac73a96702d4"
BASE_CAPI_SHA256 = "ec67079842617a3bdc4dc11f6406d94d801aca7d42b793e8473509a63985440a"
PIN = "ae746db8255aa93704012a98b4b030eefd17357d"

def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()

def require(cond: bool, msg: str) -> None:
    if not cond:
        raise SystemExit(f"[FAIL] {msg}")

for p in (BUILDER, WRAPPER, ENGINE, CAPI):
    require(p.is_file(), f"missing {p.relative_to(ROOT)}")

b = BUILDER.read_text(encoding="utf-8")
w = WRAPPER.read_text(encoding="utf-8")

# Exact user-provided SAFE app/runtime-callsite code must remain unchanged.
require(sha256(ENGINE) == BASE_ENGINE_SHA256, "litert_supertonic_tts.cpp changed from user SAFE base")
require(sha256(CAPI) == BASE_CAPI_SHA256, "tflite_c_api_minimal.h changed from user SAFE base")

# Dynamic Depthwise architecture: dedicated depthwise subgraph only.
for needle in (
    "SUPERTONIC_DYNAMIC_DEPTHWISE_QD8_V3",
    "xnn_define_dynamically_quantized_tensor_value",
    "/*num_nonbatch_dims=*/3",
    "xnn_define_channelwise_quantized_tensor_value",
    "/*channel_dim=*/3",
    "bias_tensor.type == kTfLiteFloat32",
    "filter_params->zero_point->data[i] != 0",
    "xnn_define_depthwise_convolution_2d",
    "xnn_create_convolution2d_nhwc_qd8_f32_qc8w",
    "xnn_reshape_convolution2d_nhwc_qd8_f32_qc8w",
    "xnn_setup_convolution2d_nhwc_qd8_f32_qc8w",
    "XNN_FLAG_DEPTHWISE_CONVOLUTION",
    PIN,
):
    require(needle in b, f"builder missing V3 invariant: {needle}")
require("src/subgraph/convolution-2d.c" not in b, "generic convolution subgraph is being patched")

# Restore the known-good upstream v2.2 link graph. OFFLINE2's GPU-off mutation
# caused the final linker failure and must never reappear.
for needle in (
    "-DLITERT_ENABLE_GPU=ON",
    "-DLITERT_ENABLE_NPU=ON",
    "-DTFLITE_ENABLE_GPU=ON",
    "-DLITERT_SKIP_VENDORS=ON",
    "-DLITERT_SKIP_TENSOR_EXAMPLES=ON",
    "FETCHCONTENT_FULLY_DISCONNECTED",
    "Persistent cache preload: xnn-initialize-before-load",
    "_ZNK6tflite3gpu7GpuInfo9IsPowerVREv",
    "_ZN6tflite3gpu6SizeOfENS0_8DataTypeE",
):
    require(needle in b, f"builder missing build-graph invariant: {needle}")
for forbidden in (
    "-DLITERT_ENABLE_GPU=OFF",
    "-DLITERT_ENABLE_NPU=OFF",
    "-DTFLITE_ENABLE_GPU=OFF",
    "SUPERTONIC_CPU_ONLY_RUNTIME",
    "LoadOrStartBuildForAppend",
    "WeightCacheBuilder::Resume",
):
    require(forbidden not in b, f"forbidden regression present: {forbidden}")

# Cross-process cache reload ordering. Upstream delegate creation initializes
# XNNPACK before MMapWeightCacheProvider::LoadOrStartBuild. The custom C ABI
# must preserve that ordering because it can be called before any delegate.
require('#include "xnnpack.h"' in b, "cache bridge does not include XNNPACK public API")
fn = b.rindex("TfLiteStatus SupertonicXnnpackWeightCacheProviderLoadOrStartBuild(")
fn_end = b.index("void SupertonicXnnpackWeightCacheProviderStopBuild", fn)
cache_fn = b[fn:fn_end]
require("xnn_initialize(/*allocator=*/nullptr)" in cache_fn, "cache reload does not initialize XNNPACK")
require(cache_fn.index("xnn_initialize(/*allocator=*/nullptr)") < cache_fn.index("p->impl.LoadOrStartBuild(cache_path)"),
        "xnn_initialize must precede LoadOrStartBuild")
require("CheckFingerprints" in cache_fn, "cache-init rationale marker missing")

# No implicit network bootstrap.
require('ALLOW_DOWNLOADS="${SUPERTONIC_LITERT_ALLOW_DOWNLOADS:-0}"' in b, "builder is not offline by default")
require('ALLOW_DOWNLOADS="${SUPERTONIC_LITERT_ALLOW_DOWNLOADS:-0}"' in w, "WSL wrapper is not offline by default")

print("[PASS] exact SAFE app callsites unchanged")
print("[PASS] dedicated Depthwise QD8/F32/QC8W V3 only; no generic-conv hack")
print("[PASS] pinned XNNPACK revision enforced:", PIN)
print("[PASS] known-good v2.2 CMake link graph restored; vendor/tensor downloads separated")
print("[PASS] XNNPACK initialized before persistent-cache LoadOrStartBuild")
print("[PASS] network bootstrap disabled by default")
