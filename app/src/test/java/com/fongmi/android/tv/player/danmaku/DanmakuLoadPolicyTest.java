package com.fongmi.android.tv.player.danmaku;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
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

    @Test
    public void retriesTransientDocumentDownloadOnlyOnce() {
        assertTrue(DanmakuLoadPolicy.shouldRetry(
                new DanmakuDocumentCache.HttpStatusException(503), 0));
        assertFalse(DanmakuLoadPolicy.shouldRetry(
                new DanmakuDocumentCache.HttpStatusException(503), 1));
        assertFalse(DanmakuLoadPolicy.shouldRetry(
                new DanmakuDocumentCache.HttpStatusException(404), 0));
    }

    @Test
    public void retriesRateLimitedCommentIdWithBoundedBackoff() {
        DanmakuDocumentCache.HttpStatusException limited =
                new DanmakuDocumentCache.HttpStatusException(429, 4_000L);
        assertTrue(DanmakuLoadPolicy.shouldRetrySameSource(limited, 0));
        assertEquals(4_000L, DanmakuLoadPolicy.sameSourceRetryDelayMillis(limited, 0));
        assertTrue(DanmakuLoadPolicy.shouldRetrySameSource(limited, 1));
        assertFalse(DanmakuLoadPolicy.shouldRetrySameSource(limited, 2));
        assertFalse(DanmakuLoadPolicy.shouldRetrySameSource(
                new DanmakuDocumentCache.HttpStatusException(500), 0));
    }

    @Test
    public void refreshesGeneratedIdAfterStaleOrServerFailure() {
        assertTrue(DanmakuLoadPolicy.shouldRefreshSource(
                new DanmakuDocumentCache.HttpStatusException(404)));
        assertTrue(DanmakuLoadPolicy.shouldRefreshSource(
                new DanmakuDocumentCache.HttpStatusException(500)));
        assertFalse(DanmakuLoadPolicy.shouldRefreshSource(
                new DanmakuDocumentCache.HttpStatusException(403)));
    }
}
