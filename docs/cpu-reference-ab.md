# REV33.10 - exact REV40 CPU reference A/B

Source comparison against uploaded `Supertonic-REV40-MEASURE-SM6350-HTA-FINAL-FIX3-2026-08-29` found:

- Non-streaming `synthesize()` hot path is identical except profile text.
- VE workspace/tensor reuse/ping-pong code is byte-identical.
- Uploaded REV40 CPU SessionOptions only set `intra_op` and `inter_op`; current REV33.9 additionally sets arena/memory-pattern explicitly.
- Most importantly, uploaded REV40 uses a DIFFERENT ORT AAR: SHA256 `cd37083d5d1d12ccb08ef7bf23166269c507f078dd07c5da386a601149c1f1f2`.
- Prior A/B tested REV30 AAR SHA `195229...`, not this REV40 AAR.

`BUILD_REV33.10_REV40_CPU_REFERENCE.bat` therefore recreates the exact REV40 CPU reference stack inside the current source: REV40 AAR + REV40 CPU SessionOptions policy. CPU XNN is hidden because this AAR was built with XNNPACK OFF. LiteRT source is untouched.

Expected log marker: `[ORT-RUNTIME-VARIANT] variant=REV40_HTA_CPU_REF` and `[ORT-CPU-REV40-REF]`.
