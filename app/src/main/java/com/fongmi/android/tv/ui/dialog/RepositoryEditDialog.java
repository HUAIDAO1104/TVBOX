package com.fongmi.android.tv.ui.dialog;

import android.text.TextUtils;
import android.view.inputmethod.EditorInfo;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.bean.Repository;
import com.fongmi.android.tv.databinding.DialogRepositoryEditBinding;
import com.fongmi.android.tv.event.ServerEvent;
import com.fongmi.android.tv.repository.RepositoryManager;
import com.fongmi.android.tv.repository.RepositoryStatus;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.utils.QRCode;
import com.fongmi.android.tv.utils.UrlUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

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
        android.os.Bundle args = new android.os.Bundle(); args.putString("repository", com.fongmi.android.tv.App.gson().toJson(repository)); setArguments(args);
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
        if (repository == null && getArguments() != null) repository = com.fongmi.android.tv.App.gson().fromJson(requireArguments().getString("repository"), Repository.class);
        if (repository == null) repository = new Repository();
        if (listener == null && getActivity() instanceof Listener host) listener = host;
        binding.name.setText(repository.getName());
        binding.url.setText(repository.getUrl());
        if (!com.fongmi.android.tv.utils.Util.isMobile()) {
            Server.get().start();
            String mobileAddress = Server.get().getAddress(4) + "&mode=repository";
            binding.code.setImageBitmap(QRCode.getBitmap(mobileAddress, 180, 0));
        }
        binding.url.requestFocus();
    }

    @Override
    protected void initEvent() {
        binding.url.addTextChangedListener(new com.fongmi.android.tv.ui.custom.CustomTextListener() {
            @Override public void afterTextChanged(android.text.Editable text) { binding.url.setError(null); }
        });
        binding.positive.setOnClickListener(v -> save());
        binding.negative.setOnClickListener(v -> dismiss());
        binding.url.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) binding.positive.performClick();
            return true;
        });
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onServerEvent(ServerEvent event) {
        if (event.type() != ServerEvent.Type.SETTING || TextUtils.isEmpty(event.text())) return;
        binding.name.setText(event.name());
        binding.url.setText(event.text());
        binding.url.setSelection(event.text().length());
        save();
    }

    private void save() {
        String url = binding.url.getText().toString().trim();
        if (TextUtils.isEmpty(url)) { binding.url.setError(getString(com.fongmi.android.tv.R.string.repository_url)); binding.url.requestFocus(); return; }
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
        setWidth(com.fongmi.android.tv.utils.Util.isMobile() ? 0.94f : 0.68f);
        EventBus.getDefault().register(this);
    }

    @Override
    public void onStop() {
        if (EventBus.getDefault().isRegistered(this)) EventBus.getDefault().unregister(this);
        super.onStop();
    }
}
