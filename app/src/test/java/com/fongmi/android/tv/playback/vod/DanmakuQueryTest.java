package com.fongmi.android.tv.playback.vod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DanmakuQueryTest {

    @Test
    public void cleansCatalogYearAndReleaseMetadata() {
        DanmakuQuery query = DanmakuQuery.from("【国产】2005.欢天喜地七仙女.高清修复");

        assertEquals("欢天喜地七仙女", query.searchTitle());
        assertEquals("2005", query.year());
        assertEquals("欢天喜地七仙女", query.candidates().get(0));
        assertEquals("欢天喜地七仙女 2005", query.candidates().get(1));
        assertEquals("【国产】2005.欢天喜地七仙女.高清修复", query.candidates().get(2));
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
}
