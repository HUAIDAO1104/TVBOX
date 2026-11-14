package com.fongmi.android.tv.api;

import static org.junit.Assert.*;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class DanmakuServiceHealthTest {
    @Test public void repeatedOutagesBackOffBrieflyThenAllowRecovery() {
        String source = "https://offline.fixture";
        var error = new SocketTimeoutException("fixture");
        long now = 100;
        DanmakuServiceHealth.failure(source, error, now);
        assertNull(DanmakuServiceHealth.unavailable(source, now));
        DanmakuServiceHealth.failure(source, error, now);
        assertSame(error, DanmakuServiceHealth.unavailable(source, now + 1));
        assertNull(DanmakuServiceHealth.unavailable(source, now + TimeUnit.SECONDS.toNanos(61)));
        DanmakuServiceHealth.success(source);
        assertNull(DanmakuServiceHealth.unavailable(source, now));
    }
    @Test public void authenticationAndInvalidDocumentsDoNotDisableOtherSearches() {
        String source = "https://auth.fixture";
        for (int i = 0; i < 3; i++) {
            DanmakuServiceHealth.failure(source, new DanmakuCatalogueSearch.HttpFailure(403), 100);
            DanmakuServiceHealth.failure(source, new DanmakuCatalogueSearch.InvalidResponse(null), 100);
        }
        assertNull(DanmakuServiceHealth.unavailable(source, 101));
    }
}
