package com.fongmi.android.tv.api.loader;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class SpiderCacheEpochTest {

    @Test
    public void advanceMakesInFlightCacheKeyStale() {
        SpiderCacheEpoch epoch = new SpiderCacheEpoch();
        long before = epoch.current();
        String oldKey = epoch.scope(before, "repo:site");

        epoch.advance();

        assertFalse(epoch.isCurrent(before));
        assertTrue(epoch.isCurrent(epoch.current()));
        assertNotEquals(oldKey, epoch.scope(epoch.current(), "repo:site"));
    }
}
