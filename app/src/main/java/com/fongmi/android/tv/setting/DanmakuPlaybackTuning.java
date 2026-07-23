package com.fongmi.android.tv.setting;

import androidx.media3.ui.danmaku.DanmakuConfig;

/**
 * Keeps the canvas based danmaku renderer inside a stable per-frame budget.
 *
 * <p>Android 9 TV/projector firmware commonly exposes a 4K surface backed by a comparatively
 * small GPU. The renderer draws outlined text twice per frame and shadow mode additionally forces
 * a software layer. Leaving line counts unlimited can therefore cause missed frame clears,
 * partially drawn glyphs and apparent ghosting. These limits only affect the live renderer; the
 * user's saved preferences and the source data remain untouched.</p>
 */
final class DanmakuPlaybackTuning {

    private static final int LEGACY_MAX_ON_SCREEN = 60;
    private static final int LEGACY_MAX_SCROLL_LINES = 7;
    private static final int LEGACY_MAX_FIXED_LINES = 2;
    private static final float LEGACY_MAX_TEXT_SCALE = 1.5f;
    private static final float LEGACY_MAX_STROKE_WIDTH = 0.1f;
    private static final float LEGACY_MIN_SCROLL_GAP = 0.3f;
    private static final long LEGACY_MIN_SCROLL_DURATION_MS = 6000L;
    private static final long LEGACY_MIN_FIXED_DURATION_MS = 4000L;

    private DanmakuPlaybackTuning() {
    }

    static boolean isLegacyRenderer(int sdkInt) {
        return sdkInt <= 28;
    }

    static int styleMode(int requested, int sdkInt) {
        if (!isLegacyRenderer(sdkInt)) return requested;
        // Shadow mode switches DanmakuView to a software layer; projection also draws offset
        // glyphs that look like trails when a legacy compositor misses a frame.
        return requested == DanmakuConfig.STYLE_NONE ? requested : DanmakuConfig.STYLE_STROKE;
    }

    static int maxOnScreen(int requested, int sdkInt) {
        return isLegacyRenderer(sdkInt) ? Math.min(requested, LEGACY_MAX_ON_SCREEN) : requested;
    }

    static int maxScrollLines(int requested, int sdkInt) {
        if (!isLegacyRenderer(sdkInt)) return requested;
        return requested == 0 ? LEGACY_MAX_SCROLL_LINES : Math.min(requested, LEGACY_MAX_SCROLL_LINES);
    }

    static int maxFixedLines(int requested, int sdkInt) {
        if (!isLegacyRenderer(sdkInt)) return requested;
        return requested == 0 ? LEGACY_MAX_FIXED_LINES : Math.min(requested, LEGACY_MAX_FIXED_LINES);
    }

    static float textScale(float requested, int sdkInt) {
        return isLegacyRenderer(sdkInt) ? Math.min(requested, LEGACY_MAX_TEXT_SCALE) : requested;
    }

    static float strokeWidth(float requested, int sdkInt) {
        return isLegacyRenderer(sdkInt) ? Math.min(requested, LEGACY_MAX_STROKE_WIDTH) : requested;
    }

    static float scrollGap(float requested, int sdkInt) {
        return isLegacyRenderer(sdkInt) ? Math.max(requested, LEGACY_MIN_SCROLL_GAP) : requested;
    }

    static long scrollDuration(long requested, int sdkInt) {
        return isLegacyRenderer(sdkInt) ? Math.max(requested, LEGACY_MIN_SCROLL_DURATION_MS) : requested;
    }

    static long fixedDuration(long requested, int sdkInt) {
        return isLegacyRenderer(sdkInt) ? Math.max(requested, LEGACY_MIN_FIXED_DURATION_MS) : requested;
    }
}
