package com.fongmi.android.tv.api;

import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.playback.vod.DanmakuManualMatchStore;
import com.fongmi.android.tv.playback.vod.DanmakuMatch;
import com.fongmi.android.tv.playback.vod.DanmakuQuery;
import com.fongmi.android.tv.player.danmaku.DanmakuDocumentCache;
import com.fongmi.android.tv.player.danmaku.DanmakuLoadPolicy;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Trans;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import okhttp3.Call;
import okhttp3.Response;

public class DanmakuApi {

    public enum SearchFailure { NO_MATCH, NETWORK, ACCESS_DENIED, INVALID_RESPONSE, DOWNLOAD }

    public interface SearchCallback {
        void onFound(Danmaku item, List<Danmaku> catalogue);
        void onFailure(SearchFailure failure);
        default void onProgress(int percent) {
        }
    }

    private static final String TAG = DanmakuApi.class.getSimpleName();
    private static final Set<NativeDanmaku.Job> NATIVE_SEARCHES = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final AtomicInteger REQUEST_GENERATION = new AtomicInteger();
    private static final Set<DanmakuDocumentCache.Ticket> ACTIVE_VALIDATIONS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final class SearchClient {
        // Search must not queue behind catalogue scraping, video requests or large XML files.
        static final okhttp3.OkHttpClient INSTANCE = OkHttp.client().newBuilder()
                .dispatcher(new okhttp3.Dispatcher())
                .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(14, java.util.concurrent.TimeUnit.SECONDS)
                .retryOnConnectionFailure(false).build();
    }

    public static boolean canSearch() {
        return DanmakuSetting.isLoad() && DanmakuSetting.isAuto();
    }

    public static Call newCall(String name, String episode) {
        return newCalls(name, episode).get(0);
    }

    public static List<Call> newCalls(String name, String episode) {
        beginSearch();
        String query = DanmakuQuery.from(name).searchTitle();
        List<Call> calls = new ArrayList<>();
        for (String url : DanmakuSetting.getSearchApiUrls()) calls.add(createCall(query, episode, url));
        return calls;
    }

    private static Call createCall(String name, String episode, String url) {
        name = Trans.t2s(Objects.toString(name, ""));
        episode = Trans.t2s(Objects.toString(episode, ""));
        return SearchClient.INSTANCE.newCall(DanmakuSearchRequest.build(url, name, episode, TAG));
    }

    public static void search(String name, String episode, Consumer<Danmaku> found) {
        search(name, "", "", episode, found);
    }

    public static void search(String name, String year, String type, String episode,
                              Consumer<Danmaku> found) {
        searchDetailed(name, year, type, episode, callback(found));
    }

    public static void searchDetailed(String name, String year, String type, String episode,
                                      SearchCallback callback) {
        searchStaged(name, year, type, episode, "", NativeDanmaku.sources(), orderedEndpoints(""), callback);
    }

    public static void searchPreferred(String name, String year, String type, String episode,
                                       String selectedName, String selectedSourceKey,
                                       BiConsumer<Danmaku, List<Danmaku>> found) {
        searchPreferredDetailed(name, year, type, episode, selectedName, selectedSourceKey,
                new SearchCallback() {
                    @Override public void onFound(Danmaku item, List<Danmaku> catalogue) {
                        found.accept(item, catalogue);
                    }
                    @Override public void onFailure(SearchFailure failure) {
                    }
                });
    }

    public static void searchPreferredDetailed(String name, String year, String type, String episode,
                                               String selectedName, String selectedSourceKey,
                                               SearchCallback callback) {
        searchStaged(name, year, type, episode, Objects.toString(selectedName, ""),
                NativeDanmaku.sources(), orderedEndpoints(selectedSourceKey), callback);
    }

    // One shared pipeline for automatic matching and recovery of a manually confirmed work.
    // Backup services are only contacted after direct catalogues/documents are exhausted.
    static void searchStaged(String name, String year, String type, String episode, String preferredName,
                             List<String> direct, List<String> backup, SearchCallback callback) {
        int generation = beginSearch();
        SearchStats stats = new SearchStats(generation, callback, backup);
        searchCandidateParallel(generation, DanmakuQuery.from(name, year), Objects.toString(year, ""),
                Objects.toString(type, ""), episode, preferredName, direct, callback, 0, 0, stats);
    }

    private static SearchCallback callback(Consumer<Danmaku> found) {
        return new SearchCallback() {
            @Override public void onFound(Danmaku item, List<Danmaku> catalogue) { found.accept(item); }
            @Override public void onFailure(SearchFailure failure) {
            }
        };
    }

