package com.fongmi.android.tv.ui.detail;

/** Keeps uneven TV title lengths visually balanced without truncating the work name. */
public final class DetailTitlePolicy {

    private DetailTitlePolicy() {
    }

    public static int textSizeSp(String title) {
        int length = codePointLength(title);
        if (length > 18) return 27;
        if (length > 14) return 31;
        if (length > 9) return 35;
        return 39;
    }

    public static int maxLines(String title) {
        return codePointLength(title) > 9 ? 2 : 1;
    }

    private static int codePointLength(String value) {
        if (value == null || value.isBlank()) return 0;
        return value.codePointCount(0, value.length());
    }
}
