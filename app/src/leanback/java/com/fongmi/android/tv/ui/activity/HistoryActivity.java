package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.databinding.ActivityHistoryBinding;
import com.fongmi.android.tv.ui.adapter.HistoryAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.Util;

import java.util.List;

public final class HistoryActivity extends BaseActivity implements HistoryAdapter.Listener {

    private static final int COLUMN_COUNT = 6;
    private ActivityHistoryBinding binding;
    private HistoryAdapter adapter;
    private int loadGeneration;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, HistoryActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = ActivityHistoryBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        binding.recycler.setItemAnimator(null);
        binding.recycler.setPreserveFocusAfterLayout(true);
        binding.recycler.setLayoutManager(new GridLayoutManager(this, COLUMN_COUNT));
        binding.recycler.addItemDecoration(new SpaceItemDecoration(COLUMN_COUNT, 16));
        binding.recycler.setAdapter(adapter = new HistoryAdapter(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadHistory();
    }

    @Override
    protected void initEvent() {
        binding.sync.setOnClickListener(view -> showPairing());
        binding.clear.setOnClickListener(view -> new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("清空观看历史？").setMessage("将删除当前配置的全部观看记录，此操作无法撤销。")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    int configId = VodConfig.getCid();
                    binding.clear.setEnabled(false);
                    Task.execute(() -> {
                        History.delete(configId);
                        App.post(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            adapter.setDeleteMode(false);
                            loadHistory();
                        });
                    });
                }).show());
    }

    @org.greenrobot.eventbus.Subscribe(threadMode = org.greenrobot.eventbus.ThreadMode.MAIN)
    public void onHistoryRefresh(com.fongmi.android.tv.event.RefreshEvent event) {
        if (event.getType() == com.fongmi.android.tv.event.RefreshEvent.Type.HISTORY) loadHistory();
    }

    private void showPairing() {
        if (!com.fongmi.android.tv.history.AccountSession.profile().isEmpty()) {
            PddAccountActivity.start(this);
            return;
        }
        Task.execute(() -> {
            String code = com.fongmi.android.tv.history.HistoryPeer.pairingCode();
            var qr = code.isEmpty() ? null : com.fongmi.android.tv.utils.QRCode.getStandardBitmap(code, 420);
            App.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                var dialog = new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("手机与电视续播")
                        .setNegativeButton("关闭", null);
                if (qr == null) dialog.setMessage("请连接局域网并关闭无痕模式，然后重新打开配对。");
                else {
                    var image = new android.widget.ImageView(this);
                    image.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                    image.setAdjustViewBounds(true);
                    image.setMaxHeight(com.fongmi.android.tv.utils.ResUtil.dp2px(172));
                    image.setImageBitmap(qr);
                    dialog.setView(image).setMessage("手机打开观看历史，点同步并扫描此码。两端使用相同点播订阅后，可自动续播。")
                            .setNeutralButton("解除配对", (d, w) -> com.fongmi.android.tv.history.HistoryPeer.forget());
                }
                dialog.show();
            });
        });
    }

    private void loadHistory() {
        int generation = ++loadGeneration;
        Task.execute(() -> {
            List<History> items = History.get();
            App.post(() -> {
                if (generation != loadGeneration || isFinishing() || isDestroyed()) return;
                adapter.submit(items);
                binding.clear.setEnabled(!items.isEmpty());
                binding.progressLayout.showContent();
                binding.emptyHint.setVisibility(items.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
                if (!Util.isMobile() && !items.isEmpty() && getCurrentFocus() == null) {
                    binding.recycler.requestFocus();
                }
            });
        });
    }

    @Override
    public void onOpen(History item) {
        VideoActivity.start(this, item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
    }

    @Override
    public void onDelete(History item) {
        Task.execute(() -> { item.delete(); App.post(() -> { if (!isFinishing() && !isDestroyed()) loadHistory(); }); });
    }

    @Override
    public void onDeleteMode() {
        adapter.setDeleteMode(true);
    }

    @Override
    protected void onBackInvoked() {
        if (adapter.isDeleteMode()) adapter.setDeleteMode(false);
        else super.onBackInvoked();
    }
}
