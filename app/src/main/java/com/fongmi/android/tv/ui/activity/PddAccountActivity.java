package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.databinding.ActivityPddAccountBinding;
import com.fongmi.android.tv.history.AccountSession;
import com.fongmi.android.tv.history.HistoryLink;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class PddAccountActivity extends AppCompatActivity {
    private ActivityPddAccountBinding binding;
    private boolean busy;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, PddAccountActivity.class));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        binding = ActivityPddAccountBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        var metrics = getResources().getDisplayMetrics();
        FrameLayout.LayoutParams panel = (FrameLayout.LayoutParams) binding.accountPanel.getLayoutParams();
        panel.width = Math.min(metrics.widthPixels, (int) (520 * metrics.density));
        binding.accountPanel.setLayoutParams(panel);
        binding.back.setOnClickListener(v -> finish());
        binding.server.setText(AccountSession.server());
        binding.server.setVisibility(BuildConfig.ACCOUNT_SERVICE_URL.isEmpty() ? View.VISIBLE : View.GONE);
        binding.login.setOnClickListener(v -> authenticate(false));
        binding.register.setOnClickListener(v -> authenticate(true));
        binding.logout.setOnClickListener(v -> perform(AccountSession::logout, "已退出账号"));
        binding.sync.setOnClickListener(v -> sync());
        binding.importGuest.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle("导入到 " + AccountSession.username() + "？")
                .setMessage("本机游客的观看记录会复制到这个账号，账号里已有的同一影片会保留。")
                .setNegativeButton("取消", null).setPositiveButton("导入", (dialog, which) ->
                        perform(AccountSession::importGuestHistory, "已导入本机游客历史")).show());
        render();
        binding.feedback.setText(AccountSession.profile().isEmpty() ? "" : HistoryLink.status());
    }

    private void render() {
        boolean guest = AccountSession.profile().isEmpty();
        binding.form.setVisibility(guest ? View.VISIBLE : View.GONE);
        binding.accountActions.setVisibility(guest ? View.GONE : View.VISIBLE);
        binding.summary.setText(guest ? "在手机和电视登录同一账号，即可同步同一订阅下的集数和播放进度。"
                : AccountSession.username() + "\n已开启观看历史同步");
        for (View v : new View[]{binding.login, binding.register, binding.logout, binding.sync, binding.importGuest})
            v.setEnabled(!busy);
    }

    private void authenticate(boolean register) {
        String server = binding.server.getText().toString();
        String name = binding.username.getText().toString();
        String password = binding.password.getText().toString();
        if (register && BuildConfig.PERSONAL_ACCOUNT_URL.equals(server)) {
            var input = new android.widget.EditText(this);
            input.setSingleLine();
            input.setSaveEnabled(false);
            input.setHint("专属激活码");
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            new MaterialAlertDialogBuilder(this).setTitle("激活私人账号").setMessage("仅首次注册需要激活码。同一账号可直接在其他设备登录。")
                    .setView(input).setNegativeButton("取消", null).setPositiveButton("激活", (dialog, which) -> {
                        String code = input.getText().toString();
                        perform(() -> AccountSession.login(server, name, password, true, code), "账号已创建");
                    }).show();
        } else {
            perform(() -> AccountSession.login(server, name, password, register), register ? "账号已创建" : "登录成功");
        }
    }

    private interface Operation { void run() throws Exception; }

    private void perform(Operation operation, String success) {
        if (busy) return;
        busy = true;
        render();
        binding.feedback.setText("正在处理…");
        Task.execute(() -> {
            String result = success;
            try { operation.run(); }
            catch (java.net.UnknownHostException | java.net.ConnectException | java.net.SocketTimeoutException e) {
                result = "无法连接账号服务，请检查网络后重试";
            } catch (Exception e) { result = e.getMessage() == null ? "操作失败，请重试" : e.getMessage(); }
            String message = result;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                busy = false;
                if (message.equals(success)) binding.password.setText("");
                render();
                binding.feedback.setText(message);
            });
        });
    }

    private void sync() {
        if (busy) return;
        binding.feedback.setText("正在同步观看历史…");
        HistoryLink.sync(this::syncResult);
    }

    private void syncResult(String status) {
        if (isFinishing() || isDestroyed() || AccountSession.profile().isEmpty()) return;
        binding.feedback.setText(status);
        var conflict = HistoryLink.conflict();
        if (conflict != null) new MaterialAlertDialogBuilder(this).setTitle("选择要保留的进度")
                .setMessage(conflict.description()).setNegativeButton("稍后", null)
                .setNeutralButton("使用账号进度", (d, w) -> HistoryLink.resolve(conflict, false, this::syncResult))
                .setPositiveButton("使用本机进度", (d, w) -> HistoryLink.resolve(conflict, true, this::syncResult)).show();
    }
}
