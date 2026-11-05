package com.fongmi.android.tv.offline;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
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

@RunWith(AndroidJUnit4.class)
public class OfflineDownloadTest {
    private Context context;
    private OfflineStore store;
    private MockWebServer server;
    private ActivityScenario<OfflineDownloadsActivity> screen;
    private final Map<String,byte[]> content = new ConcurrentHashMap<>();
    private final List<String> ranges = new CopyOnWriteArrayList<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger rejected = new AtomicInteger();
    private volatile boolean slow;
    private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><i><chatserver>offline</chatserver><d p=\"1,1,25,16777215,0,0,user,1\">离线弹幕测试</d><d p=\"4,1,25,16777215,0,0,user,2\">断网后仍然显示</d></i>";

    @Before public void setup() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("offline", 0).edit().putBoolean("wifi", false).commit();
        main(() -> store = OfflineStore.get(context));
        screen = ActivityScenario.launch(OfflineDownloadsActivity.class);
        main(() -> { store.manager.setRequirements(new Requirements(0)); store.manager.removeAllDownloads(); store.manager.resumeDownloads(); });
        await(() -> onMain(() -> store.manager.isInitialized() && store.downloads().isEmpty()), 15000);
        for (String file : InstrumentationRegistry.getInstrumentation().getContext().getAssets().list("offline"))
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
    }
    @After public void cleanup() throws Exception {
        if (screen != null) screen.close();
        if (store != null) main(() -> store.manager.removeAllDownloads());
        if (server != null) server.shutdown();
    }
    private byte[] asset(String name) throws IOException {
        try (InputStream in = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("offline/" + name)) {
            return read(in);
        }
    }
    private static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[16384]; int n;
        while ((n = in.read(b)) != -1) out.write(b, 0, n); return out.toByteArray();
    }
    private static void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private static <T> T onMain(Callable<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>(); AtomicReference<Exception> error = new AtomicReference<>();
        main(() -> { try { result.set(action.call()); } catch (Exception e) { error.set(e); } });
        if (error.get() != null) throw error.get(); return result.get();
    }
    private static void await(Callable<Boolean> condition, long timeout) throws Exception {
        long end = android.os.SystemClock.elapsedRealtime() + timeout;
        while (android.os.SystemClock.elapsedRealtime() < end) { if (condition.call()) return; Thread.sleep(100); }
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
        assertTrue("Preparation timed out", done.await(55, TimeUnit.SECONDS)); assertNull(error.get());
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

    @Test public void progressiveVideoDanmakuSubtitlesPlayWithoutAnyServerRequests() throws Exception {
        OfflineMetadata meta = metadata(); meta.headers.put("Range", "bytes=0-15");
        String id = enqueue("/clip.mp4", meta); Download download = complete(id);
        assertArrayEquals(content.get("/clip.mp4"), cached(id, "/clip.mp4", 0, -1));
        assertTrue(store.danmaku(id).isFile()); assertTrue(store.sidecar(id, "subtitle-0").isFile());
        try (InputStream in = new FileInputStream(store.danmaku(id))) { assertEquals(2, FilteringBiliParser.INSTANCE.parse(in).size()); }
        int before = requests.get();
        // A server that now denies every request represents an expired source / offline playback.
        content.clear();
        try (ActivityScenario<OfflinePlayerActivity> playback = ActivityScenario.launch(new Intent(context, OfflinePlayerActivity.class).putExtra("id", id))) {
            await(() -> {
                AtomicBoolean ready = new AtomicBoolean();
                playback.onActivity(activity -> { Player p = ((PlayerView)activity.findViewById(R.id.player)).getPlayer(); ready.set(p != null && p.getPlaybackState() == Player.STATE_READY && p.getCurrentPosition() > 500 && p.getVideoSize().width > 0); });
                return ready.get();
            }, 20000);
            playback.onActivity(activity -> ((PlayerView)activity.findViewById(R.id.player)).getPlayer().seekTo(4000));
            await(() -> {
                AtomicBoolean advanced = new AtomicBoolean(); playback.onActivity(activity -> advanced.set(((PlayerView)activity.findViewById(R.id.player)).getPlayer().getCurrentPosition() > 4500)); return advanced.get();
            }, 10000);
        }
        assertEquals("Offline player contacted the server", before, requests.get());
        assertEquals(0, rejected.get());
        assertEquals(1, store.mediaItem(download).localConfiguration.subtitleConfigurations.size());
    }

    @Test public void hlsPlaylistAndAllSegmentsAreAvailableOffline() throws Exception {
        String id = enqueue("/video.m3u8", metadata()); complete(id);
        int before = requests.get();
        for (String path : Arrays.asList("/video.m3u8", "/seg0.ts", "/seg1.ts", "/seg2.ts", "/seg3.ts"))
            assertArrayEquals(path, content.get(path), cached(id, path, 0, -1));
        content.clear();
        try (ActivityScenario<OfflinePlayerActivity> playback = ActivityScenario.launch(new Intent(context, OfflinePlayerActivity.class).putExtra("id", id))) {
            await(() -> { AtomicBoolean playing = new AtomicBoolean(); playback.onActivity(a -> { Player p = ((PlayerView)a.findViewById(R.id.player)).getPlayer(); playing.set(p != null && p.isPlaying() && p.getCurrentPosition() > 2500); }); return playing.get(); }, 20000);
        }
        assertEquals(before, requests.get()); assertEquals(0, rejected.get());
    }

    @Test public void encryptedHlsKeepsItsKeyAndFragmentedHlsKeepsItsInitSegment() throws Exception {
        for (String path : Arrays.asList("/encrypted.m3u8", "/fragmented.m3u8")) {
            String id = enqueue(path, metadata()); complete(id);
            int before = requests.get();
            String dependency = path.contains("encrypted") ? "/key.bin" : "/init.mp4";
            assertArrayEquals(content.get(dependency), cached(id, dependency, 0, -1));
            try (ActivityScenario<OfflinePlayerActivity> playback = ActivityScenario.launch(new Intent(context, OfflinePlayerActivity.class).putExtra("id", id))) {
                AtomicReference<String> state = new AtomicReference<>("not started");
                playback.onActivity(a -> ((androidx.media3.exoplayer.ExoPlayer)((PlayerView)a.findViewById(R.id.player)).getPlayer()).addAnalyticsListener(new androidx.media3.exoplayer.util.EventLogger()));
                try {
                    await(() -> { AtomicBoolean playing = new AtomicBoolean(); playback.onActivity(a -> {
                        Player p = ((PlayerView)a.findViewById(R.id.player)).getPlayer();
                        if (p != null) state.set("state=" + p.getPlaybackState() + ", position=" + p.getCurrentPosition() + ", buffered=" + p.getBufferedPosition() + ", playWhenReady=" + p.getPlayWhenReady() + ", suppression=" + p.getPlaybackSuppressionReason() + ", error=" + p.getPlayerError());
                        playing.set(p != null && p.isPlaying() && p.getCurrentPosition() > 2500);
                    }); return playing.get(); }, 20000);
                } catch (AssertionError e) { throw new AssertionError(path + ": " + state.get(), e); }
            }
            assertEquals("Playback fetched an HLS dependency", before, requests.get());
        }
    }

    @Test public void matroskaAndDashPlayOfflineWithSeeking() throws Exception {
        for (String path : Arrays.asList("/clip.mkv", "/video.mpd")) {
            String id = enqueue(path, metadata()); complete(id);
            int before = requests.get();
            try (ActivityScenario<OfflinePlayerActivity> playback = ActivityScenario.launch(new Intent(context, OfflinePlayerActivity.class).putExtra("id", id))) {
                await(() -> { AtomicBoolean playing = new AtomicBoolean(); playback.onActivity(a -> { Player p = ((PlayerView)a.findViewById(R.id.player)).getPlayer(); playing.set(p != null && p.isPlaying() && p.getCurrentPosition() > 500); }); return playing.get(); }, 20000);
                playback.onActivity(a -> ((PlayerView)a.findViewById(R.id.player)).getPlayer().seekTo(4000));
                await(() -> { AtomicBoolean advanced = new AtomicBoolean(); playback.onActivity(a -> advanced.set(((PlayerView)a.findViewById(R.id.player)).getPlayer().getCurrentPosition() > 4500)); return advanced.get(); }, 10000);
            }
            assertEquals("Offline media attempted a source request", before, requests.get());
        }
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
        assertTrue(done.await(5, TimeUnit.SECONDS)); assertNotNull(error.get());
        try (InputStream in = new FileInputStream(store.danmaku(id))) { assertArrayEquals(original, read(in)); }
    }

    @Test public void clearingStreamingCacheKeepsOfflineVideoAndDanmaku() throws Exception {
        String id = enqueue("/clip.mp4", metadata()); complete(id);
        CountDownLatch cleared = new CountDownLatch(1); AtomicReference<String> error = new AtomicReference<>();
        com.fongmi.android.tv.utils.FileUtil.clearCache(new com.fongmi.android.tv.impl.Callback() {
            @Override public void success() { cleared.countDown(); }
            @Override public void error(String message) { error.set(message); cleared.countDown(); }
        });
        assertTrue(cleared.await(15, TimeUnit.SECONDS)); assertNull(error.get());
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
        assertTrue(done.await(55, TimeUnit.SECONDS)); assertNotNull(error.get()); assertTrue(onMain(() -> store.downloads().isEmpty()));
    }
}
