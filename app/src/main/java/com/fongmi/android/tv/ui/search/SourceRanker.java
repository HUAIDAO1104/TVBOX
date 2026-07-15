package com.fongmi.android.tv.ui.search;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Deterministic recommendation order for sources belonging to the same work. */
public final class SourceRanker {

    private static final long DAY = Duration.ofDays(1).toMillis();
    private static final long WEEK = Duration.ofDays(7).toMillis();
    private static final long MONTH = Duration.ofDays(30).toMillis();

    public long score(SearchSource source, long nowMillis) {
        if (source == null) return Long.MIN_VALUE;
        long score = switch (source.availability()) {
            case AVAILABLE -> 4_000_000L;
            case UNKNOWN -> 1_500_000L;
            case UNAVAILABLE -> -4_000_000L;
        };
        score += source.deviceCompatible() ? 3_000_000L : -6_000_000L;
        if (source.detailsAvailable()) score += 800_000L;
        score += recencyScore(source.lastSuccessAtMillis(), nowMillis);
        score += Math.min(source.episodeCount(), 2_000) * 100L;
        score += responseScore(source.responseTimeMillis());
        score -= Math.min(source.recentFailureCount(), 10) * 250_000L;
        score += source.dataCompleteness() * 5_000L;
        if (source.requiresLogin()) score -= 350_000L;
        score += Math.max(0, 200 - Math.min(source.repositoryPriority(), 200)) * 1_000L;
        return score;
    }

    public List<SearchSource> rank(List<SearchSource> sources, long nowMillis) {
        if (sources == null || sources.isEmpty()) return Collections.emptyList();
        List<SearchSource> result = new ArrayList<>(sources);
        result.sort(Comparator
                .comparingLong((SearchSource source) -> score(source, nowMillis)).reversed()
                .thenComparing(SearchSource::stableId));
        return Collections.unmodifiableList(result);
    }

    public SearchSource recommended(List<SearchSource> sources, long nowMillis) {
        List<SearchSource> ranked = rank(sources, nowMillis);
        return ranked.isEmpty() ? null : ranked.get(0);
    }

    private long recencyScore(long lastSuccessAtMillis, long nowMillis) {
        if (lastSuccessAtMillis <= 0) return 0;
        long age = Math.max(0, nowMillis - lastSuccessAtMillis);
        if (age <= DAY) return 600_000L;
        if (age <= WEEK) return 450_000L;
        if (age <= MONTH) return 250_000L;
        return 100_000L;
    }

    private long responseScore(long responseTimeMillis) {
        if (responseTimeMillis <= 0) return 0;
        if (responseTimeMillis >= 5_500L) return 0;
        return Math.max(0, 220_000L - Math.min(responseTimeMillis * 40L, 220_000L));
    }
}
