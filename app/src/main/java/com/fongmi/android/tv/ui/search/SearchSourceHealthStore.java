package com.fongmi.android.tv.ui.search;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.utils.Prefers;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Small, process-cached health history for aggregate-search sources.
 *
 * <p>Keys are repository-scoped site keys. Results are read from memory by the UI; persistence is
 * bounded and written from the search completion thread, so ranking never performs a Room query
 * per card.</p>
 */
public final class SearchSourceHealthStore {

    private static final String PREF_KEY = "aggregate_search_source_health_v1";
    private static final int MAX_ENTRIES = 256;
    private static final int MAX_FAILURES = 10;
    private static final Type MAP_TYPE = TypeToken.getParameterized(
            LinkedHashMap.class, String.class, Entry.class).getType();

    public record Snapshot(
            SearchSource.Availability availability,
            long lastSuccessAtMillis,
            long responseTimeMillis,
            int recentFailureCount) {

        private static Snapshot unknown() {
            return new Snapshot(SearchSource.Availability.UNKNOWN, 0, 0, 0);
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, Long> starts = new ConcurrentHashMap<>();
    private final AtomicBoolean persistScheduled = new AtomicBoolean();
    private volatile boolean dirty;

    private SearchSourceHealthStore() {
        try {
            Map<String, Entry> cached = App.gson().fromJson(Prefers.getString(PREF_KEY, ""), MAP_TYPE);
            if (cached != null) entries.putAll(cached);
            trim();
        } catch (Throwable ignored) {
            entries.clear();
        }
    }

    public static SearchSourceHealthStore get() {
        return Holder.INSTANCE;
    }

    /** Starts response timing on the worker that actually executes the site request. */
    public void begin(String scopedSiteKey) {
        if (scopedSiteKey == null || scopedSiteKey.isBlank()) return;
        if (starts.size() >= MAX_ENTRIES * 2) starts.clear();
        starts.put(scopedSiteKey, System.nanoTime());
    }

    public void success(String scopedSiteKey) {
        if (scopedSiteKey == null || scopedSiteKey.isBlank()) return;
        Long started = starts.remove(scopedSiteKey);
        long elapsed = started == null ? 0
                : Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        synchronized (this) {
            Entry entry = entries.getOrDefault(scopedSiteKey, new Entry());
            entry.lastSuccessAtMillis = System.currentTimeMillis();
            if (elapsed > 0) {
                entry.responseTimeMillis = entry.responseTimeMillis <= 0
                        ? elapsed : (entry.responseTimeMillis * 3 + elapsed) / 4;
            }
            entry.recentFailureCount = Math.max(0, entry.recentFailureCount - 1);
            entry.consecutiveFailureCount = 0;
            entry.updatedAtMillis = entry.lastSuccessAtMillis;
            putMostRecent(scopedSiteKey, entry);
            persist();
        }
    }

    /** Empty responses do not promote a provider over sources returning useful matches. */
    public void empty(String scopedSiteKey) {
        starts.remove(scopedSiteKey);
    }

    public void failure(String scopedSiteKey) {
        if (scopedSiteKey == null || scopedSiteKey.isBlank()) return;
        starts.remove(scopedSiteKey);
        synchronized (this) {
            Entry entry = entries.getOrDefault(scopedSiteKey, new Entry());
            entry.recentFailureCount = Math.min(MAX_FAILURES, entry.recentFailureCount + 1);
            entry.consecutiveFailureCount = Math.min(MAX_FAILURES, entry.consecutiveFailureCount + 1);
            entry.updatedAtMillis = System.currentTimeMillis();
            putMostRecent(scopedSiteKey, entry);
            persist();
        }
    }

    public synchronized Snapshot snapshot(String scopedSiteKey) {
        if (scopedSiteKey == null || scopedSiteKey.isBlank()) return Snapshot.unknown();
        Entry entry = entries.get(scopedSiteKey);
        if (entry == null) return Snapshot.unknown();
        SearchSource.Availability availability;
        if (entry.consecutiveFailureCount >= 2) availability = SearchSource.Availability.UNAVAILABLE;
        else if (entry.lastSuccessAtMillis > 0) availability = SearchSource.Availability.AVAILABLE;
        else availability = SearchSource.Availability.UNKNOWN;
        return new Snapshot(availability, entry.lastSuccessAtMillis,
                entry.responseTimeMillis, entry.recentFailureCount);
    }

    private void putMostRecent(String key, Entry entry) {
        entries.remove(key);
        entries.put(key, entry);
        trim();
    }

    private void trim() {
        while (entries.size() > MAX_ENTRIES) {
            String oldest = entries.keySet().iterator().next();
            entries.remove(oldest);
        }
    }

    private void persist() {
        dirty = true;
        if (!persistScheduled.compareAndSet(false, true)) return;
        Task.schedule(this::flush, 500, TimeUnit.MILLISECONDS);
    }

    private void flush() {
        String json;
        synchronized (this) {
            json = App.gson().toJson(entries, MAP_TYPE);
            dirty = false;
        }
        try {
            Prefers.put(PREF_KEY, json);
        } catch (Throwable ignored) {
        } finally {
            persistScheduled.set(false);
            if (dirty) persist();
        }
    }

    private static final class Entry {
        private long lastSuccessAtMillis;
        private long responseTimeMillis;
        private int recentFailureCount;
        private int consecutiveFailureCount;
        private long updatedAtMillis;
    }

    private static final class Holder {
        private static final SearchSourceHealthStore INSTANCE = new SearchSourceHealthStore();
    }
}
