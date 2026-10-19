package com.fongmi.android.tv.playback.vod;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.DanmakuCatalogueSearch;
import com.fongmi.android.tv.api.IqiyiDanmaku;
import com.fongmi.android.tv.api.NativeDanmaku;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.player.danmaku.DanmakuDocumentCache;
import com.fongmi.android.tv.player.danmaku.DanmakuStatus;
import com.fongmi.android.tv.setting.DanmakuAiSetting;
import com.fongmi.android.tv.setting.DanmakuSetting;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** AI normalizes metadata; real platform catalogues and episode guards decide what may load. */
public final class DanmakuAiMatcher {
    public interface Callback {
        void onFound(String query, Danmaku item, List<Danmaku> catalogue);
        void onFailure();
        default void onFailure(DanmakuStatus.Failure reason) { onFailure(); }
        void onProgress(int percent);
    }
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static Job active;

    public static synchronized boolean search(DanmakuMatchContext context, Callback callback) {
        cancel();
        if (!DanmakuAiSetting.enabled()) return false;
        start(context, null, NativeDanmaku.sources(), DanmakuSetting.getSearchApiUrls(), callback);
        return true;
    }

    // Scoped dependencies also exercise the complete automatic path against controlled servers.
    static synchronized void start(DanmakuMatchContext context, DanmakuAiClient client,
                                   List<String> direct, List<String> backup, Callback callback) {
        cancel();
        Job job = new Job(callback);
        job.client = client;
        active = job;
        App.post(job.deadline, 45000);
        job.future = WORKER.submit(() -> job.run(context, direct, backup));
    }

    public static synchronized void cancel() {
        if (active != null) active.cancel();
        active = null;
    }

    private static final class Job {
        final Callback callback;
        final AtomicBoolean done = new AtomicBoolean();
        final List<DanmakuDocumentCache.Ticket> documents = new CopyOnWriteArrayList<>();
        final List<NativeDanmaku.Job> searches = new CopyOnWriteArrayList<>();
        final Set<String> completeSources = ConcurrentHashMap.newKeySet();
        final Runnable deadline = this::fail;
        volatile Future<?> future;
        volatile DanmakuAiClient client;
        volatile boolean cancelled;
        volatile String stage = "clean";
        volatile DanmakuStatus.Failure failure = DanmakuStatus.Failure.AI_NETWORK;

        Job(Callback callback) { this.callback = callback; }

        void run(DanmakuMatchContext original, List<String> direct, List<String> backup) {
            try {
                if (done.get()) return;
                // Android Keystore access belongs on the worker, never the playback UI.
                if (client == null) client = DanmakuAiClient.configured();
                if (done.get()) { client.cancel(); return; }
                String response = "";
                try { response = client.complete(DanmakuAiProtocol.context("clean", original)); }
                catch (IOException error) {
                    // An empty/malformed model answer must not block a real catalogue lookup.
                    // Authentication, transport failures and cancellation retain their cause.
                    if (!"response".equals(error.getMessage())) throw error;
                }
                if (done.get()) return;
                String query = DanmakuAiProtocol.query(response, original);
                if (query.isEmpty()) {
                    query = DanmakuAiProtocol.fallbackQuery(original);
                    response = ""; // Rejected suggestions must not normalize the episode either.
                    if (com.fongmi.android.tv.BuildConfig.DEBUG) android.util.Log.d("DanmakuAi", "Using locally verified title after invalid model normalization");
                }
                if (query.isEmpty()) { failure = DanmakuStatus.Failure.AI_INVALID_RESPONSE; fail(); return; }
                DanmakuMatchContext context = DanmakuAiProtocol.matchingContext(original, query, response);
                stage = "catalogue";
                failure = DanmakuStatus.Failure.NETWORK;
                List<Danmaku> catalogue = catalogue(query, context, direct, backup);
                if (done.get()) return;
                List<Danmaku> eligible = ranked(context, catalogue);
                // A precise result from the AI-cleaned query needs no second model round trip.
                if (eligible.isEmpty() && !catalogue.isEmpty()) {
                    List<Danmaku> choices = new ArrayList<>(catalogue);
                    choices.sort(Comparator.comparingInt((Danmaku item) -> DanmakuMatch.displayScore(context.getTitle(),
                            context.getYear(), context.getType(), context.getEpisode(), item.getMatchName())).reversed());
                    choices = new ArrayList<>(choices.subList(0, Math.min(40, choices.size())));
                    stage = "select";
                    failure = DanmakuStatus.Failure.AI_NETWORK;
                    response = client.complete(DanmakuAiProtocol.selection(original, query, choices.stream().map(Danmaku::getMatchName).toList()));
                    int selected = DanmakuAiProtocol.selectedIndex(response, choices.size());
                    if (selected >= 0) {
                        String episode = DanmakuAiProtocol.normalizedEpisode(response, context.getEpisode());
                        var normalized = new DanmakuMatchContext(context.getTitle(), context.getYear(), context.getType(), episode);
                        eligible = ranked(normalized, catalogue);
                        // The model can explain a label, never override explicit identity conflicts.
                        Danmaku selectedItem = choices.get(selected);
                        if (!eligible.contains(selectedItem)) eligible = List.of();
                        else { eligible.remove(selectedItem); eligible.add(0, selectedItem); }
                    }
                }
                if (eligible.isEmpty()) {
                    if (!catalogue.isEmpty()) failure = DanmakuStatus.Failure.NO_MATCH;
                    fail(); return;
                }
                stage = "document";
                verify(query, eligible, catalogue);
            } catch (IOException error) {
                if (com.fongmi.android.tv.BuildConfig.DEBUG) android.util.Log.d("DanmakuAi", "Request failed at " + stage + ": " + error.getClass().getSimpleName()
                        + (Objects.toString(error.getMessage(), "").matches("http_[0-9]{3}|cancelled|Canceled|credential|response") ? " " + error.getMessage() : "")
                        + (Objects.toString(error.getMessage(), "").startsWith("unexpected end of stream") ? " connection_closed" : ""));
                if (stage.equals("clean") || stage.equals("select")) {
                    failure = "http_401".equals(error.getMessage()) || "http_403".equals(error.getMessage()) || "credential".equals(error.getMessage())
                            ? DanmakuStatus.Failure.AI_ACCESS_DENIED
                            : "response".equals(error.getMessage()) ? DanmakuStatus.Failure.AI_INVALID_RESPONSE : DanmakuStatus.Failure.AI_NETWORK;
                }
                fail();
            } catch (Exception ignored) { fail(); }
        }

