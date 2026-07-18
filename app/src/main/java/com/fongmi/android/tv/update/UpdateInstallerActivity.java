package com.fongmi.android.tv.update;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Notify;

import java.io.File;

public class UpdateInstallerActivity extends Activity {

    private static final String EXTRA_PATH = "path";

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
        String path = getIntent().getStringExtra(EXTRA_PATH);
        apk = path == null ? null : new File(path);
        permissionRequested = savedInstanceState != null && savedInstanceState.getBoolean("permissionRequested");
        installerStarted = savedInstanceState != null && savedInstanceState.getBoolean("installerStarted");
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
            openInstaller();
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

    private void openInstaller() {
        installerStarted = true;
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(FileUtil.getShareUri(apk), "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!startSafely(intent)) Notify.show(R.string.update_install_failed);
        finish();
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
