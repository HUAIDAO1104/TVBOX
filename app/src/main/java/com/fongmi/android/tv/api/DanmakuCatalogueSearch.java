package com.fongmi.android.tv.api;

import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.playback.vod.DanmakuQuery;
import com.fongmi.android.tv.playback.vod.DanmakuResultGrouper;
import java.io.IOException;
import java.util.List;
import okhttp3.*;

/** Bounded catalogue lookup. Only result metadata can justify a numeric title alias. */
public final class DanmakuCatalogueSearch implements NativeDanmaku.Job {
    private static final java.util.concurrent.ScheduledExecutorService TIMER = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile java.util.concurrent.Future<?> timeout;
    private volatile java.util.concurrent.Future<?> hedge;
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
    private final List<Attempt> attempts;
    private int winner = -1;
    private IOException lastError;

    private static final class Attempt {
        boolean started, complete;
        NativeDanmaku.Job job;
        Call call;
        void cancel() { if (job != null) job.cancel(); if (call != null) call.cancel(); }
    }

    private DanmakuCatalogueSearch(String source, DanmakuQuery query, String year, String type,
                                  String episode, boolean targeted, java.util.function.Predicate<List<Danmaku>> acceptable,
                                  IqiyiDanmaku.Callback<List<Danmaku>> callback) {
        this.source = source; this.query = query; this.year = year == null ? "" : year;
        this.type = type; this.episode = episode; this.targeted = targeted; this.callback = callback;
        this.acceptable = acceptable;
        this.attempts = query.candidates().stream().map(title -> new Attempt()).toList();
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
        // Covers DNS, worker queues and exact-to-base retries together, not per attempt.
        if (!targeted) job.timeout = TIMER.schedule(() -> job.failure(new java.net.SocketTimeoutException("Catalogue search timed out")),
                20, java.util.concurrent.TimeUnit.SECONDS);
        job.next(0);
        // A slow/empty numeric query must not hold the usable base title behind its entire
        // catalogue. Manual results still pass the original work/year acceptance predicate.
        if (!targeted && job.attempts.size() > 1) job.hedge = TIMER.schedule(job::startFallback, 350, java.util.concurrent.TimeUnit.MILLISECONDS);
        return job;
    }
    private void startFallback() {
        int next = -1;
        synchronized (this) {
            if (winner >= 0 || cancelled || finished.get()) return;
            for (int i = 0; i < attempts.size(); i++) if (!attempts.get(i).started) { next = i; break; }
        }
        if (next >= 0) next(next);
    }
    private synchronized void next(int index) {
        if (cancelled || finished.get() || winner >= 0) return;
        if (index >= attempts.size()) { success(List.of()); return; }
        Attempt attempt = attempts.get(index);
        if (attempt.started) return;
        attempt.started = true;
        String title = query.candidates().get(index);
        var receiver = new IqiyiDanmaku.Callback<List<Danmaku>>() {
            public void onPartial(List<Danmaku> items) {
                if (!targeted) received(index, items, false);
            }
            public void onSuccess(List<Danmaku> items) {
                received(index, items, true);
            }
            public void onFailure(IOException error) {
                rejected(index, error);
            }
        };
        if (NativeDanmaku.isSource(source)) {
            attempt.job = targeted ? NativeDanmaku.search(source, title, year.isEmpty() ? query.year() : year, type, episode, receiver)
                    : NativeDanmaku.search(source, title, receiver);
        } else {
            attempt.call = DanmakuApi.createCall(title, episode, source);
            attempt.call.enqueue(new Callback() {
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
        if (cancelled || finished.get() || winner >= 0 && winner != index) attempt.cancel();
    }
    private void received(int index, List<Danmaku> items, boolean complete) {
        if (cancelled || finished.get()) return;
        // Matching and consumers may rank a large catalogue. Never hold the cancellation
        // lock while doing that work, otherwise tapping a new search can block the UI.
        boolean accepted = acceptable.test(items);
        synchronized (this) {
            if (cancelled || finished.get() || winner >= 0 && winner != index) return;
            if (complete) attempts.get(index).complete = true;
            if (accepted && winner < 0) {
                winner = index;
                if (hedge != null) hedge.cancel(false);
                for (int i = 0; i < attempts.size(); i++) if (i != index) attempts.get(i).cancel();
            }
        }
        if (accepted) {
            if (complete) success(items);
            else if (!cancelled && !finished.get()) callback.onPartial(items);
        } else if (complete) {
            advance();
        }
    }
    private void rejected(int index, IOException error) {
        boolean terminal;
        synchronized (this) {
            if (cancelled || finished.get() || winner >= 0 && winner != index) return;
            terminal = targeted || winner == index;
            attempts.get(index).complete = true;
            lastError = error;
        }
        if (terminal) failure(error);
        else advance();
    }
    private void advance() {
        startFallback();
        IOException error;
        synchronized (this) {
            if (cancelled || finished.get() || winner >= 0) return;
            if (!attempts.stream().allMatch(attempt -> attempt.complete)) return;
            error = lastError;
        }
        if (error == null) success(List.of());
        else failure(error);
    }
    private void success(List<Danmaku> items) {
        if (!cancelled && finished.compareAndSet(false, true)) {
            cleanup();
            if (!cancelled) callback.onSuccess(items);
        }
    }
    private void failure(IOException error) {
        if (!cancelled && finished.compareAndSet(false, true)) {
            cleanup();
            callback.onFailure(error);
        }
    }
    @Override public void cancel() {
        cancelled = true;
        finished.set(true);
        cleanup();
    }
    private synchronized void cleanup() {
        if (timeout != null) timeout.cancel(false);
        if (hedge != null) hedge.cancel(false);
        for (Attempt attempt : attempts) if (!attempt.complete) attempt.cancel();
    }
}
