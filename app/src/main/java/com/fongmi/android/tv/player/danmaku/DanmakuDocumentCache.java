package com.fongmi.android.tv.player.danmaku;

import android.net.Uri;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.App;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Materialises remote episode comments as a local document before handing them to Media3.
 *
 * <p>The danmaku endpoint is slow and declares immutable comment-id documents as
 * {@code no-cache}. Relying only on the controller's in-memory state means that replaying a video,
 * recreating the surface, or advancing to another episode can leave a selected URL with no
 * rendered items. A small disk-backed document cache gives every mount an explicit, local source
 * and makes a previously loaded episode available immediately.</p>
 */
public final class DanmakuDocumentCache {

    static final long MAX_DOCUMENT_BYTES = 24L * 1024L * 1024L;
    static final long MAX_CACHE_BYTES = 64L * 1024L * 1024L;
    static final int MIN_DOCUMENT_BYTES = 32;

    private static final Map<String, PendingDownload> DOWNLOADS = new HashMap<>();

    private DanmakuDocumentCache() {
    }

    public interface Listener {

        void onReady(Uri source, Uri local);

        void onFailure(Uri source, IOException error);
    }

    public interface Ticket {

        void cancel();
    }

    public static Ticket load(Uri source, Listener listener) {
        if (!isCacheable(source)) {
            App.post(() -> listener.onReady(source, source));
            return () -> {
            };
        }
        File target = fileFor(source);
        if (isUsable(target)) {
            // Keep recently replayed episodes during LRU pruning.
            target.setLastModified(System.currentTimeMillis());
            App.post(() -> listener.onReady(source, Uri.fromFile(target)));
            return () -> {
            };
        }
        String key = source.toString();
        Subscription subscription = new Subscription(source, listener);
        PendingDownload pending;
        synchronized (DOWNLOADS) {
            pending = DOWNLOADS.get(key);
            if (pending != null) {
                pending.add(subscription);
                return subscription;
            }
        }
        Call call;
        try {
            call = DanmakuHttp.client().newCall(new Request.Builder().url(source.toString()).get().build());
        } catch (RuntimeException error) {
            App.post(() -> listener.onFailure(source, new IOException(error)));
            return () -> {
            };
        }
        pending = new PendingDownload(key, call);
        pending.add(subscription);
        synchronized (DOWNLOADS) {
            PendingDownload existing = DOWNLOADS.get(key);
            if (existing != null) {
                call.cancel();
                existing.add(subscription);
                return subscription;
            }
            DOWNLOADS.put(key, pending);
        }
        PendingDownload download = pending;
        call.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException error) {
                complete(download, null, error);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response closeable = response) {
                    if (!closeable.isSuccessful()) {
                        throw new HttpStatusException(closeable.code());
                    }
                    ResponseBody body = closeable.body();
                    if (body == null) throw new IOException("Empty danmaku response");
                    long declared = body.contentLength();
                    if (declared > MAX_DOCUMENT_BYTES) {
                        throw new IOException("Danmaku document is too large: " + declared);
                    }
                    writeAtomically(target, body.byteStream());
                    if (!isUsable(target)) {
                        invalidate(source);
                        throw new IOException("Danmaku document is empty or incomplete");
                    }
                    prune(target);
                    complete(download, Uri.fromFile(target), null);
                } catch (IOException error) {
                    complete(download, null, error);
                }
            }
        });
        return subscription;
    }

    /** Warms one confirmed neighbouring episode without touching the renderer. */
    public static void prefetch(Uri source) {
        if (!isCacheable(source) || isUsable(fileFor(source))) return;
        load(source, new Listener() {
            @Override
            public void onReady(Uri source, Uri local) {
            }

            @Override
            public void onFailure(Uri source, IOException error) {
            }
        });
    }

    public static void invalidate(Uri source) {
        if (!isCacheable(source)) return;
        File target = fileFor(source);
        File partial = new File(target.getAbsolutePath() + ".part");
        if (target.exists()) target.delete();
        if (partial.exists()) partial.delete();
    }

    static boolean isRemote(Uri source) {
        if (source == null) return false;
        String scheme = Objects.toString(source.getScheme(), "").toLowerCase(Locale.ROOT);
        return "http".equals(scheme) || "https".equals(scheme);
    }

    static boolean isCacheable(Uri source) {
        return source != null && isCacheable(source.toString());
    }

    static boolean isCacheable(String source) {
        return DanmakuHttp.isImmutableCommentUrl(source);
    }

    static String cacheKey(String source) {
        String value = Objects.toString(source, "");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format(Locale.US, "%02x", item & 0xff));
            return result.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }

    static boolean isUsable(File file) {
        if (file == null || !file.isFile() || file.length() < MIN_DOCUMENT_BYTES
                || file.length() > MAX_DOCUMENT_BYTES) return false;
        try (InputStream input = new java.io.FileInputStream(file)) {
            byte[] prefix = new byte[64];
            int count = input.read(prefix);
            if (count <= 0) return false;
            int index = count >= 3 && (prefix[0] & 0xff) == 0xef
                    && (prefix[1] & 0xff) == 0xbb && (prefix[2] & 0xff) == 0xbf ? 3 : 0;
            while (index < count && Character.isWhitespace((char) (prefix[index] & 0xff))) index++;
            // Reject cached HTML/JSON error bodies while accepting an XML declaration/root with
            // optional BOM and leading whitespace.
            return index < count && prefix[index] == '<';
        } catch (IOException ignored) {
            return false;
        }
    }

    private static File fileFor(Uri source) {
        File directory = new File(App.get().getCacheDir(), "danmaku_documents");
        if (!directory.exists()) directory.mkdirs();
        return new File(directory, cacheKey(source.toString()) + ".xml");
    }

    private static void writeAtomically(File target, InputStream input) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Unable to create danmaku cache directory");
        }
        File partial = new File(target.getAbsolutePath() + ".part");
        if (partial.exists()) partial.delete();
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        try (InputStream source = input; FileOutputStream output = new FileOutputStream(partial)) {
            int count;
            while ((count = source.read(buffer)) != -1) {
                total += count;
                if (total > MAX_DOCUMENT_BYTES) throw new IOException("Danmaku document exceeds limit");
                output.write(buffer, 0, count);
            }
            output.getFD().sync();
        } catch (IOException error) {
            partial.delete();
            throw error;
        }
        if (target.exists() && !target.delete()) {
            partial.delete();
            throw new IOException("Unable to replace danmaku cache");
        }
        if (!partial.renameTo(target)) {
            partial.delete();
            throw new IOException("Unable to commit danmaku cache");
        }
    }

    private static void prune(File protectedFile) {
        File directory = protectedFile.getParentFile();
        File[] files = directory == null ? null : directory.listFiles(
                file -> file.isFile() && file.getName().endsWith(".xml"));
        if (files == null || files.length == 0) return;
        long total = 0;
        for (File file : files) total += file.length();
        if (total <= MAX_CACHE_BYTES) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File file : files) {
            if (total <= MAX_CACHE_BYTES) break;
            if (file.equals(protectedFile)) continue;
            long length = file.length();
            if (file.delete()) total -= length;
        }
    }

    private static void complete(PendingDownload download, Uri local, IOException error) {
        List<Subscription> subscribers;
        synchronized (DOWNLOADS) {
            if (DOWNLOADS.get(download.key) != download) return;
            DOWNLOADS.remove(download.key);
            subscribers = download.detach();
        }
        for (Subscription subscription : subscribers) {
            if (subscription.cancelled) continue;
            if (local != null) {
                App.post(() -> {
                    if (!subscription.cancelled) {
                        subscription.listener.onReady(subscription.source, local);
                    }
                });
            } else if (!download.call.isCanceled()) {
                IOException failure = error == null
                        ? new IOException("Danmaku download failed") : error;
                App.post(() -> {
                    if (!subscription.cancelled) {
                        subscription.listener.onFailure(subscription.source, failure);
                    }
                });
            }
        }
    }

    private static final class PendingDownload {

        private final String key;
        private final Call call;
        private final List<Subscription> subscribers = new ArrayList<>();

        private PendingDownload(String key, Call call) {
            this.key = key;
            this.call = call;
        }

        private void add(Subscription subscription) {
            subscription.download = this;
            subscribers.add(subscription);
        }

        private List<Subscription> detach() {
            List<Subscription> result = new ArrayList<>(subscribers);
            for (Subscription subscription : result) subscription.download = null;
            subscribers.clear();
            return result;
        }
    }

    private static final class Subscription implements Ticket {

        private final Uri source;
        private final Listener listener;
        private volatile boolean cancelled;
        private PendingDownload download;

        private Subscription(Uri source, Listener listener) {
            this.source = source;
            this.listener = listener;
        }

        @Override
        public void cancel() {
            synchronized (DOWNLOADS) {
                if (cancelled) return;
                cancelled = true;
                PendingDownload owner = download;
                if (owner == null) return;
                owner.subscribers.remove(this);
                download = null;
                if (owner.subscribers.isEmpty() && DOWNLOADS.remove(owner.key, owner)) {
                    owner.call.cancel();
                }
            }
        }
    }

    static final class HttpStatusException extends IOException {

        private final int statusCode;

        HttpStatusException(int statusCode) {
            super("Danmaku HTTP " + statusCode);
            this.statusCode = statusCode;
        }

        int statusCode() {
            return statusCode;
        }
    }
}
