package com.fongmi.android.tv.api;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.collection.ArrayMap;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.playback.vod.DanmakuMatch;
import com.fongmi.android.tv.playback.vod.DanmakuQuery;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Trans;

import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
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
        search(generation, query, Objects.toString(year, ""), Objects.toString(type, ""), episode, found, 0);
    }

    private static void search(int generation, DanmakuQuery query, String year, String type, String episode,
                               Consumer<Danmaku> found, int candidateIndex) {
        if (generation != REQUEST_GENERATION.get() || candidateIndex >= query.candidates().size()) return;
        String candidateTitle = query.candidates().get(candidateIndex);
        createCall(candidateTitle, episode).enqueue(new Callback() {
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response closeable = response) {
                    if (generation != REQUEST_GENERATION.get()) return;
                    if (closeable.body() == null) {
                        search(generation, query, year, type, episode, found, candidateIndex + 1);
                        return;
                    }
                    // Match against the title actually sent in this round. Earlier rounds may use a
                    // decorated provider title whose canonical form can never equal a catalogue
                    // entry; judging those results with the cleaned fallback title would reject
                    // every correct candidate and skip automatic loading entirely.
                    Danmaku best = bestMatch(candidateTitle, query, year, type, episode, Danmaku.arrayFrom(closeable.body().string()));
                    if (best == null) {
                        search(generation, query, year, type, episode, found, candidateIndex + 1);
                        return;
                    }
                    App.post(() -> {
                        if (generation == REQUEST_GENERATION.get()) found.accept(best);
                    });
                } catch (Exception ignored) {
                    search(generation, query, year, type, episode, found, candidateIndex + 1);
                }
            }

            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (generation == REQUEST_GENERATION.get()) {
                    search(generation, query, year, type, episode, found, candidateIndex + 1);
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
