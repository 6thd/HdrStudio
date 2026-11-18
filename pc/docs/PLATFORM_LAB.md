# Platform Compatibility & HDR Diagnostics (PC)

All operations are local. No TikTok login, injection, upload manipulation or invalid MP4 generation.

## Commands (run from pc)

    python -m hdrstudio platform-lab source.mp4 --decode-check --frame-signals --trace-headers
    python -m hdrstudio platform-lab source.mp4 --compare published.mp4
    python -m hdrstudio platform-lab source.mp4 --remux clean.mp4

    # Preview four existing export commands without creating files:
    python -m hdrstudio platform-matrix source.mp4 ./trial
    # Run on a SHORT, trusted video; creates a new folder:
    python -m hdrstudio platform-matrix source.mp4 ./trial --run

The desktop sidebar contains `Platform Lab`: select a video, optionally provide a second path,
then run read-only analysis. Enabling the header trace runs bounded HEVC SEI extraction for HEVC videos.

## Interpreting evidence

- `inspect_boxes` validates top-level box lengths. `mp4_tables.validate` checks unfragmented
  stsz/stz2, stsc, stco/co64, stts, optional ctts/stss, description references, sample counts,
  and data offsets in mdat. It is NOT a complete ISO BMFF validator.
- Fragmented MP4 (moof) is explicitly NOT_VERIFIED. Encrypted formats, timing edits and other
  uncommon box types require further examination.
- `hevc_sei` converts up to eight encoded frames to Annex B and parses NAL boundaries,
  mastering-display SEI, content-light SEI, and *candidate* HDR10+ T.35 messages. This is an
  independent Python envelope scan of the extracted bitstream, NOT SPS/VUI interpretation
  or full-file HDR metadata validation. An absent SEI in the sample is not proof of absence.
- `trace_headers` uses FFmpeg's coded-header parser; `ffprobe` reports stream tags;
  decoder frame-signals report the first 32 packets. These are separate observations but
  not independent codec implementations.
- `remux` uses FFmpeg stream-copy of the streams MP4 can carry (video, audio, `mov_text`
  subtitles); attached pictures, data tracks and other subtitle formats are listed under
  `dropped`, never converted. Each kept stream is verified by ordered packet payload SHA-256
  (`verified_by: packet_payload`); when the container must re-frame the bitstream (e.g. TS
  Annex B H.264/HEVC or ADTS AAC into MP4) it is verified by a SHA-256 of the fully decoded
  content instead (`decoded_content`). It does **not** establish timestamp, edit-list, audio
  synchronization, color metadata, or hardware playback equivalence. The original source is
  never overwritten; the output is published by hard link or, where the filesystem has none
  (FAT32/exFAT, some shares), by exclusive-create copy.
- The report is produced by one `platform_lab.analyze()` for both the CLI and the desktop
  UI. `structure` is a result, never an abort: `PASS`, `FAIL` (with the table error),
  `NOT_VERIFIED` (fragmented) or `NOT_APPLICABLE` (MKV, TS, WebM, AVI). `comparison.signals_equal`
  compares only colour signalling (`pix_fmt`, primaries, transfer, matrix, range); size,
  frame rate and bitrate differences are listed in `comparison.stream_changes`.
- Matching metadata does NOT prove HDR image quality or playback on TikTok. For published
  files, label unsupported/missing evidence NOT_VERIFIED, not PASS.

## Controlled format comparison

1. Select one SHORT source and record its SHA-256, settings, duration, frame rate and resolution.
2. Run the controlled export matrix on the same source with quality off: PQ/HDR10, HLG, SDR,
   and PQ/HDR10+. Do not call them four equivalent profiles; HDR10+ adds dynamic metadata.
3. Run local container and bitstream diagnostics on each output.
4. Upload manually through official platform interfaces. Record exact client version, device,
   account, processing time and playback device. Obtain published files only with authorization.
5. Compare the actual published outputs. Treat unavailable HDR playback evidence as NOT_VERIFIED.

## Acceptance gates and exclusions

- Windows Python 3.12/3.13/3.14 unit tests: consult the exact head's GitHub CI evidence.
- Windows 3.12 must have FFmpeg installed for actual MP4 generation and remux tests.
- Real PQ, HLG, SDR + audio-bearing remux, 4K long-run, GUI rendered interaction on Windows,
  and TikTok/YouTube post-processing remain separate device/fixture acceptance gates until run.
- This PR does not ship a new installer or release, and does not change Android/export shaders.
- Do not merge until independent review and explicit owner approval.