    private static List<String> orderedEndpoints(String preferredSourceKey) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (String apiUrl : DanmakuSetting.getSearchApiUrls()) {
            if (!preferredSourceKey.isEmpty()
                    && DanmakuManualMatchStore.sourceKey(apiUrl).equals(preferredSourceKey)) ordered.add(apiUrl);
        }
        ordered.add(DanmakuSetting.getAutomaticApiUrl());
        ordered.addAll(DanmakuSetting.getSearchApiUrls());
        ordered.removeIf(value -> value == null || value.isBlank());
        return new ArrayList<>(ordered);
    }

    /** Runs independent providers concurrently, then advances to the next normalized title once. */
    private static void searchCandidateParallel(int generation, DanmakuQuery query, String year,
                                                String type, String episode, String preferredName,
                                                List<String> apiUrls, SearchCallback callback,
                                                int candidateIndex, int refreshCount,
                                                SearchStats stats) {
        if (generation != REQUEST_GENERATION.get()) return;
        if (candidateIndex >= query.candidates().size() || apiUrls.isEmpty()) {
            if (stats.directPhase.compareAndSet(true, false) && !stats.backup.isEmpty()) {
                cancelNativeSearches();
                searchCandidateParallel(generation, query, year, type, episode, preferredName,
                        stats.backup, callback, 0, 0, stats);
                return;
            }
            SearchFailure failure = stats.download.get() > 0 ? SearchFailure.DOWNLOAD
                    : stats.denied.get() > 0 ? SearchFailure.ACCESS_DENIED
                    : stats.network.get() > 0 ? SearchFailure.NETWORK
                    : stats.invalid.get() > 0 ? SearchFailure.INVALID_RESPONSE : SearchFailure.NO_MATCH;
            App.post(() -> {
                if (generation == REQUEST_GENERATION.get() && stats.finish()) callback.onFailure(failure);
            });
            return;
        }
        String candidateTitle = query.candidates().get(candidateIndex);
        AtomicBoolean finished = new AtomicBoolean();
        AtomicBoolean refreshSource = new AtomicBoolean();
        AtomicInteger remaining = new AtomicInteger(apiUrls.size());
        List<Call> calls = new ArrayList<>();
        List<DanmakuDocumentCache.Ticket> validations =
                Collections.synchronizedList(new ArrayList<>());
        for (String url : apiUrls) calls.add(NativeDanmaku.isSource(url) ? null : createCall(candidateTitle, episode, url));
        for (int i = 0; i < calls.size(); i++) {
            Call current = calls.get(i);
            String apiUrl = apiUrls.get(i);
            class ProviderCallback extends Callback implements IqiyiDanmaku.Callback<List<Danmaku>> {
                final AtomicBoolean completed = new AtomicBoolean();
                final AtomicBoolean received = new AtomicBoolean();
                volatile NativeDanmaku.Job job;
                final Runnable searchDeadline = () -> {
                    if (!received.compareAndSet(false, true) || completed.get()) return;
                    if (job != null) job.cancel();
                    stats.network.incrementAndGet();
                    stats.failedEndpoints.add(apiUrl);
                    completeOne();
                };
                @Override public void onSuccess(List<Danmaku> items) {
                    if (generation != REQUEST_GENERATION.get() || finished.get() || !received.compareAndSet(false, true)) return;
                    App.removeCallbacks(searchDeadline);
                    accept(items);
                }

                @Override public void onFailure(IOException error) { onFailure(current, error); }

                private void accept(List<Danmaku> items) {
                    String sourceKey = NativeDanmaku.isSource(apiUrl) ? apiUrl : DanmakuManualMatchStore.sourceKey(apiUrl);
                    for (Danmaku item : items) item.setSourceKey(sourceKey);
                    List<Danmaku> candidates = preferredName.isEmpty()
                            ? DanmakuMatch.ranked(candidateTitle, TextUtils.isEmpty(year) ? query.year() : year.trim(),
                                    type, episode, items, Danmaku::getMatchName)
                            : DanmakuMatch.rankedPreferred(preferredName, year, type, episode, items, Danmaku::getName);
                    if (candidates.isEmpty()) { completeOne(); return; }
                    verifyCandidates(generation, candidates, items, 0, finished, calls,
                            validations, stats, refreshSource, this::completeOne, callback);
                }
                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) {
                    try (Response closeable = response) {
                        if (generation != REQUEST_GENERATION.get() || finished.get() || !received.compareAndSet(false, true)) return;
                        App.removeCallbacks(searchDeadline);
                        if (!closeable.isSuccessful() || closeable.body() == null) {
                            if (closeable.code() == 401 || closeable.code() == 403) stats.denied.incrementAndGet();
                            else stats.network.incrementAndGet();
                            stats.failedEndpoints.add(apiUrl);
                            completeOne();
                            return;
                        }
                        List<Danmaku> items = Danmaku.arrayFrom(closeable.body().string());
                        accept(items);
                    } catch (Exception error) {
                        stats.invalid.incrementAndGet();
                        completeOne();
                    }
                }

                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException error) {
                    if (generation != REQUEST_GENERATION.get() || finished.get() || !received.compareAndSet(false, true)) return;
                    App.removeCallbacks(searchDeadline);
                    stats.network.incrementAndGet();
                    stats.failedEndpoints.add(apiUrl);
                    completeOne();
                }

                private void completeOne() {
                    if (generation != REQUEST_GENERATION.get() || finished.get() || !completed.compareAndSet(false, true)) return;
                    App.removeCallbacks(searchDeadline);
                    if (remaining.decrementAndGet() == 0 && finished.compareAndSet(false, true)) {
                        List<String> usable = new ArrayList<>(apiUrls);
                        usable.removeAll(stats.failedEndpoints);
                        if (refreshSource.get() && refreshCount < 1) {
                            // The service creates short-lived comment ids. A freshly returned id
                            // can still answer 404/5xx while its backing document is being
                            // prepared. Re-run the same strict title/season/episode query once to
                            // obtain a new id instead of accepting a wrong season or failing fast.
                            App.post(() -> searchCandidateParallel(generation, query, year, type,
                                    episode, preferredName, usable, callback, candidateIndex,
                                    refreshCount + 1, stats), 1_200L);
                        } else {
                            searchCandidateParallel(generation, query, year, type, episode,
                                    preferredName, usable, callback, candidateIndex + 1, 0, stats);
                        }
                    }
                }
            }
            ProviderCallback provider = new ProviderCallback();
            if (current != null) current.enqueue(provider);
            else {
                // A stalled catalogue cannot hold the backup phase for tens of seconds.
                // Once a candidate is found, its bounded full-document download owns the wait.
                App.post(provider.searchDeadline, 8_000L);
                provider.job = NativeDanmaku.search(apiUrl, candidateTitle, TextUtils.isEmpty(year) ? query.year() : year.trim(), type, provider);
                NATIVE_SEARCHES.add(provider.job);
                if (generation != REQUEST_GENERATION.get() || stats.delivered.get() || provider.completed.get()) provider.job.cancel();
            }
        }
    }

    /**
     * Materialises a candidate before declaring automatic matching successful.
     *
     * <p>Search APIs can return perfectly matching rows whose comment document now answers 5xx.
     * Manual matching appeared to fix that only because the user happened to select another
     * provider.  Automatic matching now performs the same availability check and walks the
     * remaining candidates/providers instead of persisting a known-broken URL.</p>
     */
    private static void verifyCandidates(int generation, List<Danmaku> candidates,
                                         List<Danmaku> catalogue, int index,
                                         AtomicBoolean finished, List<Call> calls,
                                         List<DanmakuDocumentCache.Ticket> validations,
                                         SearchStats stats, AtomicBoolean refreshSource,
                                         Runnable exhausted,
                                         SearchCallback callback) {
        if (generation != REQUEST_GENERATION.get() || finished.get()) return;
        if (index >= candidates.size()) {
            exhausted.run();
            return;
        }
        Danmaku item = candidates.get(index);
        if (item == null || item.getUri() == null) {
            verifyCandidates(generation, candidates, catalogue, index + 1, finished, calls,
                    validations, stats, refreshSource, exhausted, callback);
            return;
        }
        if (stats.rejectedUrls.contains(item.getUrl())) {
            verifyCandidates(generation, candidates, catalogue, index + 1, finished, calls,
                    validations, stats, refreshSource, exhausted, callback);
            return;
        }
        DanmakuDocumentCache.Ticket ticket = DanmakuDocumentCache.load(item.getUri(),
                new DanmakuDocumentCache.Listener() {
                    @Override
                    public void onProgress(android.net.Uri source, int percent) {
                        if (generation == REQUEST_GENERATION.get() && !finished.get()) {
                            callback.onProgress(percent);
                        }
                    }

                    @Override
                    public void onReady(android.net.Uri source, android.net.Uri local) {
                        if (generation != REQUEST_GENERATION.get() || finished.get()) return;
                        if (!finished.compareAndSet(false, true) || !stats.finish()) return;
                        for (Call pending : calls) if (pending != null) pending.cancel();
                        cancelNativeSearches();
                        synchronized (validations) {
                            for (DanmakuDocumentCache.Ticket validation : validations) {
                                validation.cancel();
                            }
                            validations.clear();
                        }
                        cancelValidations();
                        callback.onFound(item, catalogue);
                    }

                    @Override
                    public void onFailure(android.net.Uri source, IOException error) {
                        if (generation != REQUEST_GENERATION.get() || finished.get()) return;
                        stats.download.incrementAndGet();
                        if (DanmakuLoadPolicy.shouldRefreshSource(error)) {
                            refreshSource.set(true);
                        }
                        stats.rejectedUrls.add(item.getUrl());
                        DanmakuDocumentCache.invalidate(source);
                        verifyCandidates(generation, candidates, catalogue, index + 1, finished,
                                calls, validations, stats, refreshSource, exhausted, callback);
                    }
                });
        validations.add(ticket);
        ACTIVE_VALIDATIONS.add(ticket);
        if (generation != REQUEST_GENERATION.get() || stats.delivered.get()) {
            ticket.cancel(); ACTIVE_VALIDATIONS.remove(ticket);
        }
    }

    static Danmaku bestMatch(String name, String episode, List<Danmaku> items) {
        return DanmakuMatch.best(name, "", episode, items, Danmaku::getName);
    }

    static Danmaku bestMatch(DanmakuQuery query, String episode, List<Danmaku> items) {
        return DanmakuMatch.best(query.searchTitle(), query.year(), episode, items, Danmaku::getName);
    }

    static Danmaku bestMatch(DanmakuQuery query, String year, String type, String episode,
                             List<Danmaku> items) {
        String expectedYear = TextUtils.isEmpty(year) ? query.year() : year.trim();
        return DanmakuMatch.best(query.searchTitle(), expectedYear, type, episode, items, Danmaku::getName);
    }

    static Danmaku bestMatch(String title, DanmakuQuery query, String year, String type,
                             String episode, List<Danmaku> items) {
        String expectedYear = year == null || year.isEmpty() ? query.year() : year.trim();
        return DanmakuMatch.best(title, expectedYear, type, episode, items, Danmaku::getName);
    }

    public static void cancel() {
        beginSearch();
    }

    private static int beginSearch() {
        com.fongmi.android.tv.playback.vod.DanmakuAiMatcher.cancel();
        int generation = REQUEST_GENERATION.incrementAndGet();
        OkHttp.cancel(SearchClient.INSTANCE, TAG);
        cancelValidations();
        cancelNativeSearches();
        return generation;
    }

    private static void cancelNativeSearches() {
        for (NativeDanmaku.Job job : NATIVE_SEARCHES) job.cancel();
        NATIVE_SEARCHES.clear();
    }

    private static void cancelValidations() {
        for (DanmakuDocumentCache.Ticket ticket : ACTIVE_VALIDATIONS) ticket.cancel();
        ACTIVE_VALIDATIONS.clear();
    }

    private static final class SearchStats {
        private final AtomicBoolean delivered = new AtomicBoolean();
        private final Runnable deadline;

        private final AtomicBoolean directPhase = new AtomicBoolean(true);
        private final List<String> backup;

        SearchStats(int generation, SearchCallback callback, List<String> backup) {
            this.backup = List.copyOf(backup);
            deadline = () -> {
                if (generation != REQUEST_GENERATION.get() || !delivered.compareAndSet(false, true)) return;
                beginSearch();
                callback.onFailure(download.get() > 0 ? SearchFailure.DOWNLOAD : SearchFailure.NETWORK);
            };
            // Includes title fallbacks and comment validation; no sequence of stale IDs can
            // leave automatic matching spinning for minutes.
            // Preserve enough time for a bounded direct document failure followed by a
            // backup document. Faster catalogue selection must not truncate a valid timeline.
            App.post(deadline, 90_000L);
        }

        boolean finish() {
            if (!delivered.compareAndSet(false, true)) return false;
            App.removeCallbacks(deadline);
            return true;
        }

        private final AtomicInteger invalid = new AtomicInteger();
        private final AtomicInteger network = new AtomicInteger();
        private final AtomicInteger download = new AtomicInteger();
        private final AtomicInteger denied = new AtomicInteger();
        private final Set<String> failedEndpoints = Collections.synchronizedSet(new HashSet<>());
        private final Set<String> rejectedUrls = Collections.synchronizedSet(new HashSet<>());
    }
}
