package com.fongmi.android.tv.offline;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadManager;
import androidx.media3.exoplayer.offline.DownloadNotificationHelper;
import androidx.media3.exoplayer.offline.DownloadService;
import androidx.media3.exoplayer.scheduler.Scheduler;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.ui.activity.OfflineDownloadsActivity;
import java.util.List;

public final class OfflineDownloadService extends DownloadService {
    public OfflineDownloadService() { super(7901, 1000, "offline_downloads", R.string.offline_downloads, 0); }
    @Override protected DownloadManager getDownloadManager() { return OfflineStore.get(this).manager; }
    @Override protected Scheduler getScheduler() { return null; }
    @Override protected Notification getForegroundNotification(List<Download> downloads, int requirements) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, OfflineDownloadsActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new DownloadNotificationHelper(this, "offline_downloads").buildProgressNotification(this,
                android.R.drawable.stat_sys_download, open, "视频与离线弹幕 · 点此管理", downloads, requirements);
    }
    @Override public void onTimeout(int startId, int type) {
        getDownloadManager().pauseDownloads();
        stopSelf();
    }
}
