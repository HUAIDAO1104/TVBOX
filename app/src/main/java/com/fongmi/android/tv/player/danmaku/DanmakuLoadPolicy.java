package com.fongmi.android.tv.player.danmaku;

import androidx.media3.datasource.HttpDataSource;

import java.io.IOException;

/** Keeps retries useful and bounded; a timeout is never repeated behind the user's back. */
public final class DanmakuLoadPolicy {

    private DanmakuLoadPolicy() {
    }

    public static boolean shouldRetry(IOException error, int retryCount) {
        if (retryCount >= 1 || error == null) return false;
        int responseCode;
        if (error instanceof HttpDataSource.InvalidResponseCodeException) {
            responseCode = ((HttpDataSource.InvalidResponseCodeException) error).responseCode;
        } else if (error instanceof DanmakuDocumentCache.HttpStatusException) {
            responseCode = ((DanmakuDocumentCache.HttpStatusException) error).statusCode();
        } else {
            return false;
        }
        return shouldRetryResponseCode(responseCode);
    }

    static boolean shouldRetryResponseCode(int responseCode) {
        return responseCode == 408 || responseCode == 429 || responseCode >= 500;
    }
}
