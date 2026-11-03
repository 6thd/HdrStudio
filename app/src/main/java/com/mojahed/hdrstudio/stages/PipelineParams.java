package com.mojahed.hdrstudio.stages;

/** Processing settings. Each enhancement is independently switchable (requirement: separable controls). */
public final class PipelineParams {
    public int workDomain;          // 0 E_SDR, 1 E_PQ (decided from the source)
    public int outMode;             // 0 HDR10, 1 SDR
    // clean-up
    public boolean deband = true;
    public float debandThreshold = 1.5f / 255f, debandRadius = 12f;
    public float denoiseLuma = 0.3f, denoiseChroma = 0.5f, grainKeep = 0.25f;
    public boolean temporal = true;
    public float temporalStrength = 0.7f;
    // AI detail restoration (SDR sources only)
    public boolean ai;
    public float aiAmount = 0.7f, aiWorkScale = 1f, aiSkinProtect = 0.8f;   // 0.8: AI at 50% work scale made skin waxy in tests
    public int aiTile = 256;
    // final
    public float sharpen = 0.35f, sharpenSkinProtect = 0.5f;
    // HDR mapping
    public int sdrGamma;            // 0 BT.1886 2.4, 1 2.2
    public float refWhite = 203f, peak = 1000f, expand = 0.8f, knee = 0.5f, vibrance = 0.1f, hdrSkinProtect = 0.6f;
    public float srcPeak = 1000f, sdrPeakNits = 250f;
    // output
    public int outW, outH;

    /** Source-dependent fields shared by the export and the single-frame preview. */
    public void applySource(com.mojahed.hdrstudio.core.SourceInfo s, boolean hdrOut) {
        workDomain = s.isHdr() ? 1 : 0;
        outMode = hdrOut ? 0 : 1;
        if (workDomain == 1 && ai) throw new IllegalStateException("تحسين AI غير متاح لمصدر HDR في هذا الإصدار (النموذج مدرّب على SDR)");
        if (s.isHdr()) srcPeak = s.srcMaxCll > 0 ? s.srcMaxCll : s.srcMasteringPeak > 0 ? s.srcMasteringPeak : 1000f;
    }

    public PipelineParams copy() {
        PipelineParams p = new PipelineParams();
        p.workDomain = workDomain; p.outMode = outMode; p.deband = deband; p.debandThreshold = debandThreshold; p.debandRadius = debandRadius;
        p.denoiseLuma = denoiseLuma; p.denoiseChroma = denoiseChroma; p.grainKeep = grainKeep; p.temporal = temporal; p.temporalStrength = temporalStrength;
        p.ai = ai; p.aiAmount = aiAmount; p.aiWorkScale = aiWorkScale; p.aiSkinProtect = aiSkinProtect; p.aiTile = aiTile;
        p.sharpen = sharpen; p.sharpenSkinProtect = sharpenSkinProtect; p.sdrGamma = sdrGamma; p.refWhite = refWhite; p.peak = peak;
        p.expand = expand; p.knee = knee; p.vibrance = vibrance; p.hdrSkinProtect = hdrSkinProtect; p.srcPeak = srcPeak; p.sdrPeakNits = sdrPeakNits;
        p.outW = outW; p.outH = outH;
        return p;
    }
}
