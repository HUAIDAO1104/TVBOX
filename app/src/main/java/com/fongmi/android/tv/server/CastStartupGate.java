package com.fongmi.android.tv.server;

/** A successful Play SOAP response or a file download does not mean the decoder is playing. */
public final class CastStartupGate {
    private long playingSince = -1;
    private boolean ready;
    private boolean clockKnown;

    public boolean observe(long nowMs, boolean media, boolean playing, long positionSeconds) {
        if (ready) return true;
        if (!playing || !media) { playingSince = -1; return false; }
        if (playingSince < 0) playingSince = nowMs;
        clockKnown = positionSeconds >= 0;
        // A real playback clock is preferable. Broken/unsupported position queries get a
        // conservative state-only fallback, without sending an automatic Seek command.
        ready = clockKnown ? positionSeconds > 0 : nowMs - playingSince >= 1500;
        return ready;
    }

    public boolean shouldResume(boolean sourceSeekable, long requestedPositionMs) {
        return ready && clockKnown && sourceSeekable && requestedPositionMs >= 2000;
    }
}
