package com.fongmi.android.tv.utils;

final class DownloadRange {

    private DownloadRange() {
    }

    static boolean matches(String header, long offset, long length, long total) {
        return header != null && header.trim().equals("bytes " + offset + "-" + (offset + length - 1) + "/" + total);
    }

    static boolean startsAt(String header, long offset) {
        if (header == null) return false;
        try {
            java.util.regex.Matcher match = java.util.regex.Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)").matcher(header.trim());
            return match.matches() && Long.parseLong(match.group(1)) == offset
                    && Long.parseLong(match.group(2)) >= offset && Long.parseLong(match.group(3)) > Long.parseLong(match.group(2));
        } catch (RuntimeException ignored) { return false; }
    }

    static long totalLength(String contentRange, long contentLength, long existing) {
        try {
            if (contentRange != null) {
                int slash = contentRange.lastIndexOf('/');
                if (slash >= 0) return Long.parseLong(contentRange.substring(slash + 1).trim());
            }
        } catch (Exception ignored) {
        }
        return contentLength < 0L ? -1L : existing + contentLength;
    }
}
