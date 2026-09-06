package com.fongmi.android.tv.search;

import android.content.*;
import android.os.*;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Result;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Two process slots, each globally serial across Activity/ViewModel lifetimes. */
public final class IsolatedSpiderSearch {
    private static final ArrayBlockingQueue<Class<?>> AVAILABLE = new ArrayBlockingQueue<>(2);
    static { AVAILABLE.add(SpiderSearchService.class); AVAILABLE.add(SpiderSearchService.Second.class); }

    public static Result search(Site original, String keyword, boolean quick, String page) throws Exception {
        return execute(original, keyword, quick, page, false);
    }

    public static Result detail(Site site, String id) throws Exception {
        return execute(site, id, false, "1", true);
    }

    private static Result execute(Site original, String keyword, boolean quick, String page, boolean detail) throws Exception {
        Class<?> service = AVAILABLE.take();
        Client client = new Client();
        boolean bound = false;
        ParcelFileDescriptor[] request = null;
        try {
            Intent intent = new Intent(App.get(), service);
            bound = App.get().bindService(intent, client, Context.BIND_AUTO_CREATE);
            if (!bound || !client.connected.await(5, TimeUnit.SECONDS) || client.endpoint == null)
                throw new IOException("搜索进程启动失败");
            Site site = App.gson().fromJson(App.gson().toJson(original), Site.class);
            Parcel parcel = Parcel.obtain();
            byte[] bytes;
            try {
                site.writeToParcel(parcel, 0);
                parcel.writeString(keyword);
                parcel.writeString(page);
                parcel.writeInt(quick ? 1 : 0);
                parcel.writeInt(detail ? 1 : 0);
                bytes = parcel.marshall();
            } finally { parcel.recycle(); }
            request = ParcelFileDescriptor.createPipe();
            Message message = Message.obtain(null, SpiderSearchService.SEARCH);
            message.getData().putParcelable("input", request[0]);
            message.replyTo = client.reply;
            client.endpoint.send(message);
            request[0].close();
            try (OutputStream stream = new ParcelFileDescriptor.AutoCloseOutputStream(request[1])) { stream.write(bytes); }
            if (!client.completed.await(24, TimeUnit.SECONDS)) throw new TimeoutException();
            ParcelFileDescriptor output = client.output.getAndSet(null);
            if (output == null) {
                if (client.disconnected) throw new TimeoutException("搜索源无响应，已回收独立进程");
                throw new IOException("搜索源返回失败");
            }
            try (Reader reader = new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(output), java.nio.charset.StandardCharsets.UTF_8)) {
                Result result = App.gson().fromJson(reader, Result.class);
                if (result == null) throw new IOException("搜索源返回为空");
                for (com.fongmi.android.tv.bean.Vod vod : result.getList()) vod.setSite(original);
                return result;
            }
        } finally {
            client.closed = true;
            // Service owns the watchdog; cancellation terminates only its process.
            if (client.endpoint != null) try { client.endpoint.send(Message.obtain(null, SpiderSearchService.CANCEL)); } catch (RemoteException ignored) { }
            if (bound) App.get().unbindService(client);
            ParcelFileDescriptor output = client.output.getAndSet(null);
            if (output != null) try { output.close(); } catch (IOException ignored) { }
            if (request != null) for (ParcelFileDescriptor fd : request) try { fd.close(); } catch (IOException ignored) { }
            // Death acknowledgement prevents another search binding to a process being killed.
            if (client.endpoint != null) {
                boolean interrupted = Thread.interrupted();
                try { client.died.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { interrupted = true; }
                if (interrupted) Thread.currentThread().interrupt();
            }
            AVAILABLE.offer(service);
        }
    }

    private static final class Client implements ServiceConnection {
        final CountDownLatch connected = new CountDownLatch(1), completed = new CountDownLatch(1), died = new CountDownLatch(1);
        final AtomicReference<ParcelFileDescriptor> output = new AtomicReference<>();
        volatile Messenger endpoint;
        volatile boolean disconnected, closed;
        final Messenger reply = new Messenger(new Handler(Looper.getMainLooper(), message -> {
            ParcelFileDescriptor fd = message.getData().getParcelable("output");
            if (closed) { if (fd != null) try { fd.close(); } catch (IOException ignored) { } }
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
