package com.fongmi.android.tv.update;

import com.fongmi.android.tv.App;
import com.github.catvod.net.OkHttp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import okhttp3.Response;
import okhttp3.ResponseBody;

public final class UpdateClient {

    private static final long SOURCE_TIMEOUT_MS = 7_000L;

    private UpdateClient() {
    }

    public static UpdateManifest fetch() throws IOException {
        List<String> urls = UpdateSource.manifestCandidates();
        if (urls.isEmpty()) throw new IOException("No update source configured");
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(4, urls.size()));
        CompletionService<UpdateManifest> completion = new ExecutorCompletionService<>(executor);
        List<Future<UpdateManifest>> futures = new ArrayList<>();
        for (String url : urls) futures.add(completion.submit(() -> fetch(url)));
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SOURCE_TIMEOUT_MS + 1_000L);
        IOException last = new IOException("Update source unavailable");
        try {
            for (int i = 0; i < futures.size(); i++) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) break;
                Future<UpdateManifest> future = completion.poll(remaining, TimeUnit.NANOSECONDS);
                if (future == null) break;
                try {
                    return future.get();
                } catch (Exception error) {
                    last = new IOException("Update source unavailable", error);
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Update check interrupted", error);
        } finally {
            for (Future<UpdateManifest> future : futures) future.cancel(true);
            executor.shutdownNow();
        }
        throw last;
    }

    private static UpdateManifest fetch(String url) throws IOException {
        var client = OkHttp.client(SOURCE_TIMEOUT_MS).newBuilder()
                .callTimeout(SOURCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build();
        try (Response response = OkHttp.newCall(client, url).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) throw new IOException("HTTP " + response.code());
            return UpdateManifest.parse(App.gson(), body.string());
        }
    }
}
