package com.fongmi.android.tv.ui.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.fongmi.android.tv.ui.search.SearchAggregator.UpdateType;
import com.fongmi.android.tv.ui.search.SearchSource.Availability;

import org.junit.Test;

import java.util.List;

public class SearchAggregatorTest {

    private static final long NOW = 2_000_000_000_000L;

    @Test
    public void incrementallyMergesEquivalentTitlesAndKeepsWorkPositionAndIdStable() {
        SearchAggregator aggregator = aggregator("庆余年");
        var first = aggregator.add(source("repo-a", "site", "a", "庆余年第二部", "2024", "电视剧", "张若昀 李沁"));
        String stableId = first.work().stableId();

        var second = aggregator.add(source("repo-b", "site", "b", "慶餘年 第2部【4K】", "2024", "国产剧", "张若昀、李沁"));
        var third = aggregator.add(source("repo-c", "other", "c", "庆余年Ⅱ", "2024", "剧集", "张若昀"));

        assertEquals(UpdateType.ADDED_WORK, first.type());
        assertTrue(first.inserted());
        assertEquals(UpdateType.UPDATED_WORK, second.type());
        assertFalse(second.inserted());
        assertEquals(0, second.position());
        assertEquals(0, third.position());
        assertEquals(stableId, third.work().stableId());
        assertEquals(1, aggregator.workCount());
        assertEquals(3, aggregator.sourceCount());
        assertEquals(3, aggregator.snapshot().get(0).sourceCount());
    }

    @Test
    public void neverMergesDifferentSeasonsYearsMediaKindsOrActorsAndFiltersExtras() {
        SearchAggregator aggregator = aggregator("三体");
        aggregator.add(source("repo", "s1", "1", "三体第一季", "2023", "电视剧", "张鲁一 于和伟"));
        aggregator.add(source("repo", "s2", "2", "三体第二季", "2024", "电视剧", "张鲁一 于和伟"));
        aggregator.add(source("repo", "movie", "3", "三体第一季", "2023", "电影", "冯绍峰 张静初"));
        aggregator.add(source("repo", "year", "4", "三体第一季", "2015", "电视剧", "张鲁一 于和伟"));
        aggregator.add(source("repo", "actors", "5", "三体第一季", "2023", "电视剧", "演员甲 演员乙"));
        var extra = aggregator.add(source("repo", "extra", "6", "三体第一季花絮", "2023", "花絮", "张鲁一 于和伟"));

        assertEquals(UpdateType.FILTERED, extra.type());
        assertEquals(5, aggregator.workCount());
    }

    @Test
    public void sameSiteAndVodIdsInDifferentRepositoriesRemainDistinctSources() {
        SearchSource first = source("repo-a", "same-site", "42", "繁花", "2023", "电视剧", "胡歌");
        SearchSource repeated = source("repo-a", "same-site", "42", "繁花", "2023", "电视剧", "胡歌");
        SearchSource second = source("repo-b", "same-site", "42", "繁花", "2023", "电视剧", "胡歌");

        assertEquals(first.stableId(), repeated.stableId());
        assertNotEquals(first.stableId(), second.stableId());
        SearchAggregator aggregator = aggregator("繁花");
        aggregator.add(first);
        aggregator.add(second);
        assertEquals(1, aggregator.workCount());
        assertEquals(2, aggregator.snapshot().get(0).sourceCount());
    }

    @Test
    public void filtersIrrelevantResultsBeforeTheyReachTheSnapshot() {
        SearchAggregator aggregator = aggregator("庆余年");

        var update = aggregator.add(source("repo", "site", "1", "长相思", "2024", "电视剧", ""));

        assertEquals(UpdateType.FILTERED, update.type());
        assertFalse(update.changed());
        assertEquals(-1, update.position());
        assertEquals(0, aggregator.workCount());
    }

