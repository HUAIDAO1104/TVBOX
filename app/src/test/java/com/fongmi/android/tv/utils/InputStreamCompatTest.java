package com.fongmi.android.tv.utils;

import static org.junit.Assert.*;
import java.io.*;
import java.util.Arrays;
import org.junit.Test;

public class InputStreamCompatTest {
    @Test public void shortReadsPreserveBytesAndStopAtTheLimit() throws Exception {
        byte[] data = new byte[20001];
        for (int i = 0; i < data.length; i++) data[i] = (byte) i;
        InputStream input = new ByteArrayInputStream(data) {
            @Override public synchronized int read(byte[] b, int off, int len) {
                return super.read(b, off, Math.min(len, 3));
            }
        };
        assertArrayEquals(Arrays.copyOf(data, 16385), InputStreamCompat.readUpTo(input, 16385));
        assertEquals(data[16385] & 255, input.read());
    }

    @Test public void emptyAndExactSizeBodiesDoNotRequireAFullBuffer() throws Exception {
        assertArrayEquals(new byte[0], InputStreamCompat.readUpTo(new ByteArrayInputStream(new byte[0]), 10));
        byte[] data = {0, 1, -1};
        assertArrayEquals(data, InputStreamCompat.readUpTo(new ByteArrayInputStream(data), 100));
        assertArrayEquals(data, InputStreamCompat.readUpTo(new ByteArrayInputStream(data), 3));
    }

    @Test public void zeroProgressDoesNotSpinAndCallerOwnsClose() throws Exception {
        var input = new ByteArrayInputStream(new byte[]{1, 2, 3}) {
            boolean closed;
            @Override public synchronized int read(byte[] b, int off, int len) { return 0; }
            @Override public void close() { closed = true; }
        };
        assertArrayEquals(new byte[]{1, 2, 3}, InputStreamCompat.readUpTo(input, 10));
        assertFalse(input.closed);
    }

    @Test public void zeroLimitDoesNotReadAndTransportErrorsPropagate() throws Exception {
        var input = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("transport"); }
        };
        assertArrayEquals(new byte[0], InputStreamCompat.readUpTo(input, 0));
        IOException error = assertThrows(IOException.class, () -> InputStreamCompat.readUpTo(input, 1));
        assertEquals("transport", error.getMessage());
        assertThrows(IllegalArgumentException.class, () -> InputStreamCompat.readUpTo(input, -1));
    }
}
