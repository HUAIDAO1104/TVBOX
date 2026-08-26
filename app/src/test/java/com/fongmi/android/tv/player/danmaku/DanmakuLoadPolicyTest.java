package com.fongmi.android.tv.player.danmaku;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DanmakuLoadPolicyTest {

    @Test
    public void retriesOnlyTransientResponseCodes() {
        assertTrue(DanmakuLoadPolicy.shouldRetryResponseCode(408));
        assertTrue(DanmakuLoadPolicy.shouldRetryResponseCode(429));
        assertTrue(DanmakuLoadPolicy.shouldRetryResponseCode(500));
        assertTrue(DanmakuLoadPolicy.shouldRetryResponseCode(503));
        assertFalse(DanmakuLoadPolicy.shouldRetryResponseCode(404));
        assertFalse(DanmakuLoadPolicy.shouldRetryResponseCode(403));
    }
}
