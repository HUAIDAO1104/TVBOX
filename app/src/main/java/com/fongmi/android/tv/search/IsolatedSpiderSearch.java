package com.fongmi.android.tv.search;

import android.app.ActivityManager;
import android.content.*;
import android.os.*;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Result;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Warm, bounded workers. Each process executes one Spider at a time; successful calls reuse it. */
public final class IsolatedSpiderSearch {
    private static final Object LOCK = new Object();
    private static final List<Slot> SLOTS = new ArrayList<>();
    private static final AtomicInteger REQUEST_IDS = new AtomicInteger();
    private static final int REQUEST_TIMEOUT_MS = 22000;
    private static final long IDLE_MS = 60000;
    static {
        Class<?>[] services = {SpiderSearchService.class, SpiderSearchService.Second.class,
                SpiderSearchService.Third.class, SpiderSearchService.Fourth.class};
        ActivityManager manager = (ActivityManager) App.get().getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        if (manager != null) manager.getMemoryInfo(memory);
        boolean limited = manager != null && (manager.isLowRamDevice() || memory.totalMem > 0 && memory.totalMem < 1536L * 1024 * 1024);
        int count = limited ? 2 : 4;
        for (int i = 0; i < count; i++) SLOTS.add(new Slot(services[i]));
    }
    public static int parallelism() { return SLOTS.size(); }

    public static Result search(Site original, String keyword, boolean quick, String page) throws Exception {
        return execute(original, keyword, quick, page, false);
    }
    public static Result detail(Site site, String id) throws Exception { return execute(site, id, false, "1", true); }

    private static Slot acquire(String key) throws InterruptedException {
        synchronized (LOCK) {
            while (true) {
                Slot candidate = null;
                for (Slot slot : SLOTS) if (!slot.busy) {
                    if (candidate == null) candidate = slot;
                    if (slot.sources.contains(key)) { candidate = slot; break; }
                }
                if (candidate != null) {
                    candidate.busy = true;
                    candidate.lease++;
                    return candidate;
                }
                LOCK.wait();
            }
        }
    }

