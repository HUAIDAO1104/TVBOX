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
        default void resolveCandidates(List<Danmaku> candidates, List<Danmaku> catalogue,
                                       Consumer<List<Danmaku>> ready) { ready.accept(candidates); }
    }

    private static final String TAG = DanmakuApi.class.getSimpleName();
    private static final Set<NativeDanmaku.Job> NATIVE_SEARCHES = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final AtomicInteger REQUEST_GENERATION = new AtomicInteger();
    private static final Set<DanmakuDocumentCache.Ticket> ACTIVE_VALIDATIONS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final class SearchClient {
        // Search must not queue behind catalogue scraping, video requests or large XML files.
        static final okhttp3.OkHttpClient INSTANCE = new okhttp3.OkHttpClient.Builder()
                .dispatcher(new okhttp3.Dispatcher())
                .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(14, java.util.concurrent.TimeUnit.SECONDS)
                .retryOnConnectionFailure(true).build();
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

    static Call createCall(String name, String episode, String url) {
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
        List<String> sources = new ArrayList<>(NativeDanmaku.sources());
        sources.addAll(orderedEndpoints(selectedSourceKey));
        List<String> preferred = sources.stream().filter(source -> source.equals(selectedSourceKey)
                || DanmakuManualMatchStore.sourceKey(source).equals(selectedSourceKey)).toList();
        if (preferred.isEmpty()) {
            searchStaged(name, year, type, episode, Objects.toString(selectedName, ""),
                    NativeDanmaku.sources(), orderedEndpoints(selectedSourceKey), callback);
        } else {
            sources.removeAll(preferred);
            searchStaged(name, year, type, episode, Objects.toString(selectedName, ""), preferred, sources, callback);
        }
    }

    // One shared pipeline for automatic matching and recovery of a manually confirmed work.
    // Direct providers get the first opportunity. A stalled catalogue permits backup lookup,
    // and a candidate whose document stalls must not suppress the backup deadline.
    static void searchStaged(String name, String year, String type, String episode, String preferredName,
                             List<String> direct, List<String> backup, SearchCallback callback) {
        int generation = beginSearch();
        SearchStats stats = new SearchStats(generation, callback, backup);
        DanmakuQuery query = DanmakuQuery.from(name, year).forDiscovery(type);
        stats.backupSearch = () -> searchCandidateParallel(generation, query, Objects.toString(year, ""),
                Objects.toString(type, ""), episode, preferredName, stats.backup, callback, 0, 0, stats);
        if (!backup.isEmpty()) App.post(stats.backupDeadline, 5_000L);
        searchCandidateParallel(generation, query, Objects.toString(year, ""),
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

    /** Runs independent provider lookups while sharing one result and cancellation boundary. */
    private static void searchCandidateParallel(int generation, DanmakuQuery query, String year,
                                                String type, String episode, String preferredName,
                                                List<String> apiUrls, SearchCallback callback,
                                                int candidateIndex, int refreshCount,
                                                SearchStats stats) {
        if (generation != REQUEST_GENERATION.get()) return;
        if (candidateIndex >= query.candidates().size() || apiUrls.isEmpty()) {
            stats.startBackup();
            if (stats.phases.decrementAndGet() > 0) return;
            SearchFailure failure = stats.download.get() > 0 ? SearchFailure.DOWNLOAD
                    : stats.responses.get() > 0 ? SearchFailure.NO_MATCH
                    : stats.denied.get() > 0 ? SearchFailure.ACCESS_DENIED
                    : stats.network.get() > 0 ? SearchFailure.NETWORK
                    : stats.invalid.get() > 0 ? SearchFailure.INVALID_RESPONSE : SearchFailure.NO_MATCH;
            App.post(() -> {
                if (generation == REQUEST_GENERATION.get() && stats.finish()) callback.onFailure(failure);
            });
            return;
        }
        String candidateTitle = query.searchTitle();
        AtomicBoolean finished = new AtomicBoolean();
        AtomicBoolean refreshSource = new AtomicBoolean();
        Set<String> retryAfterRefresh = java.util.concurrent.ConcurrentHashMap.newKeySet();
        AtomicInteger remaining = new AtomicInteger(apiUrls.size());
        List<Call> calls = new ArrayList<>();
        List<DanmakuDocumentCache.Ticket> validations = Collections.synchronizedList(new ArrayList<>());
        for (String apiUrl : apiUrls) {
            class ProviderCallback implements IqiyiDanmaku.Callback<List<Danmaku>> {
                final AtomicBoolean completed = new AtomicBoolean();
                boolean accepts(List<Danmaku> items) {
                    return !rank(items).isEmpty();
                }
                List<Danmaku> rank(List<Danmaku> items) {
                    return preferredName.isEmpty()
                            ? DanmakuMatch.ranked(candidateTitle, TextUtils.isEmpty(year) ? query.year() : year.trim(),
                                    type, episode, items, Danmaku::getMatchName)
                            : DanmakuMatch.rankedPreferred(preferredName, year, type, episode, items, Danmaku::getName);
                }
                @Override public void onSuccess(List<Danmaku> items) {
                    if (generation != REQUEST_GENERATION.get() || finished.get()) return;
                    stats.responses.incrementAndGet();
                    String sourceKey = NativeDanmaku.isSource(apiUrl) ? apiUrl : DanmakuManualMatchStore.sourceKey(apiUrl);
                    for (Danmaku item : items) item.setSourceKey(sourceKey);
                    List<Danmaku> candidates = rank(items);
                    if (candidates.isEmpty()) { completeOne(); return; }
                    callback.resolveCandidates(candidates, items, resolved -> {
                        if (generation != REQUEST_GENERATION.get() || finished.get()) return;
                        if (resolved.isEmpty()) { stats.startBackup(); completeOne(); return; }
                        verifyCandidates(generation, resolved, items, 0, finished, calls,
                                validations, stats, refreshSource, retryAfterRefresh, this::completeOne, callback);
                    });
                }
                @Override public void onFailure(IOException error) {
                    if (generation != REQUEST_GENERATION.get() || finished.get()) return;
                    if (error instanceof DanmakuCatalogueSearch.HttpFailure http && (http.code == 401 || http.code == 403)) stats.denied.incrementAndGet();
                    else if (error instanceof DanmakuCatalogueSearch.InvalidResponse) stats.invalid.incrementAndGet();
                    else stats.network.incrementAndGet();
                    stats.failedEndpoints.add(apiUrl);
                    completeOne();
                }
                private void completeOne() {
                    if (generation != REQUEST_GENERATION.get() || finished.get() || !completed.compareAndSet(false, true)) return;
                    if (remaining.decrementAndGet() == 0 && finished.compareAndSet(false, true)) {
                        List<String> usable = new ArrayList<>(apiUrls);
                        usable.removeAll(stats.failedEndpoints);
                        if (refreshSource.get() && refreshCount < 1) {
                            // A refreshed catalogue can return the same stable URL after a
                            // transient 404/5xx. Do not force the user to repeat this manually.
                            for (String url : retryAfterRefresh) if (stats.refreshedUrls.add(url)) stats.rejectedUrls.remove(url);
                            App.post(() -> searchCandidateParallel(generation, query, year, type,
                                    episode, preferredName, usable, callback, 0, refreshCount + 1, stats), 1_200L);
                        } else {
                            searchCandidateParallel(generation, query, year, type, episode,
                                    preferredName, usable, callback, query.candidates().size(), 0, stats);
                        }
                    }
                }
            }
            // Each provider owns its title fallbacks and bounded catalogue request. A slow
            // unrelated platform must not postpone another provider's useful base-title query.
            ProviderCallback provider = new ProviderCallback();
            NativeDanmaku.Job job = DanmakuCatalogueSearch.search(apiUrl, query, year, type, episode, true, provider::accepts, provider);
            NATIVE_SEARCHES.add(job);
            if (generation != REQUEST_GENERATION.get() || stats.delivered.get()) job.cancel();
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
                                         Set<String> retryAfterRefresh, Runnable exhausted,
                                         SearchCallback callback) {
        // Rank and verify identity first. A stalled body may then race one other exact
        // candidate; never serialize many full download timeouts behind the first result.
        class CandidateDownloads {
            int next = index, active;
            boolean exhaustedSent;
            final Runnable hedge = this::advance;

            void advance() {
                Danmaku selected = null;
                boolean complete = false;
                synchronized (this) {
                    if (generation != REQUEST_GENERATION.get() || finished.get() || stats.delivered.get() || active >= 2) return;
                    while (next < candidates.size()) {
                        Danmaku candidate = candidates.get(next++);
                        if (candidate != null && candidate.getUri() != null && !stats.rejectedUrls.contains(candidate.getUrl())) {
                            selected = candidate; active++; break;
                        }
                    }
                    if (selected == null && active == 0 && !exhaustedSent) { exhaustedSent = true; complete = true; }
                }
                if (complete) { exhausted.run(); return; }
                if (selected == null) return;
                Danmaku item = selected;
                stats.attemptedDownloads.incrementAndGet();
                DanmakuDocumentCache.Ticket ticket = DanmakuDocumentCache.loadFresh(item.getUri(), new DanmakuDocumentCache.Listener() {
                    @Override public void onProgress(android.net.Uri source, int percent) {
                        if (generation == REQUEST_GENERATION.get() && !finished.get() && !stats.delivered.get()) callback.onProgress(percent);
                    }
                    @Override public void onReady(android.net.Uri source, android.net.Uri local) {
                        if (generation != REQUEST_GENERATION.get() || !finished.compareAndSet(false, true) || !stats.finish()) return;
                        App.removeCallbacks(hedge);
                        for (Call pending : calls) if (pending != null) pending.cancel();
                        cancelNativeSearches();
                        synchronized (validations) {
                            for (DanmakuDocumentCache.Ticket validation : validations) validation.cancel();
                            validations.clear();
                        }
                        cancelValidations();
                        callback.onFound(item, catalogue);
                    }
                    @Override public void onFailure(android.net.Uri source, IOException error) {
                        if (generation != REQUEST_GENERATION.get() || finished.get() || stats.delivered.get()) return;
                        stats.download.incrementAndGet();
                        stats.startBackup();
                        if (DanmakuLoadPolicy.shouldRefreshSource(error)) {
                            refreshSource.set(true);
                            if (DanmakuLoadPolicy.canRetryAfterCatalogueRefresh(error)) retryAfterRefresh.add(item.getUrl());
                        }
                        stats.rejectedUrls.add(item.getUrl());
                        DanmakuDocumentCache.invalidate(source);
                        synchronized (CandidateDownloads.this) { active--; }
                        advance();
                    }
                });
                validations.add(ticket);
                ACTIVE_VALIDATIONS.add(ticket);
                if (generation != REQUEST_GENERATION.get() || stats.delivered.get()) {
                    ticket.cancel(); ACTIVE_VALIDATIONS.remove(ticket);
                } else {
                    App.removeCallbacks(hedge);
                    App.post(hedge, 1_000L);
                }
            }
        }
        new CandidateDownloads().advance();
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
        private final Runnable backupDeadline;
        private final int generation;
        private Runnable backupSearch;
        private final AtomicInteger phases = new AtomicInteger(1);
        private final AtomicInteger attemptedDownloads = new AtomicInteger();

        private final AtomicBoolean directPhase = new AtomicBoolean(true);
        private final List<String> backup;

        SearchStats(int generation, SearchCallback callback, List<String> backup) {
            this.generation = generation;
            this.backup = List.copyOf(backup);
            // Five seconds of direct preference, then race verified documents. Finding
            // a title is not proof that its download is usable. Keep the direct job alive:
            // a failed backup must not discard a late but healthy platform response.
            backupDeadline = this::startBackup;
            deadline = () -> {
                if (generation != REQUEST_GENERATION.get() || !delivered.compareAndSet(false, true)) return;
                beginSearch();
                callback.onFailure(attemptedDownloads.get() > 0 ? SearchFailure.DOWNLOAD
                        : responses.get() > 0 ? SearchFailure.NO_MATCH
                        : denied.get() > 0 ? SearchFailure.ACCESS_DENIED
                        : invalid.get() > 0 ? SearchFailure.INVALID_RESPONSE : SearchFailure.NETWORK);
            };
            // Includes title fallbacks and comment validation; no sequence of stale IDs can
            // leave automatic matching spinning for minutes.
            // Preserve enough time for a bounded direct document failure followed by a
            // backup document. Faster catalogue selection must not truncate a valid timeline.
            App.post(deadline, 90_000L);
        }

        void startBackup() {
            if (generation != REQUEST_GENERATION.get() || delivered.get() || backup.isEmpty()
                    || !directPhase.compareAndSet(true, false)) return;
            phases.incrementAndGet();
            App.removeCallbacks(backupDeadline);
            backupSearch.run();
        }

        boolean finish() {
            if (!delivered.compareAndSet(false, true)) return false;
            App.removeCallbacks(deadline);
            App.removeCallbacks(backupDeadline);
            return true;
        }

        private final AtomicInteger invalid = new AtomicInteger();
        private final AtomicInteger responses = new AtomicInteger();
        private final AtomicInteger network = new AtomicInteger();
        private final AtomicInteger download = new AtomicInteger();
        private final AtomicInteger denied = new AtomicInteger();
        private final Set<String> failedEndpoints = Collections.synchronizedSet(new HashSet<>());
        private final Set<String> rejectedUrls = Collections.synchronizedSet(new HashSet<>());
        private final Set<String> refreshedUrls = Collections.synchronizedSet(new HashSet<>());
    }
}
