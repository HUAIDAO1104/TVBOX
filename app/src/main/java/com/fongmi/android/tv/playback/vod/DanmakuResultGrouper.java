package com.fongmi.android.tv.playback.vod;

import com.fongmi.android.tv.bean.Danmaku;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Prepares a compact, stable manual-search list for TV and touch navigation. */
public final class DanmakuResultGrouper {

    private DanmakuResultGrouper() {
    }

    public static List<Danmaku> prepare(String title, String year, String type, String episode,
                                        Iterable<Danmaku> source) {
        return prepare(title, year, type, episode, source, true);
    }

    public static List<Danmaku> browse(String title, String year, String type, String episode, Iterable<Danmaku> source) {
        return prepare(title, year, type, episode, source, false);
    }

    private static List<Danmaku> prepare(String title, String year, String type, String episode,
                                          Iterable<Danmaku> source, boolean filterEpisode) {
        Map<String, Danmaku> unique = new LinkedHashMap<>();
        String expected = DanmakuMatch.canonicalTitle(title);
        String base = expected.replaceFirst("#\\d+$", "");
        if (source != null) {
            for (Danmaku item : source) {
                if (item == null || item.isEmpty() || item.isBlockedSource()) continue;
                String candidate = DanmakuMatch.canonicalTitle(item.getName());
                if (!base.equals(candidate.replaceFirst("#\\d+$", ""))
                        && !(expected.length() >= 2 && candidate.contains(expected))) continue;
                // The API returns the complete season even when an episode is supplied. Keeping
                // explicit wrong episodes made a small source picker into a list of hundreds.
                if (filterEpisode && !DanmakuMatch.isManualEpisodeCompatible(episode, item.getName())) continue;
                unique.putIfAbsent(item.getUrl(), item);
            }
        }
        List<Danmaku> result = new ArrayList<>(unique.values());
        result.sort(comparator(title, year, type, episode, result));
        return result;
    }

    public record Work(String key, String name, List<Danmaku> episodes) { }

    public static List<Work> works(List<Danmaku> rows) {
        Map<String, List<Danmaku>> grouped = new LinkedHashMap<>();
        for (Danmaku item : rows) grouped.computeIfAbsent(workKey(item), key -> new ArrayList<>()).add(item);
        List<Work> works = new ArrayList<>();
        for (Map.Entry<String, List<Danmaku>> entry : grouped.entrySet()) {
            List<Danmaku> episodes = entry.getValue();
            Map<Danmaku, DanmakuEpisode> identities = new java.util.HashMap<>();
            for (Danmaku item : episodes) identities.put(item, DanmakuEpisode.from(item.getName()));
            episodes.sort(Comparator.comparing((Danmaku item) -> identities.get(item).date)
                    .thenComparingInt(item -> Objects.requireNonNullElse(identities.get(item).number, 0))
                    .thenComparing(Danmaku::getName));
            String name = episodes.get(0).getName().split("(?i)\\s*from\\s+", 2)[0].trim();
            works.add(new Work(entry.getKey(), name, List.copyOf(episodes)));
        }
        return works;
    }

    public static String workKey(Danmaku item) {
        // Title + year + media type + endpoint + platform keep remakes and same-title media apart.
        String name = item.getName().split("(?i)\\s*from\\s+", 2)[0].trim();
        return name + '|' + item.getSourceKey() + '|' + groupKey(item);
    }

    public static String groupKey(Danmaku item) {
        String name = item == null ? "" : item.getName();
        Integer season = DanmakuMatch.resultSeason(name);
        return Objects.toString(season, "0") + '|' + normalized(DanmakuMatch.resultProvider(name))
                + '|' + normalized(DanmakuMatch.resultPlatform(name));
    }

    private record Rank(int edition, int score, int season, int provider, String source, String platform) { }

    private static Comparator<Danmaku> comparator(String title, String year, String type, String episode,
                                                   List<Danmaku> items) {
        // Metadata parsing uses Unicode/episode regexes. Compute it once per row instead of
        // thousands of times inside TimSort, especially for long variety catalogues on TVs.
        Map<Danmaku, Rank> ranks = new java.util.HashMap<>();
        for (Danmaku item : items) {
            String name = item.getName();
            ranks.put(item, new Rank(editionRank(title, year, type, episode, name),
                    DanmakuMatch.displayScore(title, year, type, episode, name), seasonOrder(name),
                    providerOrder(name), normalized(DanmakuMatch.resultProvider(name)),
                    normalized(DanmakuMatch.resultPlatform(name))));
        }
        return Comparator.comparingInt((Danmaku item) -> ranks.get(item).edition())
                .thenComparing(Comparator.comparingInt((Danmaku item) -> ranks.get(item).score()).reversed())
                .thenComparingInt(item -> ranks.get(item).season())
                .thenComparingInt(item -> ranks.get(item).provider())
                .thenComparing(item -> ranks.get(item).source())
                .thenComparing(item -> ranks.get(item).platform())
                .thenComparing(Danmaku::getName, String.CASE_INSENSITIVE_ORDER);
    }

    private static int editionRank(String title, String year, String type, String episode, String candidate) {
        Integer expectedSeason = firstNonNull(DanmakuMatch.seasonNumber(episode),
                firstNonNull(DanmakuMatch.seasonNumber(title), DanmakuMatch.seasonNumber(type)));
        Integer actualSeason = DanmakuMatch.resultSeason(candidate);
        if (expectedSeason != null) return expectedSeason.equals(actualSeason) ? 0 : 20;
        String expectedYear = Objects.toString(year, "").trim();
        if (!expectedYear.isEmpty() && expectedYear.equals(DanmakuMatch.candidateYear(candidate))) return 0;
        return DanmakuMatch.isSameWork(title, candidate) ? 0 : 10;
    }

    private static int seasonOrder(String name) {
        Integer season = DanmakuMatch.resultSeason(name);
        return season == null ? 0 : Math.max(1, season);
    }

    private static int providerOrder(String name) {
        String provider = DanmakuMatch.resultProvider(name);
        return "iqiyi".equalsIgnoreCase(provider) ? 0 : "360".equalsIgnoreCase(provider) ? 1 : 2;
    }

    private static String normalized(String value) {
        return Objects.toString(value, "").trim().toLowerCase(Locale.ROOT);
    }

    private static Integer firstNonNull(Integer first, Integer second) {
        return first == null ? second : first;
    }
}
