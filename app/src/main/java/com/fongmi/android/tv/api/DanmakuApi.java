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
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Trans;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import okhttp3.Call;
import okhttp3.Response;

public class DanmakuApi {

    public enum SearchFailure { NO_MATCH, NETWORK, INVALID_RESPONSE }

    public interface SearchCallback {
        void onFound(Danmaku item, List<Danmaku> catalogue);
        void onFailure(SearchFailure failure);
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
                new SearchStats());
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
                orderedEndpoints(selectedSourceKey), callback, 0, new SearchStats());
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
                                                int candidateIndex, SearchStats stats) {
        if (generation != REQUEST_GENERATION.get()) return;
        if (candidateIndex >= query.candidates().size() || apiUrls.isEmpty()) {
            SearchFailure failure = stats.valid.get() > 0 ? SearchFailure.NO_MATCH
                    : stats.invalid.get() > 0 ? SearchFailure.INVALID_RESPONSE : SearchFailure.NETWORK;
            App.post(() -> {
                if (generation == REQUEST_GENERATION.get()) callback.onFailure(failure);
            });
            return;
        }
        String candidateTitle = query.candidates().get(candidateIndex);
        AtomicBoolean finished = new AtomicBoolean();
        AtomicInteger remaining = new AtomicInteger(apiUrls.size());
        List<Call> calls = new ArrayList<>();
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
                        Danmaku best = preferredName.isEmpty()
                                ? bestMatch(candidateTitle, query, year, type, episode, items)
                                : DanmakuMatch.bestPreferred(preferredName, year, type,
                                        episode, items, Danmaku::getName);
                        if (best == null) {
                            completeOne();
                            return;
                        }
                        if (!finished.compareAndSet(false, true)) return;
                        for (Call pending : calls) if (pending != call) pending.cancel();
                        App.post(() -> {
                            if (generation == REQUEST_GENERATION.get()) callback.onFound(best, items);
                        });
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
                        searchCandidateParallel(generation, query, year, type, episode,
                                preferredName, apiUrls, callback, candidateIndex + 1, stats);
                    }
                }
            });
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
        REQUEST_GENERATION.incrementAndGet();
        OkHttp.cancel(TAG);
    }

    private static final class SearchStats {
        private final AtomicInteger valid = new AtomicInteger();
        private final AtomicInteger invalid = new AtomicInteger();
        private final AtomicInteger network = new AtomicInteger();
    }
}
