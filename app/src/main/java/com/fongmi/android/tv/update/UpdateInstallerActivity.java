package com.fongmi.android.tv.update;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.utils.Path;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class UpdateInstallerActivity extends Activity {

    private static final String EXTRA_PATH = "path";
    private static final String ACTION_INSTALL_STATUS = "com.fongmi.android.tv.action.INSTALL_STATUS";

    private boolean permissionRequested;
    private boolean installerStarted;
    private File apk;

    public static void start(File apk) {
        Intent intent = new Intent(App.get(), UpdateInstallerActivity.class);
        intent.putExtra(EXTRA_PATH, apk.getAbsolutePath());
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        App.get().startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        permissionRequested = savedInstanceState != null && savedInstanceState.getBoolean("permissionRequested");
        installerStarted = savedInstanceState != null && savedInstanceState.getBoolean("installerStarted");
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean("permissionRequested", permissionRequested);
        outState.putBoolean("installerStarted", installerStarted);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (installerStarted) return;
        if (apk == null || !apk.isFile()) {
            Notify.show(R.string.update_invalid);
            finish();
            return;
        }
        if (canInstallPackages()) {
            beginSessionInstall();
        } else if (permissionRequested) {
            Notify.show(R.string.update_permission_denied);
            finish();
        } else {
            requestInstallPermission();
        }
    }

    private boolean canInstallPackages() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || getPackageManager().canRequestPackageInstalls();
    }

    private void requestInstallPermission() {
        permissionRequested = true;
        Notify.show(R.string.update_permission);
        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()));
        if (!startSafely(intent) && !startSafely(new Intent(Settings.ACTION_SECURITY_SETTINGS))) {
            Notify.show(R.string.update_install_failed);
            finish();
        }
    }

    private void handleIntent(Intent intent) {
        String path = intent == null ? null : intent.getStringExtra(EXTRA_PATH);
        if (path != null) apk = new File(path);
        if (intent != null && ACTION_INSTALL_STATUS.equals(intent.getAction())) {
            installerStarted = true;
            handleInstallStatus(intent);
        }
    }

    private void beginSessionInstall() {
        installerStarted = true;
        Task.execute(() -> {
            try {
                commitSession(apk);
                runOnUiThread(this::finish);
            } catch (Exception e) {
                runOnUiThread(this::openLegacyInstaller);
            }
        });
    }

    private void commitSession(File payload) throws Exception {
        PackageInstaller installer = getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(getPackageName());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            params.setInstallReason(PackageManager.INSTALL_REASON_USER);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        }
        int sessionId = installer.createSession(params);
        boolean committed = false;
        try (PackageInstaller.Session session = installer.openSession(sessionId);
             InputStream input = new FileInputStream(payload)) {
            try (OutputStream output = session.openWrite("base.apk", 0L, payload.length())) {
                byte[] buffer = new byte[32 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                session.fsync(output);
            }
            session.commit(statusReceiver(sessionId, payload));
            committed = true;
        } finally {
            if (!committed) {
                try {
                    installer.abandonSession(sessionId);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private IntentSender statusReceiver(int sessionId, File payload) {
        Intent intent = new Intent(this, UpdateInstallerActivity.class);
        intent.setAction(ACTION_INSTALL_STATUS);
        intent.putExtra(EXTRA_PATH, payload.getAbsolutePath());
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
        return PendingIntent.getActivity(this, sessionId, intent, flags).getIntentSender();
    }

    private void handleInstallStatus(Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation = getParcelableIntent(intent, Intent.EXTRA_INTENT);
            if (confirmation == null || !startSafely(confirmation)) {
                clearPayload();
                Notify.show(R.string.update_install_failed);
            }
            finish();
            return;
        }
        clearPayload();
        if (status != PackageInstaller.STATUS_SUCCESS) Notify.show(R.string.update_install_failed);
        finish();
    }

    @SuppressWarnings("deprecation")
    private Intent getParcelableIntent(Intent source, String key) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return source.getParcelableExtra(key, Intent.class);
        }
        return source.getParcelableExtra(key);
    }

    private void openLegacyInstaller() {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(FileUtil.getShareUri(apk), "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!startSafely(intent)) Notify.show(R.string.update_install_failed);
        finish();
    }

    private void clearPayload() {
        if (apk == null) return;
        Path.clear(apk);
        Path.clear(new File(apk.getAbsolutePath() + ".sha256"));
    }

    private boolean startSafely(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

}
