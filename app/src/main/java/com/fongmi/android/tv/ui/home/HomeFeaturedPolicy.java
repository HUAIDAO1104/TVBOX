package com.fongmi.android.tv.ui.home;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure selection rules shared by the TV home shelf and its unit tests. */
public final class HomeFeaturedPolicy {

    public static final int PREVIEW_LIMIT = 6;
    public static final int NO_POSITION = -1;

    private HomeFeaturedPolicy() {
    }

    public static <T> List<T> preview(List<T> source) {
        if (source == null || source.isEmpty()) return Collections.emptyList();
        return new ArrayList<>(source.subList(0, Math.min(PREVIEW_LIMIT, source.size())));
    }

    public static String stableKey(String siteKey, String itemId) {
        return clean(siteKey) + '\u001f' + clean(itemId);
    }

    public static int resolvePosition(List<String> stableKeys, String focusedKey, int fallbackPosition) {
        if (stableKeys == null || stableKeys.isEmpty()) return NO_POSITION;
        if (focusedKey != null && !focusedKey.isEmpty()) {
            int exact = stableKeys.indexOf(focusedKey);
            if (exact >= 0) return exact;
        }
        return Math.max(0, Math.min(fallbackPosition, stableKeys.size() - 1));
    }

    public static long stableId(String stableKey) {
        String value = stableKey == null ? "" : stableKey;
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash == -1L ? Long.MIN_VALUE : hash;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
