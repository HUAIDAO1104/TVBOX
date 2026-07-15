package com.fongmi.android.tv.ui.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fongmi.android.tv.ui.search.SearchSource.Availability;

import org.junit.Test;

import java.time.Duration;
import java.util.List;

public class SourceRankerTest {

    private static final long NOW = 2_000_000_000_000L;
    private final SourceRanker ranker = new SourceRanker();

    @Test
    public void prioritizesUsableCompatibleSourceBeforeRichButUnavailableSource() {
        SearchSource usable = source("usable")
                .availability(Availability.AVAILABLE)
                .deviceCompatible(true)
                .build();
        SearchSource unavailable = source("unavailable")
                .availability(Availability.UNAVAILABLE)
                .detailsAvailable(true)
                .episodeCount(100)
                .dataCompleteness(100)
                .build();

        assertEquals(usable, ranker.recommended(List.of(unavailable, usable), NOW));
    }

    @Test
    public void scoresAllRecommendationSignalsDeterministically() {
        SearchSource preferred = source("preferred")
                .availability(Availability.AVAILABLE)
                .detailsAvailable(true)
                .episodeCount(40)
                .lastSuccessAtMillis(NOW - Duration.ofHours(2).toMillis())
                .responseTimeMillis(180)
                .recentFailureCount(0)
                .dataCompleteness(95)
                .requiresLogin(false)
                .repositoryPriority(1)
                .build();
        SearchSource weaker = source("weaker")
                .availability(Availability.AVAILABLE)
                .detailsAvailable(false)
                .episodeCount(20)
                .lastSuccessAtMillis(NOW - Duration.ofDays(60).toMillis())
                .responseTimeMillis(5_000)
                .recentFailureCount(3)
                .dataCompleteness(45)
                .requiresLogin(true)
                .repositoryPriority(20)
                .build();

        assertTrue(ranker.score(preferred, NOW) > ranker.score(weaker, NOW));
        assertEquals(List.of(preferred, weaker), ranker.rank(List.of(weaker, preferred), NOW));
    }

    @Test
    public void stableIdBreaksExactScoreTies() {
        SearchSource b = source("b").stableId("source_b").build();
        SearchSource a = source("a").stableId("source_a").build();

        assertEquals(List.of(a, b), ranker.rank(List.of(b, a), NOW));
    }

    private SearchSource.Builder source(String id) {
        return SearchSource.builder()
                .repositoryId("repo")
                .configId("config")
                .siteKey("site")
                .vodId(id)
                .title("测试影片")
                .deviceCompatible(true);
    }
}
