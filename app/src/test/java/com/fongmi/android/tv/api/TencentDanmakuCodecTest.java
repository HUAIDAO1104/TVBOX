package com.fongmi.android.tv.api;

import static org.junit.Assert.*;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.playback.vod.DanmakuMatch;
import com.fongmi.android.tv.playback.vod.DanmakuResultGrouper;
import com.google.gson.Gson;
import org.junit.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class TencentDanmakuCodecTest {
    @Test public void fullDramaCatalogueUsesThePlatformsEpisodeNumber() throws Exception {
        var work = new TencentDanmakuCodec.Work("mzc002002kqssyu", "庆余年第二季", "2024", "电视剧", "庆余年第二季", List.of());
        String json = "{\"ret\":0,\"data\":{\"module_list_datas\":[{\"module_datas\":[{\"item_data_lists\":{\"item_datas\":["
                + "{\"item_params\":{\"cid\":\"mzc002002kqssyu\",\"vid\":\"b41007m5t4f\",\"union_title\":\"庆余年第二季_14\",\"title\":\"14\"}},"
                + "{\"item_params\":{\"cid\":\"mzc002002kqssyu\",\"vid\":\"z4100poa1e3\",\"union_title\":\"庆余年第二季_13\",\"title\":\"13\"}},"
                + "{\"item_params\":{\"cid\":\"mzc002002kqssyu\",\"vid\":\"q4100dpkd26\",\"union_title\":\"预告\",\"title\":\"14\",\"is_trailer\":\"1\"}},"
                + "{\"item_params\":{\"cid\":\"mzc002004dw1xx2\",\"vid\":\"g4100eu22qm\",\"union_title\":\"其他版本_14\",\"title\":\"14\"}}"
                + "]}}]}]}}";
        var rows = TencentDanmakuCodec.episodes(json, work).rows();
        var match = DanmakuMatch.best("庆余年第二季", "2024", "电视剧", "第14集", rows, Danmaku::getMatchName);
        assertNotNull(match); assertTrue(match.getUrl().endsWith("b41007m5t4f")); assertEquals(2, rows.size());
        assertNull(DanmakuMatch.best("庆余年第一季", "2024", "电视剧", "第14集", rows, Danmaku::getMatchName));
    }
    @Test public void broadQueryAliasCannotEraseTheOfficialSeason() throws Exception {
        String json = "{\"ret\":0,\"data\":{\"normalList\":{\"itemList\":[{\"doc\":{\"id\":\"mzc002001bk7moh\"},\"videoInfo\":{\"title\":\"现在就出发 第4季\",\"year\":2026,\"typeName\":\"综艺\",\"hintWords\":\"又名：现在就出发\",\"episodeSites\":[{\"enName\":\"qq\",\"episodeInfoList\":[{\"id\":\"r4102opm7x3\",\"title\":\"2026-10-10 第1期上\"}]}]}}]}}}";
        var works = TencentDanmakuCodec.works(json, "现在就出发");
        assertEquals("现在就出发 第4季", works.get(0).matchTitle());
        assertNotNull(DanmakuMatch.best("现在就出发第四季", "2026", "综艺", "第1期上", works.get(0).rows(), Danmaku::getMatchName));
        assertNull(DanmakuMatch.best("现在就出发第三季", "2026", "综艺", "第1期上", works.get(0).rows(), Danmaku::getMatchName));
    }
    private String fixture(String name) throws IOException {
        try (var in = getClass().getResourceAsStream("/danmaku/tencent-" + name + ".json")) {
            assertNotNull(in); return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test public void currentSeasonHasDistinctUpperAndLowerRealIds() throws Exception {
        var works = TencentDanmakuCodec.works(fixture("variety"), "现在就出发第四季");
        var work = works.stream().filter(w -> w.title().contains("第4季")).findFirst().orElseThrow();
        var rows = TencentDanmakuCodec.episodes(fixture("episodes"), work).rows();
        for (String[] sample : List.of(new String[]{"第1期上", "r4102opm7x3"},
                new String[]{"第1期下", "z41023tinp7"}, new String[]{"20261010上", "r4102opm7x3"})) {
            var best = DanmakuMatch.best("现在就出发第四季", "2026", "综艺", sample[0], rows, Danmaku::getMatchName);
            assertNotNull(sample[0], best); assertTrue(best.getUrl(), best.getUrl().endsWith(sample[1]));
        }
        assertNull(DanmakuMatch.best("现在就出发第三季", "2025", "综艺", "第1期上", rows, Danmaku::getMatchName));
        assertNull(DanmakuMatch.best("现在就出发第四季", "2026", "综艺", "第1期", rows, Danmaku::getMatchName));
        assertFalse(DanmakuMatch.isEpisodeCompatible("20261010第2期上", rows.stream()
                .filter(d -> d.getUrl().endsWith("r4102opm7x3")).findFirst().orElseThrow().getName()));
    }

    @Test public void officialMovieAliasMatchesButRetainsActualDisplayTitle() throws Exception {
        var works = TencentDanmakuCodec.works(fixture("movie"), "哪吒2");
        var rows = works.stream().flatMap(w -> w.rows().stream()).toList();
        var movie = DanmakuMatch.best("哪吒2", "2025", "动画电影", "HD国语", rows, Danmaku::getMatchName);
        assertNotNull(movie); assertTrue(movie.getUrl().endsWith("y4101qnn3jo"));
        assertTrue(movie.getName().startsWith("哪吒之魔童闹海"));
        assertTrue(movie.getMatchName().startsWith("哪吒2"));
        for (String type : List.of("动画片", "动画 / 奇幻 / 电影", "电影"))
            assertNotNull(type, DanmakuMatch.best("哪吒2", "2025", type, "正片", rows, Danmaku::getMatchName));
        assertFalse(DanmakuResultGrouper.prepare("哪吒2", "2025", "动画电影", "正片", rows).isEmpty());
        assertTrue(TencentDanmakuCodec.works(fixture("movie"), "我是哪吒2").isEmpty());
        assertNull(DanmakuMatch.best("哪吒2", "2023", "电影", "正片", rows, Danmaku::getMatchName));
    }

    @Test public void aliasesCannotBeInjectedByThirdPartyJson() {
        Danmaku row = new Gson().fromJson("{\"name\":\"错误作品\",\"matchName\":\"哪吒2\"}", Danmaku.class);
        assertEquals("错误作品", row.getMatchName());
    }

    @Test public void zeroSummaryCountsDoNotDiscardRealSegments() throws Exception {
        var urls = TencentDanmakuCodec.segments("{\"count_to_display\":0,\"total\":0,\"segment_index\":{"
                + "\"30\":{\"segment_start\":30000,\"segment_name\":\"t/v1/30000/60000\"},"
                + "\"0\":{\"segment_start\":0,\"segment_name\":\"t/v1/0/30000\"}}}", "r4102opm7x3");
        assertEquals(2, urls.size()); assertTrue(urls.get(0).endsWith("/0/30000"));
        assertThrows(IOException.class, () -> TencentDanmakuCodec.segments("{\"segment_index\":{}}", "r4102opm7x3"));
        assertThrows(IOException.class, () -> TencentDanmakuCodec.segments("{\"segment_index\":{\"0\":{\"segment_start\":0,\"segment_name\":\"https://evil.test\"}}}", "r4102opm7x3"));
        assertFalse(TencentDanmakuCodec.supports("tencent-danmaku://video/r4102opm7x3/../bad"));
    }

    @Test public void millisecondsColorsAndXmlEscapingSurviveConversion() throws Exception {
        var comments = TencentDanmakuCodec.comments("{\"barrage_list\":[{\"id\":\"1\",\"time_offset\":12500,\"content\":\"上 & <下>\",\"content_style\":\"{\\\"color\\\":\\\"#12ab34\\\"}\"},"
                + "{\"id\":\"1\",\"time_offset\":12500,\"content\":\"重复\"}]}", 600);
        assertEquals(1, comments.size()); assertEquals(12.5, comments.get(0).seconds(), .001);
        assertEquals(0x12ab34, comments.get(0).color());
        assertTrue(new String(IqiyiDanmakuCodec.document(comments), StandardCharsets.UTF_8).contains("上 &amp; &lt;下&gt;"));
        assertThrows(IOException.class, () -> TencentDanmakuCodec.comments("{}", 600));
    }

    @Test public void denseSegmentsAreSampledAcrossTheirTimeline() throws Exception {
        var rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 1000; i++) rows.add(Map.of("id", i, "time_offset", 1000 * i, "content", "弹幕" + i));
        var comments = TencentDanmakuCodec.comments(new Gson().toJson(Map.of("barrage_list", rows)), 100);
        assertEquals(100, comments.size()); assertEquals(990, comments.get(99).seconds(), .01);
    }
}
