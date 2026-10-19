package com.fongmi.android.tv.playback.vod;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.List;

public class DanmakuAiProtocolTest {
    private final DanmakuMatchContext drama = new DanmakuMatchContext("【剧版】喜剧之王（2026）更新至14集4K", "2026", "电视剧", "第14集");

    @Test public void acceptsCleanTitleWithoutAppendingEpisodeOrYear() {
        assertEquals("喜剧之王", DanmakuAiProtocol.query("{\"query\":\"喜剧之王\",\"confidence\":0.98}", drama));
        assertEquals("喜剧之王", DanmakuAiProtocol.query("```json\n{\"query\":\"喜剧之王\",\"confidence\":1}\n```", drama));
        assertEquals("", DanmakuAiProtocol.query("{\"query\":\"喜剧之王 第14集\",\"confidence\":1}", drama));
        assertEquals("喜剧之王", DanmakuAiProtocol.query("{\"query\":\"喜劇之王\",\"confidence\":1}",
                new DanmakuMatchContext("喜劇之王", "2026", "電視劇", "第14集")));
    }

    @Test public void broadSearchKeepsOriginalSeasonAsSelectionConstraint() {
        assertEquals("", DanmakuAiProtocol.query("{\"query\":\"无关电视剧\",\"confidence\":1}", drama));
        assertEquals("", DanmakuAiProtocol.query("{\"query\":\"喜剧之王\",\"confidence\":0.6}", drama));
        var season = new DanmakuMatchContext("现在就出发第四季", "2026", "综艺", "第1期");
        assertEquals("现在就出发", DanmakuAiProtocol.query("{\"query\":\"现在就出发\",\"confidence\":1}", season));
        assertEquals("现在就出发 第四季", DanmakuAiProtocol.query("{\"query\":\"现在就出发 第四季\",\"confidence\":1}", season));
        var matching = DanmakuAiProtocol.matchingContext(season, "现在就出发", "{}");
        assertEquals(Integer.valueOf(4), DanmakuMatch.resultSeason(matching.getTitle()));
        assertFalse(DanmakuAiProtocol.sameWork(matching, "现在就出发 第三季(2025)【综艺】第1期上"));
        assertTrue(DanmakuAiProtocol.sameWork(matching, "现在就出发 第4季(2026)【综艺】第1期上"));
        String live = "现在就出发 第4季(2026)【综艺】from tencent - 【qq】2026-10-10 第1期上：出发团变落汤鸡，沈腾被迫洗头";
        assertTrue("Live platform work identity", DanmakuAiProtocol.sameWork(matching, live));
        assertFalse(DanmakuMatch.ranked(matching.getTitle(), matching.getYear(), matching.getType(), "第1期上", List.of(live), x -> x).isEmpty());
    }

    @Test public void modelCanNormalizeLabelsButCannotInventSchedulesOrSwitchHalves() {
        assertEquals("第10期上", DanmakuAiProtocol.normalizedEpisode("{\"episode\":\"第10期上\",\"confidence\":1}", "EP10 Part A"));
        for (String label : List.of("第9期上", "第10期下", "20261010第10期上", "第10期上加更"))
            assertEquals("第10期上", DanmakuAiProtocol.normalizedEpisode("{\"episode\":\"" + label + "\",\"confidence\":1}", "第10期上"));
        assertEquals("第10期", DanmakuAiProtocol.normalizedEpisode("{\"episode\":\"第10期上\",\"confidence\":1}", "第10期"));
        assertEquals("第10期上：加更笑料", DanmakuAiProtocol.normalizedEpisode("{\"episode\":\"第10期上加更\",\"confidence\":1}", "第10期上：加更笑料"));
        assertEquals("", DanmakuAiProtocol.query("{\"query\":\"哪吒\",\"confidence\":1}", new DanmakuMatchContext("哪吒2", "2025", "电影", "正片")));
    }

    @Test public void mixedProviderLabelsDoNotMakeOneEpisodeAmbiguous() {
        String dated = "现在就出发第四季(2026)【综艺】from tencent - 【qq】2026-08-16 第1期上";
        String numbered = "现在就出发第四季(2026)【综艺】from mirror - 【qq】第1期上";
        assertEquals(2, DanmakuMatch.ranked("现在就出发第四季", "2026", "综艺", "第1期上", List.of(dated, numbered), x -> x).size());
        assertTrue(DanmakuMatch.ranked("现在就出发第四季", "2026", "综艺", "第1期上", List.of(dated, numbered, dated.replace("08-16", "08-17")), x -> x).isEmpty());
        assertTrue(DanmakuMatch.ranked("现在就出发第四季", "2026", "综艺", "第1期", List.of(dated, dated.replace("上", "下")), x -> x).isEmpty());
        assertFalse(DanmakuMatch.canUsePartialCatalogue("综艺", "第1期", List.of(dated), x -> x));
        assertTrue(DanmakuMatch.canUsePartialCatalogue("综艺", "第1期上", List.of(dated), x -> x));
    }

    @Test public void onlyAllowsExistingIntegerCandidateIds() {
        assertEquals(1, DanmakuAiProtocol.selectedIndex("{\"id\":1,\"confidence\":1}", 2));
        for (String value : List.of("{\"id\":2,\"confidence\":1}", "{\"id\":-1,\"confidence\":1}",
                "{\"id\":0.5,\"confidence\":1}", "{\"id\":\"0\",\"confidence\":1}",
                "{\"id\":0,\"confidence\":0.8}", "{\"url\":\"https://invented.invalid/a.xml\"}",
                "{\"id\":0,\"confidence\":1.1}", "null", "[]", "not JSON"))
            assertEquals(value, -1, DanmakuAiProtocol.selectedIndex(value, 2));
    }

    @Test public void preservesEditionAndEpisodeGatesBeforeSelection() {
        List<String> candidates = List.of("喜剧之王 (1999)【电影】第1集", "喜剧之王 (2026)【电视剧】第13集",
                "喜剧之王 (2026)【电视剧】第14集", "喜剧之王 (2026)【动漫】第14集");
        assertEquals(List.of(candidates.get(2)), DanmakuMatch.ranked("喜剧之王", "2026", "电视剧", "第14集", candidates, x -> x));
        assertTrue(DanmakuMatch.ranked("现在就出发第四季", "2026", "综艺", "20261016上",
                List.of("现在就出发第四季 (2026)【综艺】20261016下"), x -> x).isEmpty());
    }

    @Test public void promptContainsNamesAndIdsOnly() {
        String json = DanmakuAiProtocol.selection(drama, "喜剧之王", List.of("喜剧之王 第14集")).toString();
        assertTrue(json.contains("\"id\":0"));
        assertFalse(json.contains("url"));
        assertFalse(json.contains("key"));
    }
}
