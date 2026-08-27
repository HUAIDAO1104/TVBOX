package com.fongmi.android.tv.player.danmaku;

/** Lightweight playback-wide status for the automatic danmaku pipeline. */
public final class DanmakuStatus {

    public enum Stage { HIDDEN, MATCHING, RESTORING, DOWNLOADING, READY, FAILED }

    public enum Failure { NONE, DISABLED, NO_MATCH, NETWORK, INVALID_RESPONSE, DOWNLOAD, EMPTY_DOCUMENT }

    private final Stage stage;
    private final Failure failure;
    private final int progress;
    private final int itemCount;

    private DanmakuStatus(Stage stage, Failure failure, int progress, int itemCount) {
        this.stage = stage;
        this.failure = failure;
        this.progress = progress;
        this.itemCount = itemCount;
    }

    public static DanmakuStatus hidden() { return new DanmakuStatus(Stage.HIDDEN, Failure.NONE, -1, 0); }
    public static DanmakuStatus matching() { return new DanmakuStatus(Stage.MATCHING, Failure.NONE, -1, 0); }
    public static DanmakuStatus restoring() { return new DanmakuStatus(Stage.RESTORING, Failure.NONE, -1, 0); }
    public static DanmakuStatus downloading(int progress) { return new DanmakuStatus(Stage.DOWNLOADING, Failure.NONE, progress, 0); }
    public static DanmakuStatus ready(int itemCount) { return new DanmakuStatus(Stage.READY, Failure.NONE, 100, itemCount); }
    public static DanmakuStatus failed(Failure failure) { return new DanmakuStatus(Stage.FAILED, failure, -1, 0); }

    public Stage stage() { return stage; }
    public Failure failure() { return failure; }
    public int progress() { return progress; }
    public int itemCount() { return itemCount; }
}
