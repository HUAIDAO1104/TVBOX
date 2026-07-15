package com.fongmi.android.tv.playback.vod;

/** Bounds a restored TV-list focus position against incrementally loaded content. */
public final class DetailFocusPolicy {

    public static final int INVALID_POSITION = -1;

    private DetailFocusPolicy() {
    }

    public static int clampPosition(int position, int itemCount) {
        if (itemCount <= 0) return INVALID_POSITION;
        return Math.min(Math.max(0, position), itemCount - 1);
    }

    public static float posterAlpha(int scrollY, int fadeStart, int fadeDistance) {
        if (scrollY <= fadeStart) return 1f;
        if (fadeDistance <= 0 || scrollY >= fadeStart + fadeDistance) return 0f;
        return 1f - (scrollY - fadeStart) / (float) fadeDistance;
    }

    public static boolean posterCanReceiveFocus(float alpha) {
        return alpha > 0.2f;
    }
}
