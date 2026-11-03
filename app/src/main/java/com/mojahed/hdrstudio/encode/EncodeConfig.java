package com.mojahed.hdrstudio.encode;

import com.mojahed.hdrstudio.core.FrameRate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * The one description of an encoder configuration. The export path test runs with it, the
 * export configures MediaCodec from it, and a stored path-test result is valid only for an equal
 * configuration (same canonical string), the same path-test version and the same system build.
 *
 * Everything here changes what the encoder produces: codec, MIME, profile, aligned size, exact
 * frame rate, bitrate after the encoder's clamp, bitrate mode, key-frame interval and the colour
 * description. HDR metadata VALUES are deliberately not part of it: the path test proves the
 * metadata transport with sample values, the final values are verified in the exported file.
 * Pure Java (unit-tested on the JVM).
 */
public final class EncodeConfig {
    /** Bumped whenever the canonical form or the meaning of a field changes. */
    public static final int SCHEMA = 1;
    public static final int MODE_DEFAULT = -1;

    public final boolean hdr;
    public final String encoder, mime;
    public final int profile;              // MediaCodecInfo.CodecProfileLevel value, -1 = encoder default
    public final int width, height;        // after encoder alignment
    public final FrameRate fps;
    public final int bitrate;              // bit/s after the encoder's bitrate-range clamp
    public final int bitrateMode;          // MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_*, -1 = default
    public final float iFrameSec;
    public final int colorStandard, colorTransfer, colorRange;

    public EncodeConfig(boolean hdr, String encoder, String mime, int profile, int width, int height, FrameRate fps,
                        int bitrate, int bitrateMode, float iFrameSec, int colorStandard, int colorTransfer, int colorRange) {
        if (encoder == null || encoder.isEmpty() || mime == null) throw new IllegalArgumentException("encoder and MIME required");
        if (width <= 0 || height <= 0 || bitrate <= 0) throw new IllegalArgumentException("bad size or bitrate");
        this.hdr = hdr; this.encoder = encoder; this.mime = mime; this.profile = profile; this.width = width; this.height = height;
        this.fps = fps; this.bitrate = bitrate; this.bitrateMode = bitrateMode; this.iFrameSec = iFrameSec;
        this.colorStandard = colorStandard; this.colorTransfer = colorTransfer; this.colorRange = colorRange;
    }

    /** Stable, human-readable, order-fixed description. Two configurations are the same iff these strings are equal. */
    public String canonical() {
        return String.format(Locale.US, "cfg%d|%s|enc=%s|mime=%s|profile=%d|%dx%d|fps=%s|br=%d|mode=%d|gop=%.3f|colour=%d/%d/%d",
                SCHEMA, hdr ? "hdr10" : "sdr", encoder, mime, profile, width, height, fps, bitrate, bitrateMode, iFrameSec,
                colorStandard, colorTransfer, colorRange);
    }

    /**
     * Storage/lookup key of a path-test result for this configuration.
     * @param testVersion version of the path test logic (a new version invalidates old results)
     * @param systemId    identity of the system build that runs the codec (Build.FINGERPRINT): an OS
     *                    update can change codec behaviour, so results do not survive it
     */
    public String resultKey(int testVersion, String systemId) {
        return "st_" + sha256(canonical() + "|test=" + testVersion + "|sys=" + (systemId == null ? "" : systemId)).substring(0, 40);
    }

    public String describe() {
        return String.format(Locale.US, "%s · %s · %d×%d · %s fps · %.2f Mbps%s · GOP %.1f s", hdr ? "HDR10" : "SDR", encoder, width, height,
                fps.label(), bitrate / 1e6, bitrateMode == 1 ? " VBR" : bitrateMode == 2 ? " CBR" : bitrateMode == 0 ? " CQ" : "", iFrameSec);
    }

    public EncodeConfig withBitrate(int b) {
        return new EncodeConfig(hdr, encoder, mime, profile, width, height, fps, b, bitrateMode, iFrameSec, colorStandard, colorTransfer, colorRange);
    }
    public EncodeConfig withSize(int w, int h) {
        return new EncodeConfig(hdr, encoder, mime, profile, w, h, fps, bitrate, bitrateMode, iFrameSec, colorStandard, colorTransfer, colorRange);
    }
    public EncodeConfig withFps(FrameRate f) {
        return new EncodeConfig(hdr, encoder, mime, profile, width, height, f, bitrate, bitrateMode, iFrameSec, colorStandard, colorTransfer, colorRange);
    }

    @Override public boolean equals(Object o) { return o instanceof EncodeConfig && ((EncodeConfig) o).canonical().equals(canonical()); }
    @Override public int hashCode() { return canonical().hashCode(); }
    @Override public String toString() { return canonical(); }

    static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format(Locale.US, "%02x", x));
            return b.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
