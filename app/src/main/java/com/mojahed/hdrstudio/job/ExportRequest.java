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
}
