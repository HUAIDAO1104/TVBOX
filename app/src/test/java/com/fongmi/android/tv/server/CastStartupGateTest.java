package com.fongmi.android.tv.server;

import org.junit.Test;
import static org.junit.Assert.*;

public class CastStartupGateTest {
    @Test public void downloadingAndTransitioningDoNotTriggerAnEarlySeek() {
        CastStartupGate gate = new CastStartupGate();
        assertFalse(gate.observe(0, true, false, 0));
        assertFalse(gate.observe(10000, true, false, 0));
        assertFalse(gate.shouldResume(true, 120000));
        assertFalse(gate.observe(11000, true, true, 0));
        assertTrue(gate.observe(12000, true, true, 1));
        assertTrue(gate.shouldResume(true, 120000));
        assertFalse(gate.shouldResume(false, 120000));
    }

    @Test public void unsupportedPositionQueryCannotForceUnseekableVideosToBufferToOldPosition() {
        CastStartupGate gate = new CastStartupGate();
        assertFalse(gate.observe(100, true, true, -1));
        assertFalse(gate.observe(1000, true, true, -1));
        assertTrue(gate.observe(1600, true, true, -1));
        assertFalse(gate.shouldResume(true, 120000));
    }

    @Test public void metadataAndKeyRequestsCannotConfirmPlayback() {
        CastStartupGate gate = new CastStartupGate();
        assertFalse(gate.observe(0, false, true, 1));
        assertFalse(gate.observe(10000, false, true, 2));
        assertFalse(gate.shouldResume(true, 120000));
    }
}
