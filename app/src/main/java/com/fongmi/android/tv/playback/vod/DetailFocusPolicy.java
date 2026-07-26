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

    /** Keeps the episode RecyclerView bounded so it can recycle off-screen rows. */
    public static int episodeViewportRows(int itemCount, int columns, int maxRows) {
        if (itemCount <= 0 || columns <= 0 || maxRows <= 0) return 0;
        int rows = (itemCount + columns - 1) / columns;
        return Math.min(rows, maxRows);
    }

    /** Returns the smallest deterministic scroll required to reveal a focused TV control. */
    public static int focusScrollDelta(int childTop, int childHeight, int viewportTop,
                                       int viewportHeight, int safeInset) {
        int top = viewportTop + Math.max(0, safeInset);
        int bottom = viewportTop + Math.max(0, viewportHeight) - Math.max(0, safeInset);
        if (childTop < top) return childTop - top;
        int childBottom = childTop + Math.max(0, childHeight);
        return childBottom > bottom ? childBottom - bottom : 0;
    }
}
