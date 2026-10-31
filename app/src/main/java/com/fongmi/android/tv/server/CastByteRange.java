package com.fongmi.android.tv.server;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** HTTP byte offsets stay 64-bit, including MKV indexes beyond 2 GiB. */
final class CastByteRange {
    private static final Pattern REQUEST = Pattern.compile("(?i)bytes=([0-9]*)-([0-9]*)");
    private static final Pattern RESPONSE = Pattern.compile("(?i)bytes ([0-9]+)-([0-9]+)/([0-9]+)");
    final long start, end, total;

    private CastByteRange(long start, long end, long total) {
        this.start = start; this.end = end; this.total = total;
    }

    /** null means a malformed/multipart/unknown-unit range: RFC 9110 allows ignoring it. */
    static CastByteRange request(String value, long total) {
        if (value == null || total < 0) return null;
        Matcher match = REQUEST.matcher(value.trim());
        if (!match.matches() || (match.group(1).isEmpty() && match.group(2).isEmpty())) return null;
        try {
            long start, end;
            if (match.group(1).isEmpty()) {
                long suffix = Long.parseLong(match.group(2));
                start = Math.max(0, total - suffix); end = total - 1;
            } else {
                start = Long.parseLong(match.group(1));
                end = match.group(2).isEmpty() ? total - 1 : Long.parseLong(match.group(2));
                if (!match.group(2).isEmpty() && end < start) return null;
                end = Math.min(end, total - 1);
            }
            return new CastByteRange(start, end, total);
        } catch (NumberFormatException invalid) { return null; }
    }

    static CastByteRange response(String value) {
        if (value == null) return null;
        Matcher match = RESPONSE.matcher(value.trim());
        if (!match.matches()) return null;
        try {
            long start = Long.parseLong(match.group(1)), end = Long.parseLong(match.group(2)), total = Long.parseLong(match.group(3));
            return start <= end && end < total ? new CastByteRange(start, end, total) : null;
        } catch (NumberFormatException invalid) { return null; }
    }

    boolean satisfiable() { return start <= end && start < total; }
    long length() { return end - start + 1; }
    String header() { return "bytes=" + start + "-" + end; }
    String contentRange() { return "bytes " + start + "-" + end + "/" + total; }
}
