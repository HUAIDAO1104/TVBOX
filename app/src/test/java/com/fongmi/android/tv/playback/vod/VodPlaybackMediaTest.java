package com.fongmi.android.tv.playback.vod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.fongmi.android.tv.bean.Danmaku;

import org.junit.Test;

import java.util.List;

public class VodPlaybackMediaTest {

    @Test
    public void staleDanmakuResponseCannotCrossIntoAnotherTitleOrEpisode() {
        assertTrue(VodPlaybackMedia.matchesMetadata("庆余年", "第2集", "庆余年", "第2集"));
        assertFalse(VodPlaybackMedia.matchesMetadata("百花杀", "第1集", "庆余年", "第1集"));
        assertFalse(VodPlaybackMedia.matchesMetadata("庆余年", "第1集", "庆余年", "第2集"));
    }

    @Test
    public void automaticDanmakuUsesTheSelectedEpisodeInsteadOfProviderOrdering() {
        assertEquals("9", VodPlaybackMedia.resolveEpisodeQuery("[2.1 GB]9.mp4【百花杀】", 16));
        assertEquals("12", VodPlaybackMedia.resolveEpisodeQuery("预告片", 12));
        assertEquals("3", VodPlaybackMedia.resolveEpisodeQuery("20260720特辑", 3));
        assertEquals("4", VodPlaybackMedia.resolveEpisodeQuery("360P修复版", 4));
    }

    @Test
    public void aggregateEpisodeCountNeverBecomesTheEpisodeQuery() {
        // Labels carrying the season total used to resolve every episode to the finale.
        assertEquals("5", VodPlaybackMedia.resolveEpisodeQuery("全38集第5集", 5));
        assertEquals("5", VodPlaybackMedia.resolveEpisodeQuery("05(全38集)", 5));
        assertEquals("38", VodPlaybackMedia.resolveEpisodeQuery("第38集", 38));
        assertEquals("7", VodPlaybackMedia.resolveEpisodeQuery("全38集", 7));
    }

    @Test
    public void playbackIdentityIncludesSourceAndEpisodeUrlNotOnlyVisibleLabels() {
        com.fongmi.android.tv.bean.History first = new com.fongmi.android.tv.bean.History();
        first.setKey("repoA|site|vodA");
        first.setVodFlag("线路A");
        com.fongmi.android.tv.bean.History second = new com.fongmi.android.tv.bean.History();
        second.setKey("repoB|site|vodB");
        second.setVodFlag("线路A");
        com.fongmi.android.tv.bean.Episode episodeA = com.fongmi.android.tv.bean.Episode.create("第1集", "url-a");
        com.fongmi.android.tv.bean.Episode episodeB = com.fongmi.android.tv.bean.Episode.create("第1集", "url-b");

        assertFalse(VodPlaybackMedia.identityOf(first, episodeA, 1)
                .equals(VodPlaybackMedia.identityOf(second, episodeA, 1)));
        assertFalse(VodPlaybackMedia.identityOf(first, episodeA, 1)
                .equals(VodPlaybackMedia.identityOf(first, episodeB, 1)));
    }

    @Test
    public void manualDanmakuPreferenceIsScopedToWorkSeasonNotEpisode() {
        DanmakuMatchContext episodeOne = new DanmakuMatchContext(
                "一起同过窗 第二季", "2017", "国产剧", "1");
        DanmakuMatchContext episodeTwelve = new DanmakuMatchContext(
                "一起同过窗第2季", "2017", "电视剧", "12");
        DanmakuMatchContext firstSeason = new DanmakuMatchContext(
                "一起同过窗 第一季", "2016", "国产剧", "12");

        assertEquals(DanmakuManualMatchStore.preferenceKey(episodeOne),
                DanmakuManualMatchStore.preferenceKey(episodeTwelve));
        assertFalse(DanmakuManualMatchStore.preferenceKey(episodeOne)
                .equals(DanmakuManualMatchStore.preferenceKey(firstSeason)));
    }

    @Test
    public void manualEndpointIdentityNeverPersistsRawCredentialUrl() {
        String url = "https://example.test/danmaku?token=very-secret";
        String first = DanmakuManualMatchStore.sourceKey(url);

        assertEquals(first, DanmakuManualMatchStore.sourceKey(url));
        assertFalse(first.isEmpty());
        assertFalse(first.contains("very-secret"));
        assertFalse(first.equals(DanmakuManualMatchStore.sourceKey(url + "-other")));
    }

