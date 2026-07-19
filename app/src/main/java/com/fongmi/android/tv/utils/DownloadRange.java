package com.fongmi.android.tv.utils;

final class DownloadRange {

    private DownloadRange() {
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