        List<Danmaku> ranked(DanmakuMatchContext context, List<Danmaku> items) {
            if (!DanmakuMatch.canUsePartialCatalogue(context.getType(), context.getEpisode(), items, Danmaku::getMatchName))
                items = items.stream().filter(item -> completeSources.contains(item.getSourceKey())).toList();
            List<Danmaku> result = new ArrayList<>(DanmakuMatch.ranked(context.getTitle(), context.getYear(), context.getType(),
                    context.getEpisode(), items, Danmaku::getMatchName));
            result.sort(Comparator.comparingInt(item -> NativeDanmaku.supports(item.getUrl()) ? 0 : 1));
            return result;
        }

        void verify(String query, List<Danmaku> candidates, List<Danmaku> catalogue) {
            App.removeCallbacks(deadline);
            App.post(deadline, candidates.stream().anyMatch(item -> com.fongmi.android.tv.api.MangoDanmaku.supports(item.getUrl())) ? 80000 : 45000);
            failure = DanmakuStatus.Failure.DOWNLOAD;
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

        List<Danmaku> catalogue(String title, DanmakuMatchContext context, List<String> direct, List<String> backup) throws InterruptedException {
            Map<String, Danmaku> rows = new ConcurrentHashMap<>();
            AtomicInteger pending = new AtomicInteger(direct.size());
            AtomicInteger responses = new AtomicInteger(), denied = new AtomicInteger(), invalid = new AtomicInteger();
            DanmakuQuery query = DanmakuQuery.from(context.getTitle(), context.getYear()).forDiscovery(context.getType());
            java.util.function.Consumer<String> lookup = source -> {
                if (done.get()) return;
                var search = DanmakuCatalogueSearch.searchWork(source, query, context.getYear(), context.getType(),
                        items -> items.stream().anyMatch(item -> DanmakuAiProtocol.sameWork(context, item.getMatchName())), new IqiyiDanmaku.Callback<>() {
                    private void collect(List<Danmaku> items) {
                        if (done.get()) return;
                        for (Danmaku item : items) if (DanmakuAiProtocol.sameWork(context, item.getMatchName())) {
                            item.setSourceKey(NativeDanmaku.isSource(source) ? source : DanmakuManualMatchStore.sourceKey(source));
                            rows.put(source + "|" + item.getUrl(), item);
                        }
                    }
                    @Override public void onPartial(List<Danmaku> items) { collect(items); }
                    @Override public void onSuccess(List<Danmaku> items) {
                        collect(items);
                        completeSources.add(NativeDanmaku.isSource(source) ? source : DanmakuManualMatchStore.sourceKey(source));
                        responses.incrementAndGet(); pending.decrementAndGet();
                    }
                    @Override public void onFailure(IOException error) {
                        if (error instanceof DanmakuCatalogueSearch.HttpFailure http && (http.code == 401 || http.code == 403)) denied.incrementAndGet();
                        else if (error instanceof DanmakuCatalogueSearch.InvalidResponse) invalid.incrementAndGet();
                        pending.decrementAndGet();
                    }
                });
                searches.add(search);
                if (done.get()) search.cancel();
            };
            direct.forEach(lookup);
            long start = System.nanoTime(), until = start + TimeUnit.SECONDS.toNanos(20);
            boolean backupStarted = false;
            while (!done.get() && System.nanoTime() < until) {
                if (!ranked(context, List.copyOf(rows.values())).isEmpty()) break;
                if (!backupStarted && (pending.get() == 0 || System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(1500))) {
                    backupStarted = true;
                    pending.addAndGet(backup.size());
                    backup.forEach(lookup);
                }
                if (backupStarted && pending.get() == 0) break;
                Thread.sleep(100);
            }
            for (NativeDanmaku.Job search : searches) search.cancel();
            failure = !rows.isEmpty() || responses.get() > 0 ? DanmakuStatus.Failure.NO_MATCH
                    : denied.get() > 0 ? DanmakuStatus.Failure.ACCESS_DENIED
                    : invalid.get() > 0 ? DanmakuStatus.Failure.INVALID_RESPONSE : DanmakuStatus.Failure.NETWORK;
            return List.copyOf(rows.values());
        }

        void fail() {
            if (!done.compareAndSet(false, true)) return;
            if (com.fongmi.android.tv.BuildConfig.DEBUG) android.util.Log.d("DanmakuAi", "Failed stage=" + stage + " reason=" + failure);
            cleanup();
            App.post(() -> { if (!cancelled) callback.onFailure(failure); });
        }

        void cancel() { cancelled = true; done.set(true); cleanup(); }

        void cleanup() {
            App.removeCallbacks(deadline);
            if (client != null) client.cancel();
            for (NativeDanmaku.Job search : searches) search.cancel();
            for (DanmakuDocumentCache.Ticket ticket : documents) ticket.cancel();
            if (future != null) future.cancel(true);
        }
    }
}