    @Test
    public void manualSelectionCachesEveryEpisodeFromTheExactSeasonAndPlatform() {
        Danmaku selected = danmaku(
                "一起同过窗Ⅱ(2017)【电视剧】from 360 - 【youku】 第2集", "episode-2");
        List<Danmaku> catalogue = List.of(
                selected,
                danmaku("一起同过窗Ⅱ(2017)【电视剧】from 360 - 【qq】 第3集", "wrong-platform"),
                danmaku("一起同过窗(2016)【电视剧】from 360 - 【youku】 第3集", "wrong-season"),
                danmaku("一起同过窗Ⅱ(2017)【电视剧】from 360 - 【youku】 第3集", "episode-3"));
        DanmakuMatchContext context = new DanmakuMatchContext(
                "一起同过窗 第二季", "2017", "国产剧", "2");

        var cached = DanmakuManualMatchStore.buildEpisodeCache(context, selected, catalogue);

        assertEquals(2, cached.size());
        assertEquals("episode-2", cached.get("2").url());
        assertEquals("episode-3", cached.get("3").url());
    }

    @Test
    public void releaseObfuscatedEpisodeCacheMigratesWithoutLinkedTreeMapCast() {
        String raw = "{\"work\":{\"a\":\"一起同过窗\","
                + "\"b\":\"一起同过窗Ⅱ(2017)【电视剧】from 360 - 【youku】 第2集\","
                + "\"c\":\"source\",\"d\":{"
                + "\"2\":{\"a\":\"第二集\",\"b\":\"episode-2\"},"
                + "\"3\":{\"a\":\"第三集\",\"b\":\"episode-3\"}}}}";

        DanmakuManualMatchStore.Selection selection =
                DanmakuManualMatchStore.decode(raw).get("work");

        assertNotNull(selection);
        assertEquals("episode-2", selection.episode("第2集").getUrl());
        assertEquals("episode-3", selection.episode("第3集").getUrl());
        assertTrue(selection.hasEpisodes());
    }

    @Test
    public void stableManualCacheRoundTripSurvivesFutureMinificationChanges() {
        String legacy = "{\"work\":{\"a\":\"一起同过窗\","
                + "\"b\":\"一起同过窗Ⅱ 第2集\",\"c\":\"source\","
                + "\"d\":{\"2\":{\"a\":\"第二集\",\"b\":\"episode-2\"}}}}";
        var decoded = DanmakuManualMatchStore.decode(legacy);
        String stable = DanmakuManualMatchStore.encode(decoded);
        DanmakuManualMatchStore.Selection restored =
                DanmakuManualMatchStore.decode(stable).get("work");

        assertTrue(stable.contains("\"schema\":2"));
        assertTrue(stable.contains("\"episodes\""));
        assertNotNull(restored);
        assertEquals("episode-2", restored.episode("2").getUrl());
    }

    @Test
    public void legacyKeywordOnlyMappingRemainsUsableAfterMigration() {
        String raw = "{\"work\":{\"a\":\"一起同过窗\","
                + "\"b\":\"一起同过窗Ⅱ 第2集\",\"c\":\"source\","
                + "\"d\":1720000000000}}";

        DanmakuManualMatchStore.Selection selection =
                DanmakuManualMatchStore.decode(raw).get("work");

        assertNotNull(selection);
        assertEquals("一起同过窗", selection.query());
        assertFalse(selection.hasEpisodes());
    }

    @Test
    public void retiredProviderEpisodeCacheFallsBackToFreshResolution() {
        String raw = "{\"work\":{\"query\":\"一起同过窗 第二季\","
                + "\"selectedName\":\"一起同过窗Ⅱ(2017)【电视剧】from 360 - 【youku】 第2集\","
                + "\"sourceKey\":\"retired\",\"episodes\":{"
                + "\"2\":{\"name\":\"第二集\",\"url\":"
                + "\"https://danmu.xyy.red/api/v2/comment/274234?format=xml\"}}}}";

        DanmakuManualMatchStore.Selection selection =
                DanmakuManualMatchStore.decode(raw).get("work");

        assertNotNull(selection);
        assertTrue(selection.hasEpisodes());
        assertEquals("一起同过窗 第二季", selection.query());
        assertEquals(null, selection.episode("第2集"));
    }

    private static Danmaku danmaku(String name, String url) {
        Danmaku item = Danmaku.from(url);
        item.setName(name);
        item.setSourceKey("source");
        return item;
    }
}
