package com.fongmi.android.tv.playback.vod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class EpisodePickerPolicyTest {

    @Test
    public void actionOnlyAppearsWhenThereIsARealChoice() {
        assertFalse(EpisodePickerPolicy.shouldShowAction(0, 0));
        assertFalse(EpisodePickerPolicy.shouldShowAction(1, 1));
        assertTrue(EpisodePickerPolicy.shouldShowAction(2, 1));
        assertTrue(EpisodePickerPolicy.shouldShowAction(1, 2));
    }

    @Test
    public void selectedFlagFallsBackToFirstAvailableSource() {
        Flag first = Flag.create("line-a");
        Flag second = Flag.create("line-b");
        assertEquals(-1, EpisodePickerPolicy.selectedFlagPosition(Collections.emptyList()));
        assertEquals(0, EpisodePickerPolicy.selectedFlagPosition(Arrays.asList(first, second)));
        second.setSelected(second);
        assertEquals(1, EpisodePickerPolicy.selectedFlagPosition(Arrays.asList(first, second)));
    }

    @Test
    public void selectedEpisodeFallsBackToFirstAndTracksPlayingEpisode() {
        Episode first = Episode.create("1", "url-1");
        Episode second = Episode.create("2", "url-2");
        assertEquals(-1, EpisodePickerPolicy.selectedEpisodePosition(Collections.emptyList()));
        assertEquals(0, EpisodePickerPolicy.selectedEpisodePosition(Arrays.asList(first, second)));
        second.setSelected(true);
        assertEquals(1, EpisodePickerPolicy.selectedEpisodePosition(Arrays.asList(first, second)));
    }
}
