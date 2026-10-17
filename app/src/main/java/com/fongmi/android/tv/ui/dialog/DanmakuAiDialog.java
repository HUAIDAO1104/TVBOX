package com.fongmi.android.tv.ui.dialog;

import android.content.DialogInterface;
import android.os.Bundle;
import android.view.WindowManager;
import androidx.appcompat.app.AlertDialog;
import androidx.viewbinding.ViewBinding;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogDanmakuAiBinding;
import com.fongmi.android.tv.playback.vod.DanmakuAiClient;
import com.fongmi.android.tv.playback.vod.DanmakuAiProtocol;
import com.fongmi.android.tv.playback.vod.DanmakuMatchContext;
import com.fongmi.android.tv.playback.vod.DanmakuAiMatcher;
import com.fongmi.android.tv.setting.DanmakuAiSetting;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Shared form behavior; each device flavor supplies its existing visual tokens. */
public class DanmakuAiDialog extends BaseAlertDialog {
    public static final String RESULT = "danmaku_ai_changed";
    private static final java.util.concurrent.ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private DialogDanmakuAiBinding binding;
    private Future<?> task;
    private volatile DanmakuAiClient client;
    private volatile boolean closed;
    private boolean busy;

    @Override protected ViewBinding getBinding() { return binding = DialogDanmakuAiBinding.inflate(getLayoutInflater()); }
    @Override protected MaterialAlertDialogBuilder getBuilder() {
        MaterialAlertDialogBuilder form = new MaterialAlertDialogBuilder(requireActivity(), R.style.DanmakuAiDialogTheme);
        if ("mobile".equals(BuildConfig.FLAVOR_mode)) form.setBackgroundInsetTop(0).setBackgroundInsetBottom(0);
        return form.setTitle(R.string.danmaku_ai_title).setView(getBinding().getRoot())
                .setPositiveButton(R.string.danmaku_ai_save, null).setNegativeButton(R.string.dialog_negative, null);
    }

