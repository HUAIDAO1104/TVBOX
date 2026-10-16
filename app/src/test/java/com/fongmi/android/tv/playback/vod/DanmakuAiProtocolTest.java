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

    @Test public void rejectsInventedWorkSeasonLossAndLowConfidence() {
        assertEquals("", DanmakuAiProtocol.query("{\"query\":\"无关电视剧\",\"confidence\":1}", drama));
        assertEquals("", DanmakuAiProtocol.query("{\"query\":\"喜剧之王\",\"confidence\":0.6}", drama));
        var season = new DanmakuMatchContext("现在就出发第四季", "2026", "综艺", "第1期");
        assertEquals("", DanmakuAiProtocol.query("{\"query\":\"现在就出发\",\"confidence\":1}", season));
        assertEquals("现在就出发 第四季", DanmakuAiProtocol.query("{\"query\":\"现在就出发 第四季\",\"confidence\":1}", season));
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
