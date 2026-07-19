package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.App;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;
import com.google.common.net.HttpHeaders;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class Download {

    // Small range probes overvalue mirrors with a quick first packet but aggressive sustained
    // throttling. Sample enough data to measure real throughput while keeping the same short
    // selection window before an update starts.
    private static final int PROBE_BYTES = 768 * 1024;
    private static final long PROBE_DEADLINE_MS = 6_500L;
    private static final long PROGRESS_INTERVAL_MS = 650L;

    private final File file;
    private final List<String> urls;
    private Callback callback;
    private Future<?> future;
    private String sha256;
    private long connectTimeout;
    private long readTimeout;
    private String tag;
    private boolean fastestFirst;
    private boolean resume;

    public static Download create(String url, File file) {
        return new Download(List.of(url), file);
    }

    public static Download create(List<String> urls, File file) {
        return new Download(urls, file);
    }

    public Download(String url, File file) {
        this(List.of(url), file);
    }

    public Download(List<String> urls, File file) {
        this.urls = new ArrayList<>(urls);
        this.tag = this.urls.isEmpty() ? "download" : this.urls.get(0);
        this.file = file;
    }

    public Download tag(String tag) {
        this.tag = tag;
        return this;
    }

    public Download sha256(String sha256) {
        this.sha256 = sha256;
        return this;
    }

    public Download timeout(long timeout) {
        this.connectTimeout = timeout;
        this.readTimeout = timeout;
        return this;
    }

    public Download connectTimeout(long timeout) {
        this.connectTimeout = timeout;
        return this;
    }

    public Download readTimeout(long timeout) {
        this.readTimeout = timeout;
        return this;
    }

    public Download fastestFirst() {
        this.fastestFirst = true;
        return this;
    }

    public Download resume() {
        this.resume = true;
        return this;
    }

    public File get() {
        doInBackground();
        return file;
    }

    public void start(Callback callback) {
        this.callback = callback;
        future = Task.submit(this::doInBackground);
    }

    public Download cancel() {
        if (future != null) future.cancel(true);
        OkHttp.cancel(tag);
        future = null;
        return this;
    }

    private void doInBackground() {
        Exception last = null;
        prepareResumeFile();
        if (isComplete()) {
            complete();
            return;
        }
        List<String> candidates = fastestFirst ? probeAndOrder() : new ArrayList<>(urls);
        for (String url : candidates) {
            if (Thread.currentThread().isInterrupted()) return;
            try {
                download(url);
                if (sha256 != null && !sha256.isBlank() && !com.fongmi.android.tv.update.UpdateVerifier.checksumMatches(file, sha256)) {
                    Path.clear(file);
                    throw new IOException("APK checksum mismatch; retrying from the beginning");
                }
                complete();
                return;
            } catch (Exception e) {
                last = e;
            }
        }
        if (Thread.currentThread().isInterrupted()) return;
        Exception error = last == null ? new IOException("No download source") : last;
        if (callback != null) App.post(() -> callback.error(error.getMessage()));
        else throw new RuntimeException(error.getMessage(), error);
    }

    private void download(String url) throws IOException {
        long existing = resume && file.isFile() ? file.length() : 0L;
        OkHttpClient client = client();
        Request.Builder request = new Request.Builder().url(url).tag(tag);
        if (existing > 0L) request.header(HttpHeaders.RANGE, "bytes=" + existing + "-");
        try (Response response = client.newCall(request.build()).execute()) {
            if (response.code() == 416) {
                if (isComplete()) return;
                Path.clear(file);
                throw new IOException("Saved update fragment is no longer resumable");
            }
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) throw new IOException("HTTP " + response.code());
            boolean append = existing > 0L && response.code() == 206;
            if (!append) existing = 0L;
            long total = getTotalLength(response, existing);
            notifyStatus(url, 0L, existing, total);
            download(body.byteStream(), total, existing, append, url);
        }
    }

    private OkHttpClient client() {
        OkHttpClient.Builder builder = OkHttp.client().newBuilder();
        if (connectTimeout > 0L) builder.connectTimeout(connectTimeout, TimeUnit.MILLISECONDS);
        if (readTimeout > 0L) builder.readTimeout(readTimeout, TimeUnit.MILLISECONDS);
        return builder.build();
    }

    private void download(InputStream is, long total, long existing, boolean append, String url) throws IOException {
        try (BufferedInputStream input = new BufferedInputStream(is); FileOutputStream os = new FileOutputStream(Path.create(file), append)) {
            byte[] buffer = new byte[16384];
            int readBytes;
            long totalBytes = existing;
            long sampleBytes = existing;
            long sampleAt = System.currentTimeMillis();
            while ((readBytes = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Download cancelled");
                totalBytes += readBytes;
                os.write(buffer, 0, readBytes);
                long now = System.currentTimeMillis();
                if (now - sampleAt >= PROGRESS_INTERVAL_MS) {
                    long speed = Math.max(0L, (totalBytes - sampleBytes) * 1000L / Math.max(1L, now - sampleAt));
                    notifyStatus(url, speed, totalBytes, total);
                    sampleBytes = totalBytes;
                    sampleAt = now;
                }
                if (total > 0L) notifyProgress((int) Math.min(100L, totalBytes * 100L / total));
            }
        }
    }

    private long getTotalLength(Response response, long existing) {
        String contentRange = response.header(HttpHeaders.CONTENT_RANGE);
        long contentLength = response.body() == null ? -1L : response.body().contentLength();
        return DownloadRange.totalLength(contentRange, contentLength, existing);
    }

    private List<String> probeAndOrder() {
        if (urls.size() < 2) return new ArrayList<>(urls);
        int workers = Math.min(4, urls.size());
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CompletionService<Probe> completion = new ExecutorCompletionService<>(executor);
        for (int index = 0; index < urls.size(); index++) {
            final int order = index;
            final String url = urls.get(index);
            completion.submit(() -> probe(url, order));
        }
        List<Probe> probes = new ArrayList<>();
        long deadline = System.currentTimeMillis() + PROBE_DEADLINE_MS;
        try {
            for (int count = 0; count < urls.size(); count++) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) break;
                Future<Probe> future = completion.poll(remaining, TimeUnit.MILLISECONDS);
                if (future == null) break;
                try {
                    probes.add(future.get());
                } catch (Exception ignored) {
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdownNow();
        }
        probes.sort(Comparator.comparingLong(Probe::bytesPerSecond).reversed().thenComparingInt(Probe::order));
        Set<String> ordered = new LinkedHashSet<>();
        for (Probe probe : probes) if (probe.bytesPerSecond() > 0L) ordered.add(probe.url());
        ordered.addAll(urls);
        return new ArrayList<>(ordered);
    }

    private Probe probe(String url, int order) {
        long start = System.nanoTime();
        long bytes = 0L;
        OkHttpClient client = OkHttp.client().newBuilder()
                .connectTimeout(3_000L, TimeUnit.MILLISECONDS)
                .readTimeout(5_000L, TimeUnit.MILLISECONDS)
                .callTimeout(6_000L, TimeUnit.MILLISECONDS)
                .build();
        Request request = new Request.Builder().url(url).header(HttpHeaders.RANGE, "bytes=0-" + (PROBE_BYTES - 1)).tag(tag).build();
        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) return new Probe(url, order, 0L);
            try (InputStream stream = body.byteStream()) {
                byte[] buffer = new byte[8192];
                while (bytes < PROBE_BYTES) {
                    int read = stream.read(buffer, 0, (int) Math.min(buffer.length, PROBE_BYTES - bytes));
                    if (read < 0) break;
                    bytes += read;
                }
            }
        } catch (Exception ignored) {
            return new Probe(url, order, 0L);
        }
        long elapsedMs = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        return new Probe(url, order, bytes * 1000L / elapsedMs);
    }

    private void prepareResumeFile() {
        if (!resume || sha256 == null || sha256.isBlank()) return;
        File marker = marker();
        if (sha256.equalsIgnoreCase(readMarker(marker))) return;
        Path.clear(file);
        writeMarker(marker, sha256);
    }

    private boolean isComplete() {
        return file.isFile() && sha256 != null && !sha256.isBlank()
                && com.fongmi.android.tv.update.UpdateVerifier.checksumMatches(file, sha256);
    }

    private void complete() {
        if (resume) marker().delete();
        if (callback != null) App.post(() -> callback.success(file));
    }

    private File marker() {
        return new File(file.getAbsolutePath() + ".sha256");
    }

    private String readMarker(File marker) {
        if (!marker.isFile()) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(marker), StandardCharsets.UTF_8))) {
            return reader.readLine();
        } catch (Exception ignored) {
            return "";
        }
    }

    private void writeMarker(File marker, String value) {
        try (FileOutputStream stream = new FileOutputStream(Path.create(marker))) {
            stream.write(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private void notifyProgress(int value) {
        if (callback != null) App.post(() -> callback.progress(value));
    }

    private void notifyStatus(String source, long bytesPerSecond, long downloaded, long total) {
        if (callback != null) App.post(() -> callback.status(source, bytesPerSecond, downloaded, total));
    }

    private static final class Probe {

        private final String url;
        private final int order;
        private final long bytesPerSecond;

        private Probe(String url, int order, long bytesPerSecond) {
            this.url = url;
            this.order = order;
            this.bytesPerSecond = bytesPerSecond;
        }

        private String url() {
            return url;
        }

        private int order() {
            return order;
        }

        private long bytesPerSecond() {
            return bytesPerSecond;
        }
    }

    public interface Callback {

        void progress(int progress);

        default void status(String source, long bytesPerSecond, long downloaded, long total) {
        }

        void error(String msg);

        void success(File file);
    }
}
