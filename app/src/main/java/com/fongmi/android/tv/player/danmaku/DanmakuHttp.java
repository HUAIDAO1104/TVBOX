package com.fongmi.android.tv.player.danmaku;

import com.fongmi.android.tv.App;

import java.io.File;
import java.util.concurrent.TimeUnit;

import okhttp3.Cache;
import okhttp3.OkHttpClient;

/** Isolated transport that respects provider cache validation. */
public final class DanmakuHttp {

    static final long CACHE_SIZE_BYTES = 48L * 1024L * 1024L;
    static final int CONNECT_TIMEOUT_SECONDS = 8;
    static final int READ_TIMEOUT_SECONDS = 42;
    static final int CALL_TIMEOUT_SECONDS = 45;

    private static volatile OkHttpClient client;

    private DanmakuHttp() {
    }

    public static OkHttpClient client() {
        if (client != null) return client;
        synchronized (DanmakuHttp.class) {
            if (client == null) client = createClient();
            return client;
        }
    }

    private static OkHttpClient createClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                // Use system TLS/DNS and a separate dispatcher; source plugins may replace
                // the player's DNS, proxy or interceptors. Alternate IPs/stale pooled
                // connections can recover, but the same 45 s call budget still applies.
                .retryOnConnectionFailure(true);
        try {
            File directory = new File(App.get().getCacheDir(), "danmaku_http_v2");
            builder.cache(new Cache(directory, CACHE_SIZE_BYTES));
        } catch (Throwable ignored) {
            // A read-only/full cache directory must never stop playback or danmaku loading.
        }
        return builder.build();
    }

}
