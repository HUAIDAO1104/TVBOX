package com.fongmi.android.tv;

import android.net.Uri;
import android.view.View;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.impl.UpdateListener;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.dialog.UpdateDialog;
import com.fongmi.android.tv.update.UpdateClient;
import com.fongmi.android.tv.update.UpdateInstallerActivity;
import com.fongmi.android.tv.update.UpdateManifest;
import com.fongmi.android.tv.update.UpdateSource;
import com.fongmi.android.tv.update.UpdateVerifier;
import com.fongmi.android.tv.utils.Download;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.utils.Path;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.Locale;

public class Updater implements Download.Callback, UpdateListener {

    private static final long AUTO_CHECK_INTERVAL_MS = 12L * 60L * 60L * 1000L;

    private WeakReference<FragmentActivity> activityRef;
    private UpdateManifest.Asset asset;
    private Download download;
    private UpdateDialog dialog;
    private boolean mandatory;
    private boolean forced;

    private Updater() {
    }

    public static Updater create() {
        return new Updater();
    }

    private File getFile() {
        // Keep the downloaded payload extension-neutral. Some Android TV firmwares inspect or
        // quarantine files ending in .apk before the platform installer has a chance to verify
        // them. UpdateInstallerActivity streams this verified payload into PackageInstaller.
        return Path.cache("update.payload");
    }

    public Updater force() {
        forced = true;
        Setting.putUpdate(true);
        return this;
    }

    public void start(FragmentActivity activity) {
        long now = System.currentTimeMillis();
        if (!forced && now - Setting.getUpdateCheckTime() < AUTO_CHECK_INTERVAL_MS) return;
        Setting.putUpdateCheckTime(now);
        activityRef = new WeakReference<>(activity);
        if (forced) showChecking(activity);
        Task.execute(this::doInBackground);
    }

    private void doInBackground() {
        try {
            UpdateManifest manifest = UpdateClient.fetch();
            if (manifest.versionCode() <= BuildConfig.VERSION_CODE) {
                if (forced) App.post(() -> {
                    dismiss();
                    Notify.show(R.string.update_latest);
                });
                return;
            }
            asset = manifest.assetFor(BuildConfig.FLAVOR_mode, BuildConfig.FLAVOR_abi);
            if (asset == null || asset.sha256().isEmpty()) {
                if (forced) App.post(() -> {
                    dismiss();
                    Notify.show(R.string.update_unsupported);
                });
                return;
            }
            mandatory = manifest.mandatory();
            App.post(() -> show(manifest.versionName(), manifest.releaseNotes()));
        } catch (Exception e) {
            com.github.catvod.crawler.SpiderDebug.log(e);
            if (forced) App.post(() -> {
                dismiss();
                Notify.show(R.string.update_failed);
            });
        }
    }

    private void showChecking(FragmentActivity activity) {
        dialog = UpdateDialog.create()
                .title(ResUtil.getString(R.string.update_check))
                .desc(ResUtil.getString(R.string.update_select_mirror))
                .listener(this)
                .checking()
                .show(activity);
    }

    private void show(String version, String desc) {
        FragmentActivity activity = activityRef == null ? null : activityRef.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (desc == null || desc.isBlank()) desc = ResUtil.getString(R.string.update_default_notes);
        if (forced && dialog != null) {
            dialog.setRelease(ResUtil.getString(R.string.update_version, version), desc, mandatory);
            return;
        }
        dismiss();
        dialog = UpdateDialog.create().title(ResUtil.getString(R.string.update_version, version)).desc(desc).mandatory(mandatory).listener(this).show(activity);
    }

    @Override
    public void onConfirm(View view) {
        view.setEnabled(false);
        if (dialog != null) dialog.setDownloading();
        download = Download.create(UpdateSource.assetCandidates(asset), getFile())
                .sha256(asset.sha256())
                .connectTimeout(8_000L)
                .readTimeout(60_000L)
                .fastestFirst()
                .resume()
                .tag("app-update");
        download.start(this);
    }

    @Override
    public void onCancel(View view) {
        if (download != null) download.cancel();
        Path.clear(getFile());
        Path.clear(new File(getFile().getAbsolutePath() + ".sha256"));
        dismiss();
    }

    private void dismiss() {
        try {
            if (dialog != null) dialog.dismiss();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void progress(int progress) {
        if (dialog != null) dialog.setProgress(progress);
    }

    @Override
    public void status(String source, long bytesPerSecond, long downloaded, long total) {
        if (dialog == null) return;
        String host = Uri.parse(source).getHost();
        if (host == null || host.isBlank()) host = "更新镜像";
        dialog.setStatus(ResUtil.getString(R.string.update_download_status,
                host, formatBytes(bytesPerSecond), formatBytes(downloaded), total > 0L ? formatBytes(total) : "—"));
    }

    @Override
    public void error(String msg) {
        com.github.catvod.crawler.SpiderDebug.log(msg);
        if (dialog != null) dialog.setError(ResUtil.getString(R.string.update_download_failed));
        else Notify.show(R.string.update_failed);
    }

    @Override
    public void success(File file) {
        boolean checksumMatches = UpdateVerifier.checksumMatches(file, asset.sha256());
        UpdateVerifier.SignerStatus signerStatus = UpdateVerifier.signerStatus(App.get(), file);
        if (!UpdateVerifier.canInstall(checksumMatches, signerStatus)) {
            Path.clear(file);
            Notify.show(R.string.update_invalid);
            dismiss();
            return;
        }
        UpdateInstallerActivity.start(file);
        dismiss();
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double value = bytes / 1024.0;
        if (value < 1024.0) return String.format(Locale.getDefault(), "%.1f KB", value);
        value /= 1024.0;
        return String.format(Locale.getDefault(), "%.1f MB", value);
    }
}
