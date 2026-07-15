package com.fongmi.android.tv.ui.setting;

/** Pure focus and viewport calculations for TV settings pages. */
public final class SettingsFocusPolicy {

    private SettingsFocusPolicy() {
    }

    public static int sanitizeScrollY(int scrollY) {
        return Math.max(0, scrollY);
    }

    public static int resolveFocusId(int restoredFocusId, int defaultFocusId) {
        return restoredFocusId > 0 ? restoredFocusId : defaultFocusId;
    }

    public static int requiredScrollDelta(int viewportTop, int viewportBottom, int itemTop, int itemBottom, int safeInset) {
        int inset = Math.max(0, safeInset);
        int safeTop = viewportTop + inset;
        int safeBottom = Math.max(safeTop, viewportBottom - inset);
        if (itemTop < safeTop) return itemTop - safeTop;
        if (itemBottom > safeBottom) return itemBottom - safeBottom;
        return 0;
    }
}
