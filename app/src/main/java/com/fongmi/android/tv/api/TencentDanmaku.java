package com.fongmi.android.tv.api;

import com.fongmi.android.tv.bean.Danmaku;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.IntConsumer;
import okhttp3.*;

/** Direct public Tencent lookup; independent queues and a complete-document commit boundary. */
public final class TencentDanmaku {
    private static final ExecutorService SEARCH = Executors.newFixedThreadPool(2);
    private static final ExecutorService DOCUMENTS = Executors.newFixedThreadPool(2);
    private static final ExecutorService SEGMENTS = Executors.newFixedThreadPool(4);
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor();
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS).retryOnConnectionFailure(false).build();
    private static final String SEARCH_URL = "https://pbaccess.video.qq.com/trpc.videosearch.mobile_search.MultiTerminalSearch/MbSearch?vplatform=2";
    private static final String EPISODES_URL = "https://pbaccess.video.qq.com/trpc.universal_backend_service.page_server_rpc.PageServer/GetPageData?video_appid=3000010&vversion_name=8.2.96&vversion_platform=2";
    private record Catalogue(long created, List<Danmaku> rows) { }
    private static final Map<String, Catalogue> CACHE = new LinkedHashMap<>();

    public static NativeDanmaku.Job search(String query, IqiyiDanmaku.Callback<List<Danmaku>> callback) {
        return search(query, callback, CLIENT);
    }

    public static NativeDanmaku.Job searchTarget(String query, String year, String type, IqiyiDanmaku.Callback<List<Danmaku>> callback) {
        return search(query, year, type, callback, CLIENT);
    }
    static NativeDanmaku.Job search(String query, IqiyiDanmaku.Callback<List<Danmaku>> callback, OkHttpClient client) {
        return search(query, "", "", callback, client);
    }
    static NativeDanmaku.Job search(String query, String year, String type, IqiyiDanmaku.Callback<List<Danmaku>> callback, OkHttpClient client) {
        String cacheKey = query + "|" + year + "|" + type;
        Job job = new Job(15000, client);
        job.start(SEARCH, () -> {
            synchronized (CACHE) {
                Catalogue cached = CACHE.get(cacheKey);
                if (cached != null && System.nanoTime() - cached.created() < TimeUnit.MINUTES.toNanos(5)) return copy(cached.rows());
            }
            JsonObject payload = new JsonObject();
            payload.addProperty("version", "25071701"); payload.addProperty("clientType", 1);
            payload.addProperty("filterValue", ""); payload.addProperty("uuid", UUID.randomUUID().toString());
            payload.addProperty("query", com.github.catvod.utils.Trans.t2s(query)); payload.addProperty("retry", 0);
            payload.addProperty("pagenum", 0); payload.addProperty("isPrefetch", true); payload.addProperty("pagesize", 30);
            payload.addProperty("queryFrom", 0); payload.addProperty("searchDatakey", ""); payload.addProperty("transInfo", "");
            payload.addProperty("isneedQc", true);
            JsonObject extra = new JsonObject(); extra.addProperty("multi_terminal_pc", "1"); extra.addProperty("themeType", "1");
            payload.add("extraInfo", extra);
            List<TencentDanmakuCodec.Work> works = TencentDanmakuCodec.works(job.json(SEARCH_URL, payload, 4_000_000), query);
            Map<String, Danmaku> rows = new LinkedHashMap<>();
            for (var work : works) {
                if ((!year.isEmpty() || !type.isEmpty()) && !NativeDanmaku.acceptsWork(query, year, type, work.matchTitle(), work.year(), work.type())) continue;
                for (Danmaku row : work.rows()) rows.putIfAbsent(row.getUrl(), row);
                job.check();
                if (!rows.isEmpty()) callback.onPartial(copy(List.copyOf(rows.values())));
                if ("电影".equals(work.type()) && !work.rows().isEmpty()) continue;
                Set<String> visited = new HashSet<>();
                ArrayDeque<String> contexts = new ArrayDeque<>();
                contexts.add("cid=" + work.id() + "&detail_page_type=1&req_from=web_vsite&req_from_second_type=&req_type=0");
                while (!contexts.isEmpty() && visited.size() < 12) {
                    String context = contexts.removeFirst();
                    if (!visited.add(context)) continue;
                    try {
                        var page = TencentDanmakuCodec.episodes(job.json(EPISODES_URL, episodePayload(work.id(), context), 4_000_000), work);
                        // Full catalogue labels carry broadcast dates/edition names missing
                        // from the search preview. Enrich the same verified video ID in place.
                        for (Danmaku row : page.rows()) rows.put(row.getUrl(), row);
                        job.check();
                        if (!rows.isEmpty()) callback.onPartial(copy(List.copyOf(rows.values())));
                        for (String next : page.contexts()) if (!visited.contains(next)) contexts.addLast(next);
                    } catch (IOException error) {
                        // Search already carries usable recent episodes. A supplementary page
                        // must not discard them, but an entirely failed catalogue is an error.
                        job.check();
                        if (rows.isEmpty()) throw error;
                        break;
                    }
                }
            }
            job.check();
            List<Danmaku> result = List.copyOf(rows.values());
            if (!result.isEmpty()) synchronized (CACHE) {
                CACHE.put(cacheKey, new Catalogue(System.nanoTime(), copy(result)));
                while (CACHE.size() > 12) CACHE.remove(CACHE.keySet().iterator().next());
            }
            return result;
        }, callback);
        return job;
    }

    private static JsonObject episodePayload(String id, String context) {
        JsonObject root = new JsonObject(), params = new JsonObject(); root.addProperty("has_cache", 1);
        String[][] values = {{"req_from", "web_vsite"}, {"page_id", "vsite_episode_list"}, {"page_type", "detail_operation"},
                {"id_type", "1"}, {"page_size", ""}, {"cid", id}, {"vid", ""}, {"lid", ""}, {"page_num", ""},
                {"page_context", context}, {"detail_page_type", "1"}};
        for (String[] pair : values) params.addProperty(pair[0], pair[1]);
        root.add("page_params", params); return root;
    }

    public static NativeDanmaku.Job document(String source, IntConsumer progress, IqiyiDanmaku.Callback<byte[]> callback) {
        return document(source, progress, callback, CLIENT);
    }

    static NativeDanmaku.Job document(String source, IntConsumer progress, IqiyiDanmaku.Callback<byte[]> callback, OkHttpClient client) {
        Job job = new Job(40000, client);
        job.start(DOCUMENTS, () -> {
            String id = TencentDanmakuCodec.videoId(source);
            List<String> urls = TencentDanmakuCodec.segments(job.json("https://dm.video.qq.com/barrage/base/" + id, null, 512_000), id);
            List<List<IqiyiDanmakuCodec.Comment>> segments = new ArrayList<>(Collections.nCopies(urls.size(), null));
            AtomicInteger next = new AtomicInteger(), done = new AtomicInteger(), textSize = new AtomicInteger();
            List<Future<?>> workers = new ArrayList<>();
            int perSegment = Math.min(600, 150_000 / urls.size());
            for (int i = 0; i < Math.min(4, urls.size()); i++) workers.add(job.submit(SEGMENTS, () -> {
                for (int index; (index = next.getAndIncrement()) < urls.size();) {
                    try {
                        String data;
                        try { data = job.json(urls.get(index), null, 2_000_000); }
                        catch (IOException first) {
                            // A long episode has hundreds of segments. Retry a transient transport
                            // failure once within the same deadline, never commit a partial timeline.
                            job.check(); data = job.json(urls.get(index), null, 2_000_000);
                        }
                        var comments = TencentDanmakuCodec.comments(data, perSegment);
                        int chars = 0; for (var comment : comments) chars += comment.text().length();
                        if (textSize.addAndGet(chars) > 8 * 1024 * 1024) throw new IOException("Tencent comment text exceeds limit");
                        synchronized (segments) { segments.set(index, comments); }
                        progress.accept(done.incrementAndGet() * 95 / urls.size());
                    } catch (IOException error) { throw new java.io.UncheckedIOException(error); }
                }
            }));
            for (Future<?> worker : workers) worker.get(job.remaining(), TimeUnit.MILLISECONDS);
            job.check();
            List<IqiyiDanmakuCodec.Comment> all = new ArrayList<>();
            for (var segment : segments) all.addAll(segment);
            return IqiyiDanmakuCodec.document(all);
        }, callback);
        return job;
    }

    private static List<Danmaku> copy(List<Danmaku> rows) {
        List<Danmaku> result = new ArrayList<>();
        for (Danmaku row : rows) {
            Danmaku item = new Danmaku(); item.setName(row.getName()); item.setMatchName(row.getMatchName());
            item.setUrl(row.getUrl()); item.setSourceKey(row.getSourceKey()); result.add(item);
        }
        return result;
    }

    private static final class Job implements NativeDanmaku.Job {
        private final long deadline;
        private final OkHttpClient client;
        private final AtomicBoolean finished = new AtomicBoolean();
        private final Set<Call> calls = ConcurrentHashMap.newKeySet();
        private final Set<Future<?>> tasks = ConcurrentHashMap.newKeySet();
        private volatile Future<?> timer;
        Job(long millis, OkHttpClient client) {
            deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            this.client = client;
        }
        long remaining() { return Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())); }
        void check() throws IOException {
            if (finished.get() || Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) throw new IOException("Tencent request cancelled or timed out");
        }
        <T> void start(ExecutorService executor, Callable<T> action, IqiyiDanmaku.Callback<T> callback) {
            timer = TIMER.schedule(() -> {
                if (finished.compareAndSet(false, true)) { cleanup(); callback.onFailure(new IOException("Tencent request timed out")); }
            }, remaining(), TimeUnit.MILLISECONDS);
            submit(executor, () -> {
                try {
                    check(); T result = action.call(); check();
                    if (finished.compareAndSet(false, true)) { if (timer != null) timer.cancel(false); callback.onSuccess(result); }
                } catch (Exception error) {
                    if (finished.compareAndSet(false, true)) {
                        cleanup(); callback.onFailure(error instanceof IOException ? (IOException) error : new IOException("Tencent request failed", error));
                    }
                }
            });
        }
        Future<?> submit(ExecutorService executor, Runnable task) {
            Future<?> future = executor.submit(task); tasks.add(future);
            if (finished.get()) future.cancel(true); return future;
        }
        String json(String url, JsonObject payload, int limit) throws IOException {
            check();
            Request.Builder builder = new Request.Builder().url(url).header("User-Agent", "Mozilla/5.0")
                    .header("Referer", "https://v.qq.com/").header("Origin", "https://v.qq.com");
            if (payload != null) builder.post(RequestBody.create(payload.toString(), MediaType.get("application/json; charset=utf-8")));
            Call call = client.newCall(builder.build()); call.timeout().timeout(Math.min(12000, remaining()), TimeUnit.MILLISECONDS); calls.add(call);
            try {
                check();
                try (Response response = call.execute()) {
                    if (!response.isSuccessful() || response.body() == null) throw new IOException("Tencent HTTP " + response.code());
                    return new String(IqiyiDanmakuCodec.readBounded(response.body().byteStream(), limit), StandardCharsets.UTF_8);
                }
            } finally { calls.remove(call); }
        }
        @Override public void cancel() { finished.set(true); cleanup(); }
        private void cleanup() {
            if (timer != null) timer.cancel(false);
            for (Call call : calls) call.cancel();
            for (Future<?> task : tasks) task.cancel(true);
        }
    }
    private TencentDanmaku() { }
}
