# SDR path check stopped at VideoEncoder.configure (2.3.7, SCA-LX9)

Reported 2026-11-04. EGL SDR setup succeeded (config #1 RGBA8888 WINDOW|PBUFFER, recordable). The path check then
failed in `VideoEncoder.configure` on `OMX.hisi.video.encoder.avc`:

    mime=video/avc width=1080 height=1920 (portrait source) color-format=2130708361 (Surface)
    bitrate=15507417 bitrate-mode=1 (VBR) profile=8 (AVCProfileHigh, no level) frame-rate=29.885 (float)
    i-frame-interval=1.0 color-standard=1 (BT.709) color-transfer=3 (SDR video) color-range=2 (limited)
    CodecException errorCode=-2147479551 (0x80001001, the vendor OMX "undefined" error) transient=false recoverable=false

The error code does not name the key. 2.3.8 adds `encode/EncoderProbe`, run automatically after a configure refusal
in the path check (its output is appended to the check's detail):

- encoder capabilities: width/height ranges and alignment, whether 1080x1920 and 1920x1080 are supported, the
  frame-rate range for that size and whether 29.885 and 30 are supported, bitrate range, CQ/VBR/CBR, the declared
  profile/level pairs, Surface input;
- a ladder, each step a fresh codec instance released before the next: (1) Surface + bitrate + 30 fps integer +
  I-interval 1; (2) + the real frame rate 29.885 and I-interval as floats; (3) + bitrate mode; (4) + profile without
  level; (5) + colour tags = the export format. The first refused step is printed;
- isolated variants: the export format at 30 fps; with the highest declared level for the profile; without profile;
  without bitrate mode; the same format in landscape.

Nothing changes the export setting or the video timing. If a change is needed it will be one explicit field of
`EncodeConfig` (so the path check and the export build the same MediaFormat, and a changed config no longer matches an
old check: the check must be run again, colours and output file included). The UI now says
«تعذّر بدء الفحص: رفض المرمّز الإعدادات (VideoEncoder.configure)» with the error code and the first refused ladder
step, instead of «شغّل الفحص أولاً», when the check did run.
