package com.fongmi.android.tv.api;

import com.fongmi.android.tv.bean.Danmaku;
import com.github.catvod.net.OkHttp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Native public-platform fallback, with separate search and bounded segment workers. */
public final class IqiyiDanmaku {
    private static final ExecutorService SEARCH = Executors.newFixedThreadPool(2);
    private static final ExecutorService DOCUMENTS = Executors.newFixedThreadPool(2);
    private static final ExecutorService SEGMENTS = Executors.newFixedThreadPool(4);
    private static final String CLIENT_ID = java.util.UUID.randomUUID().toString().replace("-", "");
    private static final java.util.LinkedHashMap<String, Catalogue> CATALOGUES = new java.util.LinkedHashMap<>();
    private record Catalogue(long created, List<Danmaku> rows) { }
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/133.0.0.0 Safari/537.36";

    private static final class Client {
        static final OkHttpClient INSTANCE = OkHttp.client().newBuilder()
                .dispatcher(new okhttp3.Dispatcher()).connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false).build();
    }

    public interface Callback<T> {
        void onSuccess(T value);
        void onFailure(IOException error);
    }

    private IqiyiDanmaku() { }

    public static Job search(String title, Callback<List<Danmaku>> callback) {
        return search(title, callback, Client.INSTANCE);
    }

    static Job search(String title, Callback<List<Danmaku>> callback, OkHttpClient client) {
        Job job = new Job(15_000, client);
        String query = com.github.catvod.utils.Trans.t2s(title.trim());
        job.submit(SEARCH, () -> {
            try {
                List<Danmaku> cached = cachedCatalogue(query);
                if (cached != null) {
                    job.check();
                    callback.onSuccess(cached);
                    return;
                }
                HttpUrl.Builder url = HttpUrl.get("https://mesh.if.iqiyi.com/portal/lw/search/homePageV3").newBuilder();
                String[][] params = {{"key", query}, {"current_page", "1"}, {"mode", "1"}, {"source", "input"},
                        {"suggest", ""}, {"pcv", "13.074.22699"}, {"version", "13.074.22699"}, {"pageNum", "1"},
                        {"pageSize", "25"}, {"pu", ""}, {"u", CLIENT_ID}, {"scale", "200"},
                        {"token", ""}, {"userVip", "0"}, {"conduit", ""}, {"vipType", "-1"}, {"os", ""},
                        {"osShortName", "win10"}, {"dataType", ""}, {"appMode", ""},
                        {"ad", "{\"lm\":3,\"azd\":1000000000951,\"azt\":733,\"position\":\"feed\"}"},
                        {"adExt", "{\"r\":\"2.1.5-ares6-pure\"}"}};
                for (String[] param : params) url.addQueryParameter(param[0], param[1]);
                List<Danmaku> rows = IqiyiDanmakuCodec.catalogue(new String(job.get(url.build().toString(),
                        8 * 1024 * 1024, false), StandardCharsets.UTF_8));
                job.check();
                if (!rows.isEmpty()) {
                    synchronized (CATALOGUES) {
                        CATALOGUES.put(query, new Catalogue(System.nanoTime(), List.copyOf(rows)));
                        while (CATALOGUES.size() > 12) CATALOGUES.remove(CATALOGUES.keySet().iterator().next());
                    }
                }
                callback.onSuccess(copy(rows));
            } catch (Exception error) {
                if (!job.cancelled) {
                    if (com.fongmi.android.tv.BuildConfig.DEBUG) android.util.Log.w("IqiyiDanmaku", "Search failed", error);
                    callback.onFailure(asIo(error));
                }
            }
        });
        return job;
    }

    private static List<Danmaku> cachedCatalogue(String title) {
        synchronized (CATALOGUES) {
            Catalogue cached = CATALOGUES.get(title);
            if (cached == null) return null;
            if (System.nanoTime() - cached.created() > TimeUnit.MINUTES.toNanos(5)) {
                CATALOGUES.remove(title);
                return null;
            }
            return copy(cached.rows());
        }
    }

    private static List<Danmaku> copy(List<Danmaku> rows) {
        List<Danmaku> copy = new ArrayList<>(rows.size());
        for (Danmaku row : rows) {
            Danmaku item = new Danmaku();
            item.setName(row.getName()); item.setUrl(row.getUrl()); item.setSourceKey(row.getSourceKey());
            copy.add(item);
        }
        return copy;
    }

    public static Job document(String source, IntConsumer progress, Callback<byte[]> callback) {
        return document(source, progress, callback, Client.INSTANCE);
    }

    static Job document(String source, IntConsumer progress, Callback<byte[]> callback, OkHttpClient client) {
        Job job = new Job(40_000, client);
        job.submit(DOCUMENTS, () -> {
            try {
                String id = IqiyiDanmakuCodec.videoId(source);
                int duration = IqiyiDanmakuCodec.duration(new String(job.get(
                        "https://pcw-api.iqiyi.com/video/video/baseinfo/" + id, 1024 * 1024, false), StandardCharsets.UTF_8));
                int pages = (duration + 59) / 60;
                AtomicInteger next = new AtomicInteger(1), done = new AtomicInteger();
                AtomicInteger count = new AtomicInteger(), characters = new AtomicInteger();
                List<List<IqiyiDanmakuCodec.Comment>> segments = new ArrayList<>(java.util.Collections.nCopies(pages, null));
                List<Future<?>> workers = new ArrayList<>();
                for (int i = 0; i < Math.min(4, pages); i++) {
                    workers.add(job.submit(SEGMENTS, () -> {
                        for (int page; (page = next.getAndIncrement()) <= pages;) {
                            try {
                                job.check();
                                byte[] bytes = job.get(IqiyiDanmakuCodec.segmentUrl(id, page), 2 * 1024 * 1024, true);
                                List<IqiyiDanmakuCodec.Comment> comments = bytes.length == 0 ? List.of() : IqiyiDanmakuCodec.decode(bytes);
                                int textSize = 0;
                                for (var comment : comments) textSize += comment.text().length();
                                if (count.addAndGet(comments.size()) > 150_000
                                        || characters.addAndGet(textSize) > 8 * 1024 * 1024) {
                                    throw new IOException("iQiyi comments exceed document limit");
                                }
                                synchronized (segments) { segments.set(page - 1, comments); }
                                progress.accept(done.incrementAndGet() * 95 / pages);
                            } catch (IOException error) { throw new java.io.UncheckedIOException(error); }
                        }
                    }));
                }
                for (Future<?> worker : workers) worker.get(job.remainingMillis(), TimeUnit.MILLISECONDS);
                job.check();
                List<IqiyiDanmakuCodec.Comment> comments = new ArrayList<>();
                for (List<IqiyiDanmakuCodec.Comment> segment : segments) comments.addAll(segment);
                byte[] document = IqiyiDanmakuCodec.document(comments);
                job.check();
                callback.onSuccess(document);
            } catch (Exception error) {
                boolean notify = !job.cancelled;
                job.cancel(); // No partial document survives a failed/timed-out segment.
                if (notify) {
                    if (com.fongmi.android.tv.BuildConfig.DEBUG) android.util.Log.w("IqiyiDanmaku", "Document failed", error);
                    callback.onFailure(asIo(error));
                }
            }
        });
        return job;
    }

    private static IOException asIo(Exception error) {
        Throwable cause = error instanceof ExecutionException && error.getCause() != null ? error.getCause() : error;
        return cause instanceof IOException ? (IOException) cause : new IOException("iQiyi danmaku request failed", cause);
    }

    public static final class Job {
        private final Set<Call> calls = ConcurrentHashMap.newKeySet();
        private final Set<Future<?>> futures = ConcurrentHashMap.newKeySet();
        private final long deadline;
        private final OkHttpClient client;
        private volatile boolean cancelled;

        private Job(long budgetMillis) { this(budgetMillis, Client.INSTANCE); }

        private Job(long budgetMillis, OkHttpClient client) {
            this.client = client;
            deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis);
        }

        private Future<?> submit(ExecutorService executor, Runnable task) {
            Future<?> future = executor.submit(task);
            futures.add(future);
            if (cancelled) future.cancel(true);
            return future;
        }

        public void cancel() {
            cancelled = true;
            for (Call call : calls) call.cancel();
            for (Future<?> future : futures) future.cancel(true);
        }

        private void check() throws IOException {
            if (cancelled || Thread.currentThread().isInterrupted()) throw new IOException("iQiyi request cancelled");
            if (remainingMillis() <= 0) throw new IOException("iQiyi request deadline exceeded");
        }

        private long remainingMillis() { return TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()); }

        private byte[] get(String url, int limit, boolean allowMissing) throws IOException {
            check();
            Request request = new Request.Builder().url(url).header("User-Agent", USER_AGENT)
                    .header("Referer", "https://www.iqiyi.com/").header("Origin", "https://www.iqiyi.com").build();
            Call call = client.newCall(request);
            call.timeout().timeout(Math.max(1, Math.min(12_000, remainingMillis())), TimeUnit.MILLISECONDS);
            calls.add(call);
            try {
                check();
                try (Response response = call.execute()) {
                    if (allowMissing && response.code() == 404) return new byte[0];
                    if (!response.isSuccessful() || response.body() == null) throw new IOException("iQiyi HTTP " + response.code());
                    return IqiyiDanmakuCodec.readBounded(response.body().byteStream(), limit);
                }
            } finally { calls.remove(call); }
        }
    }
}
