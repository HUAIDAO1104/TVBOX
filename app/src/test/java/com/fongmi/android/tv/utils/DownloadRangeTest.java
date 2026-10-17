package com.fongmi.android.tv.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DownloadRangeTest {

    @Test public void rejectsWrongOffsetsTotalsAndInvalidRanges() {
        assertTrue(DownloadRange.matches("bytes 262144-1310719/5000000", 262144, 1048576, 5000000));
        assertFalse(DownloadRange.matches("bytes 0-1048575/5000000", 262144, 1048576, 5000000));
        assertFalse(DownloadRange.matches("bytes 262144-1310719/5000001", 262144, 1048576, 5000000));
        assertTrue(DownloadRange.startsAt("bytes 262144-4999999/5000000", 262144));
        assertFalse(DownloadRange.startsAt("bytes 262144-262143/5000000", 262144));
        assertFalse(DownloadRange.startsAt("bytes 262144-5000000/5000000", 262144));
        assertFalse(DownloadRange.startsAt("bytes 9999999999999999999999-9/10", 262144));
    }

    @Test
    public void readsFullSizeFromPartialContentRange() {
        assertEquals(43_341_505L, DownloadRange.totalLength("bytes 1048576-2097151/43341505", 1_048_576L, 1_048_576L));
    }

    @Test
    public void fallsBackToExistingPlusResponseLength() {
        assertEquals(3_145_728L, DownloadRange.totalLength(null, 2_097_152L, 1_048_576L));
        assertEquals(-1L, DownloadRange.totalLength("invalid", -1L, 1_048_576L));
    }
}
