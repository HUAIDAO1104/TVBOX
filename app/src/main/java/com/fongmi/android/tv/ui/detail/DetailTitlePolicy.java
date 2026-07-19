package com.fongmi.android.tv.ui.detail;

/** Keeps uneven TV title lengths visually balanced without truncating the work name. */
public final class DetailTitlePolicy {

    private DetailTitlePolicy() {
    }

    public static float textSizeSp(String title) {
        int length = codePointLength(title);
        // Restore the balanced scale used before the over-aggressive 40% reduction. Long
        // names still step down and wrap, while ordinary titles remain readable at TV distance.
        if (length > 18) return 12f;
        if (length > 14) return 13f;
        if (length > 9) return 14f;
        return 15f;
    }

    public static int maxLines(String title) {
        return codePointLength(title) > 9 ? 2 : 1;
    }

    private static int codePointLength(String value) {
        if (value == null || value.isBlank()) return 0;
        return value.codePointCount(0, value.length());
    }
}
