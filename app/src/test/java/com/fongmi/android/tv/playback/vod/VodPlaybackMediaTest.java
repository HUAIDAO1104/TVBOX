package com.fongmi.android.tv.playback.vod;

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
}
