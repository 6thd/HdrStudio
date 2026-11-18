# Platform compatibility lab

Run locally from pc:

    python -m hdrstudio platform-lab source.mp4 --decode-check
    python -m hdrstudio platform-lab source.mp4 --compare published.mp4
    python -m hdrstudio platform-lab source.mp4 --remux clean.mp4

Use only files you own or have permission to analyze. Upload through official platform interfaces. No platform validation bypass or malformed output is supported.

## Controlled experiment

Use one short source, same resolution, frame rate, duration, quality and account/device. Disable AI for the initial format comparison. Export separately with the existing tiktok_hdr, tiktok_hlg and tiktok_sdr presets. For PQ only, repeat with hdr10plus=true. Keep all other settings fixed. Record exact settings and source hash. Upload manually and wait for processing. Record platform, client version, time and playback device. Obtain published files only through authorized means.

Compare source and published stream signals. Record missing files as NOT_VERIFIED. HDR signaling is not proof of visual HDR, platform acceptance, or original payload preservation. A platform may legitimately transcode an HDR file.

## Acceptance gates

- Unit tests and generated media fixture must pass on Windows.
- Real PQ, HLG, SDR and audio-bearing remux fixtures must be tested.
- Remux verifies ordered packet payload hashes, not timestamp equivalence, extradata or all metadata.
- Box inspection validates top-level boundaries only; it is not a full ISO BMFF validator.
- Independent elementary-stream HDR metadata verification, sample-table validation, automated export matrix and GUI remain pending.
- Full decode checks only the first video stream and does not establish platform compatibility.
- Do not claim TikTok HDR support until actual published output is verified.
