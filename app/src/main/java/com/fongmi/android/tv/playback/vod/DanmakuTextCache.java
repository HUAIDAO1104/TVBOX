package com.fongmi.android.tv.playback.vod;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.Function;

/** Bounded memoization of immutable text parsing, never of search or episode selections. */
final class DanmakuTextCache<T> {
    private final LinkedHashMap<String, T> values = new LinkedHashMap<>(128, .75f, true);
    private final Function<String, T> parser;

    DanmakuTextCache(Function<String, T> parser) { this.parser = parser; }

    T get(String raw) {
        String key = Objects.toString(raw, "");
        synchronized (values) {
            T value = values.get(key);
            if (value != null) return value;
        }
        T value = parser.apply(key); // Regex work must never hold a lock used by the UI.
        synchronized (values) {
            values.put(key, value);
            while (values.size() > 4096) values.remove(values.keySet().iterator().next());
        }
        return value;
    }
}
