package com.fongmi.android.tv.playback.vod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

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
}
