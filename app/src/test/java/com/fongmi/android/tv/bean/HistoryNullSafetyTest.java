package com.fongmi.android.tv.bean;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HistoryNullSafetyTest {

    @Test
    public void legacyNullDisplayFieldsAreSafeForHomeDiff() {
        History first = new History();
        first.setKey("site@@vod");
        first.setVodName(null);
        first.setVodPic(null);
        first.setVodFlag(null);

        History second = new History();
        second.setKey("site@@vod");
        second.setVodName(null);
        second.setVodPic(null);
        second.setVodFlag(null);

        assertEquals("", first.getVodName());
        assertEquals("", first.getVodPic());
        assertEquals("", first.getVodFlag());
        assertTrue(first.isSameContent(second));
    }

    @Test
    public void oversizedEpisodePayloadDoesNotEnterHistoryCursor() {
        History history = new History();
        history.setEpisodeUrl("x".repeat(History.MAX_EPISODE_URL_LENGTH + 1));

        assertEquals("", history.getEpisodeUrl());
    }

    @Test
    public void normalEpisodeIdentifierIsPreserved() {
        History history = new History();
        history.setEpisodeUrl("episode-12");

        assertEquals("episode-12", history.getEpisodeUrl());
    }
}
