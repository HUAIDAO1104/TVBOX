package com.fongmi.android.tv.playback.vod;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.Vod;

import org.junit.Test;

import java.util.List;

public class VodPlaybackStateTest {

    @Test
    public void resetClearsTransientDetailAndPlaybackState() {
        VodPlaybackState state = new VodPlaybackState();
        state.addFailedId("fixture-id");
        state.setSources(List.of(new Vod()));
        state.setFlags(List.of(new Flag()));
        state.setSelectFirstSource(true);
        state.setAutoFallback(true);
        state.setUseParse(true);
        state.setSearchKeyword("fixture title");
        state.setQualityPosition(3);

        state.reset();

        assertFalse(state.hasFailedId("fixture-id"));
        assertFalse(state.hasSources());
        assertFalse(state.hasFlags());
        assertFalse(state.isSelectFirstSource());
        assertFalse(state.isAutoFallback());
        assertFalse(state.isUseParse());
        assertTrue(state.getSearchKeyword().isEmpty());
        assertTrue(state.getQualityPosition() == 0);
    }

    @Test
    public void failedIdsIgnoreBlankAndRemainDeduplicated() {
        VodPlaybackState state = new VodPlaybackState();
        state.addFailedId(null);
        state.addFailedId("");
        state.addFailedId("fixture-id");
        state.addFailedId("fixture-id");

        assertFalse(state.hasFailedId(null));
        assertFalse(state.hasFailedId(""));
        assertTrue(state.hasFailedId("fixture-id"));
    }
}
