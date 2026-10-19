package com.fongmi.android.tv.utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** Bounded reads using APIs available on every supported Android version. */
public final class InputStreamCompat {
    private InputStreamCompat() { }

    /** Reads up to limit bytes, without closing the stream or reading past the limit. */
    public static byte[] readUpTo(InputStream input, int limit) throws IOException {
        Objects.requireNonNull(input, "input");
        if (limit < 0) throw new IllegalArgumentException("limit < 0");
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[Math.min(limit, 8192)];
        while (output.size() < limit) {
            int count = input.read(buffer, 0, Math.min(buffer.length, limit - output.size()));
            if (count < 0) break;
            if (count == 0) {
                // Avoid spinning on streams that temporarily return no bytes.
                int value = input.read();
                if (value < 0) break;
                output.write(value);
            } else output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
}
