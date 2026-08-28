package com.fongmi.android.tv.api;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.collection.ArrayMap;

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

    public enum SearchFailure { NO_MATCH, NETWORK, INVALID_RESPONSE, DOWNLOAD }

    public interface SearchCallback {
        void onFound(Danmaku item, List<Danmaku> catalogue);
        void onFailure(SearchFailure failure);
        default void onProgress(int percent) {
        }
    }

    private static final String TAG = DanmakuApi.class.getSimpleName();
    private static final AtomicInteger REQUEST_GENERATION = new AtomicInteger();

    public static boolean canSearch() {
        return DanmakuSetting.isLoad() && DanmakuSetting.isAuto()
                && !TextUtils.isEmpty(DanmakuSetting.getAutomaticApiUrl());
    }

    public static Call newCall(String name, String episode) {
        return newCalls(name, episode).get(0);
    }

    public static List<Call> newCalls(String name, String episode) {
        REQUEST_GENERATION.incrementAndGet();
        OkHttp.cancel(TAG);
        String query = DanmakuQuery.from(name).searchTitle();
        List<Call> calls = new ArrayList<>();
        for (String url : DanmakuSetting.getSearchApiUrls()) calls.add(createCall(query, episode, url));
        return calls;
    }

    private static Call createCall(String name, String episode, String url) {
        name = Trans.t2s(Objects.toString(name, ""));
        episode = Trans.t2s(Objects.toString(episode, ""));
        url = Objects.toString(url, "");
        if (url.contains("{name}") || url.contains("{episode}")) {
            return OkHttp.newCall(url.replace("{name}", name).replace("{episode}", episode), TAG);
        }
        ArrayMap<String, String> params = new ArrayMap<>();
        params.put("name", name);
        params.put("episode", episode);
        return OkHttp.newCall(url, OkHttp.toBody(params), TAG);
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
        int generation = REQUEST_GENERATION.incrementAndGet();
        OkHttp.cancel(TAG);
        searchCandidateParallel(generation, DanmakuQuery.from(name), Objects.toString(year, ""),
                Objects.toString(type, ""), episode, "", orderedEndpoints(""), callback, 0,
                0, new SearchStats());
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
        int generation = REQUEST_GENERATION.incrementAndGet();
        OkHttp.cancel(TAG);
        searchCandidateParallel(generation, DanmakuQuery.from(name), Objects.toString(year, ""),
                Objects.toString(type, ""), episode, Objects.toString(selectedName, ""),
                orderedEndpoints(selectedSourceKey), callback, 0, 0, new SearchStats());
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
            SearchFailure failure = stats.download.get() > 0 ? SearchFailure.DOWNLOAD
                    : stats.valid.get() > 0 ? SearchFailure.NO_MATCH
                    : stats.invalid.get() > 0 ? SearchFailure.INVALID_RESPONSE : SearchFailure.NETWORK;
            App.post(() -> {
                if (generation == REQUEST_GENERATION.get()) callback.onFailure(failure);
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
        for (String url : apiUrls) calls.add(createCall(candidateTitle, episode, url));
        for (int i = 0; i < calls.size(); i++) {
            Call current = calls.get(i);
            String apiUrl = apiUrls.get(i);
            current.enqueue(new Callback() {
                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) {
                    try (Response closeable = response) {
                        if (generation != REQUEST_GENERATION.get() || finished.get()) return;
                        if (!closeable.isSuccessful() || closeable.body() == null) {
                            stats.network.incrementAndGet();
                            completeOne();
                            return;
                        }
                        List<Danmaku> items = Danmaku.arrayFrom(closeable.body().string());
                        stats.valid.incrementAndGet();
                        String sourceKey = DanmakuManualMatchStore.sourceKey(apiUrl);
                        for (Danmaku item : items) item.setSourceKey(sourceKey);
                        List<Danmaku> candidates = preferredName.isEmpty()
                                ? DanmakuMatch.ranked(candidateTitle,
                                        TextUtils.isEmpty(year) ? query.year() : year.trim(), type,
                                        episode, items, Danmaku::getName)
                                : DanmakuMatch.rankedPreferred(preferredName, year, type,
                                        episode, items, Danmaku::getName);
                        if (candidates.isEmpty()) {
                            completeOne();
                            return;
                        }
                        verifyCandidates(generation, candidates, items, 0, finished, calls,
                                validations, stats, refreshSource, this::completeOne, callback);
                    } catch (Exception error) {
                        stats.invalid.incrementAndGet();
                        completeOne();
                    }
                }

                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException error) {
                    if (generation != REQUEST_GENERATION.get() || finished.get()) return;
                    stats.network.incrementAndGet();
                    completeOne();
                }

                private void completeOne() {
                    if (generation != REQUEST_GENERATION.get() || finished.get()) return;
                    if (remaining.decrementAndGet() == 0 && finished.compareAndSet(false, true)) {
                        if (refreshSource.get() && refreshCount < 1) {
                            // The service creates short-lived comment ids. A freshly returned id
                            // can still answer 404/5xx while its backing document is being
                            // prepared. Re-run the same strict title/season/episode query once to
                            // obtain a new id instead of accepting a wrong season or failing fast.
                            App.post(() -> searchCandidateParallel(generation, query, year, type,
                                    episode, preferredName, apiUrls, callback, candidateIndex,
                                    refreshCount + 1, stats), 1_200L);
                        } else {
                            searchCandidateParallel(generation, query, year, type, episode,
                                    preferredName, apiUrls, callback, candidateIndex + 1, 0, stats);
                        }
                    }
                }
            });
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
                        if (!finished.compareAndSet(false, true)) return;
                        for (Call pending : calls) pending.cancel();
                        synchronized (validations) {
                            for (DanmakuDocumentCache.Ticket validation : validations) {
                                validation.cancel();
                            }
                            validations.clear();
                        }
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
        REQUEST_GENERATION.incrementAndGet();
        OkHttp.cancel(TAG);
    }

    private static final class SearchStats {
        private final AtomicInteger valid = new AtomicInteger();
        private final AtomicInteger invalid = new AtomicInteger();
        private final AtomicInteger network = new AtomicInteger();
        private final AtomicInteger download = new AtomicInteger();
        private final Set<String> rejectedUrls = Collections.synchronizedSet(new HashSet<>());
    }
}
