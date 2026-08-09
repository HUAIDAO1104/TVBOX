package com.fongmi.android.tv.api;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.collection.ArrayMap;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.playback.vod.DanmakuMatch;
import com.fongmi.android.tv.playback.vod.DanmakuManualMatchStore;
import com.fongmi.android.tv.playback.vod.DanmakuQuery;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Trans;

import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import okhttp3.Call;
import okhttp3.Response;

public class DanmakuApi {

    private static final String TAG = DanmakuApi.class.getSimpleName();
    private static final AtomicInteger REQUEST_GENERATION = new AtomicInteger();

    public static boolean canSearch() {
        return DanmakuSetting.isLoad() && DanmakuSetting.isAuto() && !TextUtils.isEmpty(DanmakuSetting.getAutomaticApiUrl());
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

    private static Call createCall(String name, String episode) {
        return createCall(name, episode, Objects.toString(DanmakuSetting.getAutomaticApiUrl(), ""));
    }

    private static Call createCall(String name, String episode, String url) {
        name = Trans.t2s(Objects.toString(name, ""));
        episode = Trans.t2s(Objects.toString(episode, ""));
        url = Objects.toString(url, "");
        if (url.contains("{name}") || url.contains("{episode}")) {
            return OkHttp.newCall(url.replace("{name}", name).replace("{episode}", episode), TAG);
        } else {
            ArrayMap<String, String> params = new ArrayMap<>();
            params.put("name", name);
            params.put("episode", episode);
            return OkHttp.newCall(url, OkHttp.toBody(params), TAG);
        }
    }

    public static void search(String name, String episode, Consumer<Danmaku> found) {
        search(name, "", "", episode, found);
    }

    public static void search(String name, String year, String type, String episode, Consumer<Danmaku> found) {
        final int generation = REQUEST_GENERATION.incrementAndGet();
        OkHttp.cancel(TAG);
        DanmakuQuery query = DanmakuQuery.from(name);
        search(generation, query, Objects.toString(year, ""), Objects.toString(type, ""), episode,
                "", List.of(DanmakuSetting.getAutomaticApiUrl()), found, 0, 0);
    }

    /** Reuses a user-confirmed catalogue/provider identity while resolving a new episode URL. */
    public static void searchPreferred(String name, String year, String type, String episode,
                                       String selectedName, String selectedSourceKey,
                                       BiConsumer<Danmaku, List<Danmaku>> found) {
        final int generation = REQUEST_GENERATION.incrementAndGet();
        OkHttp.cancel(TAG);
        DanmakuQuery query = DanmakuQuery.from(name);
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (String apiUrl : DanmakuSetting.getSearchApiUrls()) {
            if (DanmakuManualMatchStore.sourceKey(apiUrl).equals(selectedSourceKey)) ordered.add(apiUrl);
        }
        ordered.add(DanmakuSetting.getAutomaticApiUrl());
        ordered.addAll(DanmakuSetting.getSearchApiUrls());
        ordered.removeIf(value -> value == null || value.isBlank());
        searchPreferredParallel(generation, query.searchTitle(), Objects.toString(year, ""),
                Objects.toString(type, ""), episode, Objects.toString(selectedName, ""),
                new ArrayList<>(ordered), found);
    }

    /**
     * A remembered manual query has already been verified by the person using the app. Query its
     * eligible endpoints concurrently: the old candidate-by-endpoint recursion multiplied every
     * unavailable endpoint's 30 second timeout and was the source of minute-long episode changes.
     */
    private static void searchPreferredParallel(int generation, String query, String year,
                                                String type, String episode, String preferredName,
                                                List<String> apiUrls,
                                                BiConsumer<Danmaku, List<Danmaku>> found) {
        if (generation != REQUEST_GENERATION.get() || query.isEmpty() || apiUrls.isEmpty()) return;
        AtomicBoolean delivered = new AtomicBoolean();
        List<Call> calls = new ArrayList<>();
        for (String apiUrl : apiUrls) calls.add(createCall(query, episode, apiUrl));
        for (int index = 0; index < calls.size(); index++) {
            Call current = calls.get(index);
            String apiUrl = apiUrls.get(index);
            current.enqueue(new Callback() {
                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) {
                    try (Response closeable = response) {
                        if (generation != REQUEST_GENERATION.get() || delivered.get()
                                || closeable.body() == null) return;
                        List<Danmaku> items = Danmaku.arrayFrom(closeable.body().string());
                        String sourceKey = DanmakuManualMatchStore.sourceKey(apiUrl);
                        for (Danmaku item : items) item.setSourceKey(sourceKey);
                        Danmaku best = DanmakuMatch.bestPreferred(preferredName, year, type,
                                episode, items, Danmaku::getName);
                        if (best == null || !delivered.compareAndSet(false, true)) return;
                        for (Call pending : calls) if (pending != call) pending.cancel();
                        App.post(() -> {
                            if (generation == REQUEST_GENERATION.get()) found.accept(best, items);
                        });
                    } catch (Exception ignored) {
                    }
                }

                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    // Other independent endpoints continue; one timeout cannot block them.
                }
            });
        }
    }

    private static void search(int generation, DanmakuQuery query, String year, String type, String episode,
                               String preferredName, List<String> apiUrls, Consumer<Danmaku> found,
                               int candidateIndex, int apiIndex) {
        if (generation != REQUEST_GENERATION.get() || candidateIndex >= query.candidates().size()) return;
        if (apiIndex >= apiUrls.size()) {
            search(generation, query, year, type, episode, preferredName, apiUrls, found,
                    candidateIndex + 1, 0);
            return;
        }
        String candidateTitle = query.candidates().get(candidateIndex);
        createCall(candidateTitle, episode, apiUrls.get(apiIndex)).enqueue(new Callback() {
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response closeable = response) {
                    if (generation != REQUEST_GENERATION.get()) return;
                    if (closeable.body() == null) {
                        search(generation, query, year, type, episode, preferredName, apiUrls, found,
                                candidateIndex, apiIndex + 1);
                        return;
                    }
                    // Match against the title actually sent in this round. Earlier rounds may use a
                    // decorated provider title whose canonical form can never equal a catalogue
                    // entry; judging those results with the cleaned fallback title would reject
                    // every correct candidate and skip automatic loading entirely.
                    List<Danmaku> items = Danmaku.arrayFrom(closeable.body().string());
                    Danmaku best = preferredName.isEmpty()
                            ? bestMatch(candidateTitle, query, year, type, episode, items)
                            : DanmakuMatch.bestPreferred(preferredName, year, type, episode,
                                    items, Danmaku::getName);
                    if (best == null) {
                        search(generation, query, year, type, episode, preferredName, apiUrls, found,
                                candidateIndex, apiIndex + 1);
                        return;
                    }
                    App.post(() -> {
                        if (generation == REQUEST_GENERATION.get()) found.accept(best);
                    });
                } catch (Exception ignored) {
                    search(generation, query, year, type, episode, preferredName, apiUrls, found,
                            candidateIndex, apiIndex + 1);
                }
            }

            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (generation == REQUEST_GENERATION.get()) {
                    search(generation, query, year, type, episode, preferredName, apiUrls, found,
                            candidateIndex, apiIndex + 1);
                }
            }
        });
    }

    static Danmaku bestMatch(String name, String episode, List<Danmaku> items) {
        return DanmakuMatch.best(name, "", episode, items, Danmaku::getName);
    }

    static Danmaku bestMatch(DanmakuQuery query, String episode, List<Danmaku> items) {
        return DanmakuMatch.best(query.searchTitle(), query.year(), episode, items, Danmaku::getName);
    }

    static Danmaku bestMatch(DanmakuQuery query, String year, String type, String episode, List<Danmaku> items) {
        String expectedYear = TextUtils.isEmpty(year) ? query.year() : year.trim();
        return DanmakuMatch.best(query.searchTitle(), expectedYear, type, episode, items, Danmaku::getName);
    }

    static Danmaku bestMatch(String title, DanmakuQuery query, String year, String type, String episode, List<Danmaku> items) {
        String expectedYear = year == null || year.isEmpty() ? query.year() : year.trim();
        return DanmakuMatch.best(title, expectedYear, type, episode, items, Danmaku::getName);
    }

    public static void cancel() {
        REQUEST_GENERATION.incrementAndGet();
        OkHttp.cancel(TAG);
    }
}
