package com.fongmi.android.tv.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.IntConsumer;
import okhttp3.*;

/** Bounded, cancellable transport for public catalogue/comment protocols. */
final class NativeDanmakuRequest implements NativeDanmaku.Job {
    static final ExecutorService SEARCH = Executors.newFixedThreadPool(3);
    static final ExecutorService DOCUMENTS = Executors.newFixedThreadPool(2);
    private static final ExecutorService SEGMENTS = Executors.newFixedThreadPool(4);
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor();
    static final OkHttpClient CLIENT = new OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).retryOnConnectionFailure(false).build();
    private final OkHttpClient client;
    private final String referer;
    private final long deadline;
    private final AtomicBoolean finished = new AtomicBoolean();
    private final Set<Call> calls = ConcurrentHashMap.newKeySet();
    private final Set<Future<?>> tasks = ConcurrentHashMap.newKeySet();
    private volatile Future<?> timer;
    NativeDanmakuRequest(long millis, String referer, OkHttpClient client) {
        this.client = client; this.referer = referer;
        deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
    }
    long remaining() { return Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())); }
    void check() throws IOException {
        if (finished.get() || Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline)
            throw new IOException("Platform request cancelled or timed out");
    }
    <T> void start(ExecutorService executor, Callable<T> action, IqiyiDanmaku.Callback<T> callback) {
        timer = TIMER.schedule(() -> {
            if (finished.compareAndSet(false, true)) { cleanup(); callback.onFailure(new IOException("Platform request timed out")); }
        }, remaining(), TimeUnit.MILLISECONDS);
        submit(executor, () -> {
            try {
                check(); T value = action.call(); check();
                if (finished.compareAndSet(false, true)) { timer.cancel(false); callback.onSuccess(value); }
            } catch (Exception e) {
                if (finished.compareAndSet(false, true)) { cleanup(); callback.onFailure(e instanceof IOException ? (IOException) e : new IOException("Platform request failed", e)); }
            }
        });
    }
    private Future<?> submit(ExecutorService executor, Runnable action) {
        Future<?> task = executor.submit(action); tasks.add(task);
        if (finished.get()) task.cancel(true); return task;
    }
    byte[] bytes(String url, int limit) throws IOException { return bytes(new Request.Builder().url(url).build(), limit); }
    byte[] bytes(Request request, int limit) throws IOException {
        check();
        Call call = client.newCall(request.newBuilder().header("User-Agent", "Mozilla/5.0")
                .header("Referer", referer).build());
        call.timeout().timeout(Math.min(12000, remaining()), TimeUnit.MILLISECONDS); calls.add(call);
        try {
            check();
            try (Response response = call.execute()) {
                if (!response.isSuccessful() || response.body() == null) throw new IOException("Platform HTTP " + response.code());
                return IqiyiDanmakuCodec.readBounded(response.body().byteStream(), limit);
            }
        } finally { calls.remove(call); }
    }
    String text(String url) throws IOException { return new String(bytes(url, 4_000_000), StandardCharsets.UTF_8); }
    interface Segment { List<IqiyiDanmakuCodec.Comment> get(int index) throws IOException; }
    byte[] segments(int count, IntConsumer progress, Segment fetch) throws Exception {
        if (count < 1 || count > 360) throw new IOException("Invalid platform timeline");
        List<List<IqiyiDanmakuCodec.Comment>> values = new ArrayList<>(Collections.nCopies(count, null));
        AtomicInteger next = new AtomicInteger(), done = new AtomicInteger(), chars = new AtomicInteger();
        List<Future<?>> workers = new ArrayList<>(); int limit = Math.min(2000, 150_000 / count);
        for (int i = 0; i < Math.min(count, 4); i++) workers.add(submit(SEGMENTS, () -> {
            try {
                for (int index; (index = next.getAndIncrement()) < count;) {
                    check(); List<IqiyiDanmakuCodec.Comment> rows;
                    try { rows = fetch.get(index); }
                    catch (IOException first) {
                        // One bounded retry absorbs a transient segment failure; the original
                        // overall deadline and complete-document boundary still apply.
                        check(); rows = fetch.get(index);
                    }
                    List<IqiyiDanmakuCodec.Comment> sampled = new ArrayList<>();
                    int size = Math.min(limit, rows.size());
                    for (int j = 0; j < size; j++) sampled.add(rows.get((int) ((long) j * rows.size() / size)));
                    for (var row : sampled) if (chars.addAndGet(row.text().length()) > 8_000_000) throw new IOException("Comments exceed text limit");
                    synchronized (values) { values.set(index, sampled); }
                    if (!finished.get()) progress.accept(done.incrementAndGet() * 95 / count);
                }
            } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
        }));
        for (Future<?> worker : workers) worker.get(remaining(), TimeUnit.MILLISECONDS);
        check(); List<IqiyiDanmakuCodec.Comment> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (var rows : values) for (var row : rows) if (seen.add(row.seconds() + "|" + row.color() + "|" + row.text())) all.add(row);
        return IqiyiDanmakuCodec.document(all);
    }
    public void cancel() { finished.set(true); cleanup(); }
    private void cleanup() {
        if (timer != null) timer.cancel(false);
        for (Call call : calls) call.cancel();
        for (Future<?> task : tasks) task.cancel(true);
    }
}
