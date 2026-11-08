package com.fongmi.android.tv.player.preview;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;

import com.fongmi.android.tv.R;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Starts the first frame immediately; repeated keys or touches update one pending target. */
public final class ScrubPreview implements AutoCloseable {
    private record Source(Uri uri, Map<String, String> headers) {}
    private record Key(Source source, long bucket) {}
    private record Request(Key key, long time, DataSource.Factory local, long size) {}
    private final View root;
    private final ImageView image;
    private final TextView label;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.DiscardOldestPolicy());
    private final LruCache<Key, Bitmap> cache = new LruCache<>(8);
    private final DataSource.Factory files;
    // All state is confined to the main thread. Only the bounded data source is used by the worker.
    private Request target, running;
    private PreviewDataSource active;
    private Key displayed;
    private boolean closed;

    public ScrubPreview(View root, ImageView image, TextView label) {
        this.root = root;
        this.image = image;
        this.label = label;
        files = new DefaultDataSource.Factory(root.getContext());
    }

    public static boolean supported(Uri uri, String mime) {
        if (uri == null || uri.getScheme() == null) return false;
        String path = Objects.toString(uri.getPath(), "").toLowerCase(Locale.ROOT);
        String type = Objects.toString(mime, "").toLowerCase(Locale.ROOT);
        return !path.endsWith(".m3u8") && !path.endsWith(".mpd") && !type.contains("mpegurl") && !type.contains("dash")
                && List.of("http", "https", "file", "content").contains(uri.getScheme());
    }

    public void show(Uri uri, Map<String, String> headers, String mime, long position, DataSource.Factory local, long size) {
        if (closed) return;
        long ms = Math.max(0, position);
        root.setVisibility(View.VISIBLE);
        if (!supported(uri, mime)) {
            clearTarget();
            label.setText(root.getResources().getString(R.string.scrub_time_only, time(ms)));
            return;
        }
        Source source = new Source(uri, headers == null ? Map.of() : Map.copyOf(headers));
        if (target != null && !source.equals(target.key.source)) clearTarget();
        Key key = new Key(source, ms / 1000);
        target = new Request(key, ms, local, size);
        Bitmap cached = cache.get(key);
        if (cached != null) { display(key, cached); return; }
        if (displayed == null) label.setText(root.getResources().getString(R.string.scrub_loading, time(ms)));
        else label.setText(root.getResources().getString(R.string.scrub_sample, time(ms), time(displayed.bucket * 1000)));
        if (running == null) start(target);
    }

    private void start(Request request) {
        running = request;
        Source source = request.key.source;
        DataSource.Factory factory = request.local;
        if (factory == null && ("file".equals(source.uri.getScheme()) || "content".equals(source.uri.getScheme()))) factory = files;
        PreviewDataSource data = new PreviewDataSource(source.uri, source.headers, factory, request.size);
        active = data;
        worker.execute(() -> {
            Bitmap bitmap = decode(data, request.time);
            main.post(() -> complete(request, bitmap));
        });
    }

    private static Bitmap decode(PreviewDataSource data, long position) {
        MediaMetadataRetriever retriever = null;
        try {
            if (Build.VERSION.SDK_INT <= 28) {
                Bitmap frame = LegacyKeyFrame.read(data, position);
                if (frame != null || !data.availableForPreview()) return frame;
            }
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(data);
            if (Build.VERSION.SDK_INT >= 27) return retriever.getScaledFrameAtTime(position * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180);
            Bitmap full = retriever.getFrameAtTime(position * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (full == null) return null;
            Bitmap thumb = Bitmap.createScaledBitmap(full, 320, Math.max(1, full.getHeight() * 320 / full.getWidth()), true);
            if (full != thumb) full.recycle();
            return thumb;
        } catch (Exception | OutOfMemoryError ignored) {
            return null;
        } finally {
            if (retriever != null) try { retriever.release(); } catch (Exception ignored) {}
            data.close();
        }
    }

    private void complete(Request request, Bitmap bitmap) {
        if (running != request) {
            if (bitmap != null) bitmap.recycle();
            return;
        }
        running = null;
        active = null;
        if (closed || target == null) { if (bitmap != null) bitmap.recycle(); return; }
        if (bitmap != null) cache.put(request.key, bitmap);
        Bitmap current = cache.get(target.key);
        if (current != null) { display(target.key, current); return; }
        if (!target.key.equals(request.key)) {
            // While holding a key, show a labelled sample rather than starving the image decoder.
            if (bitmap != null) display(request.key, bitmap);
            start(target);
        } else {
            clearImage();
            label.setText(root.getResources().getString(R.string.scrub_unavailable, time(target.time)));
        }
    }

    private void display(Key key, Bitmap frame) {
        displayed = key;
        image.setImageBitmap(frame);
        image.setVisibility(View.VISIBLE);
        label.setText(key.equals(target.key) ? time(target.time)
                : root.getResources().getString(R.string.scrub_sample, time(target.time), time(key.bucket * 1000)));
    }

    public static String time(long ms) {
        long sec = Math.max(0, ms) / 1000;
        return sec >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60)
                : String.format(Locale.ROOT, "%02d:%02d", sec / 60, sec % 60);
    }

    private void clearImage() { displayed = null; image.setImageDrawable(null); image.setVisibility(View.GONE); }
    private void clearTarget() {
        target = null;
        running = null;
        if (active != null) { active.cancel(); active = null; }
        clearImage();
    }
    public void hide() { clearTarget(); root.setVisibility(View.GONE); }
    @Override public void close() { closed = true; hide(); worker.shutdownNow(); cache.evictAll(); }
}
