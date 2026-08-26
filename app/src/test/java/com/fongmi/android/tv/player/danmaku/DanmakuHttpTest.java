package com.fongmi.android.tv.player.danmaku;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DanmakuHttpTest {

    @Test
    public void cachesOnlyImmutableEpisodeDocuments() {
        assertTrue(DanmakuHttp.isImmutableCommentUrl(
                "https://dm.example/api/v2/comment/2902830?format=xml"));
        assertTrue(DanmakuHttp.isImmutableCommentUrl(
                "https://dm.example/api/v1/comment/42.xml"));
        assertFalse(DanmakuHttp.isImmutableCommentUrl(
                "https://dm.example/api/v2/fongmi/danmaku?name=example&episode=2"));
        assertFalse(DanmakuHttp.isImmutableCommentUrl(null));
    }

    @Test
    public void boundsTheNetworkWait() {
        assertTrue(DanmakuHttp.CALL_TIMEOUT_SECONDS < 30);
        assertTrue(DanmakuHttp.CONNECT_TIMEOUT_SECONDS < DanmakuHttp.CALL_TIMEOUT_SECONDS);
        assertTrue(DanmakuHttp.READ_TIMEOUT_SECONDS < DanmakuHttp.CALL_TIMEOUT_SECONDS);
    }
}
