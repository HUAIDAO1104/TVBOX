package com.fongmi.android.tv.ui.dialog;

import android.text.TextUtils;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.bean.Repository;
import com.fongmi.android.tv.databinding.DialogRepositoryEditBinding;
import com.fongmi.android.tv.repository.RepositoryManager;
import com.fongmi.android.tv.repository.RepositoryStatus;
import com.fongmi.android.tv.utils.UrlUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class RepositoryEditDialog extends BaseAlertDialog {

    public interface Listener {
        void onRepositorySaved(Repository repository);
    }

    private DialogRepositoryEditBinding binding;
    private Repository repository;
    private Listener listener;

    public static RepositoryEditDialog create() {
        return new RepositoryEditDialog();
    }

    public RepositoryEditDialog repository(Repository repository) {
        this.repository = repository;
        return this;
    }

    public RepositoryEditDialog listener(Listener listener) {
        this.listener = listener;
        return this;
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogRepositoryEditBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        if (repository == null) repository = new Repository();
        binding.name.setText(repository.getName());
        binding.url.setText(repository.getUrl());
        binding.url.requestFocus();
    }

    @Override
    protected void initEvent() {
        binding.positive.setOnClickListener(v -> save());
        binding.negative.setOnClickListener(v -> dismiss());
    }

    private void save() {
        String url = binding.url.getText().toString().trim();
        if (TextUtils.isEmpty(url)) return;
        String name = binding.name.getText().toString().trim();
        repository.setUrl(url);
        repository.setName(TextUtils.isEmpty(name) ? UrlUtil.getName(url) : name);
        if (repository.getId() == 0) {
            repository.setEnabled(true);
            repository.setAutoSync(true);
            repository.setPriority(RepositoryManager.get().getAll().size());
            repository.setStatus(RepositoryStatus.IDLE);
        }
        RepositoryManager.get().save(repository);
        if (listener != null) listener.onRepositorySaved(repository);
        dismiss();
    }

    @Override
    public void onStart() {
        super.onStart();
        setWidth(0.52f);
    }
}
