package com.fongmi.android.tv.server;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import static org.junit.Assert.*;

/** Real HTTP requests through NanoHTTPD, not mocks of the relay's implementation. */
public class CastMediaRelayTest {
    private OkHttpClient player;
    private OkHttpClient tv;
    private CastMediaRelay relay;
    private NanoHTTPD source;
    private NanoHTTPD server;
    private volatile Function<NanoHTTPD.IHTTPSession, NanoHTTPD.Response> handler;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Before public void setUp() throws Exception {
        player = new OkHttpClient.Builder().readTimeout(3, TimeUnit.SECONDS).build();
        tv = new OkHttpClient.Builder().readTimeout(3, TimeUnit.SECONDS).build();
        relay = new CastMediaRelay(player);
        handler = request -> bytes(200, "video/mp4", "video-content".getBytes());
        source = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession session) { return handler.apply(session); }
        };
        server = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession session) { return relay.serve(session); }
        };
        source.start(); server.start();
    }

    @After public void tearDown() {
        relay.stop(); server.stop(); source.stop(); executor.shutdownNow();
        tv.connectionPool().evictAll(); player.connectionPool().evictAll();
        tv.dispatcher().executorService().shutdownNow(); player.dispatcher().executorService().shutdownNow();
    }

    private String upstream(String path) { return "http://127.0.0.1:" + source.getListeningPort() + path; }
    private CastMediaRelay.Session open(String path, Map<String, String> headers) {
        return relay.open("http://127.0.0.1:" + server.getListeningPort(), upstream(path), headers);
    }
    private Response get(String url) throws IOException { return tv.newCall(new Request.Builder().url(url).build()).execute(); }
    private static NanoHTTPD.Response bytes(int status, String mime, byte[] data) {
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.lookup(status), mime, new ByteArrayInputStream(data), data.length);
    }
    private static NanoHTTPD.Response text(String mime, String text) { return bytes(200, mime, text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }

    @Test public void protectedSourceFailsDirectlyButPlaysThroughPhoneHeaders() throws Exception {
        handler = request -> {
            Map<String, String> headers = request.getHeaders();
            if (!"https://example.test/player".equals(headers.get("referer"))
                    || !"phone-agent".equals(headers.get("user-agent"))
                    || !"session=secret".equals(headers.get("cookie"))
                    || !"Bearer phone-token".equals(headers.get("authorization"))) return bytes(403, "text/plain", "denied".getBytes());
            return text("video/mp4", "actual-video-bytes");
        };
        try (Response direct = get(upstream("/movie.mp4"))) { assertEquals(403, direct.code()); }
        Map<String, String> headers = new HashMap<>(Map.of("Referer", "https://example.test/player", "User-Agent", "phone-agent",
                "Cookie", "session=secret", "Authorization", "Bearer phone-token"));
        CastMediaRelay.Session session = open("/movie.mp4", headers);
        headers.clear(); // Stopping the phone player must not erase the captured headers.
        relay.prepare(session);
        assertFalse(session.url().contains("secret"));
        try (Response result = get(session.url())) { assertEquals(200, result.code()); assertEquals("actual-video-bytes", result.body().string()); }
    }

    @Test public void rewritesRedirectedHlsVariantsKeysMapsAudioSubtitlesAndSegments() throws Exception {
        List<String> requested = java.util.Collections.synchronizedList(new ArrayList<>());
        handler = request -> {
            if (!"source-cookie".equals(request.getHeaders().get("cookie"))) return bytes(403, "text/plain", new byte[0]);
            requested.add(request.getUri());
            if (request.getUri().equals("/hls/video/index.m3u8")) assertEquals("private", request.getParms().get("signature"));
            if (request.getUri().equals("/hls/key.bin")) assertEquals("private", request.getParms().get("key"));
            if (request.getUri().equals("/start")) {
                NanoHTTPD.Response redirect = bytes(302, "text/plain", new byte[0]);
                redirect.addHeader("Location", "/hls/master.m3u8?token=private"); return redirect;
            }
            if (request.getUri().equals("/hls/master.m3u8")) return text("application/octet-stream", "#EXTM3U\n"
                    + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",URI=\"audio.m3u8\"\n"
                    + "#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"sub\",URI=\"subs.m3u8\"\n"
                    + "#EXT-X-STREAM-INF:BANDWIDTH=1000000\nvideo/index.m3u8?signature=private\n");
            if (request.getUri().equals("/hls/video/index.m3u8")) return text("text/plain", "#EXTM3U\n"
                    + "#EXT-X-KEY:METHOD=AES-128,URI=\"../key.bin?key=private\"\n"
                    + "#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:6,\nseg.ts?token=private\n#EXT-X-ENDLIST\n");
            if (request.getUri().endsWith(".m3u8")) return text("application/vnd.apple.mpegurl", "#EXTM3U\n#EXTINF:6,\ntrack.aac\n");
            return text("application/octet-stream", "media-data");
        };
        CastMediaRelay.Session session = open("/start", Map.of("Cookie", "source-cookie"));
        relay.prepare(session);
        assertEquals("application/vnd.apple.mpegurl", session.mimeType());
        assertTrue(session.url().endsWith(".m3u8"));
        String master;
        try (Response result = get(session.url())) {
            master = result.body().string();
            assertEquals("no-store", result.header("Cache-Control"));
        }
        assertFalse(master.contains("private"));
        List<String> variants = links(master);
        assertEquals(3, variants.size());
        for (String variant : variants) {
            try (Response result = get(variant)) {
                String playlist = result.body().string();
                for (String media : links(playlist)) try (Response segment = get(media)) {
                    assertEquals(200, segment.code()); assertEquals("media-data", segment.body().string());
                }
            }
        }
        assertTrue(requested.containsAll(List.of("/hls/key.bin", "/hls/video/init.mp4", "/hls/video/seg.ts", "/hls/audio.m3u8", "/hls/subs.m3u8")));
    }

    private static List<String> links(String playlist) {
        List<String> links = new ArrayList<>();
        Matcher matcher = Pattern.compile("http://[^\\s\"]+").matcher(playlist);
        while (matcher.find()) links.add(matcher.group());
        return links;
    }

    @Test public void receiverRangeOverridesOldPhoneRangeAndPreserves206() throws Exception {
        handler = request -> {
            assertEquals("bytes=2-5", request.getHeaders().get("range"));
            assertEquals("etag", request.getHeaders().get("if-range"));
            assertEquals("identity", request.getHeaders().get("accept-encoding"));
            NanoHTTPD.Response response = text("video/mp4", "2345");
            response.setStatus(NanoHTTPD.Response.Status.PARTIAL_CONTENT);
            response.addHeader("Content-Range", "bytes 2-5/10"); response.addHeader("Accept-Ranges", "bytes");
            return response;
        };
        CastMediaRelay.Session session = open("/video.mp4", Map.of("Range", "bytes=900-", "Accept-Encoding", "gzip"));
        try (Response response = tv.newCall(new Request.Builder().url(session.url()).header("Range", "bytes=2-5").header("If-Range", "etag").build()).execute()) {
            assertEquals(206, response.code()); assertEquals("bytes 2-5/10", response.header("Content-Range"));
            assertEquals("4", response.header("Content-Length")); assertEquals("2345", response.body().string());
        }
    }

    @Test public void headPreservesLengthWithoutSendingBody() throws Exception {
        handler = request -> bytes(200, "video/mp4", new byte[2048]);
        CastMediaRelay.Session session = open("/video.mp4", Map.of());
        try (Response response = tv.newCall(new Request.Builder().url(session.url()).head().build()).execute()) {
            assertEquals(200, response.code()); assertEquals("2048", response.header("Content-Length")); assertEquals(0, response.body().bytes().length);
        }
    }

    @Test public void headFallsBackWhenSourceRejectsHead() throws Exception {
        handler = request -> request.getMethod() == NanoHTTPD.Method.HEAD ? bytes(405, "text/plain", new byte[0]) : bytes(200, "video/mp4", new byte[128]);
        CastMediaRelay.Session session = open("/video.mp4", Map.of());
        try (Response response = tv.newCall(new Request.Builder().url(session.url()).head().build()).execute()) {
            assertEquals(200, response.code()); assertEquals("128", response.header("Content-Length")); assertEquals(0, response.body().bytes().length);
        }
    }

    @Test public void hlsHeadReportsRewrittenPlaylistLength() throws Exception {
        handler = request -> text("application/vnd.apple.mpegurl", "#EXTM3U\n#EXTINF:6,\na.ts\n");
        CastMediaRelay.Session session = open("/index.m3u8", Map.of());
        String expected;
        try (Response response = get(session.url())) { expected = response.header("Content-Length"); }
        try (Response response = tv.newCall(new Request.Builder().url(session.url()).head().build()).execute()) {
            assertEquals(expected, response.header("Content-Length")); assertEquals(0, response.body().bytes().length);
        }
        try (Response response = get(session.url())) { assertEquals(200, response.code()); assertTrue(response.body().string().startsWith("#EXTM3U")); }
    }

    @Test public void manifestProbeRangeDoesNotTruncateRewrittenHls() throws Exception {
        handler = request -> {
            assertNull(request.getHeaders().get("range"));
            return text("application/vnd.apple.mpegurl", "#EXTM3U\n#EXTINF:6,\na.ts\n");
        };
        CastMediaRelay.Session session = open("/index.m3u8", Map.of());
        try (Response response = tv.newCall(new Request.Builder().url(session.url()).header("Range", "bytes=0-1").build()).execute()) {
            assertEquals(200, response.code()); assertTrue(response.body().string().startsWith("#EXTM3U"));
        }
    }

    @Test public void preparationAndHeadAreNotMistakenForReceiverPlayback() throws Exception {
        CastMediaRelay.Session session = open("/video.mp4", Map.of());
        relay.prepare(session);
        try (Response response = tv.newCall(new Request.Builder().url(session.url()).head().build()).execute()) { assertEquals(200, response.code()); }
        assertFalse(session.awaitMedia(10));
        try (Response response = get(session.url())) { assertEquals(200, response.code()); assertTrue(response.body().bytes().length > 0); }
        assertTrue(session.awaitMedia(1000));
    }

    @Test public void chunkedUpstreamIsNotBufferedOrTruncated() throws Exception {
        byte[] data = new byte[256 * 1024]; new java.util.Random(123).nextBytes(data);
        handler = request -> NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, "video/mp4", new ByteArrayInputStream(data));
        CastMediaRelay.Session session = open("/stream", Map.of());
        try (Response response = get(session.url())) { assertArrayEquals(data, response.body().bytes()); }
    }

    @Test public void unknownHeadLengthIsNotAdvertisedAsAnEmptyVideo() throws Exception {
        handler = request -> NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, "video/mp4", new ByteArrayInputStream("video-content".getBytes()));
        CastMediaRelay.Session session = open("/stream", Map.of());
        try (Response response = tv.newCall(new Request.Builder().url(session.url()).head().build()).execute()) {
            assertEquals(200, response.code()); assertNull(response.header("Content-Length")); assertEquals(0, response.body().bytes().length);
        }
        try (Response response = get(session.url())) { assertEquals("video-content", response.body().string()); }
    }

    @Test public void forwardsBytesBeforeTheUpstreamVideoFinishes() throws Exception {
        CountDownLatch finish = new CountDownLatch(1);
        handler = request -> NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, "video/mp4", new InputStream() {
            int remaining = 64 * 1024;
            @Override public int read() throws IOException {
                if (remaining-- > 0) return 42;
                try { if (!finish.await(3, TimeUnit.SECONDS)) throw new IOException("not streaming"); }
                catch (InterruptedException e) { throw new IOException(e); }
                return -1;
            }
            @Override public int read(byte[] b, int off, int len) throws IOException {
                if (remaining <= 0) return read();
                int count = Math.min(len, remaining); Arrays.fill(b, off, off + count, (byte) 42); remaining -= count; return count;
            }
        });
        CastMediaRelay.Session session = open("/stream", Map.of());
        try {
            java.util.concurrent.CompletableFuture<Integer> first = new java.util.concurrent.CompletableFuture<>();
            Future<?> drain = executor.submit(() -> {
                try (Response response = get(session.url())) {
                    InputStream body = response.body().byteStream();
                    first.complete(body.read());
                    byte[] buffer = new byte[8192];
                    while (body.read(buffer) != -1) { }
                }
                return null;
            });
            assertEquals(Integer.valueOf(42), first.get(2, TimeUnit.SECONDS));
            finish.countDown();
            drain.get(2, TimeUnit.SECONDS);
        } finally { finish.countDown(); }
    }

    @Test public void stoppingPhoneRequestsDoesNotCancelRelayRequests() throws Exception {
        CountDownLatch requested = new CountDownLatch(1), allow = new CountDownLatch(1);
        handler = request -> {
            requested.countDown();
            try { allow.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return text("video/mp4", "relay-keeps-playing");
        };
        CastMediaRelay.Session session = open("/video.mp4", Map.of());
        Future<String> response = executor.submit(() -> { try (Response r = get(session.url())) { return r.body().string(); } });
        assertTrue(requested.await(2, TimeUnit.SECONDS));
        player.dispatcher().cancelAll(); allow.countDown();
        assertEquals("relay-keeps-playing", response.get(2, TimeUnit.SECONDS));
    }

    @Test public void unknownAndReplacedSessionsCannotReadMedia() throws Exception {
        CastMediaRelay.Session first = open("/first.mp4", Map.of());
        CastMediaRelay.Session second = open("/second.mp4", Map.of());
        relay.close(first); // A delayed failure from the old cast must not stop the new one.
        try (Response response = get(first.url())) { assertEquals(404, response.code()); }
        try (Response response = get(second.url())) { assertEquals(200, response.code()); }
        try (Response response = get(second.url().replace("/0/", "/999/"))) { assertEquals(404, response.code()); }
        relay.stop();
        try (Response response = get(second.url())) { assertEquals(404, response.code()); }
    }

    @Test public void sourceErrorsRemainErrorsInsteadOfFakeVideo() throws Exception {
        handler = request -> bytes(403, "text/plain", "denied".getBytes());
        CastMediaRelay.Session session = open("/video.mp4", Map.of());
        assertThrows(IOException.class, () -> relay.prepare(session));
        try (Response response = get(session.url())) { assertEquals(403, response.code()); }
    }

    @Test public void rejectsHtmlErrorPagesEvenWith200() throws Exception {
        handler = request -> text("text/html", "<html>blocked</html>");
        CastMediaRelay.Session session = open("/video", Map.of());
        assertThrows(IOException.class, () -> relay.prepare(session));
    }

    @Test public void oversizedPlaylistIsBounded() throws Exception {
        handler = request -> text("application/vnd.apple.mpegurl", "#EXTM3U\n" + "x".repeat(2 * 1024 * 1024));
        CastMediaRelay.Session session = open("/playlist.m3u8", Map.of());
        try (Response response = get(session.url())) { assertEquals(502, response.code()); }
    }

    @Test public void concurrentSegmentRequestsKeepTheirHeadersAndContent() throws Exception {
        handler = request -> "phone-agent".equals(request.getHeaders().get("user-agent"))
                ? text("video/mp2t", "segment-data") : bytes(403, "text/plain", new byte[0]);
        CastMediaRelay.Session session = open("/segment.ts", Map.of("User-Agent", "phone-agent"));
        List<Future<String>> jobs = new ArrayList<>();
        for (int i = 0; i < 8; i++) jobs.add(executor.submit(() -> { try (Response response = get(session.url())) { return response.body().string(); } }));
        for (Future<String> job : jobs) assertEquals("segment-data", job.get(3, TimeUnit.SECONDS));
    }

    @Test public void inlineHlsKeyIsNotTurnedIntoAnHttpRequest() throws Exception {
        handler = request -> text("application/vnd.apple.mpegurl", "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"data:application/octet-stream;base64,AAAA\"\n#EXTINF:2,\na.ts\n");
        CastMediaRelay.Session session = open("/index.m3u8", Map.of());
        try (Response response = get(session.url())) { assertTrue(response.body().string().contains("URI=\"data:application/octet-stream;base64,AAAA\"")); }
    }
}
