package com.fongmi.android.tv.ui.dialog;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;
import android.view.View;

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
        return builder().setTitle(title).setView(getBinding().getRoot())
                .setPositiveButton(R.string.update_confirm, null)
                .setNegativeButton(R.string.update_later, null)
                .setCancelable(!mandatory && !checking);
    }

    @Override
    protected void initView() {
        binding.desc.setText(desc);
        binding.status.setVisibility(checking ? View.VISIBLE : View.GONE);
        if (checking) binding.status.setText(R.string.update_select_mirror);
    }

    @Override
    public void onStart() {
        super.onStart();
        AlertDialog dialog = (AlertDialog) getDialog();
        if (dialog != null && !mandatory) dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view -> listener.onCancel(view));
        if (dialog != null) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> listener.onConfirm(view));
        applyState();
    }

    public void setProgress(int progress) {
        AlertDialog dialog = (AlertDialog) getDialog();
        if (dialog != null) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(String.format(Locale.getDefault(), "%1$d%%", progress));
    }

    public void setDownloading() {
        binding.status.setVisibility(View.VISIBLE);
        binding.status.setText(R.string.update_select_mirror);
        AlertDialog dialog = (AlertDialog) getDialog();
        if (dialog != null) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText("0%");
        }
    }

    public void setStatus(String status) {
        binding.status.setVisibility(View.VISIBLE);
        binding.status.setText(status);
    }

    public void setError(String error) {
        setStatus(error);
        AlertDialog dialog = (AlertDialog) getDialog();
        if (dialog != null) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(R.string.update_retry);
        }
    }

    public void setRelease(String title, String desc, boolean mandatory) {
        this.title = title;
        this.desc = desc;
        this.mandatory = mandatory;
        this.checking = false;
        if (binding != null) binding.desc.setText(desc);
        AlertDialog dialog = (AlertDialog) getDialog();
        if (dialog != null) dialog.setTitle(title);
        applyState();
    }

    private void applyState() {
        AlertDialog dialog = (AlertDialog) getDialog();
        if (dialog == null) return;
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setVisibility(checking ? View.GONE : View.VISIBLE);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setVisibility(checking || mandatory ? View.GONE : View.VISIBLE);
        dialog.setCancelable(!mandatory && !checking);
        if (binding != null) {
            binding.status.setVisibility(checking ? View.VISIBLE : View.GONE);
            if (checking) binding.status.setText(R.string.update_select_mirror);
        }
    }
}
