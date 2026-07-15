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
}
