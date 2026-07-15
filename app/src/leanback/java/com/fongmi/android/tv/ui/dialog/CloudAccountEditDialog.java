package com.fongmi.android.tv.ui.dialog;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.TextUtils;
import android.view.WindowManager;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.cloud.CloudAccountManager;
import com.fongmi.android.tv.cloud.CloudProvider;
import com.fongmi.android.tv.databinding.DialogCloudAccountEditBinding;
import com.fongmi.android.tv.utils.Notify;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class CloudAccountEditDialog extends BaseAlertDialog {

    public interface Listener {
        void onSaved();
    }

    private DialogCloudAccountEditBinding binding;
    private CloudProvider provider;
    private Listener listener;

    public static CloudAccountEditDialog create() {
        return new CloudAccountEditDialog();
    }

    public CloudAccountEditDialog provider(CloudProvider provider) {
        this.provider = provider;
        return this;
    }

    public CloudAccountEditDialog listener(Listener listener) {
        this.listener = listener;
        return this;
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogCloudAccountEditBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        binding.title.setText(getString(R.string.cloud_edit_title, provider.name()));
        binding.qrStatus.setText(getString(R.string.cloud_qr_unavailable_detail, provider.name(), provider.hint()));
        binding.credential.setHint(provider.hint());
        var account = CloudAccountManager.get(provider.id());
        if (account != null) binding.displayName.setText(account.getDisplayName());
        binding.credential.requestFocus();
    }

    @Override
    protected void initEvent() {
        binding.paste.setOnClickListener(v -> paste());
        binding.negative.setOnClickListener(v -> dismiss());
        binding.positive.setOnClickListener(v -> save());
    }

    private void paste() {
        ClipboardManager manager = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData data = manager == null ? null : manager.getPrimaryClip();
        if (data == null || data.getItemCount() == 0) return;
        CharSequence value = data.getItemAt(0).coerceToText(requireContext());
        if (!TextUtils.isEmpty(value)) binding.credential.setText(value);
    }

    private void save() {
        String credential = binding.credential.getText().toString().trim();
        if (credential.isEmpty()) {
            Notify.show(R.string.cloud_credential_required);
            return;
        }
        try {
            CloudAccountManager.save(provider, credential, binding.displayName.getText().toString(), "MANUAL");
            binding.credential.setText(null);
            if (listener != null) listener.onSaved();
            dismiss();
        } catch (Throwable e) {
            Notify.show(R.string.cloud_secure_store_failed);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() != null && getDialog().getWindow() != null) {
            getDialog().getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        setWidth(0.56f);
    }
}
