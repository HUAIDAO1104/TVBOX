package com.fongmi.android.tv.playback.vod;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaybackOverlayPolicyTest {

    @Test
    public void bufferingUsesDebounceAndLongWaitThreshold() {
        assertEquals(PlaybackOverlayPolicy.BufferingPhase.HIDDEN, PlaybackOverlayPolicy.bufferingPhase(349));
        assertEquals(PlaybackOverlayPolicy.BufferingPhase.COMPACT, PlaybackOverlayPolicy.bufferingPhase(350));
        assertEquals(PlaybackOverlayPolicy.BufferingPhase.COMPACT, PlaybackOverlayPolicy.bufferingPhase(7_999));
        assertEquals(PlaybackOverlayPolicy.BufferingPhase.ACTIONABLE, PlaybackOverlayPolicy.bufferingPhase(8_000));
    }

    @Test
    public void interactiveOverlaysKeepRemoteKeysOutOfGestureLayer() {
        assertTrue(PlaybackOverlayPolicy.routeToPlaybackGestures(true, false, false, false));
        assertFalse(PlaybackOverlayPolicy.routeToPlaybackGestures(true, true, false, false));
        assertFalse(PlaybackOverlayPolicy.routeToPlaybackGestures(true, false, true, false));
        assertFalse(PlaybackOverlayPolicy.routeToPlaybackGestures(true, false, false, true));
        assertFalse(PlaybackOverlayPolicy.routeToPlaybackGestures(false, false, false, false));
    }

    @Test
    public void horizontalDirectionSeeksWhileVerticalDirectionRevealsHiddenControls() {
        assertTrue(PlaybackOverlayPolicy.shouldSeekWithHiddenControls(true, false, false, false, true));
        assertFalse(PlaybackOverlayPolicy.shouldSeekWithHiddenControls(true, true, false, false, true));
        assertFalse(PlaybackOverlayPolicy.shouldSeekWithHiddenControls(true, false, true, false, true));
        assertFalse(PlaybackOverlayPolicy.shouldSeekWithHiddenControls(true, false, false, true, true));
        assertFalse(PlaybackOverlayPolicy.shouldSeekWithHiddenControls(true, false, false, false, false));
        assertTrue(PlaybackOverlayPolicy.shouldRevealControls(true, false, false, false, true));
        assertFalse(PlaybackOverlayPolicy.shouldRevealControls(true, true, false, false, true));
        assertFalse(PlaybackOverlayPolicy.shouldRevealControls(true, false, true, false, true));
        assertFalse(PlaybackOverlayPolicy.shouldRevealControls(true, false, false, true, true));
        assertFalse(PlaybackOverlayPolicy.shouldRevealControls(true, false, false, false, false));
    }

    @Test
    public void bufferingActionsWaitForExplicitDirectionalNavigation() {
        assertTrue(PlaybackOverlayPolicy.shouldEnterProgressActions(true, true, false, true));
        assertFalse(PlaybackOverlayPolicy.shouldEnterProgressActions(true, true, true, true));
        assertFalse(PlaybackOverlayPolicy.shouldEnterProgressActions(true, false, false, true));
        assertFalse(PlaybackOverlayPolicy.shouldEnterProgressActions(false, true, false, true));
        assertFalse(PlaybackOverlayPolicy.shouldEnterProgressActions(true, true, false, false));
    }

    @Test
    public void rebufferingPreparedMediaCanStillBePausedWithConfirm() {
        assertTrue(PlaybackOverlayPolicy.canToggleDuringBuffering(true, true, true, false, false));
        assertFalse(PlaybackOverlayPolicy.canToggleDuringBuffering(true, true, true, true, false));
        assertFalse(PlaybackOverlayPolicy.canToggleDuringBuffering(true, true, false, false, false));
        assertFalse(PlaybackOverlayPolicy.canToggleDuringBuffering(true, true, true, false, true));
        assertFalse(PlaybackOverlayPolicy.canToggleDuringBuffering(false, true, true, false, false));
    }

    @Test
    public void visibleErrorRecapturesFocusFromContentButNotFromItsOwnActions() {
        assertTrue(PlaybackOverlayPolicy.shouldCaptureErrorFocus(true, false));
        assertFalse(PlaybackOverlayPolicy.shouldCaptureErrorFocus(true, true));
        assertFalse(PlaybackOverlayPolicy.shouldCaptureErrorFocus(false, false));
    }
}
