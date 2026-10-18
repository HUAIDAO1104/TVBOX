package com.fongmi.android.tv.ui.dialog;

import android.content.DialogInterface;
import android.os.Bundle;
import androidx.appcompat.app.AlertDialog;
import androidx.viewbinding.ViewBinding;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogDanmakuAiPairingBinding;
import com.fongmi.android.tv.server.AiPairingSession;
import com.fongmi.android.tv.server.AiPairingHost;
import com.fongmi.android.tv.utils.QRCode;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class DanmakuAiPairingDialog extends BaseAlertDialog {
    private DialogDanmakuAiPairingBinding binding;
    private AiPairingSession session;
    private AiPairingHost host;
    private boolean permissionAsked;
    private static final String LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK";
    private final androidx.activity.result.ActivityResultLauncher<String> networkPermission = registerForActivityResult(
            new androidx.activity.result.contract.ActivityResultContracts.RequestPermission(), allowed -> {
                if (binding == null) return;
                if (allowed) refresh(); else showUnavailable(R.string.danmaku_ai_scan_permission);
                updateButton();
            });
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
        generation++;
        App.removeCallbacks(checkAddress);
        if (session != null) session.close();
        if (host != null) { host.close(); host = null; }
        if (binding != null) {
            binding.code.setImageDrawable(null); binding.pairingCode.setText("");
            binding.connection.setText(R.string.danmaku_ai_scan_expired);
        }
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
        App.removeCallbacks(expire, checkAddress); if (session != null) { session.close(); session = null; }
        if (!hasNetworkPermission()) {
            if (host != null) { host.close(); host = null; }
            binding.pairingCode.setText("");
            showUnavailable(R.string.danmaku_ai_scan_permission);
            if (permissionAsked && !shouldShowRequestPermissionRationale(LOCAL_NETWORK_PERMISSION)) {
                startActivity(new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + requireContext().getPackageName())));
            } else { permissionAsked = true; networkPermission.launch(LOCAL_NETWORK_PERMISSION); }
            return;
        }
        if (host == null) host = new AiPairingHost();
        session = AiPairingSession.open(() -> {
            if (!isAdded() || request != generation) return;
            getParentFragmentManager().setFragmentResult(DanmakuAiDialog.RESULT, Bundle.EMPTY); dismiss();
        });
        shownAddress = "";
        binding.code.setImageDrawable(null);
        binding.address.setText(R.string.danmaku_ai_scan_preparing);
        binding.connection.setText(R.string.danmaku_ai_phone_waiting);
        binding.pairingCode.setText(getString(R.string.danmaku_ai_pairing_code, session.code().substring(0, 4) + " " + session.code().substring(4)));
        updateAddress();
        App.post(checkAddress, 2000);
        App.post(expire, AiPairingSession.LIFETIME_MS);
    }
    private void updateAddress() {
        if (host == null) return;
        int request = generation;
        host.check(state -> App.post(() -> {
            if (binding == null || session == null || request != generation) return;
            if (state.status() != AiPairingHost.Status.READY) {
                showUnavailable(state.status() == AiPairingHost.Status.NO_NETWORK ? R.string.danmaku_ai_scan_unavailable : R.string.danmaku_ai_scan_service_failed);
                return;
            }
            binding.connection.setText(session.connected() ? R.string.danmaku_ai_phone_connected : R.string.danmaku_ai_phone_waiting);
            if (state.address().equals(shownAddress)) return;
            shownAddress = state.address();
            binding.address.setText(shownAddress);
            binding.code.setImageBitmap(QRCode.getStandardBitmap(shownAddress + "#" + session.token(), 224));
        }));
    }
    private void showUnavailable(int message) {
        shownAddress = ""; binding.code.setImageDrawable(null);
        binding.address.setText(message); binding.connection.setText(message);
    }
    private boolean hasNetworkPermission() {
        return android.os.Build.VERSION.SDK_INT < 37 || androidx.core.content.ContextCompat.checkSelfPermission(
                App.get(), LOCAL_NETWORK_PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }
    private void updateButton() {
        if (getDialog() instanceof AlertDialog dialog && dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(hasNetworkPermission() ? R.string.danmaku_ai_scan_refresh : R.string.danmaku_ai_scan_allow);
    }
    @Override public void onResume() {
        super.onResume();
        if (binding != null && session == null && hasNetworkPermission()) refresh();
        updateButton();
    }
    @Override public void onStart() {
        super.onStart(); setWidth(.58f);
        ((AlertDialog) requireDialog()).getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> refresh());
        ((AlertDialog) requireDialog()).getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
        updateButton();
    }
    @Override public void onDismiss(DialogInterface dialog) {
        generation++;
        App.removeCallbacks(expire, checkAddress); if (session != null) session.close();
        if (host != null) { host.close(); host = null; }
        super.onDismiss(dialog);
        binding = null;
    }
}
