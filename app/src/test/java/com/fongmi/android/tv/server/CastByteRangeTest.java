package com.fongmi.android.tv.server;

import org.junit.Test;
import static org.junit.Assert.*;

public class CastByteRangeTest {
    @Test public void validRangesUseLongOffsets() {
        assertEquals("bytes=2147483648-2999999999", CastByteRange.request("bytes=2147483648-", 3_000_000_000L).header());
        assertEquals("bytes=2999999900-2999999999", CastByteRange.request("bytes=-100", 3_000_000_000L).header());
        assertEquals("bytes=0-99", CastByteRange.request("bytes=-200", 100).header());
        assertEquals("bytes=0-99", CastByteRange.request("bytes=0-9999999999999", 100).header());
    }
    @Test public void unsatisfiableRangesRemainDistinguishableFromMalformedOnes() {
        assertFalse(CastByteRange.request("bytes=100-", 100).satisfiable());
        assertFalse(CastByteRange.request("bytes=-0", 100).satisfiable());
        assertFalse(CastByteRange.request("bytes=0-", 0).satisfiable());
        assertNull(CastByteRange.request("bytes=80-70", 100));
    }
    @Test public void malformedAndUnsupportedRangesCanBeIgnored() {
        for (String value : new String[]{"bytes=-", "bytes=0-1,5-6", "items=0-5", "bytes=9223372036854775808-", "bytes=x-y"})
            assertNull(value, CastByteRange.request(value, 100));
    }
    @Test public void onlyConsistentContentRangesSupplyLengths() {
        CastByteRange range = CastByteRange.response("bytes 2147483648-2999999999/3000000000");
        assertNotNull(range);
        assertEquals(852516352L, range.length());
        for (String value : new String[]{"bytes 0-100/100", "bytes 5-2/100", "bytes 0-100/*", "bytes */100", "bytes 0-9223372036854775808/100"})
            assertNull(value, CastByteRange.response(value));
    }
}
