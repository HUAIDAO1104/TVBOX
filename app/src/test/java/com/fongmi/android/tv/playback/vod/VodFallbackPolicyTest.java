package com.fongmi.android.tv.playback.vod;

import com.fongmi.android.tv.bean.Vod;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class VodFallbackPolicyTest {

    @Test
    public void nullLiveDataResetDoesNotConsumeInFlightFallbackState() {
        VodPlaybackState state = new VodPlaybackState();
        Vod queuedSource = new Vod();
        state.setSources(List.of(queuedSource));
        state.setSearchKeyword("fixture title");
        state.setAutoFallback(true);
        state.setSelectFirstSource(true);

        // Controller and host are deliberately absent: a LiveData reset must be ignored before
        // the policy performs any business transition or host callback.
        VodFallbackPolicy policy = new VodFallbackPolicy(null, state, null);
        policy.onSearchResult(null);

        assertTrue(state.isAutoFallback());
        assertTrue(state.isSelectFirstSource());
        assertSame(queuedSource, state.getSources().get(0));
    }
}
