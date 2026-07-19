package com.fongmi.android.tv.ui.dialog;

import android.view.View;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogUpdateBinding;
import com.fongmi.android.tv.impl.UpdateListener;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Locale;

public class UpdateDialog extends BaseAlertDialog {

    private DialogUpdateBinding binding;
    private UpdateListener listener;
    private boolean mandatory;
    private boolean checking;
    private String title;
    private String desc;

    public static UpdateDialog create() {
        return new UpdateDialog();
    }

    public UpdateDialog title(String title) {
        this.title = title;
        return this;
    }

    public UpdateDialog desc(String desc) {
        this.desc = desc;
        return this;
    }

    public UpdateDialog listener(UpdateListener listener) {
        this.listener = listener;
        return this;
    }

    public UpdateDialog mandatory(boolean mandatory) {
        this.mandatory = mandatory;
        return this;
    }

    public UpdateDialog checking() {
        this.checking = true;
        return this;
    }

    public UpdateDialog show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
        return this;
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogUpdateBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot()).setCancelable(!mandatory);
    }

    @Override
    protected void initView() {
        binding.version.setText(title);
        binding.desc.setText(desc);
        applyState();
    }

    @Override
    protected void initEvent() {
        binding.confirm.setOnClickListener(this::onConfirm);
        binding.cancel.setOnClickListener(this::onCancel);
    }

    public void setProgress(int progress) {
        binding.confirm.setText(String.format(Locale.getDefault(), "%1$d%%", progress));
    }

    public void setDownloading() {
        binding.status.setVisibility(View.VISIBLE);
        binding.status.setText(R.string.update_select_mirror);
        binding.confirm.setEnabled(false);
        binding.confirm.setText("0%");
    }

    public void setStatus(String status) {
        binding.status.setVisibility(View.VISIBLE);
        binding.status.setText(status);
    }

    public void setError(String error) {
        setStatus(error);
        binding.confirm.setEnabled(true);
        binding.confirm.setText(R.string.update_retry);
    }

    public void setRelease(String title, String desc, boolean mandatory) {
        this.title = title;
        this.desc = desc;
        this.mandatory = mandatory;
        this.checking = false;
        if (binding == null) return;
        binding.version.setText(title);
        binding.desc.setText(desc);
        applyState();
    }

    private void applyState() {
        if (binding == null) return;
        binding.confirm.setVisibility(checking ? View.GONE : View.VISIBLE);
        binding.cancel.setVisibility(checking || mandatory ? View.GONE : View.VISIBLE);
        binding.status.setVisibility(checking ? View.VISIBLE : View.GONE);
        if (checking) binding.status.setText(R.string.update_select_mirror);
        if (getDialog() != null) getDialog().setCancelable(!mandatory && !checking);
    }

    private void onConfirm(View view) {
        listener.onConfirm(view);
    }

    private void onCancel(View view) {
        listener.onCancel(view);
    }
}
