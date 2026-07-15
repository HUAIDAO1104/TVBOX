package com.fongmi.android.tv.playback.vod;

/** Pure timing and key-routing policy shared by the TV playback UI and unit tests. */
public final class PlaybackOverlayPolicy {

    public static final long BUFFERING_INDICATOR_DELAY_MS = 350L;
    public static final long BUFFERING_ACTION_DELAY_MS = 8_000L;

    public enum BufferingPhase { HIDDEN, COMPACT, ACTIONABLE }

    private PlaybackOverlayPolicy() {
    }

    public static BufferingPhase bufferingPhase(long elapsedMs) {
        if (elapsedMs < BUFFERING_INDICATOR_DELAY_MS) return BufferingPhase.HIDDEN;
        if (elapsedMs < BUFFERING_ACTION_DELAY_MS) return BufferingPhase.COMPACT;
        return BufferingPhase.ACTIONABLE;
    }

    public static boolean routeToPlaybackGestures(boolean fullscreen, boolean controlVisible, boolean progressVisible, boolean errorVisible) {
        return fullscreen && !controlVisible && !progressVisible && !errorVisible;
    }

    /** A rebuffering overlay may still toggle an already prepared item with the confirm key. */
    public static boolean canToggleDuringBuffering(boolean fullscreen, boolean progressVisible, boolean buffering,
                                                   boolean playerEmpty, boolean errorVisible) {
        return fullscreen && progressVisible && buffering && !playerEmpty && !errorVisible;
    }

    /** Error actions are modal: visible errors must reclaim focus from the detail/player layer. */
    public static boolean shouldCaptureErrorFocus(boolean errorVisible, boolean focusInsideError) {
        return errorVisible && !focusInsideError;
    }
}
