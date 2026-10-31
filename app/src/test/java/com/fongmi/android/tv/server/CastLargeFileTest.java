package com.fongmi.android.tv.server;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Large-file index probes used by native TV demuxers; no multi-gigabyte allocation. */
public class CastLargeFileTest {
    private static final long SIZE = 2_700_000_123L;
    private final OkHttpClient client = new OkHttpClient();
    private final CastMediaRelay relay = new CastMediaRelay(client);
    private final AtomicInteger headRequests = new AtomicInteger();
    private final AtomicReference<String> lastRange = new AtomicReference<>();
    private NanoHTTPD upstream, phone;
    private boolean wrongOffset, ignoreRange;
    private CastMediaRelay.Session session;

    @Before public void start() throws Exception {
        upstream = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession request) {
                if (request.getMethod() == Method.HEAD) {
                    headRequests.incrementAndGet();
                    // A plugin/CDN may return 200 with a zero length only for HEAD.
                    return newFixedLengthResponse(Response.Status.OK, "video/mp4", "");
                }
                String range = request.getHeaders().get("range");
                lastRange.set(range);
                if (range == null || !range.matches("bytes=[0-9]+-[0-9]+"))
                    return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "Explicit bounds required");
                String[] bounds = range.substring(6).split("-");
                long start = Long.parseLong(bounds[0]), end = Long.parseLong(bounds[1]);
                if (end >= SIZE || start > end)
                    return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "Out of bounds");
                if (wrongOffset) start = 0;
                byte[] data = new byte[(int) Math.min(end - start + 1, 65536)];
                for (int i = 0; i < data.length; i++) data[i] = (byte) ((start + i) % 251);
                if (start == 0 && data.length >= 4) {
                    data[0] = 0x1a; data[1] = 0x45; data[2] = (byte) 0xdf; data[3] = (byte) 0xa3;
                }
                // Valid Content-Range but chunked framing: older renderers require Content-Length.
                Response response = newChunkedResponse(ignoreRange ? Response.Status.OK : Response.Status.PARTIAL_CONTENT,
                        "video/mp4", new ByteArrayInputStream(data));
                if (!ignoreRange) response.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + SIZE);
                return response;
            }
        };
        phone = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession request) { return relay.serve(request); }
        };
        upstream.start(); phone.start();
        session = relay.open("http://127.0.0.1:" + phone.getListeningPort(),
                "http://127.0.0.1:" + upstream.getListeningPort() + "/opaque", Map.of());
        relay.prepare(session);
    }

    @After public void stop() {
        relay.stop(); phone.stop(); upstream.stop();
        client.connectionPool().evictAll(); client.dispatcher().executorService().shutdownNow();
    }

    private Response get(String range) throws Exception {
        return client.newCall(new Request.Builder().url(session.url()).header("Range", range).build()).execute();
    }

    @Test public void headUsesVerifiedFullSizeInsteadOfBrokenCdnHead() throws Exception {
        try (Response response = client.newCall(new Request.Builder().url(session.url()).head().build()).execute()) {
            assertEquals(200, response.code());
            assertEquals(Long.toString(SIZE), response.header("Content-Length"));
            assertEquals("video/x-matroska", response.header("Content-Type"));
            assertEquals("bytes", response.header("Accept-Ranges"));
            assertEquals(0, response.body().bytes().length);
        }
        assertEquals("HEAD must not re-probe an already verified movie", 0, headRequests.get());
    }

    @Test public void suffixIndexProbeReadsOnlyTailAboveTwoGigabytes() throws Exception {
        try (Response response = get("bytes=-1024")) {
            assertEquals(206, response.code());
            assertEquals("bytes=" + (SIZE - 1024) + "-" + (SIZE - 1), lastRange.get());
            assertEquals("1024", response.header("Content-Length"));
            assertNull(response.header("Transfer-Encoding"));
            assertEquals("bytes " + (SIZE - 1024) + "-" + (SIZE - 1) + "/" + SIZE, response.header("Content-Range"));
            byte[] data = response.body().bytes();
            assertEquals(1024, data.length);
            assertEquals((byte) ((SIZE - 1024) % 251), data[0]);
        }
    }

    @Test public void openEndedProbeGetsExplicitBoundsAndFixedLength() throws Exception {
        try (Response response = get("bytes=" + (SIZE - 4096) + "-")) {
            assertEquals(206, response.code());
            assertEquals("4096", response.header("Content-Length"));
            assertEquals(4096, response.body().bytes().length);
        }
    }

    @Test public void rangePastEndIsClampedWithoutIntegerOverflow() throws Exception {
        try (Response response = get("bytes=" + (SIZE - 1024) + "-9223372036854775807")) {
            assertEquals(206, response.code());
            assertEquals(1024, response.body().bytes().length);
        }
    }

    @Test public void unsatisfiableRangeReportsKnownSizeWithoutTouchingSource() throws Exception {
        String before = lastRange.get();
        try (Response response = get("bytes=" + SIZE + "-")) {
            assertEquals(416, response.code());
            assertEquals("bytes */" + SIZE, response.header("Content-Range"));
        }
        assertEquals(before, lastRange.get());
    }

    @Test public void wrongUpstreamOffsetCannotMasqueradeAsRequestedIndex() throws Exception {
        wrongOffset = true;
        try (Response response = get("bytes=" + (SIZE - 1024) + "-" + (SIZE - 1))) {
            assertEquals(502, response.code());
        }
    }

    @Test public void ignoredTailRangeDoesNotDownloadTwoGigabytesToSkipForward() throws Exception {
        ignoreRange = true;
        try (Response response = get("bytes=" + (SIZE - 1024) + "-" + (SIZE - 1))) {
            assertEquals(502, response.code());
            assertTrue(response.body().contentLength() < 1024);
        }
    }
}