    @Override protected void initView() {
        if ("leanback".equals(BuildConfig.FLAVOR_mode)) binding.scan.requestFocus();
        binding.enabled.setChecked(DanmakuAiSetting.enabled());
        binding.keyLayout.setPlaceholderText(DanmakuAiSetting.hasKey() ? getString(R.string.danmaku_ai_key_stored_placeholder) : null);
        binding.keyLayout.setExpandedHintEnabled(!DanmakuAiSetting.hasKey());
        binding.status.setText(DanmakuAiSetting.hasKey()
                ? DanmakuAiSetting.enabled() ? R.string.danmaku_ai_saved_enabled : R.string.danmaku_ai_saved_disabled
                : R.string.danmaku_ai_key_hint);
        binding.baseUrl.setText(DanmakuAiSetting.baseUrl());
        binding.model.setText(DanmakuAiSetting.model());
        binding.keyLayout.setHelperText(getString(DanmakuAiSetting.hasKey()
                ? R.string.danmaku_ai_key_saved : R.string.danmaku_ai_key_hint));
        binding.clear.setVisibility(DanmakuAiSetting.hasKey() ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    @Override protected void initEvent() {
        binding.getRoot().addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom - top == oldBottom - oldTop) return;
            view.post(this::showFocusedField);
        });
        androidx.core.view.ViewCompat.setWindowInsetsAnimationCallback(binding.getRoot(),
                new androidx.core.view.WindowInsetsAnimationCompat.Callback(
                        androidx.core.view.WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                    @Override public androidx.core.view.WindowInsetsCompat onProgress(androidx.core.view.WindowInsetsCompat insets,
                            java.util.List<androidx.core.view.WindowInsetsAnimationCompat> animations) { return insets; }
                    @Override public void onEnd(androidx.core.view.WindowInsetsAnimationCompat animation) {
                        binding.getRoot().post(DanmakuAiDialog.this::showFocusedField);
                    }
                });
        getChildFragmentManager().setFragmentResultListener(RESULT, this, (key, result) -> {
            binding.key.setText(""); initView(); binding.status.setText(R.string.danmaku_ai_scan_saved); changed();
        });
        binding.scan.setOnClickListener(v -> new DanmakuAiPairingDialog().show(getChildFragmentManager(), "ai_pairing"));
        binding.test.setOnClickListener(v -> submit(false));
        binding.clear.setOnClickListener(v -> {
            DanmakuAiMatcher.cancel(); DanmakuAiSetting.clear();
            binding.key.setText(""); initView();
            binding.status.setText(R.string.danmaku_ai_cleared);
            changed();
        });
    }

    private void showFocusedField() {
        var scroll = binding.getRoot();
        android.view.View focused = scroll.findFocus();
        if (focused == null || focused == scroll) return;
        int[] field = new int[2], viewport = new int[2];
        focused.getLocationOnScreen(field);
        scroll.getLocationOnScreen(viewport);
        scroll.scrollBy(0, field[1] - viewport[1] - Math.round(8 * scroll.getResources().getDisplayMetrics().density));
    }

    @Override public void onStart() {
        super.onStart();
        closed = false;
        if ("leanback".equals(BuildConfig.FLAVOR_mode)) setWidth(.65f);
        if (getDialog().getWindow() != null) {
            getDialog().getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            if ("mobile".equals(BuildConfig.FLAVOR_mode)) {
                var window = getDialog().getWindow();
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false);
                if (android.os.Build.VERSION.SDK_INT >= 30)
                    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
                var params = window.getAttributes();
                params.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
                params.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
                window.setAttributes(params);
                // Keep the title, scrolling form and actions inside the same IME-safe area.
                android.view.View content = window.findViewById(android.R.id.content);
                android.view.View panel = window.findViewById(androidx.appcompat.R.id.parentPanel);
                panel.getLayoutParams().height = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
                panel.requestLayout();
                androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(content, (view, insets) -> {
                    var safe = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()
                            | androidx.core.view.WindowInsetsCompat.Type.ime());
                    view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                    return insets;
                });
                androidx.core.view.ViewCompat.requestApplyInsets(content);
            }
        }
        ((AlertDialog) requireDialog()).getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> submit(true));
    }

    private void submit(boolean save) {
        if (busy) return;
        binding.baseLayout.setError(null); binding.modelLayout.setError(null); binding.keyLayout.setError(null);
        String base;
        try { base = DanmakuAiSetting.normalizeBaseUrl(binding.baseUrl.getText().toString()); }
        catch (Exception error) { binding.baseLayout.setError(getString(R.string.danmaku_ai_bad_url)); return; }
        String model = binding.model.getText().toString().trim();
        String key = binding.key.getText().toString().trim();
        boolean enabled = binding.enabled.isChecked();
        if (model.isEmpty() || model.length() > 120) { binding.modelLayout.setError(getString(R.string.danmaku_ai_bad_model)); return; }
        if (key.isEmpty() && ((!save || enabled) && !DanmakuAiSetting.hasKey()
                || DanmakuAiSetting.hasKey() && !base.equals(DanmakuAiSetting.baseUrl()))) {
            binding.keyLayout.setError(getString(R.string.danmaku_ai_bad_key)); return;
        }
        setBusy(true);
        binding.status.setText(save ? R.string.danmaku_ai_saving : R.string.danmaku_ai_testing);
        task = WORKER.submit(() -> {
            try {
                if (save) {
                    DanmakuAiSetting.save(base, model, key, enabled);
                } else {
                    client = new DanmakuAiClient(base, model, key.isEmpty() ? DanmakuAiSetting.key() : key);
                    if (closed) { client.cancel(); return; }
                    DanmakuMatchContext example = new DanmakuMatchContext("喜剧之王【更新至14集】4K", "2026", "电视剧", "第14集");
                    String result = client.complete(DanmakuAiProtocol.context("clean", example));
                    if (!"喜剧之王".equals(DanmakuAiProtocol.query(result, example))) throw new java.io.IOException("response");
                }
                App.post(() -> {
                    if (closed || !isAdded()) return;
                    setBusy(false);
                    if (save) { DanmakuAiMatcher.cancel(); changed(); dismiss(); }
                    else binding.status.setText(R.string.danmaku_ai_test_ok);
                });
            } catch (Exception error) {
                String code = error.getMessage();
                int message = "http_401".equals(code) || "http_403".equals(code) || "credential".equals(code)
                        ? R.string.danmaku_ai_auth_error : "http_429".equals(code) ? R.string.danmaku_ai_rate_error
                        : "response".equals(code) ? R.string.danmaku_ai_response_error : R.string.danmaku_ai_connection_error;
                App.post(() -> { if (!closed && isAdded()) { setBusy(false); binding.status.setText(message); } });
            }
        });
    }

    private void setBusy(boolean value) {
        busy = value;
        binding.scan.setEnabled(!value); binding.test.setEnabled(!value); binding.clear.setEnabled(!value);
        binding.baseUrl.setEnabled(!value); binding.model.setEnabled(!value);
        binding.key.setEnabled(!value); binding.enabled.setEnabled(!value);
        ((AlertDialog) requireDialog()).getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!value);
    }

    private void changed() { getParentFragmentManager().setFragmentResult(RESULT, Bundle.EMPTY); }

    @Override public void onDismiss(DialogInterface dialog) {
        closed = true;
        if (client != null) client.cancel();
        if (task != null) task.cancel(true);
        if (binding != null) binding.key.setText("");
        super.onDismiss(dialog);
    }
}
