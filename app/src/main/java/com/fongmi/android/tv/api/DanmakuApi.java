package com.fongmi.android.tv.api;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.collection.ArrayMap;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.playback.vod.DanmakuMatch;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Trans;

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
        return DanmakuSetting.isLoad() && DanmakuSetting.isAuto() && !TextUtils.isEmpty(DanmakuSetting.getEffectiveApiUrl());
    }

    public static Call newCall(String name, String episode) {
        return newCalls(name, episode).get(0);
    }

    public static List<Call> newCalls(String name, String episode) {
        REQUEST_GENERATION.incrementAndGet();
        OkHttp.cancel(TAG);
        List<Call> calls = new ArrayList<>();
        for (String url : DanmakuSetting.getSearchApiUrls()) calls.add(createCall(name, episode, url));
        return calls;
    }

    private static Call createCall(String name, String episode) {
        return createCall(name, episode, Objects.toString(DanmakuSetting.getEffectiveApiUrl(), ""));
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
        final int generation = REQUEST_GENERATION.incrementAndGet();
        OkHttp.cancel(TAG);
        createCall(name, episode).enqueue(new Callback() {
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try {
                    if (generation != REQUEST_GENERATION.get() || response.body() == null) return;
                    Danmaku best = bestMatch(name, episode, Danmaku.arrayFrom(response.body().string()));
                    if (best == null) return;
                    App.post(() -> {
                        if (generation == REQUEST_GENERATION.get()) found.accept(best);
                    });
                } catch (Exception ignored) {
                }
            }
        });
    }

    static Danmaku bestMatch(String name, String episode, List<Danmaku> items) {
        Danmaku best = null;
        int bestScore = Integer.MIN_VALUE;
        if (items == null) return null;
        for (Danmaku item : items) {
            if (item == null || item.isEmpty() || !DanmakuMatch.isReliable(name, episode, item.getName())) continue;
            int score = DanmakuMatch.score(name, episode, item.getName());
            // Keep source ordering stable for equal scores. APIs commonly place their preferred
            // provider first and a later tie must not cause the selected source to jump.
            if (best == null || score > bestScore) {
                best = item;
                bestScore = score;
            }
        }
        return best;
    }

    public static void cancel() {
        REQUEST_GENERATION.incrementAndGet();
        OkHttp.cancel(TAG);
    }
}
