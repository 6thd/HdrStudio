package com.mojahed.hdrstudio.encode;

/**
 * Export presets. Bitrates are STARTING POINTS (bits per pixel per frame), not a claim of a
 * universally best setting; the user can change them, and TikTok re-encodes uploads anyway.
 * They should be compared on real samples (see docs/TEST_PLAN.md).
 */
public enum Presets {
    HDR10_MASTER("HDR10 رئيسي", true, "video/hevc", 0.30, 0, 1.0f),
    SDR("SDR (BT.709)", false, "video/hevc", 0.25, 0, 1.0f),
    TIKTOK_HDR("TikTok HDR10", true, "video/hevc", 0.20, 2160, 1.0f),
    TIKTOK_SDR("TikTok SDR (H.264)", false, "video/avc", 0.25, 1080, 1.0f);

    public final String label; public final boolean hdr; public final String mime;
    public final double bpp; public final int maxShortSide; public final float iFrameSec;

    Presets(String label, boolean hdr, String mime, double bpp, int maxShortSide, float iFrameSec) {
        this.label = label; this.hdr = hdr; this.mime = mime; this.bpp = bpp; this.maxShortSide = maxShortSide; this.iFrameSec = iFrameSec;
    }

    /** Starting bitrate in bit/s (e.g. HDR10 master 1080p30 ~ 19 Mbps, 2160p30 ~ 75 Mbps). Frame-rate scaling is sub-linear. */
    public long startBitrate(int w, int h, double fps) {
        double f = 30 * Math.pow(Math.max(fps, 1) / 30.0, 0.75);
        return Math.round(w * (double) h * f * bpp);
    }
}
