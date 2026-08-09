package com.fongmi.android.tv.playback.vod;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Danmaku;
import com.github.catvod.utils.Prefers;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Persists an explicit manual danmaku choice at work/season scope, never episode URL scope. */
public final class DanmakuManualMatchStore {

    private static final String PREF_KEY = "danmaku_manual_matches_v1";
    private static final int MAX_ENTRIES = 64;
    private static final int MAX_EPISODES_PER_ENTRY = 160;
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
        remember(context, query, selected, List.of());
    }

    public synchronized void remember(DanmakuMatchContext context, String query, Danmaku selected,
                                      List<Danmaku> catalogue) {
        String key = preferenceKey(context);
        String cleanedQuery = DanmakuQuery.from(Objects.toString(query, "")).searchTitle();
        if (cleanedQuery.isEmpty() && context != null) {
            cleanedQuery = DanmakuQuery.from(context.getTitle()).searchTitle();
        }
        String selectedName = selected == null ? "" : selected.getName().trim();
        if (key.isEmpty() || cleanedQuery.isEmpty() || selectedName.isEmpty()) return;
        LinkedHashMap<String, CachedEpisode> episodes = buildEpisodeCache(
                context, selected, catalogue);
        Selection previous = entries.get(key);
        if (previous != null && previous.isSameSelection(selectedName, selected.getSourceKey())) {
            episodes = previous.mergeEpisodes(episodes);
        }
        entries.remove(key);
        entries.put(key, new Selection(cleanedQuery, selectedName, selected.getSourceKey(),
                episodes, System.currentTimeMillis()));
        trim();
        persist();
    }

    static LinkedHashMap<String, CachedEpisode> buildEpisodeCache(
            DanmakuMatchContext context, Danmaku selected, List<Danmaku> catalogue) {
        LinkedHashMap<String, CachedEpisode> result = new LinkedHashMap<>();
        if (selected == null || selected.isEmpty()) return result;
        String year = context == null ? "" : context.getYear();
        String type = context == null ? "" : context.getType();
        List<Danmaku> items = catalogue == null ? List.of() : catalogue;
        for (Danmaku item : items) {
            if (item == null || item.isEmpty()) continue;
            if (!DanmakuMatch.isSamePreferredCatalogue(selected.getName(), year, type,
                    item.getName())) continue;
            Integer episode = DanmakuMatch.episodeNumber(item.getName());
            if (episode == null || episode <= 0 || result.size() >= MAX_EPISODES_PER_ENTRY) continue;
            result.putIfAbsent(String.valueOf(episode), CachedEpisode.from(item));
        }
        Integer selectedEpisode = DanmakuMatch.episodeNumber(selected.getName());
        String currentEpisode = context == null ? "" : context.getEpisode();
        Integer contextEpisode = DanmakuMatch.episodeNumber(currentEpisode);
        Integer episode = selectedEpisode == null ? contextEpisode : selectedEpisode;
        if (episode != null && episode > 0) {
            result.put(String.valueOf(episode), CachedEpisode.from(selected));
        }
        return result;
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
        private LinkedHashMap<String, CachedEpisode> episodes;
        private long updatedAt;

        @SuppressWarnings("unused")
        private Selection() {
        }

        Selection(String query, String selectedName, String sourceKey,
                  LinkedHashMap<String, CachedEpisode> episodes, long updatedAt) {
            this.query = query;
            this.selectedName = selectedName;
            this.sourceKey = sourceKey;
            this.episodes = episodes;
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

        public Danmaku episode(String episode) {
            Integer number = DanmakuMatch.episodeNumber(episode);
            if (number == null || episodes == null) return null;
            CachedEpisode cached = episodes.get(String.valueOf(number));
            if (cached == null || cached.url().isEmpty()) return null;
            Danmaku result = Danmaku.from(cached.url());
            result.setName(cached.name());
            result.setSourceKey(sourceKey());
            return result;
        }

        public boolean hasEpisodes() {
            return episodes != null && !episodes.isEmpty();
        }

        private boolean isSameSelection(String name, String key) {
            return DanmakuMatch.isSamePreferredCatalogue(
                            selectedName(), "", "", Objects.toString(name, "").trim())
                    && sourceKey().equals(Objects.toString(key, "").trim());
        }

        private LinkedHashMap<String, CachedEpisode> mergeEpisodes(
                LinkedHashMap<String, CachedEpisode> latest) {
            LinkedHashMap<String, CachedEpisode> merged = new LinkedHashMap<>();
            if (episodes != null) merged.putAll(episodes);
            if (latest != null) merged.putAll(latest);
            while (merged.size() > MAX_EPISODES_PER_ENTRY) {
                merged.remove(merged.keySet().iterator().next());
            }
            return merged;
        }
    }

    static final class CachedEpisode {
        private String name;
        private String url;

        @SuppressWarnings("unused")
        private CachedEpisode() {
        }

        private CachedEpisode(String name, String url) {
            this.name = name;
            this.url = url;
        }

        static CachedEpisode from(Danmaku item) {
            return new CachedEpisode(item == null ? "" : item.getName(),
                    item == null ? "" : item.getUrl());
        }

        String name() {
            return Objects.toString(name, "").trim();
        }

        String url() {
            return Objects.toString(url, "").trim();
        }
    }

    private static final class Holder {
        private static final DanmakuManualMatchStore INSTANCE = new DanmakuManualMatchStore();
    }
}
