package com.fongmi.android.tv.playback.vod;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.IqiyiDanmaku;
import com.fongmi.android.tv.api.IqiyiDanmakuCodec;
import com.fongmi.android.tv.api.DanmakuSearchRequest;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.player.danmaku.DanmakuDocumentCache;
import com.fongmi.android.tv.setting.DanmakuAiSetting;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.github.catvod.net.OkHttp;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.Call;
import okhttp3.Response;

/** Opt-in fallback. Two small model requests, real catalogue IDs, verified document only. */
public final class DanmakuAiMatcher {
    public interface Callback {
        void onFound(String query, Danmaku item, List<Danmaku> catalogue);
        void onFailure();
        void onProgress(int percent);
    }
    private static final java.util.concurrent.ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final okhttp3.OkHttpClient SEARCH = OkHttp.client().newBuilder()
            .dispatcher(new okhttp3.Dispatcher()).connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).build();
    private static Job active;

    public static synchronized boolean search(DanmakuMatchContext context, Callback callback) {
        cancel();
        if (!DanmakuAiSetting.enabled()) return false;
        Job job = new Job(callback);
        active = job;
        App.post(job.deadline, 45000);
        job.future = WORKER.submit(() -> job.run(context));
        return true;
    }

    public static synchronized void cancel() {
        if (active != null) active.cancel();
        active = null;
    }

    private static final class Job {
        final Callback callback;
        final AtomicBoolean done = new AtomicBoolean();
        final List<Call> calls = new CopyOnWriteArrayList<>();
        final List<DanmakuDocumentCache.Ticket> documents = new CopyOnWriteArrayList<>();
        final Runnable deadline = this::fail;
        volatile Future<?> future;
        volatile DanmakuAiClient client;
        volatile IqiyiDanmaku.Job nativeJob;
        volatile boolean cancelled;
        volatile String stage = "clean";

        Job(Callback callback) { this.callback = callback; }

        void run(DanmakuMatchContext context) {
            try {
                client = DanmakuAiClient.configured();
                if (done.get()) { client.cancel(); return; }
                String query = DanmakuAiProtocol.query(client.complete(DanmakuAiProtocol.context("clean", context)), context);
                if (query.isEmpty() || done.get()) { fail(); return; }
                stage = "catalogue";
                List<Danmaku> catalogue = catalogue(query, context);
                if (done.get()) return;
                String year = context.getYear().isEmpty() ? DanmakuQuery.from(context.getTitle()).year() : context.getYear();
                List<Danmaku> eligible = DanmakuMatch.ranked(query, year, context.getType(),
                        context.getEpisode(), catalogue, Danmaku::getName);
                if (eligible.isEmpty()) { fail(); return; }
                // An ambiguous edition/season/date is rejected by ranked() before model selection.
                eligible = new ArrayList<>(eligible.subList(0, Math.min(40, eligible.size())));
                List<String> names = new ArrayList<>();
                for (Danmaku item : eligible) names.add(item.getName());
                stage = "select";
                int selected = DanmakuAiProtocol.selectedIndex(client.complete(
                        DanmakuAiProtocol.selection(context, query, names)), eligible.size());
                if (selected < 0 || done.get()) { fail(); return; }
                // AI identifies the work/episode, not which mirror is currently healthy. Race
                // its choice with one verified-equivalent alternative so a stale/slow mirror
                // cannot suppress an available native document.
                Danmaku selectedItem = eligible.remove(selected);
                eligible.sort(java.util.Comparator.comparingInt(item ->
                        IqiyiDanmakuCodec.supports(item.getUrl()) ? 0 : 1));
                eligible.add(0, selectedItem);
                stage = "document";
                verify(query, eligible, catalogue);
            } catch (Exception ignored) { fail(); }
        }

        void verify(String query, List<Danmaku> candidates, List<Danmaku> catalogue) {
            var next = new java.util.concurrent.atomic.AtomicInteger();
            int workers = Math.min(2, candidates.size());
            var remaining = new java.util.concurrent.atomic.AtomicInteger(workers);
            class Loader implements Runnable {
                @Override public void run() {
                    if (done.get()) return;
                    int index = next.getAndIncrement();
                    if (index >= candidates.size()) { if (remaining.decrementAndGet() == 0) fail(); return; }
                    Danmaku item = candidates.get(index);
                    var ticket = DanmakuDocumentCache.load(item.getUri(), new DanmakuDocumentCache.Listener() {
                        @Override public void onProgress(android.net.Uri source, int percent) {
                            if (!done.get()) callback.onProgress(percent);
                        }
                        @Override public void onReady(android.net.Uri source, android.net.Uri local) {
                            if (!done.compareAndSet(false, true)) return;
                            cleanup();
                            callback.onFound(query, item, catalogue);
                        }
                        @Override public void onFailure(android.net.Uri source, IOException error) {
                            if (com.fongmi.android.tv.BuildConfig.DEBUG) android.util.Log.d("DanmakuAi", "Candidate document unavailable; trying equivalent alternatives");
                            Loader.this.run();
                        }
                    });
                    documents.add(ticket);
                    if (done.get()) ticket.cancel();
                }
            }
            for (int i = 0; i < workers; i++) new Loader().run();
        }

        List<Danmaku> catalogue(String query, DanmakuMatchContext context) throws InterruptedException {
            List<String> urls = DanmakuSetting.getSearchApiUrls();
            List<Danmaku> result = new CopyOnWriteArrayList<>();
            CountDownLatch pending = new CountDownLatch(urls.size() + 1);
            CountDownLatch nativeDone = new CountDownLatch(1);
            nativeJob = IqiyiDanmaku.search(query, new IqiyiDanmaku.Callback<>() {
                @Override public void onSuccess(List<Danmaku> items) {
                    for (Danmaku item : items) item.setSourceKey(IqiyiDanmakuCodec.SOURCE);
                    if (!done.get()) result.addAll(items);
                    nativeDone.countDown();
                    pending.countDown();
                }
                @Override public void onFailure(IOException error) { nativeDone.countDown(); pending.countDown(); }
            });
            if (done.get()) nativeJob.cancel();
            for (String url : urls) {
                if (done.get()) break;
                Call call = SEARCH.newCall(DanmakuSearchRequest.build(url, query, context.getEpisode(), "DanmakuAi"));
                calls.add(call);
                if (done.get()) { call.cancel(); break; }
                call.enqueue(new okhttp3.Callback() {
                    @Override public void onFailure(Call call, IOException error) { pending.countDown(); }
                    @Override public void onResponse(Call call, Response response) {
                        try (Response closeable = response) {
                            if (done.get() || !closeable.isSuccessful() || closeable.body() == null) return;
                            byte[] bytes = closeable.body().byteStream().readNBytes(2_000_001);
                            if (bytes.length > 2_000_000) return;
                            List<Danmaku> items = Danmaku.arrayFrom(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                            for (Danmaku item : items) item.setSourceKey(DanmakuManualMatchStore.sourceKey(url));
                            if (!done.get()) result.addAll(items);
                        } catch (Exception ignored) {
                        } finally { pending.countDown(); }
                    }
                });
            }
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            String year = context.getYear().isEmpty() ? DanmakuQuery.from(context.getTitle()).year() : context.getYear();
            while (!done.get() && System.nanoTime() < until && !pending.await(150, TimeUnit.MILLISECONDS)) {
                // First verified identity may proceed immediately; slow supplementary APIs
                // must not hold a native catalogue hostage for their entire timeout.
                if (nativeDone.getCount() == 0 && !DanmakuMatch.ranked(query, year, context.getType(), context.getEpisode(),
                        List.copyOf(result), Danmaku::getName).isEmpty()) break;
            }
            if (nativeJob != null) nativeJob.cancel();
            for (Call call : calls) call.cancel();
            return List.copyOf(result);
        }

        void fail() {
            if (!done.compareAndSet(false, true)) return;
            if (com.fongmi.android.tv.BuildConfig.DEBUG) android.util.Log.d("DanmakuAi", "Failed stage=" + stage);
            cleanup();
            App.post(() -> { if (!cancelled) callback.onFailure(); });
        }

        void cancel() { cancelled = true; done.set(true); cleanup(); }

        void cleanup() {
            App.removeCallbacks(deadline);
            if (client != null) client.cancel();
            if (nativeJob != null) nativeJob.cancel();
            for (Call call : calls) call.cancel();
            for (DanmakuDocumentCache.Ticket ticket : documents) ticket.cancel();
            if (future != null) future.cancel(true);
        }
    }
}
