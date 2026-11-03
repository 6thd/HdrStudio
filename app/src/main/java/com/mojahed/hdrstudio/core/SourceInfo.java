package com.mojahed.hdrstudio.core;

import java.nio.ByteBuffer;
import java.util.Locale;

/** Everything the pipeline needs to know about the input, with explicit "assumed" flags. */
public final class SourceInfo {
    public String mime, displayName;
    public long fileBytes;
    public int videoTrack = -1, width, height, rotation;
    public int parW = 1, parH = 1;
    public long durationUs, firstPtsUs, lastPtsUs;
    public int frameCount;
    public double avgFps, nominalFps, minFrameMs, maxFrameMs;
    public boolean vfr;
    public int profile = -1, bitDepth = 8;
    // colour (MediaFormat constants); *Assumed = not present in the file, default used
    public int colorStandard, colorTransfer, colorRange;
    public boolean standardAssumed, transferAssumed, rangeAssumed;
    public ByteBuffer hdrStaticInfo;      // CTA-861.3 as delivered by the extractor (may be null)
    public float srcMasteringPeak, srcMaxCll, srcMaxFall;
    public boolean dolbyVision;
    // audio
    public int audioTrack = -1, audioChannels, audioSampleRate;
    public String audioMime;
    public long audioDurationUs;

    public boolean isHdr() { return colorTransfer == 6 /* ST2084 */ || colorTransfer == 7 /* HLG */; }
    public boolean isPq() { return colorTransfer == 6; }
    public boolean isHlg() { return colorTransfer == 7; }
    public boolean is2020() { return colorStandard == 6; }
    public int displayWidth() { return rotation % 180 == 0 ? squareW() : height; }
    public int displayHeight() { return rotation % 180 == 0 ? height : squareW(); }
    /** width after converting non-square pixels to square pixels */
    public int squareW() { return parW == parH ? width : (int) Math.round((double) width * parW / parH) & ~1; }

    public static String standardName(int s) {
        switch (s) { case 1: return "BT.709"; case 2: return "BT.601 PAL"; case 4: return "BT.601 NTSC"; case 6: return "BT.2020"; default: return "?" + s; }
    }
    public static String transferName(int t) {
        switch (t) { case 1: return "Linear"; case 3: return "SDR (BT.709)"; case 6: return "PQ (ST 2084)"; case 7: return "HLG"; default: return "?" + t; }
    }
    public static String rangeName(int r) { return r == 1 ? "Full" : r == 2 ? "Limited" : "?" + r; }

    public String fpsText() {
        return String.format(Locale.US, "%.3f", avgFps) + (vfr ? " (متغير VFR)" : "");
    }

    public String summaryArabic() {
        StringBuilder b = new StringBuilder();
        b.append("الملف: ").append(displayName).append('\n');
        b.append("الترميز: ").append(mime).append(" · ").append(bitDepth).append("-bit");
        if (dolbyVision) b.append(" · Dolby Vision");
        b.append('\n');
        b.append("الدقة: ").append(width).append('×').append(height);
        if (rotation != 0) b.append(" · دوران ").append(rotation).append('°');
        if (parW != parH) b.append(" · بكسل غير مربع ").append(parW).append(':').append(parH);
        b.append('\n');
        b.append("الإطارات: ").append(frameCount).append(" · ").append(fpsText()).append(" fps · المدة ")
                .append(String.format(Locale.US, "%.2f", durationUs / 1e6)).append(" ث\n");
        b.append("الألوان: ").append(standardName(colorStandard)).append(standardAssumed ? " (مفترض)" : "")
                .append(" · ").append(transferName(colorTransfer)).append(transferAssumed ? " (مفترض)" : "")
                .append(" · ").append(rangeName(colorRange)).append(rangeAssumed ? " (مفترض)" : "").append('\n');
        if (isHdr()) {
            b.append("HDR: ");
            if (srcMasteringPeak > 0) b.append("ذروة العرض المرجعية ").append((int) srcMasteringPeak).append(" nit ");
            if (srcMaxCll > 0) b.append("· MaxCLL ").append((int) srcMaxCll).append(" · MaxFALL ").append((int) srcMaxFall);
            if (srcMasteringPeak <= 0 && srcMaxCll <= 0) b.append("بدون بيانات ثابتة في الحاوية");
            b.append('\n');
        }
        if (audioTrack >= 0) b.append("الصوت: ").append(audioMime).append(" · ").append(audioSampleRate).append(" Hz · ").append(audioChannels).append(" قناة\n");
        else b.append("الصوت: لا يوجد\n");
        return b.toString().trim();
    }
}
