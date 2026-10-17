package com.fongmi.android.tv.playback.vod;

import static org.junit.Assert.*;

import com.fongmi.android.tv.bean.Danmaku;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.junit.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Provider catalogue names captured on 2026-10-15; no expiring IDs/network in unit tests. */
public class DanmakuVarietyMovieTest {
    private List<String> fixture(String name) {
        return new Gson().fromJson(new InputStreamReader(getClass().getResourceAsStream(
                "/danmaku/" + name + ".json"), StandardCharsets.UTF_8), new TypeToken<List<String>>() {}.getType());
    }

    private List<Danmaku> items(List<String> names) {
        return java.util.stream.IntStream.range(0, names.size()).mapToObj(i -> {
            Danmaku item = Danmaku.from("https://example.test/" + i + ".xml");
            item.setName(names.get(i)); return item;
        }).toList();
    }

    @Test public void movieMainFeatureDoesNotRequireEpisodeOne() {
        String episode = VodPlaybackMedia.resolveEpisodeQuery("HD国语", 1, "科幻片");
        assertEquals("正片", episode);
        String best = DanmakuMatch.best("流浪地球2", "2023", "电影", episode, fixture("movie"), s -> s);
        assertNotNull(best); assertTrue(best.startsWith("流浪地球2(2023)"));
        assertFalse(DanmakuResultGrouper.prepare("流浪地球2", "2023", "电影", episode, items(fixture("movie"))).isEmpty());
    }

    @Test public void confirmedMovieCanRestoreWithoutSearchingItsCatalogueAgain() {
        Danmaku movie = items(List.of("哪吒之魔童闹海(2025)【电影】from tencent - 【qq】 正片")).get(0);
        var context = new DanmakuMatchContext("哪吒2", "2025", "动画电影", "HD国语");
        var cache = DanmakuManualMatchStore.buildEpisodeCache(context, movie, List.of(movie));
        assertEquals(movie.getUrl(), cache.get("movie:main").url());
        var selection = new DanmakuManualMatchStore.Selection("哪吒2", movie.getName(), "tencent-direct", cache, 0);
        assertNotNull(selection.episode("正片"));
        assertNotNull(selection.episode("HD国语"));
        assertNull(selection.episode("第1期上"));
    }

    @Test public void missingMovieTypeStillRecognizesCommonFeatureLabels() {
        for (String label : List.of("正片", "HD", "HD中字", "1080P", "蓝光", "全集")) {
            assertEquals("正片", VodPlaybackMedia.resolveEpisodeQuery(label, 1));
        }
    }

    @Test public void firstSeasonAliasAndUpperPartMatchActualVarietyCatalogue() {
        String best = DanmakuMatch.best("现在就出发 第一季", "2023", "大陆综艺", "20230813上", fixture("variety-seasons"), s -> s);
        assertNotNull(best); assertTrue(best.contains("第1期上：")); assertTrue(best.startsWith("现在就出发(2023)"));
    }

    @Test public void seasonTwoAndThreeDoNotCrossIntoFirstSeason() {
        for (int season : List.of(2, 3)) {
            String year = season == 2 ? "2024" : "2025";
            String best = DanmakuMatch.best("现在就出发 第" + season + "季", year, "综艺", "第1期上", fixture("variety-seasons"), s -> s);
            assertNotNull(best); assertTrue(best.contains("第" + season + "季")); assertTrue(best.contains("第1期上"));
        }
    }

    @Test public void absentSeasonFourDoesNotBorrowEarlierSeason() {
        assertNull(DanmakuMatch.best("现在就出发 第四季", "2026", "综艺", "第1期上", fixture("variety-seasons"), s -> s));
    }

    @Test public void varietyTypeIsNotAnOuttakeAndDatesSurvivePlaybackBoundary() {
        assertEquals("20260816", VodPlaybackMedia.resolveEpisodeQuery("20260816", 3, "综艺"));
        String best = DanmakuMatch.best("披荆斩棘2026", "2026", "综艺", "20260816", fixture("variety-dates"), s -> s);
        assertNotNull(best); assertTrue(best.startsWith("披荆斩棘2026(2026)")); assertTrue(best.contains("2026-08-16"));
    }

