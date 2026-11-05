# SDR export with 0 video frames (2.3.9, SCA-LX9)

Reported 2026-11-05. The 2.3.9 SDR path check passed in full (configure → surface → encode → decode → colour
measurement → file check) with `OMX.hisi.video.encoder.avc`, High / Level 5.2, and the SPS of the check file says
profile_idc 100 / level_idc 52. The A/B export of 4.021–5.021 s with GLSL AI then ran 291 s (app in front, screen on)
and the file check rejected the result: no video track, 0 video samples, 47 audio samples. First AI frame 7954 ms.

What the code shows (facts, not a cause): the muxer must have started, because audio samples are written only after
start, and start happens only on the encoder's output-format callback. So the encoder reported an output format, and
no frame was written. Either the encoder put out no frame, or frames were not written. Before 2.3.10 the export did
not count the stages separately, so this could not be told apart. One more gap: after `awaitEos` the encoder error
was not checked again, so an error raised by the last outputs could pass unnoticed until the file check.

2.3.10 adds, without changing colours, the model, the acceptance rules or the rejection of a file without video:

- `encode/PipeTrace`: separate counters for decoded frames, frames inside the range, processed, sent to the encoder
  surface (eglSwapBuffers), encoder config buffers, encoder output frames, frames written, frames dropped because the
  muxer was not started or already stopped, and audio samples; each with first/last PTS and the wall time of its first
  and last frame. Events: encoder output format (mime, size, profile/level, colour, csd sizes), muxer tracks and start,
  EOS sent and received, decoder end of input and EOS, encoder errors, empty output buffers. The trace is in every
  export report, passed or failed, with the first stage that has fewer frames than the one before.
- No-progress timeout: the export stops with a clear message if 16 frames were sent to the encoder, 60 s have passed
  since the first, and the encoder has put out no frame. The encoder error is now also checked after EOS.
- A rejected file is no longer only deleted: the last 3 are kept in the app's own storage
  (`Android/data/<package>/files/rejected/`, not the gallery), each with a `.txt` holding the report, and the report
  describes the file as MediaExtractor sees it (tracks, samples, sync samples, bytes, PTS range).
- «تشخيص التصدير»: the same one-second SDR range (1) without AI; (2) with GLSL, only if (1) produced a file; (3) only if
  (1) passed and (2) failed: without AI, but waiting after each frame as long as a GLSL frame took (median, at most
  12 s), with no GPU work, to tell slow frames apart from the AI work itself. Each step's trace and a summary line.

## 2.3.10 phone result and 2.3.11

2.3.10 «تشخيص التصدير», range 3.990–4.990 s:

| step | result | in range | processed | sent | encoder config | encoder out | written |
|---|---|---|---|---|---|---|---|
| 1 without AI | DONE, 2.4 s | 30 | 30 | 30 | 1 | 30 | 30 |
| 2 GLSL | stopped by the no-progress rule after 150 s | 16 | 16 | 16 | 0 | 0 | 0 |
| 3 without AI, 10038 ms wait per frame | DONE, 303 s | 30 | 30 | 30 | 1 | 30 | 30 |

Without AI the encoder announces its output format and writes SPS/PPS at the first frame (+0.35 s, or +10.4 s with the
wait). With GLSL there is no output-format event and no SPS/PPS: 16 swaps succeeded (eglSwapBuffers did not fail) and
the encoder put out nothing. Slow frames alone do not explain it (step 3).

Code review of the GLSL AI path (facts):
- No EGL context or surface switch anywhere in the export after `egl.makeCurrent(ws.surface)`. SrEngine is created and
  self-tested earlier, while the offscreen surface is current; tiles only bind the kit's FBO; `runToSurface` binds
  framebuffer 0 and sets the full viewport, disables scissor; every draw checks glGetError.
- The GLSL AI path has no glFinish (ncnn's has). A frame's AI work is queued, the final draw depends on it, and the swap
  hands the encoder a buffer whose GPU fence signals only when all of that work is done.
- `eglPresentationTimeANDROID`'s result was not checked.
- The encoder runs in callback mode: output buffers are delivered by the codec, there is no drain loop to miss.

2.3.11 (diagnosis; encoding and colours unchanged):
- `Egl.swap` checks `eglPresentationTimeANDROID` and `eglSwapBuffers`; a refused frame throws and is not counted as sent.
- Before every swap: EGL current context, draw and read surface against the encoder surface and its size
  (`Egl.checkCurrent`), and GL state of the final draw (`PresentCheck`: framebuffer binding, viewport, draw buffer,
  scissor, blend, rasterizer discard, colour mask, pending errors). Logged for the first 3 frames; a mismatch stops
  the export with what differs.
- «تشخيص التصدير القصير»: the first 5 frames, (1) without AI, (2) GLSL as the export runs it, (3) GLSL with glFinish
  before the final draw. Each frame after the swap: a fence on everything submitted, how long it took to signal, and
  whether encoder output appeared within 1.5 s. End-of-stream wait 15 s. The long-wait step is not repeated.

## 2.3.11 phone result and 2.3.12 fix

2.3.11 short diagnosis (first 5 frames, 3.984–4.151 s):
- without AI: 5 sent, 5 out, 5 written; GPU done 0.2–1.6 ms after each swap; encoder output 11–22 ms after the swap.
- GLSL as it was: EGL/GL state correct on every frame, eglPresentationTimeANDROID and eglSwapBuffers succeeded
  (each swap took ~1 s), the GPU was still busy 712–831 ms after each swap, no encoder output within 1.5 s for any
  frame. The output format and SPS/PPS appeared only after EOS was sent, then EOS with 0 frames: file without video.
- GLSL with glFinish before the final draw (1684–1869 ms per wait): 5 sent, 5 out, 5 written, output 10–21 ms after
  each swap. Frame time 10.07 s (median) against 11.47 s without the wait.

2.3.12: every GLSL AI export (A/B, preview clip, full export) calls glFinish after the frame's processing, before the
final draw and the swap — the position that succeeded. ncnn's AI stage already ends with glFinish in FramePipeline,
so it is unchanged. The wait is reported (report line «مزامنة GPU قبل التسليم», median/max in the A/B report) and is
inside the measured frame time. The diagnostic-only GPU fence probe and encoder-output wait stay limited to
«تشخيص التصدير القصير»; the normal export has no diagnostic waits. The per-frame EGL/GL state check and the checked
presentation time and swap stay in every export (they read state, they do not wait). Encoding, colours, model and
acceptance unchanged.
