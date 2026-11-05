package com.fongmi.android.tv.ui.activity;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadService;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;
import com.fongmi.android.tv.databinding.ActivityOfflineDownloadsBinding;
import com.fongmi.android.tv.databinding.AdapterOfflineDownloadBinding;
import com.fongmi.android.tv.offline.*;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.ArrayList;
import java.util.List;

public final class OfflineDownloadsActivity extends BaseActivity {
    private ActivityOfflineDownloadsBinding binding;
    private OfflineStore store;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Download> items = new ArrayList<>();
    private final Adapter adapter = new Adapter();
    private int filter;
    private String importId;
    private final Runnable tick = new Runnable() { public void run() { refresh(); handler.postDelayed(this, 1000); } };
    private final ActivityResultLauncher<String[]> importer = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
        if (uri != null && importId != null && store.download(importId) != null) store.importDanmaku(importId, uri, error -> {
            toast(error == null ? "弹幕已保存，可离线播放" : error); refresh();
        });
    });
    public static void start(Context context) { context.startActivity(new Intent(context, OfflineDownloadsActivity.class)); }
    @Override protected ViewBinding getBinding() { return binding = ActivityOfflineDownloadsBinding.inflate(getLayoutInflater()); }
    @Override protected void initView(Bundle state) {
        store = OfflineStore.get(this);
        if (state != null) { importId = state.getString("importId"); filter = state.getInt("filter"); }
        binding.list.setItemAnimator(null);
        binding.list.setAdapter(adapter);
        binding.wifi.setChecked(store.wifiOnly());
        // Reconnect persistent tasks after process death without resuming individually paused items.
        DownloadService.start(this, OfflineDownloadService.class);
    }
    @Override protected void initEvent() {
        binding.wifi.setOnCheckedChangeListener((button, checked) -> store.setWifiOnly(checked));
        binding.all.setOnClickListener(v -> { filter = 0; refresh(); });
        binding.active.setOnClickListener(v -> { filter = 1; refresh(); });
        binding.completed.setOnClickListener(v -> { filter = 2; refresh(); });
    }
    @Override protected void onStart() { super.onStart(); handler.post(tick); }
    @Override protected void onStop() { handler.removeCallbacks(tick); super.onStop(); }
    @Override protected void onSaveInstanceState(Bundle state) { state.putString("importId", importId); state.putInt("filter", filter); super.onSaveInstanceState(state); }
    private void toast(String text) { if (!isDestroyed()) Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    private String size(long value) { return Formatter.formatFileSize(this, Math.max(0, value)); }
    private void refresh() {
        if (store == null || isDestroyed()) return;
        items.clear();
        for (Download download : store.downloads()) {
            boolean complete = download.state == Download.STATE_COMPLETED;
            if (filter == 1 && complete || filter == 2 && !complete) continue;
            items.add(download);
        }
        adapter.notifyDataSetChanged();
        binding.empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        binding.empty.setText(filter == 0 ? "还没有下载\n在视频播放页点击“下载”，即可保存当前集和弹幕。" : "此分类暂无视频");
        binding.storage.setText("视频已占用 " + size(store.cache.getCacheSpace()) + " · 剩余 " + size(store.root.getUsableSpace()) + "\n下载内容独立保存，清理播放缓存不会删除。卸载应用会删除下载。");
        binding.all.setEnabled(filter != 0); binding.active.setEnabled(filter != 1); binding.completed.setEnabled(filter != 2);
    }
    private String state(Download download) {
        switch (download.state) {
            case Download.STATE_COMPLETED: return "已完成 · 可离线播放";
            case Download.STATE_FAILED: return "下载失败 · 可重试（检查空间或重新获取线路）";
            case Download.STATE_STOPPED: return "已暂停";
            case Download.STATE_REMOVING: return "正在删除";
            case Download.STATE_DOWNLOADING: return "正在保存视频和弹幕";
            default: return store.manager.isWaitingForRequirements() ? "等待网络或可用存储空间" : "等待下载";
        }
    }
    private void manage(Download original) {
        Download item = store.download(original.request.id);
        if (item == null || item.state == Download.STATE_REMOVING) return;
        List<String> labels = new ArrayList<>(); List<Runnable> actions = new ArrayList<>();
        if (item.state == Download.STATE_COMPLETED) {
            labels.add("离线播放"); actions.add(() -> OfflinePlayerActivity.start(this, item.request.id));
        } else if (item.state == Download.STATE_STOPPED || item.state == Download.STATE_FAILED) {
            labels.add(item.state == Download.STATE_FAILED ? "重试下载" : "继续下载");
            actions.add(() -> {
                DownloadService.sendAddDownload(this, OfflineDownloadService.class, item.request, 0, false);
                DownloadService.sendResumeDownloads(this, OfflineDownloadService.class, false);
            });
        } else {
            labels.add("暂停下载"); actions.add(() -> DownloadService.sendSetStopReason(this, OfflineDownloadService.class, item.request.id, 1, false));
        }
        if (item.state == Download.STATE_COMPLETED || item.state == Download.STATE_STOPPED || item.state == Download.STATE_FAILED) {
            labels.add("补存弹幕和外挂字幕"); actions.add(() -> { toast("正在补存，请稍候…"); store.retryExtras(item, error -> { toast(store.extrasStatus(item)); refresh(); }); });
            labels.add("导入本地弹幕 XML"); actions.add(() -> { importId = item.request.id; importer.launch(new String[]{"text/xml", "application/xml", "application/octet-stream"}); });
        }
        labels.add("删除视频、弹幕和字幕"); actions.add(() -> new MaterialAlertDialogBuilder(this).setTitle("删除此下载？")
                .setMessage("将删除本地视频及其弹幕、字幕，释放存储空间。").setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> DownloadService.sendRemoveDownload(this, OfflineDownloadService.class, item.request.id, false)).show());
        new MaterialAlertDialogBuilder(this).setTitle(OfflineMetadata.decode(item.request.data).title)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> actions.get(which).run()).show();
    }
    private final class Adapter extends RecyclerView.Adapter<Holder> {
        Adapter() { setHasStableIds(true); }
        @Override public long getItemId(int position) { return Long.parseLong(items.get(position).request.id.substring(0, 15), 16); }
        @NonNull public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) { return new Holder(AdapterOfflineDownloadBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false)); }
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Download item = items.get(position); OfflineMetadata meta = OfflineMetadata.decode(item.request.data);
            holder.view.title.setText(meta.title); holder.view.episode.setText(meta.episode);
            float percent = item.getPercentDownloaded();
            holder.view.progress.setIndeterminate(percent < 0 && item.state == Download.STATE_DOWNLOADING);
            holder.view.progress.setProgress(item.state == Download.STATE_COMPLETED ? 100 : (int) Math.max(0, percent));
            holder.view.status.setText(state(item) + "\n" + size(item.getBytesDownloaded()) + (item.contentLength > 0 ? " / " + size(item.contentLength) : ""));
            holder.view.extras.setText(store.extrasStatus(item));
            holder.view.getRoot().setOnClickListener(v -> manage(item));
        }
        public int getItemCount() { return items.size(); }
    }
    private static final class Holder extends RecyclerView.ViewHolder {
        final AdapterOfflineDownloadBinding view;
        Holder(AdapterOfflineDownloadBinding view) { super(view.getRoot()); this.view = view; }
    }
}
