package com.fongmi.android.tv.offline;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheKeyFactory;
import androidx.media3.datasource.cache.NoOpCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.offline.DefaultDownloadIndex;
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadCursor;
import androidx.media3.exoplayer.offline.DownloadHelper;
import androidx.media3.exoplayer.offline.DownloadManager;
import androidx.media3.exoplayer.offline.DownloadRequest;
import androidx.media3.exoplayer.offline.DownloadService;
import androidx.media3.exoplayer.offline.Downloader;
import androidx.media3.exoplayer.scheduler.Requirements;

import com.fongmi.android.tv.player.danmaku.DanmakuDocumentCache;
import com.github.catvod.net.OkHttp;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Request;
import okhttp3.Response;

/** A permanent cache, separate from streaming cache and never subject to cache eviction. */
public final class OfflineStore {
    private static OfflineStore instance;
    public static synchronized OfflineStore get(Context context) {
        if (instance == null) instance = new OfflineStore(context.getApplicationContext());
        return instance;
    }

    public final Context context;
    public final File root;
    public final SimpleCache cache;
    final StandaloneDatabaseProvider database;
    public final DownloadManager manager;
    public final ExecutorService io = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> removed = ConcurrentHashMap.newKeySet();
    private final Object sidecarLock = new Object();

    private OfflineStore(Context context) {
        this.context = context;
        // Internal files survive app updates, are private, and are not deleted by "clear cache".
        root = new File(context.getFilesDir(), "offline-v1");
        database = new StandaloneDatabaseProvider(context);
        cache = new SimpleCache(new File(root, "media"), new NoOpCacheEvictor(), database);
        manager = new DownloadManager(context, new DefaultDownloadIndex(database, "offline"), request -> {
            removed.remove(request.id);
            Downloader video = new DefaultDownloaderFactory(cacheFactory(request.id, upstream(OfflineMetadata.decode(request.data))), Runnable::run).createDownloader(request);
            return new WithSidecars(request, video);
        });
        manager.setMaxParallelDownloads(1);
        manager.setMinRetryCount(3);
        manager.setRequirements(requirements(wifiOnly()));
    }

    public boolean wifiOnly() { return context.getSharedPreferences("offline", 0).getBoolean("wifi", true); }
    public Requirements requirements(boolean wifi) {
        return new Requirements((wifi ? Requirements.NETWORK_UNMETERED : Requirements.NETWORK) | Requirements.DEVICE_STORAGE_NOT_LOW);
    }
    public void setWifiOnly(boolean wifi) {
        context.getSharedPreferences("offline", 0).edit().putBoolean("wifi", wifi).apply();
        DownloadService.sendSetRequirements(context, OfflineDownloadService.class, requirements(wifi), false);
    }
    public long position(String id) { return context.getSharedPreferences("offline", 0).getLong("position." + id, 0); }
    public void position(String id, long value) { context.getSharedPreferences("offline", 0).edit().putLong("position." + id, value).apply(); }

    public DataSource.Factory upstream(OfflineMetadata meta) {
        Map<String, String> headers = new HashMap<>(meta.headers);
        headers.keySet().removeIf(key -> key.equalsIgnoreCase("Range") || key.equalsIgnoreCase("If-Range")
                || key.equalsIgnoreCase("If-None-Match") || key.equalsIgnoreCase("If-Modified-Since")
                || key.equalsIgnoreCase("Content-Length") || key.equalsIgnoreCase("Accept-Encoding"));
        return new DefaultDataSource.Factory(context, new OkHttpDataSource.Factory(OkHttp.player())
                .setDefaultRequestProperties(headers));
    }

    public CacheDataSource.Factory cacheFactory(String id, DataSource.Factory upstream) {
        OfflineMetadata.requireId(id);
        CacheDataSource.Factory factory = new CacheDataSource.Factory().setCache(cache)
                .setCacheKeyFactory(spec -> id + "|" + CacheKeyFactory.DEFAULT.buildCacheKey(spec))
                .setUpstreamDataSourceFactory(upstream);
        if (upstream == null) factory.setCacheWriteDataSinkFactory(null);
        return factory;
    }

    /** File sidecars are local; every other URI must already exist in the permanent cache. */
    public DataSource.Factory playbackFactory(String id) {
        return new DefaultDataSource.Factory(context, cacheFactory(id, null));
    }

    public List<Download> downloads() {
        Map<String, Download> values = new LinkedHashMap<>();
        try (DownloadCursor cursor = manager.getDownloadIndex().getDownloads()) {
            while (cursor.moveToNext()) values.put(cursor.getDownload().request.id, cursor.getDownload());
        } catch (IOException ignored) { }
        for (Download download : manager.getCurrentDownloads()) values.put(download.request.id, download);
        List<Download> result = new ArrayList<>(values.values());
        result.sort((a, b) -> Long.compare(b.startTimeMs, a.startTimeMs));
        return result;
    }

