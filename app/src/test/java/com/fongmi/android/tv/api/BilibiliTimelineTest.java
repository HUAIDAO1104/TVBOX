package com.fongmi.android.tv.api;

import static org.junit.Assert.*;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.playback.vod.DanmakuMatch;
import com.fongmi.android.tv.playback.vod.DanmakuMatchContext;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.Test;

public class BilibiliTimelineTest {
    private List<Danmaku> catalogue() throws Exception {
        try (var in = getClass().getResourceAsStream("/danmaku/bilibili-cuts.json")) {
            return BilibiliDanmaku.catalogue(new String(in.readAllBytes(), StandardCharsets.UTF_8), "28747");
        }
    }
    private DanmakuMatchContext context(String episode) { return new DanmakuMatchContext("凡人修仙传", "2020", "国产动漫", episode); }
    @Test public void oldNumberOnlyChoiceReproducesTheWrongCutForEpisodeSeventeen() throws Exception {
        var rows = catalogue();
        var old = DanmakuMatch.best("凡人修仙传", "2020", "动漫", "第17集", rows, Danmaku::getMatchName);
        assertTrue(old.getUrl().contains("/733332/")); // 18:50, even if the video lasts 19:23
        var candidates = BilibiliDanmaku.timelineCandidates(context("第17集"), rows);
        assertEquals(2, candidates.size());
        assertTrue(BilibiliDanmaku.chooseTimeline(candidates, 1163000).getUrl().contains("/734379/"));
        assertTrue(BilibiliDanmaku.chooseTimeline(candidates, 1130000).getUrl().contains("/733332/"));
    }
    @Test public void consecutiveEpisodesAndRenamedWorksUseTheirOwnVerifiedDuration() throws Exception {
        var rows = catalogue();
        for (Danmaku row : rows) {
            Integer episode = DanmakuMatch.episodeNumber(row.getName());
            if (episode == null || episode < 16 || episode > 21) continue;
            long duration = Long.parseLong(row.getUrl().substring(row.getUrl().lastIndexOf('/') + 1)) * 1000;
            var candidates = BilibiliDanmaku.timelineCandidates(context("第" + episode + "集"), rows);
            assertEquals(row.getUrl(), BilibiliDanmaku.chooseTimeline(candidates, duration + 400).getUrl());
        }
        for (Danmaku row : rows) row.setName(row.getName().replace("凡人修仙传", "另一部动画"));
        var c = new DanmakuMatchContext("另一部动画", "2020", "动漫", "17");
        assertTrue(BilibiliDanmaku.chooseTimeline(BilibiliDanmaku.timelineCandidates(c, rows), 1163000).getUrl().contains("/734379/"));
    }
    @Test public void missingAmbiguousOrIncompatibleEvidenceNeverGuessesACut() throws Exception {
        var rows = catalogue(); var candidates = BilibiliDanmaku.timelineCandidates(context("17"), rows);
        assertNull(BilibiliDanmaku.chooseTimeline(candidates, 0));
        assertNull(BilibiliDanmaku.chooseTimeline(candidates, 7200000));
        Danmaku duplicate = Danmaku.from(candidates.get(0).getUrl()); duplicate.setName(candidates.get(0).getName());
        long duration = Long.parseLong(duplicate.getUrl().substring(duplicate.getUrl().lastIndexOf('/') + 1)) * 1000;
        assertNull(BilibiliDanmaku.chooseTimeline(List.of(candidates.get(0), duplicate), duration));
        for (var c : List.of(new DanmakuMatchContext("凡人修仙传", "2025", "电视剧", "17"),
                new DanmakuMatchContext("其他动画", "2020", "动漫", "17"), context("第117集")))
            assertTrue(BilibiliDanmaku.timelineCandidates(c, rows).isEmpty());
    }
    @Test public void explicitEditionIsNotReplacedByACloserDifferentCut() throws Exception {
        var candidates = BilibiliDanmaku.timelineCandidates(new DanmakuMatchContext("凡人修仙传2020版", "2020", "动漫", "17"), catalogue());
        assertEquals(1, candidates.size());
        assertTrue(BilibiliDanmaku.chooseTimeline(candidates, 1130000).getUrl().contains("/734379/"));
    }
}