    @Test public void formattedDatesAndChinesePeriodNumbersAreRecognized() {
        assertEquals("date:20260816", DanmakuMatch.episodeKey("2026年8月16日"));
        assertEquals("date:20260816", DanmakuMatch.episodeKey("2026-08-16期"));
        assertEquals(Integer.valueOf(12), DanmakuMatch.episodeNumber("第十二期上"));
        assertEquals(Integer.valueOf(5), DanmakuMatch.episodeNumber("EP05"));
        assertNull(DanmakuMatch.episodeNumber("20260816期"));
        assertNull(DanmakuMatch.episodeNumber("2026-08-16期"));
    }

    @Test public void datesPartsAndExtrasCannotShareATimeline() {
        String upper = "现在就出发(2023)【综艺】from 360 - 【qq】 第1期上：节目 2023-08-13期";
        String lower = upper.replace("第1期上", "第1期下");
        String extra = upper.replace("第1期上", "第1期加更");
        assertTrue(DanmakuMatch.isEpisodeCompatible("20230813上", upper));
        assertFalse(DanmakuMatch.isEpisodeCompatible("20230813上", lower));
        assertFalse(DanmakuMatch.isEpisodeCompatible("20230813上", extra));
        assertFalse(DanmakuMatch.isEpisodeCompatible("20230814上", upper));
        assertNull(DanmakuMatch.best("现在就出发", "2023", "综艺", "第1期", List.of(upper, lower, extra), s -> s));
        assertEquals(3, DanmakuResultGrouper.prepare("现在就出发", "2023", "综艺", "第1期", items(List.of(upper, lower, extra))).size());
    }

    @Test public void subpartsAreDistinct() {
        assertFalse(DanmakuMatch.isEpisodeCompatible("第1期上(一)", "喜剧之王单口季 from 360 - 第1期上(三)"));
        assertTrue(DanmakuMatch.isEpisodeCompatible("第1期上(一)", "喜剧之王单口季 from 360 - 第1期上（一）"));
    }

    @Test public void manualPartialTitleFindsVarietyWithoutAutomaticAliasGuess() {
        assertFalse(DanmakuResultGrouper.prepare("喜剧之王", "2024", "综艺", "第1期", items(fixture("partial-title"))).isEmpty());
        assertNull(DanmakuMatch.best("喜剧之王", "2024", "综艺", "第1期上(一)", fixture("partial-title"), s -> s));
        assertNotNull(DanmakuMatch.best("喜剧之王单口季", "2024", "综艺", "第1期上(一)", fixture("partial-title"), s -> s));
    }

    @Test public void unknownYearIsAFallbackButExplicitYearConflictIsRejected() {
        assertNotNull(DanmakuMatch.best("示例电影", "2024", "电影", "正片", List.of("示例电影【电影】from 360 - 正片"), s -> s));
        assertNull(DanmakuMatch.best("示例电影", "2024", "电影", "正片", List.of("示例电影(1999)【电影】from 360 - 正片"), s -> s));
    }

    @Test public void varietyCacheRetainsDateAndPart() {
        List<Danmaku> rows = items(List.of(
                "现在就出发(2023)【综艺】from 360 - 【qq】 第1期上 2023-08-13期",
                "现在就出发(2023)【综艺】from 360 - 【qq】 第1期下 2023-08-13期",
                "现在就出发(2023)【综艺】from 360 - 【qq】 第1期加更 2023-08-13期"));
        var cache = DanmakuManualMatchStore.buildEpisodeCache(new DanmakuMatchContext("现在就出发", "2023", "综艺", "20230813上"), rows.get(0), rows);
        assertEquals(rows.get(0).getUrl(), cache.get("date:20230813|上").url());
        assertEquals(rows.get(1).getUrl(), cache.get("date:20230813|下").url());
        assertEquals(rows.get(2).getUrl(), cache.get("date:20230813|加更").url());
        assertFalse(cache.containsKey("1"));
    }
}
