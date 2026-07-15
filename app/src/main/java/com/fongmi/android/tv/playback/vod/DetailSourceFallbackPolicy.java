package com.fongmi.android.tv.playback.vod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Keeps detail-source fallback deterministic and bounded.
 *
 * <p>The selected source is always first, duplicate source identities are removed while preserving
 * rank order, and every remaining source is offered at most once.</p>
 */
public final class DetailSourceFallbackPolicy {

    public static final int DEFAULT_LIMIT = 8;

    private final int size;
    private int position;

    public DetailSourceFallbackPolicy(int size, int position) {
        this.size = Math.max(0, size);
        this.position = Math.clamp(position, 0, Math.max(0, this.size - 1));
    }

    public int nextIndex() {
        if (position + 1 >= size) return -1;
        return ++position;
    }

    public int position() {
        return position;
    }

    public static <T> ArrayList<T> prioritize(T selected, List<T> ranked, Function<T, String> identity) {
        return prioritize(selected, ranked, identity, DEFAULT_LIMIT);
    }

    static <T> ArrayList<T> prioritize(T selected, List<T> ranked, Function<T, String> identity, int limit) {
        Map<String, T> unique = new LinkedHashMap<>();
        add(unique, selected, identity);
        if (ranked != null) for (T item : ranked) add(unique, item, identity);
        ArrayList<T> result = new ArrayList<>(Math.min(Math.max(0, limit), unique.size()));
        for (T item : unique.values()) {
            if (result.size() >= Math.max(0, limit)) break;
            result.add(item);
        }
        return result;
    }

    private static <T> void add(Map<String, T> unique, T item, Function<T, String> identity) {
        if (item == null || identity == null) return;
        String key = identity.apply(item);
        if (key == null || key.isBlank()) return;
        unique.putIfAbsent(key, item);
    }
}
