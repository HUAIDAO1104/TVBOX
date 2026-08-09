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
        Map<String, Danmaku> unique = new LinkedHashMap<>();
        if (source != null) {
            for (Danmaku item : source) {
                if (item == null || item.isEmpty() || item.isBlockedSource()) continue;
                if (!DanmakuMatch.isSameBaseWork(title, item.getName())) continue;
                // The API returns the complete season even when an episode is supplied. Keeping
                // explicit wrong episodes made a small source picker into a list of hundreds.
                if (!DanmakuMatch.isEpisodeCompatible(episode, item.getName())) continue;
                unique.putIfAbsent(item.getUrl(), item);
            }
        }
        List<Danmaku> result = new ArrayList<>(unique.values());
        result.sort(comparator(title, year, type, episode));
        return result;
    }

    public static String groupKey(Danmaku item) {
        String name = item == null ? "" : item.getName();
        Integer season = DanmakuMatch.resultSeason(name);
        return Objects.toString(season, "0") + '|' + normalized(DanmakuMatch.resultProvider(name))
                + '|' + normalized(DanmakuMatch.resultPlatform(name));
    }

    private static Comparator<Danmaku> comparator(String title, String year, String type, String episode) {
        return Comparator
                .comparingInt((Danmaku item) -> editionRank(title, year, type, episode, item.getName()))
                .thenComparingInt(item -> seasonOrder(item.getName()))
                .thenComparingInt(item -> providerOrder(item.getName()))
                .thenComparing(item -> normalized(DanmakuMatch.resultProvider(item.getName())))
                .thenComparing(item -> normalized(DanmakuMatch.resultPlatform(item.getName())))
                .thenComparing(Comparator.comparingInt((Danmaku item) -> DanmakuMatch.displayScore(
                        title, year, type, episode, item.getName())).reversed())
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
        return "360".equalsIgnoreCase(DanmakuMatch.resultProvider(name)) ? 0 : 1;
    }

    private static String normalized(String value) {
        return Objects.toString(value, "").trim().toLowerCase(Locale.ROOT);
    }

    private static Integer firstNonNull(Integer first, Integer second) {
        return first == null ? second : first;
    }
}
