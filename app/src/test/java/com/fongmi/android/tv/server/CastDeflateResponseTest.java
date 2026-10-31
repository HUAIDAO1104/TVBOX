package com.fongmi.android.tv.server;

import com.github.catvod.net.interceptor.ResponseInterceptor;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import fi.iki.elonen.NanoHTTPD;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import static org.junit.Assert.*;

public class CastDeflateResponseTest {
    @Test public void decodedPlayerResponseKeepsOneBufferAndCanBeRelayed() throws Exception {
        byte[] video = "video-stream-from-a-deflated-source".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream out = new DeflaterOutputStream(compressed, new Deflater(6, true))) { out.write(video); }
        byte[] wire = compressed.toByteArray();
        NanoHTTPD source = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession request) {
                Response response = newFixedLengthResponse(Response.Status.OK, "video/mp4", new ByteArrayInputStream(wire), wire.length);
                response.addHeader("Content-Encoding", "deflate");
                return response;
            }
        };
        OkHttpClient player = new OkHttpClient.Builder().addNetworkInterceptor(new ResponseInterceptor()).build();
        CastMediaRelay relay = new CastMediaRelay(player);
        NanoHTTPD phone = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession request) { return relay.serve(request); }
        };
        OkHttpClient tv = new OkHttpClient();
        try {
            source.start(); phone.start();
            String sourceUrl = "http://127.0.0.1:" + source.getListeningPort() + "/video.mp4";
            try (Response response = player.newCall(new Request.Builder().url(sourceUrl).build()).execute()) {
                assertNull(response.header("Content-Encoding"));
                assertNull(response.header("Content-Length"));
                assertSame(response.body().source(), response.body().source());
                assertTrue(response.body().source().request(7));
                assertArrayEquals(video, response.body().bytes());
            }
            CastMediaRelay.Session session = relay.open("http://127.0.0.1:" + phone.getListeningPort(), sourceUrl, Map.of());
            relay.prepare(session);
            try (Response response = tv.newCall(new Request.Builder().url(session.url()).build()).execute()) {
                assertEquals(200, response.code());
                assertNull(response.header("Content-Encoding"));
                assertArrayEquals(video, response.body().bytes());
            }
        } finally {
            relay.stop(); phone.stop(); source.stop();
            player.connectionPool().evictAll(); tv.connectionPool().evictAll();
        }
    }
}
