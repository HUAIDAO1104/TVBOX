package com.fongmi.android.tv.player.danmaku;

import com.fongmi.android.tv.App;
import com.github.catvod.net.OkHttp;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Cache;
import okhttp3.OkHttpClient;
import okhttp3.Response;

/** Network policy for immutable, episode-specific danmaku documents. */
public final class DanmakuHttp {

    static final long CACHE_SIZE_BYTES = 48L * 1024L * 1024L;
    static final int CONNECT_TIMEOUT_SECONDS = 6;
    static final int READ_TIMEOUT_SECONDS = 24;
    static final int CALL_TIMEOUT_SECONDS = 26;
    static final int CACHE_MAX_AGE_SECONDS = 7 * 24 * 60 * 60;

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
        OkHttpClient.Builder builder = OkHttp.player().newBuilder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                // The shared player client can retry a slow route invisibly. For a large XML
                // document that turns one bad request into a minute-long blank danmaku layer.
                .retryOnConnectionFailure(false)
                .addNetworkInterceptor(chain -> {
                    Response response = chain.proceed(chain.request());
                    if (!response.isSuccessful() || !isImmutableCommentUrl(chain.request().url().toString())) {
                        return response;
                    }
                    // The provider currently replies with "Cache-Control: no-cache" even though
                    // a comment id identifies one immutable episode. Revalidate-free local cache
                    // makes revisits and service/view re-attachments immediate and keeps playback
                    // independent of a temporary provider slowdown.
                    return response.newBuilder()
                            .removeHeader("Pragma")
                            .header("Cache-Control", "public, max-age=" + CACHE_MAX_AGE_SECONDS + ", immutable")
                            .build();
                });
        try {
            File directory = new File(App.get().getCacheDir(), "danmaku_http");
            builder.cache(new Cache(directory, CACHE_SIZE_BYTES));
        } catch (Throwable ignored) {
            // A read-only/full cache directory must never stop playback or danmaku loading.
        }
        return builder.build();
    }

    static boolean isImmutableCommentUrl(String value) {
        String url = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return url.startsWith("http")
                && (url.contains("/api/v2/comment/") || url.contains("/api/v1/comment/"))
                && (url.contains("format=xml") || url.endsWith(".xml"));
    }
}
