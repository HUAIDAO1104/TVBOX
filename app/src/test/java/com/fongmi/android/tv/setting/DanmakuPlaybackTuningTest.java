package com.fongmi.android.tv.setting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.media3.ui.danmaku.DanmakuConfig;

import org.junit.Test;

public class DanmakuPlaybackTuningTest {

    @Test
    public void androidNineCapsDensityAndUnlimitedLines() {
        assertTrue(DanmakuPlaybackTuning.isLegacyRenderer(28));
        assertEquals(60, DanmakuPlaybackTuning.maxOnScreen(150, 28));
        assertEquals(7, DanmakuPlaybackTuning.maxScrollLines(0, 28));
        assertEquals(2, DanmakuPlaybackTuning.maxFixedLines(0, 28));
        assertEquals(0.3f, DanmakuPlaybackTuning.scrollGap(0f, 28), 0.0f);
    }

    @Test
    public void androidNineAvoidsSoftwareShadowAndProjectionTrails() {
        assertEquals(DanmakuConfig.STYLE_STROKE,
                DanmakuPlaybackTuning.styleMode(DanmakuConfig.STYLE_SHADOW, 28));
        assertEquals(DanmakuConfig.STYLE_STROKE,
                DanmakuPlaybackTuning.styleMode(DanmakuConfig.STYLE_PROJECTION, 28));
        assertEquals(DanmakuConfig.STYLE_NONE,
                DanmakuPlaybackTuning.styleMode(DanmakuConfig.STYLE_NONE, 28));
        assertEquals(0.1f, DanmakuPlaybackTuning.strokeWidth(0.3f, 28), 0.0f);
    }

    @Test
    public void modernDevicesKeepUserPreferences() {
        assertFalse(DanmakuPlaybackTuning.isLegacyRenderer(31));
        assertEquals(240, DanmakuPlaybackTuning.maxOnScreen(240, 31));
        assertEquals(0, DanmakuPlaybackTuning.maxScrollLines(0, 31));
        assertEquals(DanmakuConfig.STYLE_SHADOW,
                DanmakuPlaybackTuning.styleMode(DanmakuConfig.STYLE_SHADOW, 31));
        assertEquals(0.05f, DanmakuPlaybackTuning.scrollGap(0.05f, 31), 0.0f);
    }

    @Test
    public void legacyDurationsStayReadableAndStable() {
        assertEquals(6000L, DanmakuPlaybackTuning.scrollDuration(3000L, 28));
        assertEquals(4000L, DanmakuPlaybackTuning.fixedDuration(2000L, 28));
        assertEquals(9000L, DanmakuPlaybackTuning.scrollDuration(9000L, 28));
    }
}
