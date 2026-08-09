package com.fongmi.android.tv.playback.vod;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Danmaku;
import com.github.catvod.utils.Prefers;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Persists an explicit manual danmaku choice at work/season scope, never episode URL scope. */
public final class DanmakuManualMatchStore {

    private static final String PREF_KEY = "danmaku_manual_matches_v1";
    private static final int MAX_ENTRIES = 128;
    private static final Type MAP_TYPE = TypeToken.getParameterized(
            LinkedHashMap.class, String.class, Selection.class).getType();

    private final LinkedHashMap<String, Selection> entries = new LinkedHashMap<>();

    private DanmakuManualMatchStore() {
        try {
            Map<String, Selection> saved = App.gson().fromJson(Prefers.getString(PREF_KEY, ""), MAP_TYPE);
            if (saved != null) entries.putAll(saved);
            trim();
        } catch (Throwable ignored) {
            entries.clear();
        }
    }

    public static DanmakuManualMatchStore get() {
        return Holder.INSTANCE;
    }

    public synchronized Selection find(DanmakuMatchContext context) {
        String key = preferenceKey(context);
        if (key.isEmpty()) return null;
        Selection selection = entries.get(key);
        if (selection == null || selection.query().isEmpty() || selection.selectedName().isEmpty()) return null;
        // Refresh insertion order so frequently used mappings survive the bounded cache.
        entries.remove(key);
        entries.put(key, selection);
        return selection;
    }

    public synchronized void remember(DanmakuMatchContext context, String query, Danmaku selected) {
        String key = preferenceKey(context);
        String cleanedQuery = DanmakuQuery.from(Objects.toString(query, "")).searchTitle();
        if (cleanedQuery.isEmpty() && context != null) {
            cleanedQuery = DanmakuQuery.from(context.getTitle()).searchTitle();
        }
        String selectedName = selected == null ? "" : selected.getName().trim();
        if (key.isEmpty() || cleanedQuery.isEmpty() || selectedName.isEmpty()) return;
        entries.remove(key);
        entries.put(key, new Selection(cleanedQuery, selectedName, selected.getSourceKey(),
                System.currentTimeMillis()));
        trim();
        persist();
    }

    static String preferenceKey(DanmakuMatchContext context) {
        if (context == null) return "";
        String title = DanmakuMatch.canonicalTitle(context.getTitle());
        if (title.length() < 2) return "";
        return title + '\u001f' + DanmakuMatch.normalizeYear(context.getYear()) + '\u001f'
                + DanmakuMatch.normalizeMediaType(context.getType());
    }

    /** Non-reversible endpoint identity; configured URLs and possible credentials are not copied. */
    public static String sourceKey(String apiUrl) {
        String value = Objects.toString(apiUrl, "").trim();
        if (value.isEmpty()) return "";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format("%02x", item & 0xff));
            return result.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private void trim() {
        while (entries.size() > MAX_ENTRIES) entries.remove(entries.keySet().iterator().next());
    }

    private void persist() {
        try {
            Prefers.put(PREF_KEY, App.gson().toJson(entries, MAP_TYPE));
        } catch (Throwable ignored) {
        }
    }

    public static final class Selection {
        private String query;
        private String selectedName;
        private String sourceKey;
        private long updatedAt;

        @SuppressWarnings("unused")
        private Selection() {
        }

        Selection(String query, String selectedName, String sourceKey, long updatedAt) {
            this.query = query;
            this.selectedName = selectedName;
            this.sourceKey = sourceKey;
            this.updatedAt = updatedAt;
        }

        public String query() {
            return Objects.toString(query, "").trim();
        }

        public String selectedName() {
            return Objects.toString(selectedName, "").trim();
        }

        public String sourceKey() {
            return Objects.toString(sourceKey, "").trim();
        }

        public long updatedAt() {
            return updatedAt;
        }
    }

    private static final class Holder {
        private static final DanmakuManualMatchStore INSTANCE = new DanmakuManualMatchStore();
    }
}