    public Download download(String id) {
        for (Download value : downloads()) if (value.request.id.equals(id)) return value;
        return null;
    }

    public interface Callback { void done(String error); }

    public void enqueue(MediaItem media, OfflineMetadata meta, Callback callback) {
        // Manifest preparation may read a media segment; obey the same network policy
        // before probing, not just once the background download is in the queue.
        if (wifiOnly() && (requirements(true).getNotMetRequirements(context)
                & (Requirements.NETWORK | Requirements.NETWORK_UNMETERED)) != 0) {
            callback.done("请连接非计费网络，或在“下载管理”关闭仅非计费网络后重试"); return;
        }
        if (media.localConfiguration == null || media.localConfiguration.drmConfiguration != null) {
            callback.done("当前视频需要在线授权，暂不支持离线保存"); return;
        }
        String scheme = media.localConfiguration.uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            callback.done("当前线路不支持下载，请尝试其他线路"); return;
        }
        String id = OfflineMetadata.id(media.localConfiguration.uri.toString(), meta.headers);
        Download existing = download(id);
        if (existing != null) { callback.done("已在下载管理中，可继续下载或离线播放"); return; }
        if (root.getUsableSpace() < 128L * 1024 * 1024) { callback.done("存储空间不足，请先清理下载文件"); return; }
        io.execute(() -> {
            try {
                String mime = sniffMime(media, meta);
                MediaItem item = media.buildUpon().setMimeType(mime).setSubtitleConfigurations(Collections.emptyList()).build();
                main.post(() -> prepare(item, meta, id, callback));
            } catch (Exception e) { main.post(() -> callback.done("无法读取视频，请重新播放后再下载，或尝试其他线路")); }
        });
    }

    private String sniffMime(MediaItem media, OfflineMetadata meta) throws IOException {
        String uri = media.localConfiguration.uri.toString();
        Request.Builder builder = new Request.Builder().url(uri);
        for (Map.Entry<String,String> header : meta.headers.entrySet()) builder.header(header.getKey(), header.getValue());
        builder.header("Range", "bytes=0-4095").header("Accept-Encoding", "identity");
        try (Response response = OkHttp.player().newBuilder().callTimeout(20, TimeUnit.SECONDS).build().newCall(builder.build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IOException("Source unavailable");
            InputStream in = response.body().byteStream();
            byte[] bytes = new byte[4096]; int count = 0, n;
            while (count < bytes.length && (n = in.read(bytes, count, bytes.length-count)) != -1) count += n;
            String text = new String(bytes, 0, count, StandardCharsets.UTF_8).trim();
            if (text.startsWith("\ufeff")) text = text.substring(1).trim();
            if (text.startsWith("#EXTM3U")) return MimeTypes.APPLICATION_M3U8;
            if (count >= 4 && bytes[0] == 0x1a && bytes[1] == 0x45 && (bytes[2] & 255) == 0xdf && (bytes[3] & 255) == 0xa3)
                return MimeTypes.VIDEO_MATROSKA;
            if (text.contains("<MPD")) return MimeTypes.APPLICATION_MPD;
            if (text.startsWith("<") || text.startsWith("{")) throw new IOException("Not video");
            String mime = media.localConfiguration.mimeType;
            // Sniffed progressive content must not inherit a plugin's incorrect m3u8 hint.
            if (MimeTypes.APPLICATION_M3U8.equals(mime) || MimeTypes.APPLICATION_MPD.equals(mime)) return MimeTypes.VIDEO_MP4;
            return mime == null ? MimeTypes.VIDEO_MP4 : mime;
        }
    }

    private void prepare(MediaItem item, OfflineMetadata meta, String id, Callback callback) {
        String mime = item.localConfiguration.mimeType;
        if (!MimeTypes.APPLICATION_M3U8.equals(mime) && !MimeTypes.APPLICATION_MPD.equals(mime)) {
            try {
                DownloadRequest request = new DownloadRequest.Builder(id, item.localConfiguration.uri)
                        .setMimeType(mime).setData(meta.encode()).build();
                DownloadService.sendAddDownload(context, OfflineDownloadService.class, request, false);
                callback.done(null);
            } catch (RuntimeException error) { callback.done("无法启动下载，请返回下载页重试"); }
            return;
        }
        DownloadHelper helper = DownloadHelper.forMediaItem(item,
                new TrackSelectionParameters.Builder(context).setForceHighestSupportedBitrate(true).build(),
                new DefaultRenderersFactory(context), upstream(meta));
        java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable timeout = () -> { if (finished.compareAndSet(false, true)) { helper.release(); callback.done("读取下载信息超时，请稍后重试"); } };
        main.postDelayed(timeout, 45000);
        helper.prepare(new DownloadHelper.Callback() {
            public void onPrepared(DownloadHelper ready, boolean tracksChanged) {
                if (!finished.compareAndSet(false, true)) { ready.release(); return; }
                main.removeCallbacks(timeout);
                try {
                    // Reject encrypted tracks even if the source omitted a top-level DRM config.
                    for (int period = 0; period < ready.getPeriodCount(); period++) {
                        for (androidx.media3.common.Tracks.Group group : ready.getTracks(period).getGroups()) {
                            for (int track = 0; track < group.length; track++) {
                                if (group.getTrackFormat(track).drmInitData != null) {
                                    callback.done("当前视频需要在线授权，暂不支持离线保存"); return;
                                }
                            }
                        }
                    }
                    // DownloadHelper rejects live media during preparation. No DRM licences are requested.
                    DownloadRequest request = ready.getDownloadRequest(id, meta.encode());
                    DownloadService.sendAddDownload(context, OfflineDownloadService.class, request, false);
                    callback.done(null);
                } catch (RuntimeException error) { callback.done("当前视频暂不支持离线保存"); }
                finally { ready.release(); }
            }
            public void onPrepareError(DownloadHelper ready, IOException error) {
                if (!finished.compareAndSet(false, true)) { ready.release(); return; }
                main.removeCallbacks(timeout); ready.release();
                callback.done("无法保存该视频；直播和需要在线授权的视频暂不支持下载");
            }
        });
    }

    public File sidecar(String id, String name) { return new File(new File(root, OfflineMetadata.requireId(id)), name); }
    public File danmaku(String id) { return sidecar(id, "danmaku.xml"); }
    public String extrasStatus(Download download) {
        OfflineMetadata meta = OfflineMetadata.decode(download.request.data);
        String danmaku = danmaku(download.request.id).isFile() ? "弹幕已保存" :
                meta.danmaku == null || meta.danmaku.isEmpty() ? "未选择弹幕（可导入 XML）" : "弹幕未保存，可重试";
        int saved = 0;
        for (int i = 0; i < meta.subtitles.size(); i++) if (sidecar(download.request.id, "subtitle-" + i).isFile()) saved++;
        return danmaku + (meta.subtitles.isEmpty() ? "" : " · 外挂字幕 " + saved + "/" + meta.subtitles.size());
    }

    public MediaItem mediaItem(Download download) {
        List<MediaItem.SubtitleConfiguration> subs = new ArrayList<>();
        OfflineMetadata meta = OfflineMetadata.decode(download.request.data);
        for (int i = 0; i < meta.subtitles.size(); i++) {
            File file = sidecar(download.request.id, "subtitle-" + i);
            if (!file.isFile()) continue;
            OfflineMetadata.Subtitle sub = meta.subtitles.get(i);
            subs.add(new MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file)).setMimeType(sub.mime)
                    .setLanguage(sub.language).setLabel(sub.label).setSelectionFlags(sub.selectionFlags).build());
        }
        return download.request.toMediaItem().buildUpon().setSubtitleConfigurations(subs).build();
    }

    public void retryExtras(Download download, Callback callback) {
        io.execute(() -> {
            try { new WithSidecars(download.request, null).saveExtras(); }
            catch (Exception ignored) { }
            main.post(() -> callback.done(null));
        });
    }

    public void importDanmaku(String id, Uri uri, Callback callback) {
        io.execute(() -> {
            String error = null;
            try (InputStream in = openLocal(uri)) { saveBounded(id, "danmaku.xml", in, true); }
            catch (Exception e) { error = "无法导入，请选择有效的 B 站格式弹幕 XML（最大 24 MB）"; }
            String message = error; main.post(() -> callback.done(message));
        });
    }

    private InputStream openLocal(Uri uri) throws IOException {
        if ("file".equalsIgnoreCase(uri.getScheme())) return new FileInputStream(new File(uri.getPath()));
        if ("content".equalsIgnoreCase(uri.getScheme())) return context.getContentResolver().openInputStream(uri);
        throw new IOException("Not a local document");
    }

    private void saveBounded(String id, String name, InputStream in, boolean xml) throws IOException {
        if (in == null) throw new IOException("No document");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384]; int size;
        while ((size = in.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
            if (bytes.size() + size > 24 * 1024 * 1024) throw new IOException("Document too large");
            bytes.write(buffer, 0, size);
        }
        if (bytes.size() == 0) throw new IOException("Empty document");
        if (xml) validateDanmaku(bytes.toByteArray());
        synchronized (sidecarLock) {
            if (removed.contains(id)) throw new IOException("Removed");
            File file = sidecar(id, name); file.getParentFile().mkdirs();
            AtomicFile atomic = new AtomicFile(file); FileOutputStream out = null;
            try { out = atomic.startWrite(); out.write(bytes.toByteArray()); atomic.finishWrite(out); }
            catch (IOException e) { atomic.failWrite(out); throw e; }
        }
    }

    static void validateDanmaku(byte[] bytes) throws IOException {
        try {
            org.xmlpull.v1.XmlPullParser parser = android.util.Xml.newPullParser();
            parser.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
            parser.setInput(new ByteArrayInputStream(bytes), null);
            boolean root = false, comment = false;
            int event;
            while ((event = parser.next()) != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (event == org.xmlpull.v1.XmlPullParser.START_TAG && parser.getDepth() == 1) {
                    if (!"i".equals(parser.getName())) throw new IOException("Not a danmaku document");
                    root = true;
                }
                if (event == org.xmlpull.v1.XmlPullParser.START_TAG && "d".equals(parser.getName()) && parser.getAttributeValue(null, "p") != null) comment = true;
            }
            if (!root || !comment) throw new IOException("Empty danmaku XML");
        } catch (org.xmlpull.v1.XmlPullParserException e) { throw new IOException("Invalid XML", e); }
    }

    private final class WithSidecars implements Downloader {
        final DownloadRequest request;
        final Downloader video;
        volatile boolean cancelled;
        volatile DanmakuDocumentCache.Ticket ticket;
        volatile okhttp3.Call documentCall;
        WithSidecars(DownloadRequest request, Downloader video) { this.request = request; this.video = video; }
        public void download(ProgressListener listener) throws IOException, InterruptedException {
            saveExtras();
            if (cancelled) throw new InterruptedException();
            video.download(listener);
        }
        void saveExtras() throws InterruptedException {
            OfflineMetadata meta = OfflineMetadata.decode(request.data);
            if (!danmaku(request.id).isFile() && meta.danmaku != null && !meta.danmaku.isEmpty()) {
                CountDownLatch latch = new CountDownLatch(1);
                AtomicReference<Uri> local = new AtomicReference<>();
                ticket = DanmakuDocumentCache.load(Uri.parse(meta.danmaku), new DanmakuDocumentCache.Listener() {
                    public void onReady(Uri source, Uri file) { local.set(file); latch.countDown(); }
                    public void onFailure(Uri source, IOException error) { latch.countDown(); }
                });
                try {
                    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(50);
                    while (!cancelled && System.nanoTime() < until && !latch.await(200, TimeUnit.MILLISECONDS)) { }
                    if (cancelled) throw new InterruptedException();
                    if (local.get() != null) {
                        Uri document = local.get();
                        try {
                            if ("http".equalsIgnoreCase(document.getScheme()) || "https".equalsIgnoreCase(document.getScheme())) {
                                // The streaming cache only materialises native/immutable sources.
                                // Arbitrary XML URLs are returned unchanged and need a persistent copy here.
                                documentCall = com.fongmi.android.tv.player.danmaku.DanmakuHttp.client().newCall(new Request.Builder().url(document.toString()).build());
                                if (cancelled) documentCall.cancel();
                                try (Response response = documentCall.execute()) {
                                    if (!response.isSuccessful() || response.body() == null) throw new IOException("Danmaku unavailable");
                                    saveBounded(request.id, "danmaku.xml", response.body().byteStream(), true);
                                } finally { documentCall = null; }
                            } else try (InputStream in = openLocal(document)) {
                                saveBounded(request.id, "danmaku.xml", in, true);
                            }
                        } catch (IOException | SecurityException ignored) { }
                    }
                } finally { ticket.cancel(); ticket = null; }
            }
            for (int i = 0; i < meta.subtitles.size() && !cancelled; i++) {
                if (sidecar(request.id, "subtitle-" + i).isFile()) continue;
                DataSource source = upstream(meta).createDataSource();
                try (androidx.media3.datasource.DataSourceInputStream in = new androidx.media3.datasource.DataSourceInputStream(source,
                        new androidx.media3.datasource.DataSpec(Uri.parse(meta.subtitles.get(i).uri)))) {
                    saveBounded(request.id, "subtitle-" + i, in, false);
                } catch (IOException ignored) { }
            }
        }
        public void cancel() { cancelled = true; if (documentCall != null) documentCall.cancel(); if (ticket != null) ticket.cancel(); if (video != null) video.cancel(); }
        public void remove() {
            synchronized (sidecarLock) {
                removed.add(request.id);
                File dir = sidecar(request.id, "danmaku.xml").getParentFile();
                File[] files = dir.listFiles();
                if (files != null) for (File file : files) file.delete();
                dir.delete();
            }
            video.remove();
            context.getSharedPreferences("offline", 0).edit().remove("position." + request.id).apply();
        }
    }
}
