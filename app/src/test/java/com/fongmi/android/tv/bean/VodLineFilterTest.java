package com.fongmi.android.tv.bean;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

public class VodLineFilterTest {

    @Test
    public void removesGhostLineWithoutShiftingItsNeighbourUrl() {
        Vod vod = new Vod();
        vod.setPlayFrom("正常线路$$$小白\u200B弹幕$$$备用线路");
        vod.setPlayUrl("第1集$normal-url$$$无效$ghost-url$$$第1集$backup-url");

        vod.setFlags();

        assertEquals(2, vod.getFlags().size());
        assertEquals("正常线路", vod.getFlags().get(0).getFlag());
        assertEquals("normal-url", vod.getFlags().get(0).getEpisodes().get(0).getUrl());
        assertEquals("备用线路", vod.getFlags().get(1).getFlag());
        assertEquals("backup-url", vod.getFlags().get(1).getEpisodes().get(0).getUrl());
    }

    @Test
    public void removesGhostFromStructuredFlagsToo() {
        Vod vod = new Vod();
        vod.setFlags(List.of(Flag.create("线路A", "1$a"), Flag.create("<b>小白</b>弹幕", "1$bad")));

        assertEquals(1, vod.getFlags().size());
        assertEquals("线路A", vod.getFlags().get(0).getFlag());
    }

    @Test
    public void fallsBackToPairedRealLineWhenStructuredListOnlyContainsGhost() {
        Vod vod = new Vod();
        vod.setFlags(List.of(Flag.create("小白彈幕", "1$bad")));
        vod.setPlayFrom("真实线路");
        vod.setPlayUrl("第1集$real-url");

        vod.setFlags();

        assertEquals(1, vod.getFlags().size());
        assertEquals("真实线路", vod.getFlags().get(0).getFlag());
        assertEquals("real-url", vod.getFlags().get(0).getEpisodes().get(0).getUrl());
    }
}
