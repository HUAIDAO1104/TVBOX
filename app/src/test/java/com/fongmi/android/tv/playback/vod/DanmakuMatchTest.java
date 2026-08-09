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

    @Test
    public void neverTreatsASubstringAsTheSameWork() {
        assertFalse(DanmakuMatch.isReliable("仙女", "2", "仙女湖(2012)【电视剧】from 360 - 第2集"));
        assertFalse(DanmakuMatch.isReliable("庆余年", "1", "庆余年第二季(2024)【电视剧】from 360 - 第1集"));
        assertFalse(DanmakuMatch.isReliable("庆余年", "1", "庆余年独家专访(2020)【综艺】from 360 - 第1期"));
    }

    @Test
    public void seasonAliasesAreEquivalentButBaseTitleStaysDistinct() {
        assertEquals(DanmakuMatch.canonicalTitle("庆余年 第二季"), DanmakuMatch.canonicalTitle("庆余年2"));
        assertEquals(DanmakuMatch.canonicalTitle("庆余年 第2部"), DanmakuMatch.canonicalTitle("庆余年Ⅱ"));
        assertFalse(DanmakuMatch.canonicalTitle("庆余年").equals(DanmakuMatch.canonicalTitle("庆余年第二季")));
    }

    @Test
    public void providerMetadataDoesNotPolluteExactTitleButYearDisambiguates() {
        List<String> response = List.of(
                "同名剧(2024)【电视剧】from 360 - 第2集",
                "同名剧(2005)【电视剧】from 360 - 第2集");
        assertEquals(response.get(1), DanmakuMatch.best("同名剧", "2005", "2", response, item -> item));
        assertEquals(null, DanmakuMatch.best("同名剧", "", "2", response, item -> item));
    }

    @Test
    public void realProviderOrderingCannotSelectAnotherSeason() {
        List<String> response = List.of(
                "庆余年第二季(2024)【电视剧】from 360 - 【qq】 第1集",
                "庆余年(2019)【电视剧】from 360 - 【qq】 第1集",
                "庆余年独家专访(2020)【综艺】from 360 - 第1期");
        assertEquals(response.get(1), DanmakuMatch.best("庆余年", "2019", "1", response, item -> item));
        assertEquals(response.get(0), DanmakuMatch.best("庆余年2", "2024", "1", response, item -> item));
    }

    @Test
    public void yearAndSeasonMetadataSelectTheCorrectTogetherWindowSeason() {
        List<String> response = List.of(
                "一起同过窗第三季(2022)【电视剧】from 360 - 【qq】 第1集",
                "一起同过窗Ⅱ(2017)【电视剧】from 360 - 【qq】 第1集",
                "一起同过窗(2016)【电视剧】from 360 - 【youku】 第1集");

        assertEquals(response.get(1), DanmakuMatch.best(
                "一起同过窗", "2017", "电视剧 第二季", "1", response, item -> item));
        assertEquals(response.get(2), DanmakuMatch.best(
                "一起同过窗", "2016", "电视剧 第一季", "1", response, item -> item));
        assertEquals(Integer.valueOf(2), DanmakuMatch.resultSeason(response.get(1)));
    }

    @Test
    public void detailMediaTypeDisambiguatesSameTitleMovieAndSeries() {
        List<String> response = List.of(
                "同名作品(2019)【电影】from 360 - 第1集",
                "同名作品(2019)【电视剧】from 360 - 第1集");
        assertEquals(response.get(1), DanmakuMatch.best("同名作品", "2019", "国产剧", "1", response, item -> item));
        assertEquals(response.get(0), DanmakuMatch.best("同名作品", "2019", "电影", "1", response, item -> item));
    }

    @Test
    public void animationOutranksLiveActionRegardlessOfProviderOrder() {
        List<String> response = List.of(
                "凡人修仙传(2025)【真人版电视剧】from 360 - 第12集",
                "凡人修仙传(2020)【国产动漫】from 360 - 第12集",
                "凡人修仙传(2020)【番剧】from bilibili - 第12集");
        assertEquals(response.get(1), DanmakuMatch.best(
                "凡人修仙传", "2020", "国产动漫", "12", response, item -> item));
        assertTrue(DanmakuMatch.displayScore(
                        "凡人修仙传", "", "动画", "12", response.get(1))
                > DanmakuMatch.displayScore(
                        "凡人修仙传", "", "动画", "12", response.get(0)));
    }

    @Test
    public void unknownProviderTypeRemainsSafeFallback() {
        List<String> response = List.of("凡人修仙传 from 360 - 第3集");
        assertEquals(response.get(0), DanmakuMatch.best(
                "凡人修仙传", "", "国漫", "3", response, item -> item));
        assertEquals("anime", DanmakuMatch.normalizeMediaType("国产动画年番"));
        assertEquals("series", DanmakuMatch.normalizeMediaType("真人版"));
    }

    @Test
    public void bracketedSubtitleRemainsPartOfWorkIdentity() {
        assertFalse(DanmakuMatch.canonicalTitle("名侦探柯南【绀青之拳】")
                .equals(DanmakuMatch.canonicalTitle("名侦探柯南")));
        assertFalse(DanmakuMatch.isReliable("名侦探柯南【绀青之拳】", "1", "名侦探柯南【电视剧】from 360 - 第1集"));
        assertEquals(DanmakuMatch.canonicalTitle("庆余年【电视剧】【4K】"), DanmakuMatch.canonicalTitle("庆余年"));
    }

    @Test
    public void aggregateEpisodeCountIsNeverAnEpisodeNumber() {
        assertEquals(null, DanmakuMatch.episodeNumber("全38集"));
        assertEquals(null, DanmakuMatch.episodeNumber("共40集"));
        assertEquals(null, DanmakuMatch.episodeNumber("38集全"));
        assertEquals(null, DanmakuMatch.episodeNumber("全 38 集"));
        assertEquals(null, DanmakuMatch.episodeNumber("共 40集"));
        assertEquals(null, DanmakuMatch.episodeNumber("38集 全"));
        assertEquals(Integer.valueOf(1), DanmakuMatch.episodeNumber("全38集第1集"));
        assertEquals(Integer.valueOf(2), DanmakuMatch.episodeNumber("全 38 集 第2集"));
        assertEquals(Integer.valueOf(5), DanmakuMatch.episodeNumber("(全38集)第5集"));
        assertEquals(Integer.valueOf(38), DanmakuMatch.episodeNumber("第38集"));
    }

    @Test
    public void aggregateEntryCannotImpersonateTheFinale() {
        assertFalse(DanmakuMatch.isReliable("欢天喜地七仙女", "38",
                "欢天喜地七仙女(2005)【电视剧】from 360 - 【youku】 全38集"));
        List<String> response = List.of(
                "欢天喜地七仙女(2005)【电视剧】from 360 - 【youku】 全38集",
                "欢天喜地七仙女(2005)【电视剧】from 360 - 【youku】 第38集");
        assertEquals(response.get(1), DanmakuMatch.best("欢天喜地七仙女", "38", response, item -> item));
    }

    @Test
    public void manualChoiceRetargetsSameSeasonAndProviderForFollowingEpisode() {
        String selected = "一起同过窗 第二季(2017)【电视剧】from 360 - 第3集";
        List<String> response = List.of(
                "一起同过窗 第一季(2016)【电视剧】from 360 - 第4集",
                "一起同过窗 第二季(2017)【电视剧】from bilibili - 第4集",
                "一起同过窗 第二季(2017)【电视剧】from 360 - 第5集",
                "一起同过窗 第二季(2017)【电视剧】from 360 - 第4集");

        assertEquals(response.get(3), DanmakuMatch.bestPreferred(
                selected, "2017", "国产剧", "4", response, item -> item));
        assertEquals(null, DanmakuMatch.bestPreferred(
                selected, "2017", "国产剧", "6", response, item -> item));
    }

    @Test
    public void manualChoiceCannotCrossIntoAnotherEditionOrMedium() {
        String selected = "凡人修仙传 年番(2024)【动漫】from 360 - 第100集";
        List<String> response = List.of(
                "凡人修仙传(2020)【电视剧】from 360 - 第101集",
                "凡人修仙传 年番(2024)【动漫】from other - 第101集");

        assertEquals(null, DanmakuMatch.bestPreferred(
                selected, "2024", "国产动画年番", "101", response, item -> item));
    }
}
