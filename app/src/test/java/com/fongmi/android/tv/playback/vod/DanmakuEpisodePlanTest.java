package com.fongmi.android.tv.playback.vod;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;

public class DanmakuEpisodePlanTest {
    private DanmakuEpisodePlan anime(String label) {
        return DanmakuEpisodePlan.from(new DanmakuMatchContext("凡人修仙传", "2020", "国产动漫", label), 1);
    }
    private String response(String episode, String evidence) {
        var json = new com.google.gson.JsonObject(); json.addProperty("episode", episode); json.addProperty("evidence", evidence); return json.toString();
    }
    @Test public void packagedLabelsDoNotFallbackToFirstPlaylistPosition() {
        // These are beyond the previous release's leading-number-only fix.
        for (String label : List.of("【4K】凡人修仙传 22 1-73集", "凡人修仙传 22 4K 全1-73集", "[2.1 GB]凡人修仙传 22.mp4 1-73集")) {
            var plan = anime(label); assertTrue(label, plan.complex);
            assertEquals(label,"22",plan.fallback); assertEquals(List.of(22),plan.numbers);
            assertEquals("第22集",plan.accept(response("第22集","22")));
            for (int wrong : new int[]{1,4,73,1080}) assertEquals("",plan.accept(response("第"+wrong+"集",String.valueOf(wrong))));
        }
    }
    @Test public void ambiguousNumbersAllowAiCorrectionButNotAnInventedEpisode() {
        var plan=anime("序号01【4K】凡人修仙传 22 1-73集");
        assertTrue(plan.complex);assertEquals("",plan.fallback);assertEquals(List.of(1,22),plan.numbers);
        assertEquals("第22集",plan.accept(response("第22集","22")));
        assertEquals("",plan.accept(response("第23集","22")));
        assertEquals("",plan.accept(response("第22集","73")));
        assertEquals("",plan.accept("{\"episode\":\"第22集\"}"));
        assertEquals("",anime("凡人修仙传 全1-73集 4K").fallback);
    }
    @Test public void unambiguousEpisodesAvoidModelRoundTrips() {
        for(String label:List.of("22","第22集","第十七集","EP22","S01E22")) assertFalse(label,anime(label).complex);
        assertEquals("22",anime("S01E22").fallback);
        var movie=DanmakuEpisodePlan.from(new DanmakuMatchContext("哪吒2","2025","电影","01 4K 哪吒2 正片"),1);
        assertFalse(movie.complex);assertEquals("正片",movie.fallback);
    }
    @Test public void dateHalvesAndExtrasCannotBeChangedByTheModel() {
        String raw="2026-10-12 第1期下 加更 1080P";
        var plan=DanmakuEpisodePlan.from(new DanmakuMatchContext("现在就出发第四季","2026","综艺",raw),1);
        assertTrue(plan.complex);assertEquals(raw,plan.fallback);
        assertEquals("2026-10-12 第1期下 加更",plan.accept(response("2026-10-12 第1期下 加更","第1期下")));
        for(String wrong:List.of("2026-10-13 第1期下 加更","2026-10-12 第1期上 加更","2026-10-12 第1期下"))
            assertEquals("",plan.accept(response(wrong,"第1期下")));
        var english=DanmakuEpisodePlan.from(new DanmakuMatchContext("节目","2026","综艺","EP01 Part B"),3);
        assertTrue(english.complex);assertEquals("EP01 Part B",english.fallback);
        assertEquals("第1期下",english.accept(response("第1期下","EP01 Part B")));
        assertEquals("",english.accept(response("第1期上","EP01 Part B")));
        var bonus=DanmakuEpisodePlan.from(new DanmakuMatchContext("节目","2026","综艺","EP01 Bonus 4K"),1);
        assertEquals("",bonus.accept(response("第1期","EP01 Bonus")));
        assertEquals("第1期加更",bonus.accept(response("第1期加更","EP01 Bonus")));
    }
    @Test public void numericFilenamePrefixIsNotAnAnimeBroadcastDate() {
        var plan=anime("01.22 4K 凡人修仙传 1-73集");
        assertEquals("",plan.fallback);assertFalse(plan.dateRequired);
        assertEquals("第22集",plan.accept(response("第22集","22")));
    }
}
