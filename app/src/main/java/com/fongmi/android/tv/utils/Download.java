package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.App;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;
import com.google.common.net.HttpHeaders;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

import okhttp3.Response;
import okhttp3.ResponseBody;

public class Download {

    private final File file;
    private final List<String> urls;
    private Callback callback;
    private Future<?> future;
    private String sha256;
    private long timeout;
    private String tag;

    public static Download create(String url, File file) {
        return new Download(List.of(url), file);
    }

    public static Download create(List<String> urls, File file) {
        return new Download(urls, file);
    }

    public Download(String url, File file) {
        this(List.of(url), file);
    }

    public Download(List<String> urls, File file) {
        this.urls = new ArrayList<>(urls);
        this.tag = this.urls.isEmpty() ? "download" : this.urls.get(0);
        this.file = file;
    }

    public Download tag(String tag) {
        this.tag = tag;
        return this;
    }

    public Download sha256(String sha256) {
        this.sha256 = sha256;
        return this;
    }

    public Download timeout(long timeout) {
        this.timeout = timeout;
        return this;
    }

    public File get() {
        doInBackground();
        return file;
    }

    public void start(Callback callback) {
        this.callback = callback;
        future = Task.submit(this::doInBackground);
    }

    public Download cancel() {
        if (future != null) future.cancel(true);
        OkHttp.cancel(tag);
        future = null;
        return this;
    }

    private void doInBackground() {
        Exception last = null;
        for (String url : urls) {
            if (Thread.currentThread().isInterrupted()) return;
            try (Response res = timeout > 0 ? OkHttp.newCall(OkHttp.client(timeout), url, tag).execute() : OkHttp.newCall(url, tag).execute()) {
                ResponseBody body = res.body();
                if (!res.isSuccessful() || body == null) throw new IOException("HTTP " + res.code());
                download(body.byteStream(), getLength(res));
                if (sha256 != null && !sha256.isBlank() && !com.fongmi.android.tv.update.UpdateVerifier.checksumMatches(file, sha256)) {
                    throw new IOException("APK checksum mismatch");
                }
                if (callback != null) App.post(() -> callback.success(file));
                return;
            } catch (Exception e) {
                last = e;
                Path.clear(file);
            }
        }
        if (Thread.currentThread().isInterrupted()) return;
        Exception error = last == null ? new IOException("No download source") : last;
        if (callback != null) App.post(() -> callback.error(error.getMessage()));
        else throw new RuntimeException(error.getMessage(), error);
    }

    private void download(InputStream is, double length) throws IOException {
        try (BufferedInputStream input = new BufferedInputStream(is); FileOutputStream os = new FileOutputStream(Path.create(file))) {
            byte[] buffer = new byte[16384];
            int readBytes;
            long totalBytes = 0;
            while ((readBytes = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Download cancelled");
                totalBytes += readBytes;
                os.write(buffer, 0, readBytes);
                if (length <= 0) continue;
                int progress = (int) (totalBytes / length * 100.0);
                if (callback != null) App.post(() -> callback.progress(progress));
            }
        }
    }

    private double getLength(Response res) {
        try {
            String header = res.header(HttpHeaders.CONTENT_LENGTH);
            return header != null ? Double.parseDouble(header) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    public interface Callback {

        void progress(int progress);

        void error(String msg);

        void success(File file);
    }
}
