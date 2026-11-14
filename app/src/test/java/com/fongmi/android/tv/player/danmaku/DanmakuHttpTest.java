package com.fongmi.android.tv.player.danmaku;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DanmakuHttpTest {

    @Test
    public void boundsTheNetworkWait() {
        // Real generated XML documents regularly take 20-30 seconds on the provider. Keep a
        // finite ceiling, but do not abort a healthy later episode at the old 26-second limit.
        assertTrue(DanmakuHttp.CALL_TIMEOUT_SECONDS >= 40);
        assertTrue(DanmakuHttp.CALL_TIMEOUT_SECONDS <= 45);
        assertTrue(DanmakuHttp.CONNECT_TIMEOUT_SECONDS < DanmakuHttp.CALL_TIMEOUT_SECONDS);
        assertTrue(DanmakuHttp.READ_TIMEOUT_SECONDS < DanmakuHttp.CALL_TIMEOUT_SECONDS);
    }
}
