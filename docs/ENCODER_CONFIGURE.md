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

## 2.3.8 phone result and the 2.3.9 fix

2.3.8 on the phone: ladder steps 1–3 accepted, step 4 (+ profile 8 without level) refused. Isolated: 30 fps refused,
export format + level 65536 accepted, no profile accepted, no bitrate mode refused, landscape refused. Declared
pairs: 1/32768, 2/32768, 8/65536. Cause: `KEY_PROFILE` without `KEY_LEVEL`.

2.3.9 (`encode/AvcProfileLevel`, `EncodeConfig.level`):

- For SDR AVC the profile and level are one explicit pair taken from the encoder's declared list: High with the
  highest level the encoder declares for High, else Main with its highest declared level, else neither key (encoder
  default). On this phone that is High / Level 5.2 (8/65536); another encoder gets its own declared level. No level
  number is fixed in the code.
- `EncodeConfig` has a `level` field (-1 = not set). `buildFormat` sets `KEY_LEVEL` with `KEY_PROFILE`. The level is
  part of the canonical string (`|profile=8|level=65536|`) and so of the stored path-check result key: the old SDR
  result does not match and the full path check must run again. Configurations without a level (HDR10 HEVC) keep the
  exact 2.3.8 canonical form; the HDR path is not changed.
- Unchanged: aligned size, 29.885 fps (float), bitrate after clamp, VBR, I-interval, BT.709 / SDR video / limited.
- Recorded: the path check's detail starts with the configuration (profile/level included), and after encoding it
  reports the requested pair next to `profile_idc`/`level_idc` read from the file's SPS (csd-0); the export's file
  check reports the same line. A level_idc lower than requested is reported as within the limit (encoders may write
  the lowest sufficient level), not as a failure.
- The gate is unchanged: export needs a passing full path check (configure, surface, encode, decode, colour
  measurement, file check) for this exact configuration; configure acceptance alone does not open it.
- JVM tests (`tools/jvmtests/AvcTests.java`): pair choice for the phone's list and other lists, level in the key,
  HDR canonical unchanged, SPS parser on hand-made buffers and on x264 High@5.2 / Main@3.1 streams.
