package com.fongmi.android.tv.playback.vod;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class DanmakuMatchTest {

    @Test
    public void exactTitleAndEpisodeOutrankPreviewAndWrongEpisode() {
        int exact = DanmakuMatch.score("庆余年 第二季", "第12集", "庆余年第二季 第12集 4K");
        int wrong = DanmakuMatch.score("庆余年 第二季", "第12集", "庆余年第二季 第11集");
        int preview = DanmakuMatch.score("庆余年 第二季", "第12集", "庆余年第二季 第12集预告");
        assertTrue(exact > wrong);
        assertTrue(exact > preview);
    }

    @Test
    public void supportsSeasonEpisodeNotation() {
        int exact = DanmakuMatch.score("示例剧", "S02E08", "示例剧.S02E08.1080P");
        int wrong = DanmakuMatch.score("示例剧", "S02E08", "示例剧.S02E09.1080P");
        int wrongSeason = DanmakuMatch.score("示例剧", "S02E08", "示例剧.S01E08.1080P");
        assertTrue(exact > wrong);
        assertTrue(exact > wrongSeason);
        assertTrue(DanmakuMatch.isReliable("示例剧", "S02E08", "示例剧.S02E08.1080P"));
        assertFalse(DanmakuMatch.isReliable("示例剧", "S02E08", "示例剧.S01E08.1080P"));
    }

    @Test
    public void understandsRawNetworkDiskEpisodeFileNames() {
        int exact = DanmakuMatch.score("百花杀", "[2.1 GB]9.mp4【百花杀】", "百花杀 第9集");
        int wrong = DanmakuMatch.score("百花杀", "[2.1 GB]9.mp4【百花杀】", "百花杀 第14集");
        assertTrue(exact > wrong);
        assertTrue(DanmakuMatch.isReliable("百花杀", "[2.1 GB]9.mp4【百花杀】", "百花杀 第9集"));
        assertFalse(DanmakuMatch.isReliable("百花杀", "[2.1 GB]9.mp4【百花杀】", "百花杀 第14集"));
    }

    @Test
    public void acceptsTitleVariantsButRejectsUnrelatedExactEpisode() {
        assertTrue(DanmakuMatch.isReliable("庆余年 第二季", "第12集", "庆余年2 第12集"));
        assertTrue(DanmakuMatch.isReliable("【4K HDR】庆余年 第二季 国语", "第12集", "庆余年第二季 第12集"));
        assertFalse(DanmakuMatch.isReliable("庆余年 第二季", "第12集", "莲花楼 第12集"));
        assertFalse(DanmakuMatch.isReliable("庆余年 第二季", "第12集", "庆余年第二季 第12集预告"));
    }

    @Test
    public void refusesAutomaticGuessWhenPlaybackTitleIsMissing() {
        assertFalse(DanmakuMatch.isReliable("", "第1集", "百花杀 第1集"));
        assertFalse(DanmakuMatch.isReliable("影", "第1集", "百花杀 第1集"));
    }

    @Test
    public void prefers360OnlyAfterEpisodeMatches() {
        int preferred = DanmakuMatch.score("庆余年第二季", "2", "庆余年第二季 from 360 第2集");
        int sameEpisode = DanmakuMatch.score("庆余年第二季", "2", "庆余年第二季 from bilibili 第2集");
        assertTrue(preferred > sameEpisode);
        assertFalse(DanmakuMatch.isReliable("庆余年第二季", "2", "庆余年第二季 from 360 第12集"));
    }

    @Test
    public void selectsExact360EpisodeFromWholeSeasonResponse() {
        List<String> response = List.of(
                "欢天喜地七仙女(2005)【电视剧】from 360 - 【youku】 第1集",
                "欢天喜地七仙女(2005)【电视剧】from 360 - 【youku】 第10集",
                "欢天喜地七仙女(2005)【电视剧】from bilibili - 第2集",
                "欢天喜地七仙女(2005)【电视剧】from 360 - 【youku】 第2集");
        assertEquals(response.get(3), DanmakuMatch.best("欢天喜地七仙女", "2", response, item -> item));
        assertFalse(DanmakuMatch.isReliable("欢天喜地七仙女", "2", "欢天喜地七仙女 正片"));
    }
}
