package com.fongmi.android.tv.player;

/** Startup expires after inactivity, not after a fixed amount of total buffering time. */
public final class PlaybackStartupTimeout {
    private long lastProgressMs;
    private long timeoutMs;
    private long transferredBytes;
    private long bufferedDurationMs;
    private boolean active;

    public void start(long nowMs, long timeoutMs, long transferredBytes) {
        this.lastProgressMs = nowMs;
        this.timeoutMs = timeoutMs;
        this.transferredBytes = transferredBytes;
        this.bufferedDurationMs = 0;
        active = timeoutMs >= 0;
    }

    public void observe(long nowMs, long bytes, long bufferMs) {
        if (!active) return;
        if (bytes > transferredBytes || bufferMs > bufferedDurationMs) progress(nowMs);
        transferredBytes = bytes;
        bufferedDurationMs = bufferMs;
    }

    public void progress(long nowMs) {
        if (active) lastProgressMs = nowMs;
    }

    public boolean expired(long nowMs) {
        return active && nowMs - lastProgressMs >= timeoutMs;
    }

    public boolean isActive() { return active; }

    public void cancel() { active = false; }
}
