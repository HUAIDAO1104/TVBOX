package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.method.PasswordTransformationMethod;
import android.widget.LinearLayout;
import androidx.activity.result.contract.ActivityResultContracts;
import com.fongmi.android.tv.utils.Util;
import com.fongmi.android.tv.utils.QRCode;
import com.google.gson.JsonObject;
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
    private final boolean tv = Util.isLeanback();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int generation;
    private String claim, pairServer;
    private long expiresAt;
    private AccountWebLogin web;

    private final androidx.activity.result.ActivityResultLauncher<Intent> scanner = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null)
                    confirmScan(result.getData().getStringExtra("address"));
            });
    private final androidx.activity.result.ActivityResultLauncher<String> camera = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) openScanner(); else binding.feedback.setText("需要相机权限才能扫描电视二维码");
            });

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, PddAccountActivity.class));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        binding = ActivityPddAccountBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        var metrics = getResources().getDisplayMetrics();
        FrameLayout.LayoutParams panel = (FrameLayout.LayoutParams) binding.accountPanel.getLayoutParams();
        panel.width = Math.min(metrics.widthPixels, (int) ((tv ? 900 : 480) * metrics.density));
        binding.accountPanel.setLayoutParams(panel);
        if (tv) {
            Util.hideSystemUI(this);
            binding.brand.setTextSize(23);
            binding.brand.setTextColor(0xFFF4F5F7);
            binding.title.setVisibility(View.GONE);
            binding.columns.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams qrSize = new LinearLayout.LayoutParams(0, -2, 1);
            qrSize.setMarginEnd((int)(18 * metrics.density));
            binding.qrCard.setLayoutParams(qrSize);
            binding.form.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.15f));
        }
        binding.refreshQr.setOnClickListener(v -> startCode());
        binding.scanTv.setOnClickListener(v -> {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openScanner();
            else camera.launch(Manifest.permission.CAMERA);
        });
        binding.webLogin.setOnClickListener(v -> openWeb());
        binding.reveal.setOnClickListener(v -> {
            boolean show = binding.password.getTransformationMethod() != null;
            binding.password.setTransformationMethod(show ? null : PasswordTransformationMethod.getInstance());
            binding.password.setSelection(binding.password.length());
            binding.reveal.setText(show ? "隐藏" : "显示");
            binding.reveal.setContentDescription(show ? "隐藏密码" : "显示密码");
        });
        binding.password.setOnEditorActionListener((view, action, event) -> {
            if (action != android.view.inputmethod.EditorInfo.IME_ACTION_DONE) return false;
            authenticate(false); return true;
        });
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
        String server = binding.server.getText().toString().trim();
        boolean supported = AccountSession.hasDeviceLogin(server);
        binding.columns.setVisibility(guest ? View.VISIBLE : View.GONE);
        binding.qrCard.setVisibility(guest && tv && (supported || BuildConfig.DEBUG) ? View.VISIBLE : View.GONE);
        binding.webLogin.setVisibility(BuildConfig.PERSONAL_ACCOUNT_URL.equals(server) ? View.VISIBLE : View.GONE);
        binding.scanTv.setVisibility(!guest && !tv && supported ? View.VISIBLE : View.GONE);
        binding.accountActions.setVisibility(guest ? View.GONE : View.VISIBLE);
        binding.title.setText(guest ? "登录片多多" : AccountSession.username());
        if (tv) binding.brand.setText(guest ? "片多多账号" : AccountSession.username());
        binding.summary.setText(guest ? (tv ? "扫码确认，或使用已有账号登录。" : "在手机和电视之间同步观看进度。") : "已登录 · 观看记录随账号同步");
        for (View v : new View[]{binding.login,binding.register,binding.logout,binding.sync,binding.importGuest,binding.webLogin,binding.scanTv})
            v.setEnabled(!busy);
    }

    private void authenticate(boolean register) {
        if (busy) return;
        String server = binding.server.getText().toString().trim();
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

    private void openWeb() {
        if (web != null && web.isShowing()) return;
        suspendCodeForLogin();
        web = new AccountWebLogin(this, value -> {
            if (value.has("error")) binding.feedback.setText(value.get("error").getAsString());
            else perform(() -> AccountSession.accept(BuildConfig.PERSONAL_ACCOUNT_URL, value), "登录成功");
        });
        web.setOnDismissListener(dialog -> resumeCode());
        web.show();
    }

    private void openScanner() {
        scanner.launch(new Intent().setClassName(this, getPackageName() + ".ui.activity.ScanActivity"));
    }

    private void confirmScan(String address) {
        String server = AccountSession.server();
        String prefix = server + "/tv#";
        if (!AccountSession.hasDeviceLogin(server) || address == null || !address.startsWith(prefix) ||
                !address.substring(prefix.length()).matches("[A-Za-z0-9_-]{43}")) {
            binding.feedback.setText("请扫描片多多电视账号页上的登录二维码"); return;
        }
        String scan = address.substring(prefix.length());
        new MaterialAlertDialogBuilder(this).setTitle("允许这台电视登录？")
                .setMessage("请核对电视屏幕上的识别码  " + scan.substring(0,6) + "\n电视将登录账号 " + AccountSession.username() + "。")
                .setNegativeButton("取消", null).setPositiveButton("确认电视登录", (d,w) ->
                        perform(() -> AccountSession.approve(scan), "已确认，电视会自动登录")).show();
    }

    @Override protected void onStart() {
        super.onStart();
        resumeCode();
    }

    private void resumeCode() {
        if (!tv || busy || isFinishing() || isDestroyed() || !AccountSession.profile().isEmpty()) return;
        if (web != null && web.isShowing()) return;
        if (AccountSession.hasDeviceLogin(binding.server.getText().toString()) && claim == null) startCode();
    }

    private void suspendCodeForLogin() {
        if (!tv || !AccountSession.profile().isEmpty()) return;
        stopCode();
        binding.qr.setImageDrawable(null);
        binding.qrHint.setText("正在使用账号登录…");
        binding.refreshQr.setEnabled(false);
    }

    @Override protected void onStop() { stopCode(); super.onStop(); }

    @Override protected void onDestroy() {
        if (web != null && web.isShowing()) web.dismiss();
        super.onDestroy();
    }

    private void stopCode() {
        generation++;
        handler.removeCallbacksAndMessages(null);
        String previous = claim, server = pairServer;
        claim = null;
        if (previous != null) cancelCode(server, previous);
    }

    private void cancelCode(String server, String secret) {
        Task.execute(() -> {
            try { AccountSession.deviceRequest(server, "cancel", "secret", secret); } catch (Exception ignored) { }
        });
    }

    private void startCode() {
        stopCode();
        String server = binding.server.getText().toString().trim();
        int current = generation;
        binding.qr.setImageDrawable(null);
        binding.qrHint.setText("正在获取登录二维码…");
        binding.refreshQr.setEnabled(false);
        Task.execute(() -> {
            try {
                JsonObject value = AccountSession.deviceRequest(server,"start",null,null);
                String secret = value.get("secret").getAsString(), scan = value.get("scan").getAsString();
                if (!secret.matches("[A-Za-z0-9_-]{43}") || !scan.matches("[A-Za-z0-9_-]{43}")) throw new java.io.IOException("二维码数据不完整");
                var bitmap = QRCode.getStandardBitmap(server + "/tv#" + scan, 180);
                if (bitmap == null) throw new java.io.IOException("二维码生成失败");
                runOnUiThread(() -> {
                    if (generation != current) { cancelCode(server,secret); return; }
                    claim=secret; pairServer=server;
                    expiresAt=SystemClock.elapsedRealtime()+Math.min(180,value.get("expiresIn").getAsInt())*1000L;
                    binding.qr.setImageBitmap(bitmap);
                    binding.qrHint.setText("识别码  " + scan.substring(0,6) + "\n用手机片多多或相机扫码");
                    binding.refreshQr.setEnabled(true);
                    schedulePoll(current);
                });
            } catch (Exception e) { runOnUiThread(() -> qrError(current, e.getMessage())); }
        });
    }

    private void qrError(int current, String message) {
        if (generation != current) return;
        stopCode();
        binding.qr.setImageDrawable(null);
        binding.qrHint.setText(message == null ? "二维码暂不可用，请刷新" : message);
        binding.refreshQr.setEnabled(true);
    }

    private void schedulePoll(int current) {
        handler.postDelayed(() -> {
            if (generation != current) return;
            if (SystemClock.elapsedRealtime()>=expiresAt) { qrError(current,"二维码已过期，请刷新"); return; }
            String server=pairServer, secret=claim;
            Task.execute(() -> {
                try {
                    JsonObject value=AccountSession.deviceRequest(server,"claim","secret",secret);
                    runOnUiThread(() -> {
                        if (generation != current || busy) {
                            if (value.has("token")) Task.execute(() -> AccountSession.discardDeviceSession(server, value));
                            return;
                        }
                        if (value.has("pending")) schedulePoll(current);
                        else {
                            claim=null;
                            stopCode();
                            perform(() -> AccountSession.accept(server,value),"电视已登录");
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        if (generation != current) return;
                        if (e instanceof AccountSession.ServiceError && ((AccountSession.ServiceError)e).status==410)
                            qrError(current,"二维码已过期，请刷新");
                        else schedulePoll(current);
                    });
                }
            });
        }, 3000);
    }

    private interface Operation { void run() throws Exception; }

    private void perform(Operation operation, String success) {
        if (busy) return;
        busy = true;
        suspendCodeForLogin();
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
                if (message.equals(success)) {
                    binding.password.setText("");
                    if (!AccountSession.profile().isEmpty()) stopCode();

                }
                if (getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) resumeCode();
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
