package com.fongmi.android.tv.offline;

import android.app.*;
import android.content.*;
import android.os.*;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.impl.ParseCallback;
import com.fongmi.android.tv.player.media.MediaItemFactory;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.player.parse.ParseJob;
import com.fongmi.android.tv.ui.activity.OfflineDownloadsActivity;
import java.util.Map;
import java.util.concurrent.*;

/** Resolves episodes serially without changing the playing episode or its danmaku search. */
public final class OfflineBatchService extends Service {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private OfflineBatchQueue queue;
    private OfflineStore store;
    private OfflineBatchQueue.Job current;
    private Future<?> lookup;
    private ParseJob parser;
    private boolean destroyed;
    public static void kick(Context context) {
        if (OfflineBatchQueue.get(context).pending()) ContextCompat.startForegroundService(context, new Intent(context, OfflineBatchService.class));
    }
    @Override public void onCreate() {
        super.onCreate(); queue = OfflineBatchQueue.get(this); queue.recover(); store = OfflineStore.get(this);
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("offline_prepare", "批量下载准备", NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(7902, notification("正在准备所选剧集")); main.removeCallbacks(pump); main.post(pump); return START_NOT_STICKY;
    }
    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 7902, new Intent(this, OfflineDownloadsActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, "offline_prepare").setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("批量下载")
                .setContentText(text).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true).build();
    }
    private final Runnable pump = new Runnable() { public void run() {
        if (destroyed) return;
        if (current != null) {
            if (!queue.active(current)) clearCurrent();
            else { main.postDelayed(this, 1000); return; }
        }
        if (!queue.pending()) { stopSelf(); return; }
        if (store.requirements(store.wifiOnly()).getNotMetRequirements(OfflineBatchService.this) != 0) {
            getSystemService(NotificationManager.class).notify(7902, notification("等待符合下载设置的网络或存储空间"));
            main.postDelayed(this, 3000); return;
        }
        OfflineBatchQueue.Job job = queue.claim(); if (job == null) { stopSelf(); return; }
        current = job;
        getSystemService(NotificationManager.class).notify(7902, notification(job.meta.title + " · " + job.meta.episode));
        main.postDelayed(() -> { if (valid(job)) finish(job, "准备超时，请检查线路后重试"); }, 65000);
        if (!job.resolvedUrl.isEmpty()) {
            enqueue(job, new MediaItem.Builder().setUri(job.resolvedUrl).setMimeType(job.mime).build(), job.meta);
        } else {
            lookup = executor.submit(() -> {
                try {
                    Result result = SiteApi.downloadContent(job.site, job.flag, job.sourceUrl);
                    main.post(() -> resolved(job, result));
                } catch (Exception error) { main.post(() -> finish(job, "未能获取片源，请重新播放该集或更换线路")); }
            });
        }
        main.postDelayed(this, 1000);
    }};
    private boolean valid(OfflineBatchQueue.Job job) { return !destroyed && current == job && queue.active(job); }
    private void resolved(OfflineBatchQueue.Job job, Result result) {
        if (!valid(job)) return;
        PlaySpec spec = PlaySpec.from(result, job.site, new MediaMetadata.Builder().setTitle(job.meta.title).setArtist(job.meta.episode).build());
        if (result.needParse()) {
            parser = ParseJob.create(new ParseCallback() {
                public void onParseSuccess(Map<String,String> headers, String url, String from) {
                    if (!valid(job)) return;
                    spec.setHeaders(headers); spec.setUrl(url); ready(job, spec);
                }
                public void onParseError() { finish(job, "此线路解析失败，可重试或切换线路"); }
            }).start(result, result.isUseParse());
        } else ready(job, spec);
    }
    private void ready(OfflineBatchQueue.Job job, PlaySpec spec) {
        if (!valid(job)) return;
        try {
            spec.checkUa();
            OfflineMetadata meta = OfflineMetadata.decode(job.meta.encode());
            meta.headers.putAll(spec.getHeaders());
            MediaItem media = MediaItemFactory.from(spec);
            if (media.localConfiguration != null) for (MediaItem.SubtitleConfiguration sub : media.localConfiguration.subtitleConfigurations) {
                OfflineMetadata.Subtitle value = new OfflineMetadata.Subtitle();
                value.uri = sub.uri.toString(); value.mime = sub.mimeType; value.label = sub.label;
                value.language = sub.language; value.selectionFlags = sub.selectionFlags; meta.subtitles.add(value);
            }
            enqueue(job, media, meta);
        } catch (RuntimeException error) { finish(job, "当前线路暂不支持下载，请换一条线路"); }
    }
    private void enqueue(OfflineBatchQueue.Job job, MediaItem media, OfflineMetadata meta) {
        if (!valid(job)) return;
        if (media.localConfiguration != null && store.download(OfflineMetadata.id(media.localConfiguration.uri.toString(), meta.headers)) != null) { finish(job, null); return; }
        store.enqueue(media, meta, () -> valid(job), error -> finish(job, error));
    }
    private void finish(OfflineBatchQueue.Job job, String error) {
        if (!valid(job)) return;
        queue.finish(job, error); clearCurrent(); main.removeCallbacks(pump); main.post(pump);
    }
    private void clearCurrent() {
        current = null; if (lookup != null) lookup.cancel(true); lookup = null;
        if (parser != null) parser.stop(); parser = null;
    }
    @Override public void onDestroy() {
        destroyed = true; main.removeCallbacksAndMessages(null); clearCurrent(); queue.recover(); executor.shutdownNow(); super.onDestroy();
    }
    @Override public void onTimeout(int startId, int fgsType) { stopSelf(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
