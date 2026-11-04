package com.mojahed.hdrstudio.ui;

import android.graphics.Bitmap;
import android.net.Uri;

import com.mojahed.hdrstudio.core.DeviceCaps;
import com.mojahed.hdrstudio.core.SourceInfo;
import com.mojahed.hdrstudio.job.EncodeSelfTest;

import java.util.HashMap;
import java.util.Map;

/** Process-level UI state so a configuration change (rotation) does not lose work. */
final class UiState {
    private static final UiState I = new UiState();
    static UiState get() { return I; }

    volatile DeviceCaps caps;
    volatile Uri uri;
    volatile SourceInfo src;
    final Map<String, EncodeSelfTest.Result> selfTests = new HashMap<>();
    volatile String aiStatus;
    volatile boolean aiReady;
    volatile Bitmap before, after;
    volatile double measuredMsPerFrame;
    volatile String lastPreviewPath;
    volatile String trialReport;
    volatile String aiCompareReport;
}
