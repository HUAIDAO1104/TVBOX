package com.fongmi.android.tv.server;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/** Receiver-facing formats, not just successful byte downloads. */
public class CastStreamFormatTest {
    private final OkHttpClient client = new OkHttpClient();
    private final CastMediaRelay relay = new CastMediaRelay(client);
    private NanoHTTPD upstream, phone;
    private Function<NanoHTTPD.IHTTPSession, NanoHTTPD.Response> handler;

    @Before public void start() throws Exception {
        upstream = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession request) { return handler.apply(request); }
        };
        phone = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession request) { return relay.serve(request); }
        };
        upstream.start(); phone.start();
    }

    @After public void stop() {
        relay.stop(); phone.stop(); upstream.stop();
        client.connectionPool().evictAll(); client.dispatcher().executorService().shutdownNow();
    }

    private CastMediaRelay.Session open(String path) {
        return relay.open("http://127.0.0.1:" + phone.getListeningPort(),
                "http://127.0.0.1:" + upstream.getListeningPort() + path, Map.of());
    }

    private static NanoHTTPD.Response bytes(String type, byte[] data) {
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, type, new ByteArrayInputStream(data), data.length);
    }

    private static NanoHTTPD.Response text(String type, String data) { return bytes(type, data.getBytes(StandardCharsets.UTF_8)); }

    @Test public void matroskaIsNotAdvertisedAsAnMp4File() throws Exception {
        handler = request -> bytes("video/x-matroska", new byte[]{0x1a,0x45,(byte)0xdf,(byte)0xa3,0,0,0,0});
        CastMediaRelay.Session session = open("/proxy?file=opaque"); relay.prepare(session);
        assertEquals("video/x-matroska", session.mimeType());
        assertTrue(session.url(), session.url().endsWith(".mkv"));
    }

    @Test public void sourceRejectingRangeStillStartsWithoutAdvertisingSeek() throws Exception {
        handler = request -> request.getHeaders().containsKey("range")
                ? NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "Unsupported range")
                : bytes("video/x-matroska", new byte[]{0x1a,0x45,(byte)0xdf,(byte)0xa3,0,0,0,0});
        CastMediaRelay.Session session = open("/opaque"); relay.prepare(session);
        assertFalse(session.canSeek());
        assertTrue(session.url().endsWith(".mkv"));
        try (Response response = client.newCall(new Request.Builder().url(session.url()).build()).execute()) {
            assertEquals(200, response.code());
            assertEquals(8, response.body().bytes().length);
        }
    }

    @Test public void matroskaRangeRetainsVerifiedTypeWhenCdnAlwaysClaimsMp4() throws Exception {
        byte[] mkv = new byte[1000];
        mkv[0] = 0x1a; mkv[1] = 0x45; mkv[2] = (byte) 0xdf; mkv[3] = (byte) 0xa3;
        handler = request -> {
            int start = request.getHeaders().getOrDefault("range", "").equals("bytes=500-") ? 500 : 0;
            NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.PARTIAL_CONTENT,
                    "video/mp4", new ByteArrayInputStream(mkv, start, mkv.length - start), mkv.length - start);
            response.addHeader("Content-Range", "bytes " + start + "-999/1000");
            return response;
        };
        CastMediaRelay.Session session = open("/proxy"); relay.prepare(session);
        assertTrue(session.canByteSeek());
        assertEquals(Long.valueOf(1000), session.length());
        try (Response response = client.newCall(new Request.Builder().url(session.url()).head().build()).execute()) {
            assertEquals("video/x-matroska", response.header("Content-Type"));
        }
        try (Response response = client.newCall(new Request.Builder().url(session.url()).header("Range", "bytes=500-").build()).execute()) {
            assertEquals(206, response.code());
            assertEquals("video/x-matroska", response.header("Content-Type"));
            assertEquals(500, response.body().bytes().length);
        }
    }

    @Test public void transportStreamWithAnImageHeaderIsSniffedWithoutLosingBytes() throws Exception {
        byte[] ts = new byte[188 * 4];
        for (int offset = 0; offset < ts.length; offset += 188) ts[offset] = 0x47;
        handler = request -> bytes("image/jpeg", ts);
        CastMediaRelay.Session session = open("/opaque.jpg"); relay.prepare(session);
        assertEquals("video/mp2t", session.mimeType());
        assertTrue(session.url().endsWith(".ts"));
        try (Response response = client.newCall(new Request.Builder().url(session.url()).build()).execute()) {
            assertEquals("video/mp2t", response.header("Content-Type"));
            assertArrayEquals(ts, response.body().bytes());
        }
    }

    @Test public void bomPlaylistIsRecognizedAndReturnedAsHls() throws Exception {
        handler = request -> text("text/plain", "\uFEFF#EXTM3U\n#EXTINF:2,\na.ts\n#EXT-X-ENDLIST\n");
        CastMediaRelay.Session session = open("/proxy"); relay.prepare(session);
        assertEquals("application/vnd.apple.mpegurl", session.mimeType());
        assertTrue(session.url().endsWith(".m3u8"));
        try (Response response = client.newCall(new Request.Builder().url(session.url()).build()).execute()) {
            assertTrue(response.body().string().startsWith("#EXTM3U"));
        }
    }

    @Test public void hlsKeepsDecodableExtensionsForVariantsMapsAndFragments() throws Exception {
        handler = request -> text("application/vnd.apple.mpegurl", "#EXTM3U\n"
                + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",URI=\"audio\"\n"
                + "#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:2,\nsegment.m4s\n#EXT-X-ENDLIST\n");
        CastMediaRelay.Session session = open("/video.m3u8");
        try (Response response = client.newCall(new Request.Builder().url(session.url()).build()).execute()) {
            String playlist = response.body().string();
            assertTrue(playlist, playlist.contains(".m3u8\""));
            assertTrue(playlist, playlist.contains(".mp4\""));
            assertTrue(playlist, playlist.contains(".m4s\n"));
            assertFalse(playlist, playlist.contains("/media\n"));
        }
    }

    @Test public void extensionlessHlsSegmentsGetTransportStreamNames() throws Exception {
        handler = request -> text("application/vnd.apple.mpegurl", "#EXTM3U\n#EXTINF:2,\nopaque?id=123\n#EXT-X-ENDLIST\n");
        CastMediaRelay.Session session = open("/video.m3u8");
        try (Response response = client.newCall(new Request.Builder().url(session.url()).build()).execute()) {
            assertTrue(response.body().string().contains(".ts\n"));
        }
    }

    @Test public void encryptionKeyDownloadDoesNotCountAsVideoPlayback() throws Exception {
        handler = request -> request.getUri().endsWith(".m3u8")
                ? text("application/vnd.apple.mpegurl", "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF:2,\na.ts\n")
                : bytes("application/octet-stream", new byte[16]);
        CastMediaRelay.Session session = open("/video.m3u8");
        String playlist;
        try (Response response = client.newCall(new Request.Builder().url(session.url()).build()).execute()) { playlist = response.body().string(); }
        Matcher key = Pattern.compile("URI=\"([^\"]+)\"").matcher(playlist); assertTrue(key.find());
        try (Response response = client.newCall(new Request.Builder().url(key.group(1)).build()).execute()) { assertEquals(16, response.body().bytes().length); }
        assertFalse("Keys cannot confirm decoder startup", session.awaitMedia(20));
    }
}
