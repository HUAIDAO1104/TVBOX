package com.fongmi.android.tv.search;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Result;
import java.io.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/** A hung or crashing third-party native search cannot take down the playback/UI process. */
public class SpiderSearchService extends Service {
    static final int SEARCH = 1, CANCEL = 2, RESPONSE = 3;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean busy;
    private final Runnable deadline = () -> android.os.Process.killProcess(android.os.Process.myPid());
    private final Messenger endpoint = new Messenger(new Handler(Looper.getMainLooper(), message -> {
        if (message.what == CANCEL) { deadline.run(); return true; }
        if (message.what != SEARCH) return true;
        ParcelFileDescriptor input = message.getData().getParcelable("input");
        Messenger reply = message.replyTo;
        int id = message.arg1;
        int timeout = message.arg2;
        worker.execute(() -> execute(input, reply, id, timeout));
        return true;
    }));

    @Override public IBinder onBind(Intent intent) { return endpoint.getBinder(); }

    private void execute(ParcelFileDescriptor descriptor, Messenger reply, int id, int timeout) {
        busy = true;
        handler.postDelayed(deadline, Math.max(1000, timeout));
        ParcelFileDescriptor[] output = null;
        try (InputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = stream.read(chunk)) != -1) buffer.write(chunk, 0, count);
            byte[] bytes = buffer.toByteArray();
            Parcel parcel = Parcel.obtain();
            Site site;
            String keyword, page;
            boolean quick, detail;
            try {
                parcel.unmarshall(bytes, 0, bytes.length);
                parcel.setDataPosition(0);
                site = Site.CREATOR.createFromParcel(parcel);
                keyword = parcel.readString();
                page = parcel.readString();
                quick = parcel.readInt() != 0;
                detail = parcel.readInt() != 0;
            } finally { parcel.recycle(); }
            com.fongmi.android.tv.server.Server.get().start();
            VodConfig.get().installSearchContext(site);
            Result result = detail ? Result.fromJson(site.spider().detailContent(java.util.List.of(keyword)))
                    : SiteApi.searchContent(site, keyword, quick, page);
            output = ParcelFileDescriptor.createPipe();
            Message response = Message.obtain(null, RESPONSE, 0, id);
            response.getData().putParcelable("output", output[0]);
            reply.send(response);
            output[0].close();
            try (Writer writer = new OutputStreamWriter(new ParcelFileDescriptor.AutoCloseOutputStream(output[1]), java.nio.charset.StandardCharsets.UTF_8)) {
                App.gson().toJson(result, writer);
            }
        } catch (Throwable error) {
            try { reply.send(Message.obtain(null, RESPONSE, 1, id)); } catch (RemoteException ignored) { }
        } finally {
            if (output != null) for (ParcelFileDescriptor fd : output) try { fd.close(); } catch (IOException ignored) { }
            handler.removeCallbacks(deadline);
            busy = false;
        }
    }

    @Override public void onDestroy() {
        // Unbinding during a call must release the process, including non-interruptible JNI.
        if (busy) deadline.run();
        worker.shutdownNow();
        super.onDestroy();
    }

    public static final class Second extends SpiderSearchService { }
    public static final class Third extends SpiderSearchService { }
    public static final class Fourth extends SpiderSearchService { }
}
