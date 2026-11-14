package com.fongmi.android.tv.playback.vod;

import static org.junit.Assert.*;
import com.fongmi.android.tv.bean.Danmaku;
import java.util.*;
import org.junit.Test;

public class DanmakuContinuationTest {
    private Danmaku row(String label, String url) {
        Danmaku value = new Danmaku(); value.setName("连续节目(2020)【综艺】from fixture - " + label); value.setUrl(url); return value;
    }
    private DanmakuManualMatchStore.Selection selection(List<Danmaku> rows, String current) {
        var context = new DanmakuMatchContext("连续节目", "2020", "综艺", current);
        return new DanmakuManualMatchStore.Selection("连续节目", rows.get(0).getName(), "fixture",
                DanmakuManualMatchStore.buildEpisodeCache(context, rows.get(0), rows), System.currentTimeMillis());
    }
    @Test public void datesAndPeriodLabelsFindTheSameUnambiguousHalfAndBonus() {
        var rows = List.of(row("第1期上 20261010", "upper"), row("第1期下 20261010", "lower"), row("第1期加更 20261010", "bonus"));
        var saved = selection(rows, "第1期上");
        assertEquals("lower", saved.episode("第1期下").getUrl());
        assertEquals("lower", saved.episode("20261010下").getUrl());
        assertEquals("bonus", saved.episode("第1期加更").getUrl());
        assertTrue(saved.canReuseEpisode("第1期下"));
        assertNull(saved.episode("第2期下"));
    }
    @Test public void ambiguousPeriodWithoutDateNeverUsesAnArbitraryBroadcast() {
        var saved = selection(List.of(row("第1期上 20261010", "upper"), row("第1期下 20261010", "first"), row("第1期下 20261011", "second")), "第1期上");
        assertNull(saved.episode("第1期下"));
        assertEquals("second", saved.episode("20261011下").getUrl());
    }
    @Test public void failedDocumentEvictsBothDateAndPeriodAliasesOnly() {
        var saved = selection(List.of(row("第1期下 20261010", "lower"), row("第2期下 20261017", "next")), "第1期下");
        assertTrue(saved.removeEpisode("20261010下"));
        assertNull(saved.episode("第1期下"));
        assertNull(saved.episode("20261010下"));
        assertEquals("next", saved.episode("第2期下").getUrl());
    }
    @Test public void longSeriesKeepsNeighboursOfCurrentEpisodeBeyondTheOld160RowLimit() {
        List<Danmaku> rows = new ArrayList<>();
        for (int i=1;i<=350;i++) rows.add(row("第"+i+"集", "episode-"+i));
        var context = new DanmakuMatchContext("连续节目", "2020", "", "第250集");
        var cached = DanmakuManualMatchStore.buildEpisodeCache(context, rows.get(249), rows);
        assertEquals("episode-251", cached.get("251").url());
        assertTrue(cached.size() <= 160);
    }
    @Test public void snapshotAgeSurvivesSerializationAndDoesNotBecomePermanent() {
        var saved = selection(List.of(row("第17集", "https://example.test/17"), row("第18集", "https://example.test/18")), "第17集");
        long first = saved.discoveredAt("18");
        String json = DanmakuManualMatchStore.encode(Map.of("work", saved));
        var decoded = DanmakuManualMatchStore.decode(json).get("work");
        assertEquals(first, decoded.discoveredAt("18"));
        assertTrue(decoded.canReuseEpisode("18"));
        String expired = json.replaceAll("\"discoveredAt\":\\d+", "\"discoveredAt\":"+(System.currentTimeMillis()-DanmakuManualMatchStore.CATALOGUE_TTL_MS-1000));
        assertFalse(DanmakuManualMatchStore.decode(expired).get("work").canReuseEpisode("18"));
        assertFalse(DanmakuManualMatchStore.decode(json.replaceAll(",\"discoveredAt\":\\d+", "")).get("work").canReuseEpisode("18"));
    }
    @Test public void explicitMetadataCorrectionAppliesOnlyToTheConfirmedRepositoryEdition() {
        String json="{\"work\":{\"query\":\"连续节目\",\"selectedName\":\"连续节目(2020)【动漫】from fixture - 第17集\",\"confirmed\":true,\"contextYear\":\"2026\",\"contextType\":\"series\"}}";
        var saved=DanmakuManualMatchStore.decode(json).get("work");
        assertTrue(DanmakuManualMatchStore.compatible(new DanmakuMatchContext("连续节目","2026","电视剧","18"),saved));
        assertFalse(DanmakuManualMatchStore.compatible(new DanmakuMatchContext("连续节目","2025","电视剧","18"),saved));
        assertFalse(DanmakuManualMatchStore.compatible(new DanmakuMatchContext("连续节目","2026","电影","18"),saved));
    }
}
