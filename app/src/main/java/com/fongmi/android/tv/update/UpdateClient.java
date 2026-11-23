package com.fongmi.android.tv.update;

import com.fongmi.android.tv.App;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
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
        List<Callable<UpdateManifest>> sources = new ArrayList<>();
        for (String url : UpdateSource.manifestCandidates()) sources.add(() -> fetch(url));
        return newestAvailable(sources, SOURCE_TIMEOUT_MS + 1000);
    }

    static UpdateManifest newestAvailable(List<Callable<UpdateManifest>> sources, long timeoutMs) throws IOException {
        if (sources.isEmpty()) throw new IOException("No update source configured");
        // Start the official endpoint alongside the mirrors. A fast cached manifest may lag a release.
        ExecutorService executor = Executors.newFixedThreadPool(sources.size());
        CompletionService<UpdateManifest> completion = new ExecutorCompletionService<>(executor);
        List<Future<UpdateManifest>> futures = new ArrayList<>();
        for (var source : sources) futures.add(completion.submit(source));
        UpdateManifest newest = null;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        IOException last = new IOException("Update source unavailable");
        try {
            for (int i = 0; i < futures.size(); i++) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) break;
                Future<UpdateManifest> future = completion.poll(remaining, TimeUnit.NANOSECONDS);
                if (future == null) break;
                try {
                    UpdateManifest received = future.get();
                    if (newest == null || received.versionCode() > newest.versionCode()) newest = received;
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
        if (newest != null) return newest;
        throw last;
    }

    private static UpdateManifest fetch(String url) throws IOException {
        var client = UpdateNetwork.client().newBuilder()
                .callTimeout(SOURCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build();
        try (Response response = client.newCall(new okhttp3.Request.Builder().url(url).build()).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) throw new IOException("HTTP " + response.code());
            return UpdateManifest.parse(App.gson(), body.string());
        }
    }
}
