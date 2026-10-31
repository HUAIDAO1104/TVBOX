package com.fongmi.android.tv.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.server.process.CastProxy;
import com.fongmi.android.tv.ui.activity.HomeActivity;

/** Keeps the media relay alive after the casting sheet closes or the phone screen turns off. */
public final class CastRelayService extends Service {
    private static final String CHANNEL = "dlna_media_relay";
    private static final String STOP = "com.fongmi.android.tv.STOP_CAST_RELAY";
    private static final int NOTIFICATION_ID = 9979;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private final Runnable checkIdle = new Runnable() {
        @Override public void run() {
            if (CastProxy.relay().isIdle(30 * 60_000L)) { stopSelf(); return; }
            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(60 * 60_000L);
            handler.postDelayed(this, 30_000);
        }
    };

    public static void start(Context context, String title) {
        Intent intent = new Intent(context, CastRelayService.class).putExtra("title", title);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "电视投屏", NotificationManager.IMPORTANCE_LOW));
        }
        // Foreground notification first; hardware lock failures must not prevent serving media.
        startForeground(NOTIFICATION_ID, notification("正在投屏"));
        try {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":castRelay");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(60 * 60_000L);
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "castRelay");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        } catch (RuntimeException ignored) { }
        handler.postDelayed(checkIdle, 30_000);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) {
            CastProxy.relay().stop();
            stopSelf();
        } else {
            startForeground(NOTIFICATION_ID, notification(intent.getStringExtra("title")));
        }
        return START_NOT_STICKY;
    }

    private Notification notification(String title) {
        Intent stop = new Intent(this, CastRelayService.class).setAction(STOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent stopIntent = PendingIntent.getService(this, 0, stop, flags);
        PendingIntent openIntent = PendingIntent.getActivity(this, 0, new Intent(this, HomeActivity.class), flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setSmallIcon(R.drawable.ic_control_cast).setContentTitle(title == null ? "电视投屏" : title)
                .setContentText("正在为电视传输视频，请保持同一 Wi-Fi")
                .setContentIntent(openIntent).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "停止投屏", stopIntent).build()).build();
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(checkIdle);
        CastProxy.relay().stop();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
