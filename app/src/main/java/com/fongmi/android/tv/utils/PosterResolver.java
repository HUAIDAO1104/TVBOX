package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.ui.search.SearchTitleNormalizer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Reuses real posters for the same cautiously-normalized work during the current app session.
 *
 * <p>The cache deliberately stays in memory: provider poster URLs may contain short-lived headers
 * and must not be persisted.  SearchTitleNormalizer keeps seasons/installments separate so a second
 * season cannot accidentally inherit the first season's artwork.</p>
 */
public final class PosterResolver {

    private static final int MAX_CANDIDATES = 12;
    private static final Map<String, CopyOnWriteArrayList<String>> POSTERS = new ConcurrentHashMap<>();

    private PosterResolver() {
    }

    public static String resolve(String title, String candidate) {
        String key = key(title);
        String url = clean(candidate);
        if (!url.isEmpty()) {
            if (!key.isEmpty()) rememberCandidate(key, url);
            return url;
        }
        if (key.isEmpty()) return "";
        List<String> candidates = POSTERS.get(key);
        return candidates == null || candidates.isEmpty() ? "" : candidates.get(0);
    }

    public static void remember(String title, String url) {
        resolve(title, url);
    }

    public static void forget(String title, String url) {
        String key = key(title);
        String candidate = clean(url);
        if (key.isEmpty() || candidate.isEmpty()) return;
        CopyOnWriteArrayList<String> candidates = POSTERS.get(key);
        if (candidates == null) return;
        candidates.remove(candidate);
        if (candidates.isEmpty()) POSTERS.remove(key, candidates);
    }

    static void clearForTest() {
        POSTERS.clear();
    }

    private static String key(String title) {
        return SearchTitleNormalizer.normalize(title == null ? "" : title);
    }

    private static void rememberCandidate(String key, String url) {
        CopyOnWriteArrayList<String> candidates = POSTERS.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>());
        candidates.remove(url);
        candidates.add(0, url);
        while (candidates.size() > MAX_CANDIDATES) candidates.remove(candidates.size() - 1);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
