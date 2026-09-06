package com.fongmi.android.tv;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.os.HandlerCompat;

import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.cloud.CloudAccountManager;
import com.fongmi.android.tv.repository.RepositoryManager;
import com.fongmi.android.tv.security.ToastPolicy;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.hook.Hook;
import com.github.catvod.Init;
import com.google.gson.Gson;

public class App extends Application implements Application.ActivityLifecycleCallbacks {

    private static volatile App instance;

    private final Handler handler;
    private final Gson gson;
    private final long time;

    private Activity activity;
    private Hook hook;

    public App() {
        instance = this;
        gson = new Gson();
        time = System.currentTimeMillis();
        handler = HandlerCompat.createAsync(Looper.getMainLooper());
    }

    public static App get() {
        return instance;
    }

    public static Gson gson() {
        return get().gson;
    }

    public static long time() {
        return get().time;
    }

    public static Activity activity() {
        return get().activity;
    }

    public static void post(Runnable runnable) {
        get().handler.post(runnable);
    }

    public static void post(Runnable runnable, long delayMillis) {
        get().handler.removeCallbacks(runnable);
        if (delayMillis >= 0) get().handler.postDelayed(runnable, delayMillis);
    }

    public static void removeCallbacks(Runnable runnable) {
        get().handler.removeCallbacks(runnable);
    }

    public static void removeCallbacks(Runnable... runnable) {
        for (Runnable r : runnable) get().handler.removeCallbacks(r);
    }

    public void setHook(Hook hook) {
        this.hook = hook;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // Both contexts have a process lifetime; avoid wrapping a weakly retained context.
        Init.set(isSearchProcess() ? this : base);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (isSearchProcess()) {
            com.orhanobut.logger.Logger.addLogAdapter(new com.orhanobut.logger.AndroidLogAdapter());
            com.github.catvod.net.OkHttp.dns().setDoh(com.github.catvod.bean.Doh.objectFrom(com.fongmi.android.tv.setting.Setting.getDoh()));
            return;
        }
        Notify.createChannel();
        registerActivityLifecycleCallbacks(this);
        // Keystore access, Room creation/migrations and repository bootstrap all touch disk.
        // None of them is required to draw the first frame, so keep them off the main thread.
        Task.execute(CloudAccountManager::migrateLegacyCredentials);
        RepositoryManager.get().initialize();
        com.fongmi.android.tv.player.exo.MediaSourceFactory.prepareCache(null);
    }

    private static String processName() {
        if (android.os.Build.VERSION.SDK_INT >= 28) return Application.getProcessName();
        try (java.io.InputStream input = new java.io.FileInputStream("/proc/self/cmdline")) {
            byte[] buffer = new byte[256];
            int count = input.read(buffer);
            return count < 0 ? "" : new String(buffer, 0, count, java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (java.io.IOException ignored) { return ""; }
    }

    public static boolean isSearchProcess() { return processName().contains(":source_search"); }

    @Override public java.io.File getCacheDir() {
        java.io.File base = super.getCacheDir();
        if (!isSearchProcess()) return base;
        java.io.File isolated = new java.io.File(base, "search-worker-" + processName().substring(processName().lastIndexOf("source_search") + "source_search".length()));
        isolated.mkdirs();
        return isolated;
    }

    @Override
    public PackageManager getPackageManager() {
        return hook != null ? hook : getBaseContext().getPackageManager();
    }

    @Override
    public String getPackageName() {
        return hook != null ? hook.getPackageName() : getBaseContext().getPackageName();
    }

    @Override
    public String getOpPackageName() {
        if (ToastPolicy.shouldRejectPackageLookup()) return null;
        return hook != null ? hook.getPackageName() : getBaseContext().getPackageName();
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        if (activity != activity()) this.activity = activity;
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
        if (activity == activity()) this.activity = null;
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
    }
}