    private static Result execute(Site original, String keyword, boolean quick, String page, boolean detail) throws Exception {
        Slot slot = acquire(original.getKey());
        boolean successful = false;
        ParcelFileDescriptor[] request = null;
        int id = REQUEST_IDS.incrementAndGet();
        try {
            // SharedPreferences are cached per process. Recreate workers on account/config changes.
            String context = VodConfig.getUrl() + '#' + com.fongmi.android.tv.cloud.CloudCredentialBridge.generation();
            if (!context.equals(slot.context) || slot.client != null && slot.client.disconnected) slot.close();
            slot.context = context;
            Client client = slot.connect();
            client.begin(id);
            Parcel parcel = Parcel.obtain();
            byte[] bytes;
            try {
                original.writeToParcel(parcel, 0);
                parcel.writeString(keyword); parcel.writeString(page);
                parcel.writeInt(quick ? 1 : 0); parcel.writeInt(detail ? 1 : 0);
                bytes = parcel.marshall();
            } finally { parcel.recycle(); }
            request = ParcelFileDescriptor.createPipe();
            Message message = Message.obtain(null, SpiderSearchService.SEARCH, id, REQUEST_TIMEOUT_MS);
            message.getData().putParcelable("input", request[0]);
            message.replyTo = client.reply;
            long started = android.os.SystemClock.elapsedRealtime();
            client.endpoint.send(message);
            request[0].close();
            try (OutputStream stream = new ParcelFileDescriptor.AutoCloseOutputStream(request[1])) { stream.write(bytes); }
            if (!client.completed.await(REQUEST_TIMEOUT_MS + 2000, TimeUnit.MILLISECONDS)) throw new TimeoutException();
            ParcelFileDescriptor output = client.output.getAndSet(null);
            if (output == null) {
                if (client.disconnected && SystemClock.elapsedRealtime() - started >= REQUEST_TIMEOUT_MS - 1000)
                    throw new TimeoutException("搜索源无响应，已回收搜索进程");
                throw new IOException(client.disconnected ? "来源运行异常，搜索进程已重建" : "搜索源返回失败");
            }
            try (Reader reader = new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(output), java.nio.charset.StandardCharsets.UTF_8)) {
                Result result = App.gson().fromJson(reader, Result.class);
                if (result == null) throw new IOException("搜索源返回为空");
                for (com.fongmi.android.tv.bean.Vod vod : result.getList()) vod.setSite(original);
                successful = true;
                if (slot.sources.size() >= 64) slot.sources.clear();
                slot.sources.add(original.getKey());
                return result;
            }
        } finally {
            if (request != null) for (ParcelFileDescriptor fd : request) try { fd.close(); } catch (IOException ignored) { }
            if (!successful) slot.close();
            synchronized (LOCK) {
                slot.busy = false;
                long lease = slot.lease;
                // A bounded idle lifetime releases RAM without throwing away every successful load.
                com.fongmi.android.tv.utils.Task.schedule(() -> com.fongmi.android.tv.utils.Task.execute(() -> {
                    synchronized (LOCK) {
                        if (slot.busy || slot.lease != lease) return;
                        slot.busy = true;
                    }
                    try { slot.close(); }
                    finally { synchronized (LOCK) { slot.busy = false; LOCK.notifyAll(); } }
                }), IDLE_MS, TimeUnit.MILLISECONDS);
                LOCK.notifyAll();
            }
        }
    }

    private static final class Slot {
        final Class<?> service;
        final Set<String> sources = new LinkedHashSet<>();
        boolean busy;
        long lease;
        String context = "";
        Client client;
        Slot(Class<?> service) { this.service = service; }
        Client connect() throws Exception {
            if (client != null && !client.disconnected) return client;
            client = new Client();
            client.bound = App.get().bindService(new Intent(App.get(), service), client, Context.BIND_AUTO_CREATE);
            if (!client.bound || !client.connected.await(5, TimeUnit.SECONDS) || client.endpoint == null || client.disconnected)
                throw new IOException("搜索进程启动失败");
            return client;
        }
        void close() {
            Client previous = client;
            client = null;
            sources.clear();
            if (previous == null) return;
            previous.closed = true;
            if (previous.endpoint != null) try { previous.endpoint.send(Message.obtain(null, SpiderSearchService.CANCEL)); } catch (RemoteException ignored) { }
            if (previous.bound) try { App.get().unbindService(previous); } catch (IllegalArgumentException ignored) { }
            ParcelFileDescriptor output = previous.output.getAndSet(null);
            if (output != null) try { output.close(); } catch (IOException ignored) { }
            if (previous.endpoint != null && !previous.disconnected) {
                boolean interrupted = Thread.interrupted();
                try { previous.died.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { interrupted = true; }
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    private static final class Client implements ServiceConnection {
        final CountDownLatch connected = new CountDownLatch(1), died = new CountDownLatch(1);
        volatile CountDownLatch completed = new CountDownLatch(1);
        final AtomicReference<ParcelFileDescriptor> output = new AtomicReference<>();
        volatile Messenger endpoint;
        volatile boolean disconnected, closed, bound;
        volatile int requestId;
        void begin(int id) { completed = new CountDownLatch(1); requestId = id; }
        final Messenger reply = new Messenger(new Handler(Looper.getMainLooper(), message -> {
            ParcelFileDescriptor fd = message.getData().getParcelable("output");
            if (closed || message.arg2 != requestId) { if (fd != null) try { fd.close(); } catch (IOException ignored) { } }
            else { output.set(fd); completed.countDown(); }
            return true;
        }));
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            endpoint = new Messenger(binder);
            try { binder.linkToDeath(() -> { disconnected = true; completed.countDown(); died.countDown(); }, 0); }
            catch (RemoteException error) { disconnected = true; completed.countDown(); died.countDown(); }
            connected.countDown();
        }
        @Override public void onServiceDisconnected(ComponentName name) { disconnected = true; completed.countDown(); died.countDown(); }
        @Override public void onNullBinding(ComponentName name) { connected.countDown(); }
    }
}
