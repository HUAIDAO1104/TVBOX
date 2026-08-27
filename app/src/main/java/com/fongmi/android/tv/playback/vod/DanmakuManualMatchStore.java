package com.fongmi.android.tv.playback.vod;

import com.fongmi.android.tv.bean.Danmaku;
import com.github.catvod.utils.Prefers;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Persists a manual work/season choice plus a bounded cache of its confirmed episode URLs. */
public final class DanmakuManualMatchStore {

    private static final String PREF_KEY = "danmaku_manual_matches_v1";
    private static final int SCHEMA_VERSION = 3;
    private static final int MAX_ENTRIES = 64;
    private static final int MAX_EPISODES_PER_ENTRY = 160;

    private final LinkedHashMap<String, Selection> entries = new LinkedHashMap<>();

    private DanmakuManualMatchStore() {
        String saved = Prefers.getString(PREF_KEY, "");
        try {
            entries.putAll(decode(saved));
            trim();
            // v5.5.55/56 used reflectively serialized, obfuscated field names. Rewrite any
            // existing value once into the explicit schema so future R8 changes stay compatible.
            if (!saved.isBlank()) persist();
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
        String matchedKey = key;
        if (selection == null) {
            String legacy = legacyPreferenceKey(context);
            selection = entries.get(legacy);
            matchedKey = legacy;
        }
        if (selection == null) {
            String prefix = key + '\u001f';
            for (Map.Entry<String, Selection> entry : entries.entrySet()) {
                if (entry.getKey().startsWith(prefix) && compatible(context, entry.getValue())) {
                    matchedKey = entry.getKey();
                    selection = entry.getValue();
                    break;
                }
            }
        }
        if (selection == null || selection.query().isEmpty() || selection.selectedName().isEmpty()) return null;
        if (!compatible(context, selection)) return null;
        // Refresh insertion order so frequently used mappings survive the bounded cache.
        entries.remove(matchedKey);
        entries.remove(key);
        entries.put(key, selection);
        if (!matchedKey.equals(key)) persist();
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
        // Work + explicit season is the stable identity. Repository metadata often disappears or
        // changes wording between sources/episodes, so year/type must validate a saved choice but
        // must not make it unreachable.
        return title;
    }

    static String legacyPreferenceKey(DanmakuMatchContext context) {
        String title = preferenceKey(context);
        if (title.isEmpty()) return "";
        return title + '\u001f' + DanmakuMatch.normalizeYear(context.getYear()) + '\u001f'
                + DanmakuMatch.normalizeMediaType(context.getType());
    }

    private static boolean compatible(DanmakuMatchContext context, Selection selection) {
        if (context == null || selection == null) return false;
        String expectedYear = DanmakuMatch.normalizeYear(context.getYear());
        String actualYear = DanmakuMatch.candidateYear(selection.selectedName());
        if (!expectedYear.isEmpty() && !actualYear.isEmpty() && !expectedYear.equals(actualYear)) return false;
        String expectedType = DanmakuMatch.normalizeMediaType(context.getType());
        String actualType = DanmakuMatch.candidateType(selection.selectedName());
        return expectedType.isEmpty() || actualType.isEmpty() || expectedType.equals(actualType);
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
            Prefers.put(PREF_KEY, encode(entries));
        } catch (Throwable ignored) {
        }
    }

    static LinkedHashMap<String, Selection> decode(String raw) {
        LinkedHashMap<String, Selection> result = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return result;
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject()) return result;
            JsonObject root = parsed.getAsJsonObject();
            JsonObject stored = root.has("schema") && root.has("entries")
                    && root.get("entries").isJsonObject()
                    ? root.getAsJsonObject("entries") : root;
            for (Map.Entry<String, JsonElement> entry : stored.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                Selection selection = Selection.fromJson(entry.getValue().getAsJsonObject());
                if (selection.query().isEmpty() || selection.selectedName().isEmpty()) continue;
                result.put(entry.getKey(), selection);
                if (result.size() >= MAX_ENTRIES) break;
            }
        } catch (Throwable ignored) {
            result.clear();
        }
        return result;
    }

    static String encode(Map<String, Selection> values) {
        JsonObject root = new JsonObject();
        JsonObject stored = new JsonObject();
        root.addProperty("schema", SCHEMA_VERSION);
        if (values != null) {
            for (Map.Entry<String, Selection> entry : values.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) continue;
                stored.add(entry.getKey(), entry.getValue().toJson());
            }
        }
        root.add("entries", stored);
        return root.toString();
    }

    private static String string(JsonObject object, String... names) {
        for (String name : names) {
            JsonElement value = object.get(name);
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                return value.getAsString();
            }
        }
        return "";
    }

    private static long number(JsonObject object, String... names) {
        for (String name : names) {
            JsonElement value = object.get(name);
            if (value == null || !value.isJsonPrimitive()
                    || !value.getAsJsonPrimitive().isNumber()) continue;
            try {
                return value.getAsLong();
            } catch (RuntimeException ignored) {
            }
        }
        return 0L;
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

        private static Selection fromJson(JsonObject object) {
            String query = string(object, "query", "a");
            String selectedName = string(object, "selectedName", "b");
            String sourceKey = string(object, "sourceKey", "c");
            long updatedAt = number(object, "updatedAt", "e", "d");
            LinkedHashMap<String, CachedEpisode> episodes = new LinkedHashMap<>();
            JsonElement episodeValue = object.has("episodes")
                    ? object.get("episodes") : object.get("d");
            if (episodeValue != null && episodeValue.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry
                        : episodeValue.getAsJsonObject().entrySet()) {
                    if (!entry.getValue().isJsonObject()) continue;
                    CachedEpisode cached = CachedEpisode.fromJson(
                            entry.getValue().getAsJsonObject());
                    if (cached.url().isEmpty()) continue;
                    episodes.put(entry.getKey(), cached);
                    if (episodes.size() >= MAX_EPISODES_PER_ENTRY) break;
                }
            }
            return new Selection(query, selectedName, sourceKey, episodes, updatedAt);
        }

        private JsonObject toJson() {
            JsonObject object = new JsonObject();
            JsonObject cachedEpisodes = new JsonObject();
            object.addProperty("query", query());
            object.addProperty("selectedName", selectedName());
            object.addProperty("sourceKey", sourceKey());
            object.addProperty("updatedAt", updatedAt());
            if (episodes != null) {
                for (Map.Entry<String, CachedEpisode> entry : episodes.entrySet()) {
                    if (entry.getKey() == null || entry.getValue() == null) continue;
                    cachedEpisodes.add(entry.getKey(), entry.getValue().toJson());
                }
            }
            object.add("episodes", cachedEpisodes);
            return object;
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
            // The retired provider's comment IDs cannot be reused on another deployment. Return
            // a cache miss so playback keeps the confirmed title/season identity and resolves a
            // fresh URL from the replacement endpoint.
            if (Danmaku.isRetiredSourceUrl(cached.url())) return null;
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

        static CachedEpisode fromJson(JsonObject object) {
            return new CachedEpisode(string(object, "name", "a"),
                    string(object, "url", "b"));
        }

        JsonObject toJson() {
            JsonObject object = new JsonObject();
            object.addProperty("name", name());
            object.addProperty("url", url());
            return object;
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
