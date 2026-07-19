package com.fongmi.android.tv.ui.detail;

/** Keeps uneven TV title lengths visually balanced without truncating the work name. */
public final class DetailTitlePolicy {

    private DetailTitlePolicy() {
    }

    public static float textSizeSp(String title) {
        int length = codePointLength(title);
        // The redesigned detail pane is only 30% of a 16:9 TV canvas.  The previous
        // 27-39sp values were inherited from the old full-width hero and overwhelmed
        // the poster, cast and synopsis in this compact card. Keep the same progressive
        // hierarchy while reducing the current type scale by 40 percent.
        if (length > 18) return 7.2f;
        if (length > 14) return 7.8f;
        if (length > 9) return 8.4f;
        return 9.0f;
    }

    public static int maxLines(String title) {
        return codePointLength(title) > 9 ? 2 : 1;
    }

    private static int codePointLength(String value) {
        if (value == null || value.isBlank()) return 0;
        return value.codePointCount(0, value.length());
    }
}
