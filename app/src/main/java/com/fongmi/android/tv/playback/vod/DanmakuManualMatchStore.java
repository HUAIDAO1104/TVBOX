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
    private static final int SCHEMA_VERSION = 6;
    static final long CATALOGUE_TTL_MS = java.util.concurrent.TimeUnit.HOURS.toMillis(6);
    private static final int MAX_ENTRIES = 64;
    private static final int MAX_EPISODES_PER_ENTRY = 160;

    private static final java.util.concurrent.ExecutorService WRITER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "danmaku-preferences"));
    private long writeVersion;
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

    public void remember(DanmakuMatchContext context, String query, Danmaku selected) {
        remember(context, query, selected, List.of());
    }

    public void remember(DanmakuMatchContext context, String query, Danmaku selected, List<Danmaku> catalogue) {
        savePrepared(context, prepare(context, query, selected, catalogue), 0);
    }

    public void rememberAutomatic(DanmakuMatchContext context, String query, Danmaku selected, List<Danmaku> catalogue) {
        savePrepared(context, prepare(context, query, selected, catalogue), 1);
    }

    public void rememberResolved(DanmakuMatchContext context, String query, Danmaku selected, List<Danmaku> catalogue) {
        savePrepared(context, prepare(context, query, selected, catalogue), 2);
    }

    /** Build the potentially large directory off the UI thread, then commit after request checks. */
    static Selection prepare(DanmakuMatchContext context, String query, Danmaku selected, List<Danmaku> catalogue) {
        String cleanedQuery = DanmakuQuery.from(Objects.toString(query, "")).searchTitle();
        if (cleanedQuery.isEmpty() && context != null) cleanedQuery = DanmakuQuery.from(context.getTitle()).searchTitle();
        String name = selected == null ? "" : selected.getName().trim();
        if (preferenceKey(context).isEmpty() || cleanedQuery.isEmpty() || name.isEmpty()) return null;
        Selection value = new Selection(cleanedQuery, name, selected.getSourceKey(),
                buildEpisodeCache(context, selected, catalogue), System.currentTimeMillis());
        value.contextYear = context == null ? "" : DanmakuMatch.normalizeYear(context.getYear());
        value.contextType = context == null ? "" : DanmakuMatch.normalizeMediaType(context.getType());
        return value;
    }

    // kind: explicit choice / automatic guess / freshly resolved confirmed catalogue.
    synchronized void savePrepared(DanmakuMatchContext context, Selection value, int kind) {
        if (value == null) return;
        Selection previous = find(context);
        if (kind == 1 && previous != null && previous.isUserConfirmed()) return;
        value.confirmed = kind == 0 || kind == 2 && previous != null && previous.confirmed;
        if (kind == 2 && previous != null && previous.confirmed) {
            value.contextYear = previous.contextYear;
            value.contextType = previous.contextType;
        }
        if (previous != null && previous.isSameSelection(value.selectedName(), value.sourceKey())) {
            value.correctedLabels.putAll(previous.correctedLabels);
            CachedEpisode old = previous.findEpisode(context.getResolvedEpisode()), current = value.findEpisode(context.getResolvedEpisode());
            // A correction is evidence that the old directory is unsafe. Do not preserve its
            // unseen neighbours and send the next episode back to the same wrong document.
            if (old == null || current == null || old.url().equals(current.url())) value.episodes = previous.mergeEpisodes(value.episodes);
        }
        if (kind == 0 && context.getEpisode().length() <= 512 && !context.getResolvedEpisode().isEmpty()
                && DanmakuEpisodePlan.from(context, 0).complex) {
            value.correctedLabels.remove(context.getEpisode());
            value.correctedLabels.put(context.getEpisode(), context.getResolvedEpisode());
            while (value.correctedLabels.size() > 32) value.correctedLabels.remove(value.correctedLabels.keySet().iterator().next());
        }
        removeConflictingDocuments(value.episodes);
        String key = preferenceKey(context);
        entries.remove(key); entries.put(key, value);
        trim(); persist();
    }

    public void extendConfirmedCatalogue(DanmakuMatchContext context, String sourceKey, List<Danmaku> rows) {
        Selection selection = find(context);
        if (selection == null || !selection.confirmed || !selection.sourceKey().equals(sourceKey)) return;
        Danmaku current = selection.episode(context.getResolvedEpisode());
        if (current == null) return;
        LinkedHashMap<String, CachedEpisode> additions = buildEpisodeCache(context, current, rows);
        synchronized (this) {
            if (find(context) != selection) return; // A newer click owns the directory now.
            for (Map.Entry<String, CachedEpisode> row : additions.entrySet()) {
                CachedEpisode old = selection.episodes.get(row.getKey());
                if (old != null && old.url().equals(row.getValue().url())
                        && (old.verifiedIdentity || !row.getValue().verifiedIdentity)) row.setValue(old);
            }
            Selection updated = selection.copy(selection.mergeEpisodes(additions));
            removeConflictingDocuments(updated.episodes);
            entries.put(preferenceKey(context), updated);
            persist();
        }
    }

    /** Removes one stale document while retaining the user's confirmed work/provider choice. */
    public synchronized void forgetEpisode(DanmakuMatchContext context) {
        Selection selection = find(context);
        if (selection == null || context == null) return;
        Selection updated = selection.copy(new LinkedHashMap<>(selection.episodes));
        if (!updated.removeEpisode(context.getResolvedEpisode())) return;
        entries.put(preferenceKey(context), updated);
        persist();
    }

    static LinkedHashMap<String, CachedEpisode> buildEpisodeCache(
            DanmakuMatchContext context, Danmaku selected, List<Danmaku> catalogue) {
        LinkedHashMap<String, CachedEpisode> result = new LinkedHashMap<>();
        if (selected == null || selected.isEmpty()) return result;
        String year = context == null ? "" : context.getYear();
        String type = context == null ? "" : context.getType();
        List<Danmaku> items = catalogue == null ? List.of() : catalogue;
        if (items.size() > MAX_EPISODES_PER_ENTRY) {
            int selectedIndex = -1;
            for (int i = 0; i < items.size(); i++) if (items.get(i) != null && selected.getUrl().equals(items.get(i).getUrl())) { selectedIndex = i; break; }
            if (selectedIndex >= 0) {
                int from = Math.max(0, selectedIndex - MAX_EPISODES_PER_ENTRY / 4);
                items = items.subList(from, Math.min(items.size(), from + MAX_EPISODES_PER_ENTRY));
            }
        }
        java.util.Set<String> ambiguous = new java.util.HashSet<>();
        for (Danmaku item : items) {
            if (item == null || item.isEmpty()) continue;
            if (!DanmakuMatch.isSamePreferredCatalogue(selected.getName(), year, type,
                    item.getName())) continue;
            String key = DanmakuMatch.episodeKey(item.getName());
            if (key.isEmpty() || result.size() >= MAX_EPISODES_PER_ENTRY || ambiguous.contains(key)) continue;
            CachedEpisode previous = result.putIfAbsent(key, CachedEpisode.from(item));
            if (previous != null && !previous.url().equals(item.getUrl())) {
                // Distinct segments can share a date/period. Never cache an arbitrary timeline.
                result.remove(key);
                ambiguous.add(key);
            }
        }
        removeConflictingDocuments(result);
        String currentEpisode = context == null ? "" : context.getResolvedEpisode();
        String selectedKey = DanmakuMatch.episodeKey(selected.getName());
        String contextKey = DanmakuMatch.episodeKey(currentEpisode);
        if (!selectedKey.isEmpty()) result.put(selectedKey, CachedEpisode.from(selected));
        if (!contextKey.isEmpty() && DanmakuMatch.isEpisodeCompatible(currentEpisode, selected.getName())) result.put(contextKey, CachedEpisode.from(selected));
        return result;
    }

    private static void removeConflictingDocuments(Map<String, CachedEpisode> episodes) {
        Map<String, DanmakuEpisode> identities = new java.util.HashMap<>();
        java.util.Set<String> conflicting = new java.util.HashSet<>();
        for (CachedEpisode row : episodes.values()) {
            DanmakuEpisode identity = row.identity;
            DanmakuEpisode previous = identities.putIfAbsent(row.url(), identity);
            if (previous != null && !previous.sameTimeline(identity)) conflicting.add(row.url());
        }
        episodes.values().removeIf(row -> conflicting.contains(row.url()));
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

    static boolean compatible(DanmakuMatchContext context, Selection selection) {
        if (context == null || selection == null) return false;
        // A pasted video URL confirms this episode only; never search that URL as the
        // next episode's work title or let it pin a different episode to the old video.
        if (selection.isVideoLink() && selection.episode(context.getResolvedEpisode()) == null) return false;
        // An explicit choice may correct inaccurate repository metadata, but only for
        // that same repository edition (or missing metadata), not another known remake.
        if (selection.isUserConfirmed()
                && selection.contextYear != null && selection.contextType != null
                && (context.getYear().isBlank() || selection.contextYear.equals(DanmakuMatch.normalizeYear(context.getYear())))
                && (context.getType().isBlank() || selection.contextType.equals(DanmakuMatch.normalizeMediaType(context.getType())))) return true;
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
        // Selection snapshots are immutable after insertion. Encoding and disk writes must not
        // hold the store monitor: playback reads should never wait for a whole-library save.
        Map<String, Selection> snapshot = new LinkedHashMap<>(entries);
        long version = ++writeVersion;
        WRITER.execute(() -> {
            synchronized (this) { if (version != writeVersion) return; }
            try { Prefers.put(PREF_KEY, encode(snapshot)); } catch (Throwable ignored) { }
        });
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
        private boolean confirmed = true;
        private String contextYear, contextType;
        private final LinkedHashMap<String, String> correctedLabels = new LinkedHashMap<>();

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

        private Selection copy(LinkedHashMap<String, CachedEpisode> rows) {
            Selection value = new Selection(query, selectedName, sourceKey, rows, updatedAt);
            value.confirmed = confirmed; value.contextYear = contextYear; value.contextType = contextType;
            value.correctedLabels.putAll(correctedLabels);
            return value;
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
            removeConflictingDocuments(episodes);
            Selection value = new Selection(query, selectedName, sourceKey, episodes, updatedAt);
            // Older releases did not distinguish automatic guesses from user confirmations.
            // Keep their work hints, but never promote an unverified old URL to a manual choice.
            value.confirmed = object.has("confirmed") && object.get("confirmed").getAsBoolean();
            value.contextYear = object.has("contextYear") ? string(object, "contextYear") : null;
            value.contextType = object.has("contextType") ? string(object, "contextType") : null;
            if (value.confirmed && object.has("correctedLabels") && object.get("correctedLabels").isJsonObject()) {
                for (var entry : object.getAsJsonObject("correctedLabels").entrySet()) {
                    if (entry.getKey().length() > 512 || !entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) continue;
                    String label = entry.getValue().getAsString();
                    if (!label.isEmpty() && label.length() <= 80) value.correctedLabels.put(entry.getKey(), label);
                    if (value.correctedLabels.size() >= 32) break;
                }
            }
            return value;
        }

        private JsonObject toJson() {
            JsonObject object = new JsonObject();
            JsonObject cachedEpisodes = new JsonObject();
            object.addProperty("query", query());
            object.addProperty("selectedName", selectedName());
            object.addProperty("sourceKey", sourceKey());
            object.addProperty("updatedAt", updatedAt());
            object.addProperty("confirmed", confirmed);
            if (contextYear != null) object.addProperty("contextYear", contextYear);
            if (contextType != null) object.addProperty("contextType", contextType);
            if (episodes != null) {
                for (Map.Entry<String, CachedEpisode> entry : episodes.entrySet()) {
                    if (entry.getKey() == null || entry.getValue() == null) continue;
                    cachedEpisodes.add(entry.getKey(), entry.getValue().toJson());
                }
            }
            object.add("episodes", cachedEpisodes);
            JsonObject labels = new JsonObject(); correctedLabels.forEach(labels::addProperty); object.add("correctedLabels", labels);
            return object;
        }

        public String correctedEpisode(String raw) { return confirmed ? correctedLabels.getOrDefault(raw, "") : ""; }

        public String query() {
            return Objects.toString(query, "").trim();
        }

        boolean isVideoLink() { return com.fongmi.android.tv.api.NativeDanmaku.supports(query()); }

        public String selectedName() {
            return Objects.toString(selectedName, "").trim();
        }

        public String sourceKey() {
            return Objects.toString(sourceKey, "").trim();
        }

        public long updatedAt() {
            return updatedAt;
        }

        public boolean isUserConfirmed() { return confirmed; }

        public Danmaku episode(String episode) {
            String key = DanmakuMatch.episodeKey(episode);
            if (key.isEmpty() || episodes == null) return null;
            CachedEpisode cached = findEpisode(episode);
            if (cached == null || cached.url().isEmpty()) return null;
            // The retired provider's comment IDs cannot be reused on another deployment. Return
            // a cache miss so playback keeps the confirmed title/season identity and resolves a
            // fresh URL from the replacement endpoint.
            if (Danmaku.isRetiredSourceUrl(cached.url())
                    || !isVideoLink() && !DanmakuMatch.isEpisodeCompatible(episode, cached.name())) return null;
            Danmaku result = Danmaku.from(cached.url());
            result.setName(cached.name());
            result.setSourceKey(sourceKey());
            result.setVerifiedIdentity(cached.verifiedIdentity);
            return result;
        }

        private CachedEpisode findEpisode(String episode) {
            if (episodes == null) return null;
            CachedEpisode exact = episodes.get(DanmakuMatch.episodeKey(episode));
            if (exact != null) return exact;
            CachedEpisode match = null;
            for (CachedEpisode row : episodes.values()) {
                if (!DanmakuMatch.isEpisodeCompatible(episode, row.name())) continue;
                if (match != null && !match.url().equals(row.url())) return null;
                match = row;
            }
            return match;
        }

        public long discoveredAt(String episode) {
            CachedEpisode row = findEpisode(episode);
            return row == null ? 0 : row.discoveredAt;
        }

        public boolean canReuseEpisode(String episode) {
            long time = discoveredAt(episode), now = System.currentTimeMillis();
            return confirmed && time > 0 && now >= time && now - time < CATALOGUE_TTL_MS;
        }

        public boolean hasEpisodes() {
            return episodes != null && !episodes.isEmpty();
        }

        /** A saved platform episode can locate its catalogue; it is never the next document. */
        public Danmaku catalogueAnchor() {
            if (episodes == null) return null;
            for (CachedEpisode row : episodes.values()) {
                if (!com.fongmi.android.tv.api.BilibiliDanmaku.supports(row.url())) continue;
                Danmaku anchor = Danmaku.from(row.url()); anchor.setName(row.name()); anchor.setSourceKey(sourceKey());
                return anchor; // Deliberately unverified: resolve the current episode first.
            }
            return null;
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

        boolean removeEpisode(String episode) {
            CachedEpisode cached = findEpisode(episode);
            if (cached == null) return false;
            // A confirmed row may be indexed by both its date and the playlist's period.
            // Evict every alias of a failed document so another label cannot restore it.
            return episodes.values().removeIf(row -> row.url().equals(cached.url()));
        }
    }

    static final class CachedEpisode {
        private String name;
        private String url;
        private long discoveredAt;
        private boolean verifiedIdentity;
        private DanmakuEpisode identity;

        @SuppressWarnings("unused")
        private CachedEpisode() {
        }

        private CachedEpisode(String name, String url) {
            this.name = name;
            this.identity = DanmakuEpisode.from(name);
            this.url = url;
            this.discoveredAt = System.currentTimeMillis();
        }

        static CachedEpisode from(Danmaku item) {
            CachedEpisode row = new CachedEpisode(item == null ? "" : item.getName(),
                    item == null ? "" : item.getUrl());
            row.verifiedIdentity = item != null && item.hasVerifiedIdentity();
            return row;
        }

        static CachedEpisode fromJson(JsonObject object) {
            CachedEpisode value = new CachedEpisode(string(object, "name", "a"), string(object, "url", "b"));
            value.discoveredAt = number(object, "discoveredAt");
            value.verifiedIdentity = object.has("verifiedIdentity") && object.get("verifiedIdentity").getAsBoolean();
            return value;
        }

        JsonObject toJson() {
            JsonObject object = new JsonObject();
            object.addProperty("name", name());
            object.addProperty("url", url());
            object.addProperty("discoveredAt", discoveredAt);
            object.addProperty("verifiedIdentity", verifiedIdentity);
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
