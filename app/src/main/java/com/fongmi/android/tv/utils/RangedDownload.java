package com.fongmi.android.tv.utils;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.*;

/** Bounded, verified byte ranges. Only contiguous completed bytes are committed for resume. */
final class RangedDownload {
    private static final int SAMPLE = 256 * 1024, CHUNK = 1024 * 1024, PARALLEL = 3;
    interface Progress { void update(String url, long speed, long downloaded, long total); }
    private record Part(String url, long offset, byte[] bytes) { }
    private final OkHttpClient client;
    private final Set<Call> calls = ConcurrentHashMap.newKeySet();
    private final Set<String> slowMirrors = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    RangedDownload(OkHttpClient client) {
        this.client = client.newBuilder().connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build();
    }
    void cancel() { cancelled.set(true); for (Call call : calls) call.cancel(); }
    private void check() throws InterruptedIOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Download cancelled");
    }
    // False means that no mirror offered a valid range; caller retains the ordinary HTTP path.
    boolean transfer(List<String> urls, File file, long size, Progress progress) throws IOException {
        if (urls.isEmpty() || size <= 0) return false;
        check();
        long existing = file.isFile() ? file.length() : 0;
        if (existing >= size) return false;
        long started = System.nanoTime();
        Part sample = select(urls, existing, Math.min(SAMPLE, size - existing), size);
        if (sample == null) { check(); return false; }
        List<String> ordered = new ArrayList<>(urls); ordered.remove(sample.url); ordered.add(0, sample.url);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Cannot create download directory");
        ExecutorService workers = Executors.newFixedThreadPool(PARALLEL);
        ArrayDeque<Future<Part>> pending = new ArrayDeque<>();
        long committed = existing;
        try (FileOutputStream output = new FileOutputStream(file, existing > 0)) {
            check(); output.write(sample.bytes); committed += sample.bytes.length;
            progress.update(sample.url, speed(committed - existing, started), committed, size);
            long next = committed;
            while (committed < size) {
                check();
                while (pending.size() < PARALLEL && next < size) {
                    long offset = next, length = Math.min(CHUNK, size - next); next += length;
                    pending.add(workers.submit(() -> fetchWithFallback(ordered, offset, length, size)));
                }
                Part part;
                try { part = pending.removeFirst().get(); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new InterruptedIOException("Download cancelled"); }
                catch (ExecutionException error) { throw new IOException("Update range failed", error.getCause()); }
                check();
                if (part.offset != committed) throw new IOException("Non-contiguous update fragment");
                output.write(part.bytes); committed += part.bytes.length;
                progress.update(part.url, speed(committed - existing, started), committed, size);
            }
            output.getFD().sync();
            return committed == size;
        } finally {
            for (Future<?> task : pending) task.cancel(true);
            workers.shutdownNow();
            for (Call call : calls) call.cancel();
        }
    }
    private static long speed(long bytes, long started) {
        return bytes * 1000 / Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }
    private Part select(List<String> urls, long offset, long length, long size) throws IOException {
        ExecutorService probes = Executors.newFixedThreadPool(Math.min(4, urls.size()));
        CompletionService<Part> ready = new ExecutorCompletionService<>(probes);
        List<Future<Part>> tasks = new ArrayList<>();
        Set<Call> probeCalls = ConcurrentHashMap.newKeySet();
        for (String url : urls) tasks.add(ready.submit(() -> fetch(url, offset, length, size, probeCalls, 4000)));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        try {
            for (int i = 0; i < urls.size(); i++) {
                check(); long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                Future<Part> next = ready.poll(remaining, TimeUnit.NANOSECONDS);
                if (next == null) break;
                try { return next.get(); } catch (ExecutionException ignored) { }
            }
            return null;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new InterruptedIOException("Download cancelled");
        } finally {
            tasks.forEach(task -> task.cancel(true));
            for (Call call : probeCalls) call.cancel();
            probes.shutdownNow();
        }
    }
    private Part fetchWithFallback(List<String> urls, long offset, long length, long size) throws IOException {
        IOException last = null;
        // A mirror that slows down after the first packet only costs one bounded chunk request.
        List<String> ranked = new ArrayList<>(urls);
        ranked.sort(Comparator.comparing(slowMirrors::contains));
        for (String url : ranked) {
            check();
            try { return fetch(url, offset, length, size, null, 15000); }
            catch (IOException error) { last = error; slowMirrors.add(url); }
        }
        throw last == null ? new IOException("No update mirror") : last;
    }
    private Part fetch(String url, long offset, long length, long size, Set<Call> group, long timeout) throws IOException {
        check();
        Call call = client.newCall(new Request.Builder().url(url).header("Accept-Encoding", "identity")
                .header("Range", "bytes=" + offset + "-" + (offset + length - 1)).build());
        call.timeout().timeout(timeout, TimeUnit.MILLISECONDS);
        calls.add(call); if (group != null) group.add(call);
        try {
            check();
            try (Response response = call.execute()) {
                ResponseBody body = response.body();
                if (response.code() != 206 || body == null || !DownloadRange.matches(response.header("Content-Range"), offset, length, size))
                    throw new IOException("Mirror does not support the requested update range");
                if (body.contentLength() >= 0 && body.contentLength() != length) throw new IOException("Invalid update range length");
                byte[] bytes = new byte[(int) length]; int read = 0;
                long bodyStarted = System.nanoTime();
                try (InputStream input = body.byteStream()) {
                    while (read < bytes.length) {
                        check(); int count = input.read(bytes, read, bytes.length - read);
                        if (count < 0) throw new EOFException("Truncated update range");
                        read += count;
                        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - bodyStarted);
                        if (group == null && elapsed >= 3500 && read * 1000L / elapsed < 128 * 1024L)
                            throw new IOException("Mirror slowed down; trying another source");
                    }
                    if (input.read() != -1) throw new IOException("Oversized update range");
                }
                return new Part(url, offset, bytes);
            }
        } finally { calls.remove(call); if (group != null) group.remove(call); }
    }
}