    @Test
    public void repeatedStableSourceUpdatesOnlyItsExistingWork() {
        SearchAggregator aggregator = aggregator("繁花");
        SearchSource initial = SearchSource.builder()
                .stableId("fixed-source")
                .repositoryId("repo")
                .siteKey("site")
                .vodId("1")
                .title("繁花")
                .posterUrl("poster-a")
                .year("2023")
                .build();
        SearchSource enriched = SearchSource.builder()
                .stableId("fixed-source")
                .repositoryId("repo")
                .siteKey("site")
                .vodId("1")
                .title("繁花")
                .posterUrl("poster-b")
                .year("2023")
                .detailsAvailable(true)
                .episodeCount(30)
                .build();

        String workId = aggregator.add(initial).work().stableId();
        var update = aggregator.add(enriched);

        assertEquals(UpdateType.UPDATED_WORK, update.type());
        assertEquals(0, update.position());
        assertEquals(workId, update.work().stableId());
        assertEquals(1, update.work().sourceCount());
        assertEquals("poster-b", update.work().posterUrl());
        assertEquals(30, update.work().episodeCount());
    }

    @Test
    public void irrelevantReuseOfStableSourceDoesNotOverwriteValidResult() {
        SearchAggregator aggregator = aggregator("繁花");
        SearchSource initial = SearchSource.builder()
                .stableId("reused-source")
                .repositoryId("repo").siteKey("site").vodId("1")
                .title("繁花").posterUrl("valid-poster").year("2023")
                .build();
        SearchSource unrelated = SearchSource.builder()
                .stableId("reused-source")
                .repositoryId("repo").siteKey("site").vodId("1")
                .title("长相思").posterUrl("wrong-poster").year("2023")
                .build();

        aggregator.add(initial);
        var update = aggregator.add(unrelated);

        assertEquals(UpdateType.FILTERED, update.type());
        assertEquals(1, aggregator.workCount());
        assertEquals(1, aggregator.sourceCount());
        assertEquals("繁花", aggregator.snapshot().get(0).displayTitle());
        assertEquals("valid-poster", aggregator.snapshot().get(0).posterUrl());
    }

    @Test
    public void recommendedSourceChangesWithoutMovingTheWork() {
        SearchAggregator aggregator = aggregator("繁花");
        SearchSource slow = SearchSource.builder()
                .repositoryId("repo-a").siteKey("slow").vodId("1").title("繁花")
                .year("2023").type("电视剧").availability(Availability.UNKNOWN)
                .posterUrl("slow-poster").build();
        SearchSource preferred = SearchSource.builder()
                .repositoryId("repo-b").siteKey("fast").vodId("2").title("繁花")
                .year("2023").type("电视剧").availability(Availability.AVAILABLE)
                .detailsAvailable(true).lastSuccessAtMillis(NOW - 1_000).responseTimeMillis(120)
                .posterUrl("preferred-poster").build();

        aggregator.add(slow);
        var update = aggregator.add(preferred);

        assertEquals(0, update.position());
        assertEquals(preferred, update.work().recommendedSource());
        assertEquals("preferred-poster", update.work().posterUrl());
        assertNotNull(update.work().rankedSources());
    }

    @Test
    public void snapshotIsImmutableAndPreservesFirstInsertionOrder() {
        SearchAggregator aggregator = aggregator("测试");
        SearchSource first = source("repo", "first", "1", "测试甲", "2023", "电视剧", "演员甲");
        SearchSource second = source("repo", "second", "2", "测试乙", "2023", "电视剧", "演员乙");
        String firstWorkId = aggregator.add(first).work().stableId();
        String secondWorkId = aggregator.add(second).work().stableId();
        List<SearchWork> beforeUpdate = aggregator.snapshot();

        SearchSource betterFirst = SearchSource.builder()
                .repositoryId("repo-b").siteKey("faster").vodId("3").title("测试甲")
                .year("2023").type("电视剧").actors("演员甲")
                .availability(Availability.AVAILABLE).detailsAvailable(true)
                .lastSuccessAtMillis(NOW - 1_000).responseTimeMillis(80)
                .build();
        aggregator.add(betterFirst);
        List<SearchWork> afterUpdate = aggregator.snapshot();

        assertEquals(List.of(firstWorkId, secondWorkId),
                afterUpdate.stream().map(SearchWork::stableId).toList());
        assertEquals(1, beforeUpdate.get(0).sourceCount());
        assertEquals(2, afterUpdate.get(0).sourceCount());
        assertThrows(UnsupportedOperationException.class, () -> afterUpdate.add(beforeUpdate.get(0)));
    }

