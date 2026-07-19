package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.ui.search.SearchTitleNormalizer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reuses a real poster for the same cautiously-normalized work during the current app session.
 *
 * <p>The cache deliberately stays in memory: provider poster URLs may contain short-lived headers
 * and must not be persisted.  SearchTitleNormalizer keeps seasons/installments separate so a second
 * season cannot accidentally inherit the first season's artwork.</p>
 */
public final class PosterResolver {

    private static final Map<String, String> POSTERS = new ConcurrentHashMap<>();

    private PosterResolver() {
    }

    public static String resolve(String title, String candidate) {
        String key = key(title);
        String url = clean(candidate);
        if (!url.isEmpty()) {
            if (!key.isEmpty()) POSTERS.put(key, url);
            return url;
        }
        return key.isEmpty() ? "" : POSTERS.getOrDefault(key, "");
    }

    public static void remember(String title, String url) {
        resolve(title, url);
    }

    public static void forget(String title, String url) {
        String key = key(title);
        if (!key.isEmpty() && !clean(url).isEmpty()) POSTERS.remove(key, clean(url));
    }

    static void clearForTest() {
        POSTERS.clear();
    }

    private static String key(String title) {
        return SearchTitleNormalizer.normalize(title == null ? "" : title);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
