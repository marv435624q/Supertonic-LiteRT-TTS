# REV33.9 — log-driven ONNX CPU auto fix + exact REV30 ORT native-core A/B

## Supplied REV33.8 log result

Original ONNX, 4 steps, same manual Korean test text:

- ORT CPU EP totals: 1751.736 / 1748.653 / 1691.293 ms
  - median total: 1748.653 ms
  - audio: 6.1837 s
  - median RTF: ~0.2828
- ORT XNNPACK EP + CPU fallback totals: 1865.563 / 1791.787 / 1867.712 ms
  - median total: 1865.563 ms
  - audio: 6.1837 s
  - median RTF: ~0.3017
- On this run, ORT CPU EP is ~6.3% faster by median total time.

REV33.8 auto incorrectly selected XNNPACK because it averaged only two samples:
- CPU: 1747.178 / 1738.996 ms -> 1743.087-ish measured winner statistic
- XNN: 1857.148 / 1411.168 ms -> one anomalously fast XNN run pulled the mean down

## Auto selector changes

- 2-run mean removed.
- Each backend now gets 3 measured warm runs and uses median-of-3.
- If `(max-min)/median > 10%`, two more runs are collected and median-of-5 is used.
- Every sample, median and spread are logged.
- XNN still must beat CPU median by >=3% to win.
- Cancellation/stale selection is explicitly logged and never writes a partial winner cache.
- Winner cache schema bumped to REV33.9 and also includes the ORT runtime variant.

## Why an ORT native-core A/B is included

Restoring the REV30 SessionOptions did not restore the historical ~0.20 RTF. The non-streaming CPU data flow in current OnnxSupertonicRunner is otherwise materially the same as REV30 for the hot synthesis path, while REV32 changed the native ORT core itself:

- REV30 core/AAR: `sdk/libs/onnxruntime-android-qnn-1.28.0-hta.aar`
  - SHA-256 `1952291600ec5f69f871c4365e75d82be1e28348988f0d8521d5bb2e4e5a93db`
  - QNN/HTA custom ORT 1.28 core, no XNNPACK EP.
- REV32+ normal core: rebuilt ORT 1.28 with `--use_qnn static_lib --use_xnnpack` plus HTA patch.

This is not yet claimed as the cause. REV33.9 provides a controlled A/B to prove or reject it.

## Build modes

### Normal QNN+XNNPACK build

Run `BUILD_ALL.bat`.

- ORT runtime marker: `variant=REV32_QNN_XNNPACK`
- ONNX UI: CPU / CPU XNN / NPU (when NPU is available)
- Auto selector enabled.

### Exact REV30 ORT CPU A/B

Run `BUILD_REV33.9_REV30_ORT_CPU_AB.bat`.

- Uses the bundled REV30 AAR above and verifies its exact SHA before building.
- Does NOT rebuild ORT.
- ORT runtime marker: `variant=REV30_QNN_ONLY`
- CPU XNN is hidden/unavailable in this diagnostic build.
- Existing stored CPU-XNN preference is migrated to CPU, so `adb install -r` can preserve app data safely.
- LiteRT and QNN/NPU application code are unchanged; the purpose is specifically to compare ONNX CPU EP performance with the old native core.

## Test

Use `RUN_REV33.9_ONNX_CPU_AB_LOG_CAPTURE.bat`.

For the decisive runtime-core A/B, use the same Original ONNX model, same text, 4 steps, 4 threads and run CPU three times on each build. Compare median `total` / `vector` / RTF.

If the REV30 ORT-core build returns close to ~0.20 while the normal QNN+XNNPACK core remains around ~0.28, the regression is in the native ORT rebuild/build configuration. If both remain around ~0.28, the next A/B target is ORT VE memory-pattern handling and remaining runner/runtime differences rather than XNNPACK registration itself.

## Scope preservation

- LiteRT native C++ inference tree was not changed.
- QNN HTP/HTA session code was not changed.
- NPU Pregen remains locked OFF/disabled.
- Model preload behavior remains enabled.
- Manual ONNX CPU / CPU XNN selection remains available in the normal build.

Version: 0.1.23 / versionCode 32.
