package com.fongmi.android.tv.playback.vod;

import androidx.media3.common.Player;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.BilibiliDanmaku;
import com.fongmi.android.tv.api.IqiyiDanmaku;
import com.fongmi.android.tv.api.NativeDanmaku;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.danmaku.DanmakuDocumentCache;
import com.fongmi.android.tv.player.danmaku.DanmakuStatus;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Final playback gate: an episode number alone does not identify a recut timeline. */
final class BilibiliPlaybackMatcher implements NativeDanmaku.Job {
    private final PlayerManager player;
    private final DanmakuMatchContext context;
    private final BiConsumer<Danmaku, List<Danmaku>> ready;
    private final Consumer<DanmakuStatus.Failure> failure;
    private final long deadline = android.os.SystemClock.elapsedRealtime() + 3000;
    private final Runnable poll = this::select;
    private NativeDanmaku.Job catalogueJob;
    private DanmakuDocumentCache.Ticket document;
    private List<Danmaku> catalogue, candidates;
    private volatile boolean cancelled;
    private boolean resolveOnly;

    BilibiliPlaybackMatcher(PlayerManager player, DanmakuMatchContext context,
                            BiConsumer<Danmaku, List<Danmaku>> ready, Consumer<DanmakuStatus.Failure> failure) {
        this.player = player; this.context = context; this.ready = ready; this.failure = failure;
    }

    void start(Danmaku initial) {
        player.notifyDanmakuStatus(DanmakuStatus.matching());
        catalogueJob = BilibiliDanmaku.playbackCatalogue(initial.getUrl(), new IqiyiDanmaku.Callback<>() {
            public void onSuccess(List<Danmaku> rows) {
                App.post(() -> {
                    if (cancelled) return;
                    catalogue = rows; candidates = BilibiliDanmaku.timelineCandidates(context, rows);
                    select();
                });
            }
            public void onFailure(IOException error) { App.post(() -> fail(DanmakuStatus.Failure.NETWORK)); }
        });
    }

    void resolve(Danmaku initial) {
        resolveOnly = true;
        start(initial);
    }

    private void select() {
        if (cancelled) return;
        if (candidates.isEmpty()) { fail(DanmakuStatus.Failure.NO_MATCH); return; }
        long duration = 0;
        var media = player.getPlayer().getCurrentMediaItem();
        // During parsing the old engine can still be READY. Only the new video's timeline
        // may decide its cut; reading an old duration would silently reintroduce cross-episode matches.
        if (player.getPlaybackState() == Player.STATE_READY && media != null && media.localConfiguration != null
                && Objects.equals(player.getUrl(), media.localConfiguration.uri.toString())) duration = player.getDuration();
        if (candidates.size() > 1 && duration <= 0 && android.os.SystemClock.elapsedRealtime() < deadline) {
            App.post(poll, 200); return;
        }
        Danmaku selected = BilibiliDanmaku.chooseTimeline(candidates, duration);
        if (selected == null) { fail(DanmakuStatus.Failure.NO_MATCH); return; }
        if (resolveOnly) {
            cancelled = true;
            ready.accept(selected, catalogue);
            return;
        }
        document = DanmakuDocumentCache.load(selected.getUri(), new DanmakuDocumentCache.Listener() {
            public void onProgress(android.net.Uri source, int percent) {
                if (!cancelled) player.notifyDanmakuStatus(DanmakuStatus.downloading(percent));
            }
            public void onReady(android.net.Uri source, android.net.Uri local) {
                if (!cancelled) { cancelled = true; ready.accept(selected, catalogue); }
            }
            public void onFailure(android.net.Uri source, IOException error) { fail(DanmakuStatus.Failure.DOWNLOAD); }
        });
    }

    private void fail(DanmakuStatus.Failure reason) {
        if (cancelled) return;
        cancel(); failure.accept(reason);
    }

    public void cancel() {
        cancelled = true;
        App.removeCallbacks(poll);
        if (catalogueJob != null) catalogueJob.cancel();
        if (document != null) document.cancel();
    }
}
