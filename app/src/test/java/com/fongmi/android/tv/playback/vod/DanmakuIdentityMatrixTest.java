package com.fongmi.android.tv.playback.vod;
import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;

/** Labels from different repositories must resolve the same timeline, not just an integer. */
public class DanmakuIdentityMatrixTest {
    private String row(String label) { return "测试综艺(2026)【综艺】from source - 【platform】 " + label; }
    @Test public void datesWithShortMonthsAndBracketsRetainParts() {
        for (String label : List.of("2026-8-6上", "2026年8月6日（上）", "20260806上", "8月6日上", "08-06（上）", "0806上")) {
            assertTrue(label, DanmakuMatch.isEpisodeCompatible(label, row("2026-08-06 第1期（上）")));
            assertFalse(label, DanmakuMatch.isEpisodeCompatible(label, row("2026-08-06 第1期（下）")));
            assertFalse(label, DanmakuMatch.isEpisodeCompatible(label, row("2026-08-07 第1期（上）")));
        }
        assertFalse(DanmakuMatch.isEpisodeCompatible("20250806上", row("2026-08-06 第1期上")));
        assertEquals("", DanmakuEpisode.from("[2.1 GB]09.mp4").date);
        assertEquals("", DanmakuEpisode.from("20261340").date);
    }
    @Test public void upperLowerAndSubpartsRemainDistinct() {
        for (String label : List.of("第1期（上）", "第1期上半集", "第1期 上", "01上", "第一期上篇")) {
            assertTrue(label, DanmakuMatch.isEpisodeCompatible(label, row("20260806 第1期（上）")));
            assertFalse(label, DanmakuMatch.isEpisodeCompatible(label, row("20260807 第1期（下）")));
        }
        assertEquals(Integer.valueOf(1), DanmakuEpisode.from("01上").number);
        assertFalse(DanmakuMatch.isEpisodeCompatible("第1期加更上", row("第1期加更下")));
        assertFalse(DanmakuMatch.isEpisodeCompatible("第1期上（一）", row("第1期上（二）")));
        assertEquals("", DanmakuEpisode.from("第1期：上班生活").part);
    }
    @Test public void canonicalExtrasAndNamedOffshootsCannotBecomeMainEpisodes() {
        for (String extra : List.of("会员加更", "加更版", "加更篇")) {
            assertTrue(extra, DanmakuMatch.isEpisodeCompatible("第1期" + extra, row("加更版第1期")));
            assertFalse(extra, DanmakuMatch.isEpisodeCompatible("第1期" + extra, row("第1期")));
        }
        for (String extra : List.of("还有加更", "纯享版", "先导片", "预热直播", "超前企划", "花絮", "陪看", "Plus版", "番外", "团建日记")) {
            assertFalse(extra, DanmakuMatch.isEpisodeCompatible("第1期", row(extra + "第1期")));
        }
        assertFalse(DanmakuMatch.isEpisodeCompatible("第1期加更", row("还有加更第1期")));
        assertTrue(DanmakuMatch.isEpisodeCompatible("第1期", row("第1期：嘉宾陪看自己的表演")));
    }
    @Test public void duplicateDateCannotHideConflictingPeriodNumbers() {
        List<String> rows = List.of(row("20260806超前企划第1期"), row("20260806超前企划第2期"));
        assertNull(DanmakuMatch.best("测试综艺", "2026", "综艺", "20260806超前企划", rows, x -> x));
        assertEquals(rows.get(1), DanmakuMatch.best("测试综艺", "2026", "综艺", "20260806超前企划第2期", rows, x -> x));
    }
    @Test public void moviesAcceptReleaseLabelsWithoutPromotingTrailers() {
        for (String label : List.of("正片（国语）", "HD国语中字", "1080P蓝光", "4K国语中字", "完整版")) {
            assertTrue(label, DanmakuEpisode.isMovieLabel(label));
            assertEquals("movie:main", DanmakuMatch.episodeKey(label));
            assertFalse(DanmakuMatch.isEpisodeCompatible(label, "预告片"));
        }
        for (String label : List.of("正片预告", "第2部", "1080P花絮")) assertFalse(DanmakuEpisode.isMovieLabel(label));
    }
}
