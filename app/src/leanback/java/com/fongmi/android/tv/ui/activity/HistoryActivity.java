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

import java.util.List;

public final class HistoryActivity extends BaseActivity implements HistoryAdapter.Listener {

    private static final int COLUMN_COUNT = 6;
    private ActivityHistoryBinding binding;
    private HistoryAdapter adapter;

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
        loadHistory();
    }

    @Override
    protected void initEvent() {
        binding.back.setOnClickListener(view -> onBackInvoked());
        binding.clear.setOnClickListener(view -> {
            History.delete(VodConfig.getCid());
            adapter.setDeleteMode(false);
            loadHistory();
        });
    }

    private void loadHistory() {
        Task.execute(() -> {
            List<History> items = History.get();
            App.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                adapter.submit(items);
                binding.clear.setEnabled(!items.isEmpty());
                binding.progressLayout.showContent(true, items.size());
            });
        });
    }

    @Override
    public void onOpen(History item) {
        VideoActivity.start(this, item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
    }

    @Override
    public void onDelete(History item) {
        item.delete();
        loadHistory();
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
