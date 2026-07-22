package com.fongmi.android.tv.playback.vod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VodPlaybackMediaTest {

    @Test
    public void staleDanmakuResponseCannotCrossIntoAnotherTitleOrEpisode() {
        assertTrue(VodPlaybackMedia.matchesMetadata("庆余年", "第2集", "庆余年", "第2集"));
        assertFalse(VodPlaybackMedia.matchesMetadata("百花杀", "第1集", "庆余年", "第1集"));
        assertFalse(VodPlaybackMedia.matchesMetadata("庆余年", "第1集", "庆余年", "第2集"));
    }

    @Test
    public void automaticDanmakuUsesTheSelectedEpisodeInsteadOfProviderOrdering() {
        assertEquals("9", VodPlaybackMedia.resolveEpisodeQuery("[2.1 GB]9.mp4【百花杀】", 16));
        assertEquals("12", VodPlaybackMedia.resolveEpisodeQuery("预告片", 12));
        assertEquals("3", VodPlaybackMedia.resolveEpisodeQuery("20260720特辑", 3));
        assertEquals("4", VodPlaybackMedia.resolveEpisodeQuery("360P修复版", 4));
    }
}
