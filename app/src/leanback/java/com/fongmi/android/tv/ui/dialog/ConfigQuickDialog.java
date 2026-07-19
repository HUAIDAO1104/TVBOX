package com.fongmi.android.tv.ui.dialog;

import android.view.View;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.RepositoryItem;
import com.fongmi.android.tv.databinding.DialogConfigQuickBinding;
import com.fongmi.android.tv.impl.ConfigListener;
import com.fongmi.android.tv.ui.activity.RepositoryActivity;
import com.fongmi.android.tv.ui.adapter.ConfigAdapter;
import com.fongmi.android.tv.ui.adapter.RepositoryItemAdapter;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.ui.search.SearchDisplayName;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class ConfigQuickDialog extends BaseAlertDialog implements ConfigAdapter.OnClickListener {

    private DialogConfigQuickBinding binding;
    private ConfigAdapter adapter;
    private RepositoryItemAdapter repositoryAdapter;

    public static ConfigQuickDialog create() {
        return new ConfigQuickDialog();
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogConfigQuickBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        Config current = VodConfig.get().getConfig();
        binding.current.setText(SearchDisplayName.removeEmoji(current.getDesc()));
        adapter = new ConfigAdapter(this).glass().readOnly(true).current(current).addAll(0);
        binding.recycler.setItemAnimator(null);
        binding.recycler.setHasFixedSize(false);
        binding.recycler.addItemDecoration(new SpaceItemDecoration(1, 10));
        binding.recycler.setAdapter(adapter);
        repositoryAdapter = new RepositoryItemAdapter(this::select).glass();
        repositoryAdapter.load();
        binding.repositoryRecycler.setItemAnimator(null);
        binding.repositoryRecycler.addItemDecoration(new SpaceItemDecoration(1, 10));
        binding.repositoryRecycler.setAdapter(repositoryAdapter);
        int repositoryVisibility = repositoryAdapter.getItemCount() == 0 ? View.GONE : View.VISIBLE;
        binding.repositoryTitle.setVisibility(repositoryVisibility);
        binding.repositoryRecycler.setVisibility(repositoryVisibility);
        binding.dynamic.setVisibility(VodConfig.get().getSites().isEmpty() ? View.GONE : View.VISIBLE);
    }

    @Override
    protected void initEvent() {
        binding.refresh.setOnClickListener(v -> select(VodConfig.get().getConfig()));
        binding.dynamic.setOnClickListener(v -> {
            FragmentActivity activity = requireActivity();
            dismiss();
            SiteDialog.create().show(activity);
        });
        binding.add.setOnClickListener(v -> {
            FragmentActivity activity = requireActivity();
            dismiss();
            ConfigDialog.create().vod().show(activity);
        });
        binding.manage.setOnClickListener(v -> {
            FragmentActivity activity = requireActivity();
            dismiss();
            RepositoryActivity.start(activity);
        });
    }

    private void select(Config item) {
        ((ConfigListener) requireActivity()).setConfig(item);
        dismiss();
    }

    private void select(RepositoryItem item) {
        select(Config.find(item.getUrl(), item.getName(), item.getType()));
    }

    @Override
    public void onTextClick(Config item) {
        select(item);
    }

    @Override
    public void onDeleteClick(Config item) {
    }

    @Override
    public void onStart() {
        super.onStart();
        setWidth(0.52f);
    }
}
