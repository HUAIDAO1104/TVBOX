package com.fongmi.android.tv.utils;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DownloadRangeTest {

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
