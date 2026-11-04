# 2.3.2-test: what it changed, and what was ported

Cursor built 2.3.2-test (APK sha256 4752e52b203c445d67edd17370bd9f9f841e5effdd64644bd6a0061e3917a1bf) on top of
2.3.1 (add2840) and its source never reached GitHub. Branch `import/2.3.2-cursor` starts at add2840 so the
original working tree can be pushed there as one commit and diffed against 2.3.1 directly.

Until then, the change list below comes from the 2.3.2 dex: every class was compared with 2.3.1 compiled from
add2840 by method set, string constants, called methods and fields (compiler accessor noise removed), and the
changed methods were decompiled. Code was re-written from that, not copied.

| 2.3.2 change | Kind | 2.3.4 |
|---|---|---|
| `PathTestVerdict.markNotMeasured(stage, detail)`; `limitations()` says a stop at `VideoEncoder.configure` is not an `eglCreateWindowSurface` failure | HDR diagnostic | ported; `DiagTests` |
| `VideoEncoder`: configure failure carries "مرحلة الفشل: VideoEncoder.configure", the full MediaFormat (`describeFormat`, hdr-static-info bytes) and the exception chain with CodecException details (`describeThrowable`) | HDR diagnostic | ported; createInputSurface/start get their own stage line |
| `EncodeSelfTest`: `isConfigureFailure`; failure stage printed (configure, EGL, other with exception chain) | HDR diagnostic | ported |
| `UndeclaredConfigTrial`: `tryOne` returns the stage reached (early, configure, window, surface); MediaFormat logged before configure; the conclusion separates "configure failed before any EGL surface" from "eglCreateWindowSurface failed" | HDR diagnostic | ported |
| `NcnnMatchProbe` + `NcnnSr.layer/probe` + `nativeLayer/nativeProbe`: stored 16x12 vector, per-layer CPU vs Vulkan comparison, NaN/Inf counts after packing and PixelShuffle | ncnn diagnostic | per-layer comparison ported (`NcnnSr.layer`, `nativeLayer`, `NcnnTileCheck.firstDivergentLayer`, runs only for a failed Vulkan mode); the probe's other checks are covered by direct-vs-reference in `NcnnTileCheck`; `nativeProbe` not kept |
| `NcnnSr.Opts(vulkan, fp16, winograd, sgemm, packing)` | ncnn | replaced by separate FP16 storage / arithmetic flags and the no-silent-change check |
| Black-margin tiling | ncnn | replaced by `TilePlan` (2.3.3) |
| UI: «تشخيص مطابقة ncnn (صغير)» button text | UI | replaced by the per-mode check button |
| `libhdrstudio_ncnn.so` b7fff38b... | native | not used from 2.3.4 on; CI fails if that binary is in the APK |

What cannot be recovered from the dex: comments, the C++ of `nativeLayer`/`nativeProbe` as written, and any
change that left method sets, strings and calls identical (pure arithmetic edits). The push to
`import/2.3.2-cursor` settles those.
