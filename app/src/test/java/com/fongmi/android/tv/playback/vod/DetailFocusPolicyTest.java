package com.fongmi.android.tv.playback.vod;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DetailFocusPolicyTest {

    @Test
    public void restoredPositionIsStableWhenListShrinksOrIsEmpty() {
        assertEquals(DetailFocusPolicy.INVALID_POSITION, DetailFocusPolicy.clampPosition(4, 0));
        assertEquals(0, DetailFocusPolicy.clampPosition(-1, 6));
        assertEquals(3, DetailFocusPolicy.clampPosition(3, 6));
        assertEquals(5, DetailFocusPolicy.clampPosition(9, 6));
    }

    @Test
    public void posterLeavesTheFocusGraphAsDetailRowsScrollOverItsSlot() {
        assertEquals(1f, DetailFocusPolicy.posterAlpha(180, 220, 120), 0.001f);
        assertEquals(0.5f, DetailFocusPolicy.posterAlpha(280, 220, 120), 0.001f);
        assertEquals(0f, DetailFocusPolicy.posterAlpha(340, 220, 120), 0.001f);
        assertTrue(DetailFocusPolicy.posterCanReceiveFocus(0.21f));
        assertFalse(DetailFocusPolicy.posterCanReceiveFocus(0.2f));
    }

    @Test
    public void episodeViewportStaysBoundedForLongPlaylists() {
        assertEquals(0, DetailFocusPolicy.episodeViewportRows(0, 2, 6));
        assertEquals(1, DetailFocusPolicy.episodeViewportRows(1, 2, 6));
        assertEquals(2, DetailFocusPolicy.episodeViewportRows(4, 2, 6));
        assertEquals(6, DetailFocusPolicy.episodeViewportRows(600, 2, 6));
    }

    @Test
    public void focusedControlScrollsOnlyWhenOutsideSafeViewport() {
        assertEquals(-14, DetailFocusPolicy.focusScrollDelta(10, 44, 0, 720, 24));
        assertEquals(0, DetailFocusPolicy.focusScrollDelta(100, 44, 0, 720, 24));
        assertEquals(28, DetailFocusPolicy.focusScrollDelta(680, 44, 0, 720, 24));
    }
}
