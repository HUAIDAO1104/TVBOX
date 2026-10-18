package com.fongmi.android.tv.ui.dialog;

import android.content.DialogInterface;
import android.os.Bundle;
import androidx.appcompat.app.AlertDialog;
import androidx.viewbinding.ViewBinding;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogDanmakuAiPairingBinding;
import com.fongmi.android.tv.server.AiPairingSession;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.utils.QRCode;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class DanmakuAiPairingDialog extends BaseAlertDialog {
    private DialogDanmakuAiPairingBinding binding;
    private AiPairingSession session;
    private int generation;
    private String shownAddress = "";
    private final Runnable checkAddress = new Runnable() {
        @Override public void run() {
            if (binding == null || session == null) return;
            updateAddress();
            App.post(this, 2000);
        }
    };
    private final Runnable expire = () -> {
        App.removeCallbacks(checkAddress);
        if (session != null) session.close();
        if (binding != null) { binding.code.setImageDrawable(null); binding.address.setText(R.string.danmaku_ai_scan_expired); }
    };
    @Override protected ViewBinding getBinding() { return binding = DialogDanmakuAiPairingBinding.inflate(getLayoutInflater()); }
    @Override protected MaterialAlertDialogBuilder getBuilder() {
        return new MaterialAlertDialogBuilder(requireActivity(), R.style.DanmakuAiDialogTheme)
                .setTitle(R.string.danmaku_ai_scan).setView(getBinding().getRoot())
                .setPositiveButton(R.string.danmaku_ai_scan_refresh, null).setNegativeButton(R.string.dialog_negative, null);
    }
    @Override protected void initView() { refresh(); }
    private void refresh() {
        int request = ++generation;
        App.removeCallbacks(expire); if (session != null) session.close();
        Server.get().start();
        session = AiPairingSession.open(() -> {
            if (!isAdded() || request != generation) return;
            getParentFragmentManager().setFragmentResult(DanmakuAiDialog.RESULT, Bundle.EMPTY); dismiss();
        });
        shownAddress = "";
        updateAddress();
        App.post(checkAddress, 2000);
        App.post(expire, AiPairingSession.LIFETIME_MS);
    }
    private void updateAddress() {
        Server.get().start();
        String base = Server.get().getAddress();
        if (base.isEmpty()) {
            shownAddress = "";
            binding.code.setImageDrawable(null);
            binding.address.setText(R.string.danmaku_ai_scan_unavailable);
            return;
        }
        String address = base + "/ai-setup";
        if (address.equals(shownAddress)) return;
        shownAddress = address;
        binding.address.setText(address);
        binding.code.setImageBitmap(QRCode.getStandardBitmap(address + "#" + session.token(), 224));
    }
    @Override public void onStart() {
        super.onStart(); setWidth(.58f);
        ((AlertDialog) requireDialog()).getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> refresh());
        ((AlertDialog) requireDialog()).getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
    }
    @Override public void onDismiss(DialogInterface dialog) {
        generation++;
        App.removeCallbacks(expire, checkAddress); if (session != null) session.close();
        super.onDismiss(dialog);
    }
}
