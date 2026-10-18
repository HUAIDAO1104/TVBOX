package com.fongmi.android.tv.server;

import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.Proxy;

public class Server {

    private volatile PlaybackService service;
    private volatile Nano nano;

    private static class Loader {
        static volatile Server INSTANCE = new Server();
    }

    public static Server get() {
        return Loader.INSTANCE;
    }

    public PlaybackService getService() {
        return service;
    }

    public void setService(PlaybackService service) {
        this.service = service;
    }

    public String getAddress() {
        return getAddress(false);
    }

    public String getAddress(int tab) {
        return getAddress(false) + "?tab=" + tab;
    }

    public String getAddress(String path) {
        return getAddress(true) + path;
    }

    public String getAddress(boolean local) {
        Nano running = nano;
        String host = local ? "127.0.0.1" : LanAddress.current();
        if (host.isEmpty() || running == null || !running.isAlive()) return "";
        return "http://" + host + ":" + running.getListeningPort();
    }

    public synchronized void start() {
        if (nano != null && nano.isAlive()) return;
        if (nano != null) nano.stop();
        for (int i = 9978; i < 9999; i++) {
            Nano started = null;
            try {
                started = com.fongmi.android.tv.App.isSearchProcess() ? new Nano("127.0.0.1", i) : new Nano(i);
                // A phone can pause between headers and body while switching Wi-Fi radios.
                // Keep a bounded socket wait without rejecting an otherwise valid QR save.
                started.start(10_000);
                nano = started;
                Proxy.set(i);
                break;
            } catch (Throwable e) {
                if (started != null) started.stop();
                nano = null;
            }
        }
    }

    public void stop() { stop(Task::execute); }

    synchronized void stop(java.util.function.Consumer<Runnable> defer) {
        Nano stopped = nano;
        nano = null;
        service = null;
        // Capture this instance now: a queued stop must never close a newly opened QR server.
        if (stopped != null) defer.accept(stopped::stop);
    }
}
