package com.fongmi.android.tv.offline;

import android.app.Activity;
import android.Manifest;
import android.os.Build;
import android.widget.Toast;
import androidx.core.app.ActivityCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.ui.activity.OfflineDownloadsActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class OfflineActions {
    private OfflineActions() { }
    public static void download(Activity activity, PlayerManager player) {
        MediaItem item = player.getCurrentMediaItem();
        if (item == null || player.getPlayer().isCurrentMediaItemLive()) {
            Toast.makeText(activity, "请先播放要下载的点播视频", Toast.LENGTH_LONG).show(); return;
        }
        OfflineMetadata meta = new OfflineMetadata();
        MediaMetadata metadata = player.getMetadata();
        if (metadata != null) {
            if (metadata.title != null) meta.title = metadata.title.toString();
            if (metadata.artist != null) meta.episode = metadata.artist.toString();
        }
        if (player.getHeaders() != null) meta.headers.putAll(player.getHeaders());
        if (player.getSelectedDanmakuUri() != null) meta.danmaku = player.getSelectedDanmakuUri().toString();
        if (item.localConfiguration != null) for (MediaItem.SubtitleConfiguration sub : item.localConfiguration.subtitleConfigurations) {
            OfflineMetadata.Subtitle value = new OfflineMetadata.Subtitle();
            value.uri = sub.uri.toString(); value.mime = sub.mimeType; value.label = sub.label;
            value.language = sub.language; value.selectionFlags = sub.selectionFlags; meta.subtitles.add(value);
        }
        new MaterialAlertDialogBuilder(activity).setTitle("下载当前集")
                .setMessage(meta.title + "\n" + meta.episode + "\n\n" + (meta.danmaku.isEmpty() ?
                        "当前没有选中弹幕。可先返回播放器选择弹幕，或下载后导入弹幕 XML。" : "会一并保存当前选中的弹幕和外挂字幕。")
                        + "\n下载完成后，可在“我的 → 下载管理”中离线播放。")
                .setNegativeButton("取消", null).setPositiveButton("下载", (dialog, which) -> {
                    if (Build.VERSION.SDK_INT >= 33 && ActivityCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7901);
                    Toast.makeText(activity, "正在读取下载信息…", Toast.LENGTH_SHORT).show();
                    OfflineStore.get(activity).enqueue(item, meta, error -> {
                        if (activity.isFinishing() || activity.isDestroyed()) return;
                        if (error != null) Toast.makeText(activity, error, Toast.LENGTH_LONG).show();
                        else OfflineDownloadsActivity.start(activity);
                    });
                }).show();
    }
}
