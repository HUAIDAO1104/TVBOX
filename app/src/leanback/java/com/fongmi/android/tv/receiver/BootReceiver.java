package com.fongmi.android.tv.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.activity.HomeActivity;
import com.fongmi.android.tv.utils.Util;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !isBootAction(intent.getAction())) return;
        launchHome(context);
        registerCallback();
    }

    private boolean isBootAction(String action) {
        return Intent.ACTION_BOOT_COMPLETED.equals(action) || "android.intent.action.QUICKBOOT_POWERON".equals(action);
    }

    private void registerCallback() {
        ConnectivityManager manager = (ConnectivityManager) App.get().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return;
        try {
            manager.registerDefaultNetworkCallback(new Callback(manager));
        } catch (Exception ignored) {
            // Home initializes VOD/config independently. A vendor network stack must never block
            // or crash boot auto-start.
        }
    }

    private void launchHome(Context context) {
        if (!Util.isLeanback() || !Setting.isBootStart()) return;
        try {
            Intent home = new Intent(context, HomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(home);
        } catch (Exception ignored) {
            // Some TV launchers disallow background activity starts. The setting remains safe and
            // the normal launcher entry continues to work.
        }
    }

    static class Callback extends ConnectivityManager.NetworkCallback {

        private final ConnectivityManager manager;

        Callback(ConnectivityManager manager) {
            this.manager = manager;
        }

        @Override
        public void onAvailable(@NonNull Network network) {
            doJob();
        }

        @Override
        public void onLost(@NonNull Network network) {
        }

        private void doJob() {
            LiveConfig.get().init().load();
            try {
                manager.unregisterNetworkCallback(this);
            } catch (Exception ignored) {
            }
        }
    }
}
