package com.fongmi.android.tv.player.danmaku;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DanmakuStatusTest {

    @Test
    public void progressAndFailureRemainExplicit() {
        DanmakuStatus progress = DanmakuStatus.downloading(65);
        DanmakuStatus failure = DanmakuStatus.failed(DanmakuStatus.Failure.NO_MATCH);

        assertEquals(DanmakuStatus.Stage.DOWNLOADING, progress.stage());
        assertEquals(65, progress.progress());
        assertEquals(DanmakuStatus.Stage.FAILED, failure.stage());
        assertEquals(DanmakuStatus.Failure.NO_MATCH, failure.failure());
    }
}
