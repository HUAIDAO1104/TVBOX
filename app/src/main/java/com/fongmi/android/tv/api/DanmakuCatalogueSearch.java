package com.fongmi.android.tv.api;

import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.playback.vod.DanmakuQuery;
import com.fongmi.android.tv.playback.vod.DanmakuResultGrouper;
import java.io.IOException;
import java.util.List;
import okhttp3.*;

/** Shared exact-then-base lookup. Only result metadata can justify a numeric title alias. */
public final class DanmakuCatalogueSearch implements NativeDanmaku.Job {
    public static final class HttpFailure extends IOException {
        public final int code;
        HttpFailure(int code) { super("Search HTTP " + code); this.code = code; }
    }
    public static final class InvalidResponse extends IOException {
        InvalidResponse(Throwable cause) { super("Invalid search response", cause); }
    }
    private final String source, year, type, episode;
    private final DanmakuQuery query;
    private final boolean targeted;
    private final IqiyiDanmaku.Callback<List<Danmaku>> callback;
    private final java.util.function.Predicate<List<Danmaku>> acceptable;
    private volatile boolean cancelled;
    private final java.util.Set<NativeDanmaku.Job> nativeJobs = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile Call call;

    private DanmakuCatalogueSearch(String source, DanmakuQuery query, String year, String type,
                                  String episode, boolean targeted, java.util.function.Predicate<List<Danmaku>> acceptable,
                                  IqiyiDanmaku.Callback<List<Danmaku>> callback) {
        this.source = source; this.query = query; this.year = year == null ? "" : year;
        this.type = type; this.episode = episode; this.targeted = targeted; this.callback = callback;
        this.acceptable = acceptable;
    }
    public static NativeDanmaku.Job search(String source, DanmakuQuery query, String year, String type,
                                           String episode, boolean targeted, IqiyiDanmaku.Callback<List<Danmaku>> callback) {
        return search(source, query, year, type, episode, targeted,
                items -> items.stream().anyMatch(item -> DanmakuResultGrouper.isWorkCandidate(query.searchTitle(), item)), callback);
    }
    static NativeDanmaku.Job search(String source, DanmakuQuery query, String year, String type,
                                    String episode, boolean targeted, java.util.function.Predicate<List<Danmaku>> acceptable,
                                    IqiyiDanmaku.Callback<List<Danmaku>> callback) {
        var job = new DanmakuCatalogueSearch(source, query, year, type, episode, targeted, acceptable, callback);
        job.next(0); return job;
    }
    private void next(int index) {
        if (cancelled) return;
        if (index >= query.candidates().size()) { callback.onSuccess(List.of()); return; }
        String title = query.candidates().get(index);
        var receiver = new IqiyiDanmaku.Callback<List<Danmaku>>() {
            public void onSuccess(List<Danmaku> items) {
                if (cancelled) return;
                // The list is ranked once by its consumer, not twice on a slow TV.
                if (!acceptable.test(items)) next(index + 1);
                else callback.onSuccess(items);
            }
            public void onFailure(IOException error) {
                if (!cancelled) callback.onFailure(error);
            }
        };
        if (NativeDanmaku.isSource(source)) {
            nativeJobs.add(targeted ? NativeDanmaku.search(source, title, year.isEmpty() ? query.year() : year, type, episode, receiver)
                    : NativeDanmaku.search(source, title, receiver));
        } else {
            call = DanmakuApi.createCall(title, episode, source);
            call.enqueue(new Callback() {
                public void onFailure(Call call, IOException error) { receiver.onFailure(error); }
                public void onResponse(Call call, Response response) {
                    try (Response closeable = response) {
                        if (!closeable.isSuccessful() || closeable.body() == null) throw new HttpFailure(closeable.code());
                        receiver.onSuccess(Danmaku.arrayFrom(closeable.body().string()));
                    } catch (Exception error) {
                        receiver.onFailure(error instanceof IOException io ? io : new InvalidResponse(error));
                    }
                }
            });
        }
        if (cancelled) cancel();
    }
    @Override public void cancel() {
        cancelled = true;
        for (NativeDanmaku.Job job : nativeJobs) job.cancel();
        nativeJobs.clear();
        if (call != null) call.cancel();
    }
}