    @Test
    public void ambiguousMetadataFreeSourceIsNotForcedIntoEitherHomonym() {
        SearchAggregator aggregator = aggregator("无名");
        aggregator.add(source("repo", "old", "1", "无名", "2021", "电影", "演员甲"));
        aggregator.add(source("repo", "new", "2", "无名", "2023", "电影", "演员乙"));
        aggregator.add(source("repo", "unknown", "3", "无名", "", "电影", ""));

        assertEquals(3, aggregator.workCount());
    }

    @Test
    public void sourceMetadataAppendedToTitleDoesNotCreateADuplicateWork() {
        SearchAggregator aggregator = aggregator("野狗骨头");
        // A metadata-decorated cloud entry may arrive before the clean title.
        aggregator.add(source("repo-d", "site-d", "4", "野狗骨头 剧情 爱情 张婧仪", "", "", ""));
        aggregator.add(source("repo-c", "site-c", "3", "【新】野狗骨头 宋威龙 / 张婧仪", "", "", "宋威龙 / 张婧仪"));
        aggregator.add(source("repo-a", "site-a", "1", "野狗骨头", "2026", "剧情 爱情", "张婧仪"));
        aggregator.add(source("repo-b", "site-b", "2", "野狗骨头 剧情 爱情 张婧仪", "2026", "剧情 爱情", "张婧仪"));

        assertEquals(1, aggregator.workCount());
        assertEquals(4, aggregator.sourceCount());
    }

    @Test
    public void releaseEpisodeYearAndCloudSuffixesRemainOneWork() {
        SearchAggregator aggregator = aggregator("野狗骨头");
        List<String> titles = List.of(
                "野狗骨头",
                "野狗骨头(臻彩)",
                "野狗骨头(2026)",
                "野狗骨头 4K高码率 [",
                "野狗骨头 1-19集 完整合集",
                "野狗骨头 第19集 超清!!",
                "【野狗骨头】第18-19集已更~~",
                "野狗骨头 +更新至19集+",
                "野狗骨头.1080p更19",
                "野狗骨头 4k 更至ep19",
                "野狗骨头 s01e01 - e19",
                "野狗骨头 2026 4K",
                "[夸克网盘]野狗骨头:",
                "野狗骨头 - 百度网盘");

        for (int index = 0; index < titles.size(); index++) {
            var update = aggregator.add(source("repo-" + index, "site-" + index,
                    String.valueOf(index), titles.get(index), "2026", "剧情 爱情", "张婧仪"));
            assertEquals(index == 0 ? UpdateType.ADDED_WORK : UpdateType.UPDATED_WORK, update.type());
        }

        assertEquals(1, aggregator.workCount());
        assertEquals(titles.size(), aggregator.sourceCount());
        assertEquals(titles.size(), aggregator.snapshot().get(0).sourceCount());
    }

    @Test
    public void runtimeCatalogBadgesQualityFragmentsAndActorListsRemainOneWork() {
        SearchAggregator aggregator = aggregator("野狗骨头");
        List<String> titles = List.of(
                "野狗骨头",
                "🗜 【国剧】野狗骨头",
                "野狗骨头 4k 首",
                "【国剧】野狗骨头 4k超",
                "野狗骨头 宋威龙 / 张婧仪",
                "🗜 野狗骨头 剧情 爱情 张婧仪",
                "野狗骨头 更4集 4k 简中");

        for (int index = 0; index < titles.size(); index++) {
            aggregator.add(source("runtime-" + index, "site-" + index,
                    String.valueOf(index), titles.get(index), "", "", ""));
        }

        assertEquals(1, aggregator.workCount());
        assertEquals(titles.size(), aggregator.sourceCount());
    }

    private SearchAggregator aggregator(String keyword) {
        return new SearchAggregator(keyword, new SearchRelevance(), new SourceRanker(), () -> NOW);
    }

    private SearchSource source(String repository, String site, String id, String title, String year, String type, String actors) {
        return SearchSource.builder()
                .repositoryId(repository)
                .repositoryName(repository)
                .configId(repository + "-config")
                .siteKey(site)
                .siteName(site)
                .vodId(id)
                .title(title)
                .posterUrl("https://example.invalid/" + id + ".jpg")
                .year(year)
                .type(type)
                .actors(actors)
                .availability(Availability.AVAILABLE)
                .build();
    }
}
