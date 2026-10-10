package com.fongmi.android.tv.player;

import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackStartupTimeoutTest {
    @Test public void ongoingReadsCanBufferLongerThanTheIdleLimit() {
        var timeout = new PlaybackStartupTimeout();
        timeout.start(0, 30000, 0);
        // Bytes progress before the demuxer can report a buffered duration (e.g. a large MP4).
        for (int second = 1; second <= 120; second++) {
            timeout.observe(second * 1000L, second * 1024L, 0);
            assertFalse(timeout.expired(second * 1000L));
        }
        assertFalse(timeout.expired(149999));
        assertTrue(timeout.expired(150000));
    }

    @Test public void frozenFirstFrameStillTimesOut() {
        var timeout = new PlaybackStartupTimeout();
        timeout.start(0, 30000, 0);
        timeout.progress(5000);
        assertFalse(timeout.expired(34999));
        assertTrue(timeout.expired(35000));
    }

    @Test public void nativePlayerBufferGrowthCountsWithoutTransferEvents() {
        var timeout = new PlaybackStartupTimeout();
        timeout.start(0, 30000, 0);
        timeout.observe(25000, 0, 100);
        timeout.observe(50000, 0, 200);
        assertFalse(timeout.expired(79000));
        assertTrue(timeout.expired(80000));
    }

    @Test public void unchangedReadsAndBufferDoNotExtendDeadline() {
        var timeout = new PlaybackStartupTimeout();
        timeout.start(0, 30000, 123);
        for (int second = 1; second <= 30; second++) timeout.observe(second * 1000L, 123, 0);
        assertTrue(timeout.expired(30000));
    }

    @Test public void nextItemDoesNotInheritPreviousItemsProgress() {
        var timeout = new PlaybackStartupTimeout();
        timeout.start(0, 30000, 0);
        timeout.observe(29000, 10000, 5000);
        timeout.start(30000, 30000, 10000);
        timeout.observe(59000, 10000, 0);
        assertTrue(timeout.expired(60000));
    }

    @Test public void cancelAndDisabledTimeoutIgnoreLateProgress() {
        var timeout = new PlaybackStartupTimeout();
        timeout.start(0, 30000, 0);
        timeout.cancel();
        timeout.observe(50000, 10000, 5000);
        assertFalse(timeout.isActive());
        assertFalse(timeout.expired(100000));
        timeout.start(0, -1, 0);
        assertFalse(timeout.expired(100000));
    }

    @Test public void longerSourceTimeoutIsRespected() {
        var timeout = new PlaybackStartupTimeout();
        timeout.start(0, 60000, 0);
        assertFalse(timeout.expired(59999));
        assertTrue(timeout.expired(60000));
    }
}
