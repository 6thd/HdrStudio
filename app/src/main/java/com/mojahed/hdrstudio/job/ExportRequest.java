package com.mojahed.hdrstudio.job;

import android.net.Uri;

import com.mojahed.hdrstudio.core.SourceInfo;
import com.mojahed.hdrstudio.encode.Presets;
import com.mojahed.hdrstudio.stages.PipelineParams;

/** Immutable-by-convention description of one export (or preview clip). */
public final class ExportRequest {
    public Uri uri;
    public SourceInfo src;
    public PipelineParams params;
    public Presets preset = Presets.HDR10_MASTER;
    public long bitrateOverride;          // 0 = preset starting point
    public int targetShortSide;           // 0 = same resolution
    public boolean measureLight = true;   // extra analysis pass for MaxCLL/MaxFALL
    public boolean keepAudio = true;
    public boolean preview;               // short clip into the app cache, not the gallery
    public long startUs, endUs = Long.MAX_VALUE;
    public EncodeSelfTest.Result selfTest; // must be usable for HDR export
    /** A/B export test: the engine tag ("GLSL" / "ncnn"); bounded like a preview, saved to the gallery under that tag. */
    public String abTag;
    /** A/B: the AI tile both engines use (0 = this job computes the common tile of both engines' memory plans). */
    public int tileOverride;
    /** Export diagnosis only: after each swap, measure GPU completion and wait briefly for encoder output. */
    public boolean diagProbe;
    /** How long to wait for the encoder's end of stream after EOS was sent. */
    public long eosWaitMs = 60_000;

    /** Only [startUs, endUs] is exported. */
    public boolean bounded() { return preview || abTag != null; }
}
