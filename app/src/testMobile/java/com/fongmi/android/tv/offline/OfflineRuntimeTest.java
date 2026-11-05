package com.fongmi.android.tv.offline;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadService;
import androidx.media3.exoplayer.scheduler.Requirements;
import androidx.media3.ui.PlayerView;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.ui.activity.OfflineDownloadsActivity;
import com.fongmi.android.tv.ui.activity.OfflinePlayerActivity;
import com.fongmi.android.tv.player.danmaku.FilteringBiliParser;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.mockwebserver.*;
import okio.Buffer;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = OfflineRuntimeTest.TestApp.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class OfflineRuntimeTest {
    public static class TestApp extends com.fongmi.android.tv.App { @Override public void onCreate() { } }
    private static OfflineDownloadService activeService;
    private ServiceController<OfflineDownloadService> service;
    private Context context;
    private OfflineStore store;
    private MockWebServer server;

    private final Map<String,byte[]> content = new ConcurrentHashMap<>();
    private final List<String> ranges = new CopyOnWriteArrayList<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger rejected = new AtomicInteger();
    private volatile boolean slow;
    private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><i><chatserver>offline</chatserver><d p=\"1,1,25,16777215,0,0,user,1\">离线弹幕测试</d><d p=\"4,1,25,16777215,0,0,user,2\">断网后仍然显示</d></i>";

    @Before public void setup() throws Exception {
        System.out.println("offline: app ready");
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("offline-batch-v1",0).edit().clear().commit();
        org.robolectric.util.ReflectionHelpers.setStaticField(OfflineBatchQueue.class, "instance", null);
        context.getSharedPreferences("offline", 0).edit().putBoolean("wifi", false).commit();
        main(() -> store = OfflineStore.get(context));
        System.out.println("offline: store ready");
        store.manager.addListener(new androidx.media3.exoplayer.offline.DownloadManager.Listener() {
            @Override public void onDownloadChanged(androidx.media3.exoplayer.offline.DownloadManager manager, Download download, Exception error) {
                if (error != null) error.printStackTrace();
            }
        });
        service = Robolectric.buildService(OfflineDownloadService.class).create();
        activeService = service.get();
        System.out.println("offline: service ready");
        main(() -> { store.manager.setRequirements(new Requirements(0)); store.manager.removeAllDownloads(); store.manager.resumeDownloads(); });
        await(() -> onMain(() -> store.manager.isInitialized() && store.downloads().isEmpty()), 15000);
        for (String file : new String[]{"chunk-stream0-00001.m4s", "chunk-stream0-00002.m4s", "chunk-stream0-00003.m4s", "chunk-stream0-00004.m4s", "chunk-stream1-00001.m4s", "chunk-stream1-00002.m4s", "chunk-stream1-00003.m4s", "chunk-stream1-00004.m4s", "clip.mkv", "clip.mp4", "enc0.ts", "enc1.ts", "enc2.ts", "enc3.ts", "encrypted.m3u8", "frag0.m4s", "frag1.m4s", "frag2.m4s", "frag3.m4s", "fragmented.m3u8", "init-stream0.m4s", "init-stream1.m4s", "init.mp4", "key.bin", "seg0.ts", "seg1.ts", "seg2.ts", "seg3.ts", "video.m3u8", "video.mpd"})
            content.put("/" + file, asset(file));
        content.put("/danmaku.xml", XML.getBytes(StandardCharsets.UTF_8));
        content.put("/sub.vtt", "WEBVTT\n\n00:00.000 --> 00:06.000\nOffline subtitle\n".getBytes(StandardCharsets.UTF_8));
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                requests.incrementAndGet();
                String path = request.getRequestUrl().encodedPath();
                // Danmaku providers have their own request policy; video/subtitle headers must survive.
                if (!path.equals("/danmaku.xml") && (!"fixture-auth".equals(request.getHeader("X-Offline-Test")) || !"fixture-agent".equals(request.getHeader("User-Agent")))) {
                    rejected.incrementAndGet(); return new MockResponse().setResponseCode(403);
                }
                byte[] bytes = content.get(path);
                if (bytes == null) return new MockResponse().setResponseCode(404);
                long begin = 0, end = bytes.length - 1;
                String range = request.getHeader("Range");
                MockResponse response = new MockResponse().setHeader("Accept-Ranges", "bytes");
                if (range != null) {
                    ranges.add(range);
                    String[] parts = range.substring(6).split("-", -1);
                    begin = Long.parseLong(parts[0]);
                    if (parts.length > 1 && !parts[1].isEmpty()) end = Math.min(end, Long.parseLong(parts[1]));
                    if (begin >= bytes.length) return new MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */" + bytes.length);
                    response.setResponseCode(206).setHeader("Content-Range", "bytes " + begin + "-" + end + "/" + bytes.length);
                }
                response.setBody(new Buffer().write(bytes, (int)begin, (int)(end - begin + 1)));
                if (slow && path.equals("/large.mp4")) response.throttleBody(32768, 100, TimeUnit.MILLISECONDS);
                return response;
            }
        });
        server.start();
        System.out.println("offline: fixture server ready");
    }
    @After public void cleanup() throws Exception {
        if (service != null) service.destroy();
        activeService = null;
        ((Map<?,?>) org.robolectric.util.ReflectionHelpers.getStaticField(DownloadService.class, "downloadManagerHelpers")).clear();
        org.robolectric.util.ReflectionHelpers.setStaticField(OfflineStore.class, "instance", null);
        if (store != null) { store.manager.release(); store.io.shutdownNow(); store.cache.release(); store.database.close(); }
        if (server != null) server.shutdown();
    }
    private byte[] asset(String name) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("offline/" + name)) {
            return read(in);
        }
    }
    private static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[16384]; int n;
        while ((n = in.read(b)) != -1) out.write(b, 0, n); return out.toByteArray();
    }
    private static void main(Runnable action) { action.run(); }
    private static <T> T onMain(Callable<T> action) throws Exception { return action.call(); }
    private static void pump() {
        ShadowLooper.idleMainLooper();
        Intent intent;
        while ((intent = Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService()) != null) {
            if (activeService != null && intent.getComponent() != null && intent.getComponent().getClassName().equals(OfflineDownloadService.class.getName()))
                activeService.onStartCommand(intent, 0, 1);
        }
    }
    private static void await(Callable<Boolean> condition, long timeout) throws Exception {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        while (System.nanoTime() < end) { pump(); if (condition.call()) return; Thread.sleep(100); }
        fail("Condition timed out after " + timeout + " ms");
    }
    private OfflineMetadata metadata() {
        OfflineMetadata meta = new OfflineMetadata(); meta.title = "离线测试视频"; meta.episode = "第 1 集";
        meta.headers.put("X-Offline-Test", "fixture-auth"); meta.headers.put("User-Agent", "fixture-agent"); meta.danmaku = server.url("/danmaku.xml").toString();
        OfflineMetadata.Subtitle sub = new OfflineMetadata.Subtitle(); sub.uri = server.url("/sub.vtt").toString(); sub.mime = "text/vtt"; sub.selectionFlags = 1;
        meta.subtitles.add(sub); return meta;
    }
    private String enqueue(String path, OfflineMetadata meta) throws Exception {
        MediaItem item = MediaItem.fromUri(server.url(path).toString());
        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> error = new AtomicReference<>();
        main(() -> store.enqueue(item, meta, message -> { error.set(message); done.countDown(); }));
        await(() -> done.getCount() == 0, 55000); assertNull(error.get());
        return OfflineMetadata.id(item.localConfiguration.uri.toString(), meta.headers);
    }
    private Download complete(String id) throws Exception {
        await(() -> onMain(() -> {
            Download d = store.download(id);
            if (d != null && d.state == Download.STATE_FAILED) throw new AssertionError("Download failed");
            return d != null && d.state == Download.STATE_COMPLETED;
        }), 45000);
        return onMain(() -> store.download(id));
    }
    private byte[] cached(String id, String path, long position, long length) throws IOException {
        DataSource source = store.playbackFactory(id).createDataSource();
        try {
            source.open(new DataSpec.Builder().setUri(server.url(path).toString()).setPosition(position).setLength(length).build());
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
            while ((n = source.read(b,0,b.length)) != -1) out.write(b,0,n); return out.toByteArray();
        } finally { source.close(); }
    }

    private void decodeCached(String id, String input, List<String> paths) throws Exception {
        File dir = new File(context.getCacheDir(), "decoded-" + id.substring(0, 8)); assertTrue(dir.mkdirs());
        for (String path : paths) try (FileOutputStream out = new FileOutputStream(new File(dir, path.substring(1)))) {
            out.write(cached(id, path, 0, -1));
        }
        String executable = System.getenv("CAST_FFMPEG"); assertNotNull("Set CAST_FFMPEG to run real offline decoder checks", executable);
        File frame = new File(dir, "frame.rgb"), log = new File(dir, "decode.log");
        // The host FFmpeg build may omit the XML-based DASH demuxer. Verify the exact
        // downloaded video fragments as fragmented MP4 after Media3 parsed the MPD.
        if (input.endsWith(".mpd")) {
            File joined = new File(dir, "joined.mp4");
            try (FileOutputStream out = new FileOutputStream(joined)) {
                out.write(java.nio.file.Files.readAllBytes(new File(dir, "init-stream0.m4s").toPath()));
                for (int i = 1; i <= 4; i++) out.write(java.nio.file.Files.readAllBytes(new File(dir, String.format(java.util.Locale.ROOT, "chunk-stream0-%05d.m4s", i)).toPath()));
            }
            input = "joined.mp4";
        }
        List<String> args = new ArrayList<>(Arrays.asList(executable, "-hide_banner", "-loglevel", "error", "-protocol_whitelist", "file,crypto,data"));
        if (input.endsWith(".m3u8")) args.addAll(Arrays.asList("-allowed_extensions", "m3u8,ts,m4s,mp4,bin"));
        args.addAll(Arrays.asList("-i", new File(dir, input).getAbsolutePath(), "-ss", "4", "-map", "0:v:0", "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "rgb24", "-y", frame.getAbsolutePath()));
        Process process = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Offline decoder timed out"); }
        assertEquals("Offline decoding failed: " + new String(java.nio.file.Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8), 0, process.exitValue());
        assertEquals("Decoded frame missing for " + input, 320 * 180 * 3, frame.length());
    }

    @Test public void progressiveVideoDanmakuSubtitlesReadWithoutAnyServerRequests() throws Exception {
        OfflineMetadata meta = metadata(); meta.headers.put("Range", "bytes=0-15");
        String id = enqueue("/clip.mp4", meta); Download download = complete(id);
        assertArrayEquals(content.get("/clip.mp4"), cached(id, "/clip.mp4", 0, -1));
        assertTrue(store.danmaku(id).isFile()); assertTrue(store.sidecar(id, "subtitle-0").isFile());
        try (InputStream in = new FileInputStream(store.danmaku(id))) { assertEquals(2, FilteringBiliParser.INSTANCE.parse(in).size()); }
        int before = requests.get();
        // A server that now denies every request represents an expired source / offline playback.
        content.clear();
        assertArrayEquals(asset("clip.mp4"), cached(id, "/clip.mp4", 0, -1));
        assertEquals("Offline player contacted the server", before, requests.get());
        assertEquals(0, rejected.get());
        assertEquals(1, store.mediaItem(download).localConfiguration.subtitleConfigurations.size());
        decodeCached(id, "clip.mp4", Arrays.asList("/clip.mp4"));
    }

    @Test public void hlsPlaylistAndAllSegmentsAreAvailableOffline() throws Exception {
        String id = enqueue("/video.m3u8", metadata()); complete(id);
        int before = requests.get();
        for (String path : Arrays.asList("/video.m3u8", "/seg0.ts", "/seg1.ts", "/seg2.ts", "/seg3.ts"))
            assertArrayEquals(path, content.get(path), cached(id, path, 0, -1));
        content.clear();
        assertArrayEquals(asset("seg2.ts"), cached(id, "/seg2.ts", 0, -1));
        assertEquals(before, requests.get()); assertEquals(0, rejected.get());
        decodeCached(id, "video.m3u8", Arrays.asList("/video.m3u8", "/seg0.ts", "/seg1.ts", "/seg2.ts", "/seg3.ts"));
    }

    @Test public void encryptedHlsKeepsItsKeyAndFragmentedHlsKeepsItsInitSegment() throws Exception {
        for (String path : Arrays.asList("/encrypted.m3u8", "/fragmented.m3u8")) {
            String id = enqueue(path, metadata()); complete(id);
            int before = requests.get();
            String dependency = path.contains("encrypted") ? "/key.bin" : "/init.mp4";
            assertArrayEquals(content.get(dependency), cached(id, dependency, 0, -1));
            String segment = path.contains("encrypted") ? "/enc2.ts" : "/frag2.m4s";
            assertArrayEquals(content.get(segment), cached(id, segment, 0, -1));
            assertEquals("Playback fetched an HLS dependency", before, requests.get());
            decodeCached(id, path.substring(1), path.contains("encrypted") ? Arrays.asList(path, "/key.bin", "/enc0.ts", "/enc1.ts", "/enc2.ts", "/enc3.ts") : Arrays.asList(path, "/init.mp4", "/frag0.m4s", "/frag1.m4s", "/frag2.m4s", "/frag3.m4s"));
        }
    }

    @Test public void matroskaKeepsItsContainerAndEmbeddedTracks() throws Exception {
        String id = enqueue("/clip.mkv", metadata()); Download download = complete(id);
        assertEquals("video/x-matroska", download.request.mimeType);
        assertArrayEquals(content.get("/clip.mkv"), cached(id, "/clip.mkv", 0, -1));
        decodeCached(id, "clip.mkv", Arrays.asList("/clip.mkv"));
    }

    @Test public void dashDownloadsManifestAndBothAudioAndVideo() throws Exception {
        String id = enqueue("/video.mpd", metadata()); complete(id);
        List<String> paths = new ArrayList<>(); paths.add("/video.mpd");
        for (String path : content.keySet()) if (path.contains("stream")) paths.add(path);
        int before = requests.get();
        for (String path : paths) assertArrayEquals(path, content.get(path), cached(id, path, 0, -1));
        decodeCached(id, "video.mpd", paths); assertEquals(before, requests.get());
    }

    @Test public void managementScreenCanListAndFilterCompletedDownloads() throws Exception {
        String id = enqueue("/clip.mp4", metadata()); complete(id);
        org.robolectric.android.controller.ActivityController<OfflineDownloadsActivity> activity = Robolectric.buildActivity(OfflineDownloadsActivity.class).setup();
        try {
            pump();
            androidx.recyclerview.widget.RecyclerView list = activity.get().findViewById(R.id.list);
            assertEquals(1, list.getAdapter().getItemCount());
            activity.get().findViewById(R.id.active).performClick(); assertEquals(0, list.getAdapter().getItemCount());
            activity.get().findViewById(R.id.completed).performClick(); assertEquals(1, list.getAdapter().getItemCount());
        } finally { activity.pause().stop().destroy(); }
    }

    @Test public void localPlayerMountsSavedDanmakuAndReleasesOnClose() throws Exception {
        String id = enqueue("/clip.mp4", metadata()); complete(id);
        int before = requests.get(); content.clear();
        org.robolectric.android.controller.ActivityController<OfflinePlayerActivity> activity = Robolectric.buildActivity(OfflinePlayerActivity.class,
                new Intent(context, OfflinePlayerActivity.class).putExtra("id", id)).setup();
        try {
            PlayerView view = activity.get().findViewById(R.id.player);
            assertNotNull(view.getPlayer());
            await(() -> {
                androidx.media3.ui.danmaku.Danmaku[] comments = org.robolectric.util.ReflectionHelpers.getField(view.getDanmakuController(), "sortedItems");
                return comments != null && comments.length == 2;
            }, 10000);
            activity.get().findViewById(R.id.danmaku).performClick();
            assertEquals("弹幕已关闭", activity.get().findViewById(R.id.danmaku).getContentDescription().toString());
        } finally { activity.pause().stop().destroy(); }
        assertEquals(before, requests.get());
    }

    @Test public void unmeteredOnlyPolicyPreventsEvenThePreparationRequest() throws Exception {
        context.getSharedPreferences("offline", 0).edit().putBoolean("wifi", true).commit();
        android.net.ConnectivityManager connectivity = (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Shadows.shadowOf(connectivity).setActiveNetworkInfo(null);
        AtomicReference<String> error = new AtomicReference<>();
        store.enqueue(MediaItem.fromUri(server.url("/video.m3u8").toString()), metadata(), error::set);
        assertNotNull(error.get()); assertEquals(0, requests.get()); assertTrue(store.downloads().isEmpty());
    }

    @Test public void pauseResumeUsesDownloadedRangesAndPersistsTheStopReason() throws Exception {
        byte[] large = new byte[4 * 1024 * 1024]; new Random(42).nextBytes(large); content.put("/large.mp4", large); slow = true;
        OfflineMetadata meta = metadata(); meta.danmaku = ""; meta.subtitles.clear();
        String id = enqueue("/large.mp4", meta);
        await(() -> onMain(() -> { Download d = store.download(id); return d != null && d.state == Download.STATE_DOWNLOADING && d.getBytesDownloaded() > 131072; }), 15000);
        main(() -> store.manager.setStopReason(id, 1));
        await(() -> onMain(() -> store.download(id).state == Download.STATE_STOPPED), 10000);
        assertEquals(1, store.manager.getDownloadIndex().getDownload(id).stopReason);
        slow = false; ranges.clear();
        main(() -> store.manager.setStopReason(id, 0)); complete(id);
        assertTrue("Resume restarted from byte zero", ranges.stream().anyMatch(r -> !r.startsWith("bytes=0-")));
        assertArrayEquals(large, cached(id, "/large.mp4", 0, -1));
    }

    @Test public void deletingOneDownloadDoesNotEvictAnotherAndCacheMissCannotUseNetwork() throws Exception {
        OfflineMetadata first = metadata(); String id1 = enqueue("/clip.mp4", first); complete(id1);
        OfflineMetadata second = metadata(); second.headers.put("X-Variant", "another"); String id2 = enqueue("/clip.mp4", second); complete(id2);
        main(() -> store.manager.removeDownload(id1));
        await(() -> onMain(() -> store.download(id1) == null), 15000);
        assertFalse(store.danmaku(id1).exists()); assertTrue(store.danmaku(id2).exists());
        int before = requests.get();
        assertArrayEquals(content.get("/clip.mp4"), cached(id2, "/clip.mp4", 0, -1));
        try { cached(id1, "/clip.mp4", 0, 20); fail("Deleted content remained readable"); } catch (IOException expected) { }
        assertEquals(before, requests.get());
    }

    @Test public void malformedDanmakuImportPreservesPreviouslySavedDocument() throws Exception {
        String id = enqueue("/clip.mp4", metadata()); complete(id);
        byte[] original;
        try (InputStream in = new FileInputStream(store.danmaku(id))) { original = read(in); }
        File invalid = new File(context.getCacheDir(), "bad-offline.xml");
        try (FileOutputStream out = new FileOutputStream(invalid)) { out.write("<html>error</html>".getBytes(StandardCharsets.UTF_8)); }
        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> error = new AtomicReference<>();
        store.importDanmaku(id, Uri.fromFile(invalid), message -> { error.set(message); done.countDown(); });
        await(() -> done.getCount() == 0, 5000); assertNotNull(error.get());
        try (InputStream in = new FileInputStream(store.danmaku(id))) { assertArrayEquals(original, read(in)); }
    }

    @Test public void clearingStreamingCacheKeepsOfflineVideoAndDanmaku() throws Exception {
        String id = enqueue("/clip.mp4", metadata()); complete(id);
        CountDownLatch cleared = new CountDownLatch(1); AtomicReference<String> error = new AtomicReference<>();
        com.fongmi.android.tv.utils.FileUtil.clearCache(new com.fongmi.android.tv.impl.Callback() {
            @Override public void success() { cleared.countDown(); }
            @Override public void error(String message) { error.set(message); cleared.countDown(); }
        });
        await(() -> cleared.getCount() == 0, 15000); assertNull(error.get());
        assertArrayEquals(content.get("/clip.mp4"), cached(id, "/clip.mp4", 0, -1));
        assertTrue(store.danmaku(id).isFile());
    }

    @Test public void cacheReadsOffsetsAboveTwoGiBWithoutNetwork() throws Exception {
        String id = OfflineMetadata.id(server.url("/sparse.mp4").toString(), Collections.emptyMap());
        long offset = 3L * 1024 * 1024 * 1024 + 123;
        byte[] bytes = new byte[4096]; new Random(13).nextBytes(bytes);
        String key = id + "|" + server.url("/sparse.mp4");
        androidx.media3.datasource.cache.CacheSpan hole = store.cache.startReadWrite(key, offset, bytes.length);
        File part = store.cache.startFile(key, offset, bytes.length);
        try (FileOutputStream out = new FileOutputStream(part)) { out.write(bytes); }
        store.cache.commitFile(part, bytes.length); store.cache.releaseHoleSpan(hole);
        int before = requests.get();
        assertArrayEquals(bytes, cached(id, "/sparse.mp4", offset, bytes.length));
        assertEquals(before, requests.get()); store.cache.removeResource(key);
    }

    @Test public void liveHlsIsRejectedRatherThanShownAsCompleted() throws Exception {
        String live = new String(content.get("/video.m3u8"), StandardCharsets.UTF_8).replace("#EXT-X-ENDLIST", "").replace("#EXT-X-PLAYLIST-TYPE:VOD", "");
        content.put("/live.m3u8", live.getBytes(StandardCharsets.UTF_8));
        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> error = new AtomicReference<>();
        main(() -> store.enqueue(MediaItem.fromUri(server.url("/live.m3u8").toString()), metadata(), message -> { error.set(message); done.countDown(); }));
        await(() -> done.getCount() == 0, 55000); assertNotNull(error.get()); assertTrue(onMain(() -> store.downloads().isEmpty()));
    }

    @Test public void batchQueueSurvivesRestartWithoutDuplicatingEpisodes() {
        OfflineBatchQueue queue = OfflineBatchQueue.get(context);
        OfflineBatchQueue.Job first = batchJob("/clip.mp4", "第 1 集");
        OfflineBatchQueue.Job second = batchJob("/clip.mkv", "第 2 集");
        assertEquals(2, queue.add(java.util.Arrays.asList(first, second, first), java.util.Collections.emptySet()));
        OfflineBatchQueue.Job claimed = queue.claim();
        queue.pause(second.id);
        org.robolectric.util.ReflectionHelpers.setStaticField(OfflineBatchQueue.class, "instance", null);
        OfflineBatchQueue restored = OfflineBatchQueue.get(context);
        assertEquals(2, restored.jobs().size());
        assertEquals(OfflineBatchQueue.PENDING, restored.jobs().get(0).state);
        assertEquals(OfflineBatchQueue.PAUSED, restored.jobs().get(1).state);
        assertEquals(0, restored.add(java.util.List.of(first), java.util.Collections.emptySet()));
        assertNotNull(claimed);
    }

    @Test public void pausedAndDeletedPreparationRejectLateCallbacks() {
        OfflineBatchQueue queue = OfflineBatchQueue.get(context);
        OfflineBatchQueue.Job job = batchJob("/clip.mp4", "第 1 集");
        queue.add(java.util.List.of(job), java.util.Collections.emptySet());
        OfflineBatchQueue.Job old = queue.claim(); queue.pause(job.id);
        assertFalse(queue.active(old)); queue.finish(old, null);
        assertEquals(1, queue.jobs().size());
        queue.resume(job.id); OfflineBatchQueue.Job newer=queue.claim();
        assertFalse(queue.active(old)); assertTrue(queue.active(newer));
        queue.remove(job.id); queue.finish(newer,"late failure");
        assertTrue(queue.jobs().isEmpty());
    }

    @Test public void cancelledManifestPreparationCannotCreateDownload() throws Exception {
        AtomicBoolean active = new AtomicBoolean(true);
        store.enqueue(MediaItem.fromUri(server.url("/plain.m3u8").toString()), metadata(), active::get, error -> {});
        active.set(false);
        for(int i=0;i<15;i++){pump();Thread.sleep(50);}
        assertTrue(store.downloads().isEmpty());
    }

    @Test public void batchServicePreparesTwoVideosWithAuthAndOwnDanmaku() throws Exception {
        android.net.ConnectivityManager connectivity = (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        org.robolectric.shadows.ShadowConnectivityManager network = Shadows.shadowOf(connectivity);
        network.setActiveNetworkInfo(org.robolectric.shadows.ShadowNetworkInfo.newInstance(android.net.NetworkInfo.DetailedState.CONNECTED, android.net.ConnectivityManager.TYPE_WIFI, 0, true, true));
        android.net.Network active = connectivity.getActiveNetwork();
        android.net.NetworkCapabilities capabilities=org.robolectric.shadows.ShadowNetworkCapabilities.newInstance();
        Shadows.shadowOf(capabilities).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
        Shadows.shadowOf(capabilities).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        Shadows.shadowOf(capabilities).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        network.setNetworkCapabilities(active,capabilities);
        assertEquals("Fixture must have a validated network",0,store.requirements(false).getNotMetRequirements(context));
        OfflineBatchQueue queue = OfflineBatchQueue.get(context);
        OfflineBatchQueue.Job one=batchJob("/clip.mp4","第 1 集"),two=batchJob("/clip.mkv","第 2 集");
        queue.add(java.util.List.of(one,two),java.util.Collections.emptySet());
        ServiceController<OfflineBatchService> batch = Robolectric.buildService(OfflineBatchService.class).create();
        try {
            batch.get().onStartCommand(new Intent(context,OfflineBatchService.class),0,1);
            String first=OfflineMetadata.id(one.resolvedUrl,one.meta.headers), second=OfflineMetadata.id(two.resolvedUrl,two.meta.headers);
            complete(first); complete(second);
            assertTrue(queue.jobs().isEmpty()); assertEquals(0,rejected.get());
            assertTrue(store.danmaku(first).isFile()); assertTrue(store.danmaku(second).isFile());
            assertEquals(one.id,OfflineMetadata.decode(store.download(first).request.data).sourceId);
        } finally { batch.destroy(); }
    }

    @Test public void managementSelectionSearchAndBatchPauseAreIsolated() throws Exception {
        OfflineBatchQueue queue=OfflineBatchQueue.get(context);
        OfflineBatchQueue.Job one=batchJob("/clip.mp4","第 1 集"),two=batchJob("/clip.mkv","第 2 集");
        queue.add(java.util.List.of(one,two),java.util.Collections.emptySet());
        org.robolectric.android.controller.ActivityController<OfflineDownloadsActivity> screen=Robolectric.buildActivity(OfflineDownloadsActivity.class).setup();
        try {
            pump(); screen.get().findViewById(R.id.manage).performClick();
            ((android.widget.EditText)screen.get().findViewById(R.id.search)).setText("第 1 集");
            screen.get().findViewById(R.id.selectAll).performClick();
            screen.get().findViewById(R.id.pauseSelected).performClick();
            assertEquals(OfflineBatchQueue.PAUSED,queue.jobs().get(0).state);
            assertEquals(OfflineBatchQueue.PENDING,queue.jobs().get(1).state);
            screen.get().findViewById(R.id.resumeSelected).performClick();
            assertEquals(OfflineBatchQueue.PENDING,queue.jobs().get(0).state);
        } finally { screen.pause().stop().destroy(); }
    }

    private OfflineBatchQueue.Job batchJob(String path,String episode) {
        OfflineBatchQueue.Job job=new OfflineBatchQueue.Job(); job.site="push_agent";job.flag="test";job.sourceUrl=server.url(path).toString();
        job.id=OfflineBatchQueue.identity(job.site,"fixture",job.flag,job.sourceUrl);job.resolvedUrl=job.sourceUrl;
        job.meta=metadata();job.meta.episode=episode;job.meta.sourceId=job.id; return job;
    }
}
