package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Repository;
import com.fongmi.android.tv.databinding.ActivityRepositoryBinding;
import com.fongmi.android.tv.repository.RepositoryManager;
import com.fongmi.android.tv.repository.RepositoryDeleteMode;
import com.fongmi.android.tv.repository.RepositorySyncManager;
import com.fongmi.android.tv.ui.adapter.RepositoryAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.ui.dialog.RepositoryEditDialog;
import com.fongmi.android.tv.ui.dialog.DialogGlass;
import com.fongmi.android.tv.utils.Notify;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Collections;

public class RepositoryActivity extends BaseActivity implements RepositoryAdapter.Listener, RepositoryEditDialog.Listener {

    private final RepositoryManager manager = RepositoryManager.get();
    private ActivityRepositoryBinding binding;
    private RepositoryAdapter adapter;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, RepositoryActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = ActivityRepositoryBinding.inflate(getLayoutInflater());
    }

    @Override
    protected boolean customWall() {
        return false;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        binding.recycler.setItemAnimator(null);
        binding.recycler.addItemDecoration(new SpaceItemDecoration(1, 12));
        binding.recycler.setAdapter(adapter = new RepositoryAdapter(this));
        refresh();
        binding.add.requestFocus();
    }

    @Override
    protected void initEvent() {
        binding.add.setOnClickListener(v -> RepositoryEditDialog.create().listener(this).show(this));
        binding.syncAll.setOnClickListener(v -> manager.syncAll(syncListener()));
    }

    private void refresh() {
        try {
            adapter.submit(manager.getAll());
            binding.empty.setText(R.string.repository_empty);
        } catch (Throwable error) {
            adapter.submit(Collections.emptyList());
            binding.empty.setText(R.string.repository_load_failed);
        }
        binding.empty.setVisibility(adapter.getItemCount() == 0 ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    @Override
    public void onRepositorySaved(Repository repository) {
        refresh();
        manager.sync(repository, syncListener());
    }

    @Override
    public void onEdit(Repository repository) {
        RepositoryEditDialog.create().repository(repository).listener(this).show(this);
    }

    @Override
    public void onOpen(Repository repository) {
        RepositoryDetailActivity.start(this, repository.getId());
    }

    @Override
    public void onToggle(Repository repository) {
        manager.setEnabled(repository, !repository.isEnabled());
        refresh();
    }

    @Override
    public void onSync(Repository repository) {
        manager.sync(repository, syncListener());
    }

    @Override
    public void onMove(Repository repository, int delta) {
        manager.move(repository, delta);
        refresh();
    }

    @Override
    public void onDelete(Repository repository) {
        int[] selected = {0};
        String[] choices = getResources().getStringArray(R.array.repository_delete_modes);
        DialogGlass.apply(new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.repository_delete_title)
                .setMessage(getString(R.string.repository_delete_impact, manager.getItemCount(repository.getId())))
                .setSingleChoiceItems(choices, 0, (dialog, which) -> selected[0] = which)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    int modeIndex = Math.max(0, Math.min(selected[0], RepositoryDeleteMode.values().length - 1));
                    RepositoryDeleteMode mode = RepositoryDeleteMode.values()[modeIndex];
                    if (!manager.delete(repository, mode)) Notify.show(R.string.repository_delete_failed);
                    refresh();
                })
                .show());
    }

    private RepositorySyncManager.Listener syncListener() {
        return new RepositorySyncManager.Listener() {
            @Override
            public void onStart(Repository repository) {
                refresh();
            }

            @Override
            public void onSuccess(Repository repository, boolean cached) {
                refresh();
                Notify.show(cached ? R.string.repository_cache_used : R.string.repository_sync_success);
            }

            @Override
            public void onError(Repository repository, String message, boolean hasCache) {
                refresh();
                Notify.show(hasCache ? getString(R.string.repository_offline_fallback) : message);
            }
        };
    }
}
