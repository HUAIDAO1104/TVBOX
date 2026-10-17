package com.fongmi.android.tv.playback.vod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DanmakuQueryTest {

    @Test public void preservesNezhaSpellingThroughSimplifiedConversion() {
        assertEquals("哪吒2", DanmakuQuery.from("哪吒2").searchTitle());
        assertEquals("哪吒之魔童闹海", DanmakuQuery.from("哪吒之魔童鬧海【HD】").searchTitle());
        assertEquals("叱吒风云", com.github.catvod.utils.Trans.t2s(false, "叱吒風雲"));
    }

    @Test
    public void cleansCatalogYearAndReleaseMetadata() {
        DanmakuQuery query = DanmakuQuery.from("【国产】2005.欢天喜地七仙女.高清修复");

        assertEquals("欢天喜地七仙女", query.searchTitle());
        assertEquals("2005", query.year());
        assertEquals("欢天喜地七仙女", query.candidates().get(0));
        assertEquals(java.util.List.of("欢天喜地七仙女"), query.candidates());
    }

    @Test
    public void preservesSeasonAndMeaningfulTitleNumbers() {
        assertEquals("庆余年 第二季", DanmakuQuery.from("【4K HDR】庆余年 第二季.2160P.国语").searchTitle());
        assertEquals("2046", DanmakuQuery.from("2046").searchTitle());
        assertEquals("请回答1988", DanmakuQuery.from("请回答1988").searchTitle());
        assertEquals("国产凌凌漆", DanmakuQuery.from("国产凌凌漆").searchTitle());
    }

    @Test
    public void unwrapsARealTitleWithoutTreatingItAsMetadata() {
        DanmakuQuery query = DanmakuQuery.from("【推理笔记】");

        assertEquals("推理笔记", query.searchTitle());
        assertTrue(query.year().isEmpty());
    }

    @Test
    public void stripsAggregateEpisodeCountFromProviderTitles() {
        DanmakuQuery query = DanmakuQuery.from("欢天喜地七仙女2005全38集");

        assertEquals("欢天喜地七仙女2005", query.searchTitle());
        assertTrue(query.candidates().contains("欢天喜地七仙女"));
        assertEquals("2005", query.year());
        assertEquals("某剧", DanmakuQuery.from("某剧共40集").searchTitle());
        assertEquals("某剧", DanmakuQuery.from("某剧38集全").searchTitle());
        assertEquals("某剧", DanmakuQuery.from("某剧共 40 集").searchTitle());
    }

    @Test
    public void keepsNumericTitleExactAndAllowsMetadataCheckedFallback() {
        DanmakuQuery query = DanmakuQuery.from("欢天喜地七仙女2005");

        assertEquals("欢天喜地七仙女2005", query.searchTitle());
        assertTrue(query.year().isEmpty());
        assertTrue(query.candidates().contains("欢天喜地七仙女"));
        // Real title numbers must keep their identity as the primary term.
        DanmakuQuery numberedTitle = DanmakuQuery.from("请回答1988");
        assertEquals("请回答1988", numberedTitle.searchTitle());
        assertTrue(numberedTitle.candidates().contains("请回答"));
        assertEquals("2046", DanmakuQuery.from("2046").searchTitle());
    }

    @Test public void stripsInterleavedUpdateBadgesWithoutAddingNoiseBack() {
        DanmakuQuery query = DanmakuQuery.from("【剧版】喜剧之王（2026）【更新至14集】4K");
        assertEquals("喜剧之王", query.searchTitle());
        assertEquals("2026", query.year());
        assertEquals(java.util.List.of("喜剧之王"), query.candidates());
        assertEquals("喜剧之王", DanmakuQuery.from("喜剧之王 更新至14集").searchTitle());
        assertEquals("喜剧之王", DanmakuQuery.from("喜剧之王[全24集]1080P").searchTitle());
    }

    @Test public void gluedYearFallbackRequiresMatchingCatalogueMetadata() {
        assertEquals(java.util.List.of("喜剧之王2026", "喜剧之王"),
                DanmakuQuery.from("喜剧之王2026", "2026").candidates());
        assertEquals(java.util.List.of("请回答1988"), DanmakuQuery.from("请回答1988", "2015").candidates());
        assertEquals(java.util.List.of("2046"), DanmakuQuery.from("2046", "2004").candidates());
        assertEquals("", DanmakuQuery.from("测试动画[更新至2026集]").year());
        assertEquals(java.util.List.of("披荆斩棘2026", "披荆斩棘"), DanmakuQuery.from("披荆斩棘2026").candidates());
    }
}
