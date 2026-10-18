package com.fongmi.android.tv.server;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;

public class LanAddressTest {
    @Test public void ethernetInUseWinsOverStaleWifiAndVpn() {
        assertEquals("192.168.31.42", LanAddress.select(List.of(
                new LanAddress.Candidate("192.168.8.9", true, false, false),
                new LanAddress.Candidate("10.8.0.2", false, true, true),
                new LanAddress.Candidate("192.168.31.42", true, true, true))));
    }
    @Test public void noLanNeverProducesLoopbackCellularOrInvalidQr() {
        for (String value : List.of("", "0.0.0.0", "127.0.0.1", "169.254.8.9", "224.0.0.1", "256.1.1.1", "fe80::1"))
            assertEquals(value, "", LanAddress.select(List.of(new LanAddress.Candidate(value, true, true, true))));
        assertEquals("", LanAddress.select(List.of(new LanAddress.Candidate("10.1.2.3", false, true, true))));
    }
}
