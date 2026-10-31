package com.fongmi.android.tv.server;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.OkHttpClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Real demux/decode checks. CI sets CAST_FFMPEG; no external media or Android device is required. */
public class CastDecoderIntegrationTest {
    private String ffmpeg;
    private Path directory;
    private final OkHttpClient client = new OkHttpClient();
    private final CastMediaRelay relay = new CastMediaRelay(client);
    private NanoHTTPD upstream, phone;
    private Function<NanoHTTPD.IHTTPSession, NanoHTTPD.Response> handler;
    private final CountDownLatch blockedTail = new CountDownLatch(1);

    @Before public void start() throws Exception {
        ffmpeg = System.getenv("CAST_FFMPEG");
        assumeTrue("Set CAST_FFMPEG to run real decoder integration tests", ffmpeg != null && !ffmpeg.isBlank());
        directory = Files.createTempDirectory("cast-decoder-");
        upstream = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession request) { return handler.apply(request); }
        };
        phone = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession request) { return relay.serve(request); }
        };
        upstream.start(); phone.start();
    }

    @After public void stop() throws Exception {
        blockedTail.countDown(); relay.stop();
        if (phone != null) phone.stop();
        if (upstream != null) upstream.stop();
        client.connectionPool().evictAll(); client.dispatcher().executorService().shutdownNow();
        if (directory != null) try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private String run(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-y"));
        command.addAll(Arrays.asList(arguments));
        Path log = Files.createTempFile(directory, "ffmpeg-", ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue("Decoder waited for unavailable video tail: " + Files.readString(log), process.waitFor(15, TimeUnit.SECONDS));
            String output = Files.readString(log);
            assertEquals(output, 0, process.exitValue());
            return output;
        } finally { process.destroyForcibly(); }
    }

    private CastMediaRelay.Session open(String path) throws Exception {
        CastMediaRelay.Session session = relay.open("http://127.0.0.1:" + phone.getListeningPort(),
                "http://127.0.0.1:" + upstream.getListeningPort() + path, Map.of());
        relay.prepare(session);
        return session;
    }

    private NanoHTTPD.Response file(NanoHTTPD.IHTTPSession request, Path path, String mime) {
        try {
            byte[] data = Files.readAllBytes(path);
            int start = 0, end = data.length - 1;
            String range = request.getHeaders().get("range");
            if (range != null && range.matches("bytes=[0-9]+-[0-9]*")) {
                String[] bounds = range.substring(6).split("-", -1);
                start = Integer.parseInt(bounds[0]);
                if (!bounds[1].isEmpty()) end = Math.min(end, Integer.parseInt(bounds[1]));
            }
            if (start >= data.length) return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "");
            NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(range == null ? NanoHTTPD.Response.Status.OK
                    : NanoHTTPD.Response.Status.PARTIAL_CONTENT, mime, new ByteArrayInputStream(data, start, end - start + 1), end - start + 1);
            if (range != null) response.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + data.length);
            response.addHeader("Accept-Ranges", "bytes");
            return response;
        } catch (Exception e) { return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "text/plain", e.toString()); }
    }

    @Test public void hlsDecodesFirstFrameWhileLaterSegmentsRemainUnavailable() throws Exception {
        run("-f", "lavfi", "-i", "testsrc2=size=320x180:rate=20", "-t", "24", "-c:v", "libx264", "-preset", "ultrafast",
                "-g", "40", "-sc_threshold", "0", "-f", "hls", "-hls_time", "2", "-hls_list_size", "0",
                "-hls_segment_filename", directory.resolve("seg%02d.ts").toString(), directory.resolve("video.m3u8").toString());
        AtomicInteger tailRequests = new AtomicInteger();
        handler = request -> {
            String name = request.getUri().substring(1);
            if (name.matches("seg[0-9]+\\.ts") && Integer.parseInt(name.substring(3, 5)) >= 4) {
                tailRequests.incrementAndGet();
                try { blockedTail.await(20, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            // The CDN labels segments as images. The relay must preserve and identify TS bytes.
            return file(request, directory.resolve(name), name.endsWith("m3u8") ? "application/vnd.apple.mpegurl" : "image/jpeg");
        };
        CastMediaRelay.Session session = open("/video.m3u8");
        String frame = run("-i", session.url(), "-map", "0:v:0", "-frames:v", "1", "-f", "framehash", "-");
        assertTrue(frame, frame.contains("#dimensions 0: 320x180"));
        assertTrue(frame, frame.lines().anyMatch(line -> line.startsWith("0,")));
        assertEquals("First frame must not require the remaining movie", 0, tailRequests.get());
    }

    @Test public void hevcTenBit4kAndEac3SurviveNativeStyleRangeProbes() throws Exception {
        Path movie = directory.resolve("hdr.mkv");
        run("-f", "lavfi", "-i", "color=c=blue:size=3840x2160:rate=2", "-f", "lavfi", "-i", "anullsrc=r=48000:cl=5.1",
                "-t", "2", "-c:v", "libx265", "-preset", "ultrafast", "-pix_fmt", "yuv420p10le",
                "-x265-params", "pools=2:frame-threads=2:log-level=error:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc",
                "-c:a", "eac3", "-b:a", "384k", movie.toString());
        handler = request -> {
            if (request.getMethod() == NanoHTTPD.Method.HEAD)
                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "video/mp4", "");
            String range = request.getHeaders().get("range");
            if (range != null && !range.matches("bytes=[0-9]+-[0-9]+"))
                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "Closed bounds required");
            NanoHTTPD.Response original = file(request, movie, "video/mp4");
            NanoHTTPD.Response chunked = NanoHTTPD.newChunkedResponse(original.getStatus(), "video/mp4", original.getData());
            if (original.getHeader("Content-Range") != null) chunked.addHeader("Content-Range", original.getHeader("Content-Range"));
            return chunked;
        };
        CastMediaRelay.Session session = open("/opaque-4k");
        try (okhttp3.Response response = client.newCall(new okhttp3.Request.Builder().url(session.url()).build()).execute()) {
            assertEquals(Long.toString(Files.size(movie)), response.header("Content-Length"));
            assertNull(response.header("Transfer-Encoding"));
            assertArrayEquals(Files.readAllBytes(movie), response.body().bytes());
        }
        String frame = run("-i", session.url(), "-map", "0:v:0", "-frames:v", "1", "-f", "framehash", "-");
        assertTrue(frame, frame.contains("#dimensions 0: 3840x2160"));
        assertTrue(frame, frame.lines().anyMatch(line -> line.startsWith("0,")));
        String audio = run("-i", session.url(), "-map", "0:a:0", "-t", "0.2", "-f", "framehash", "-");
        assertTrue(audio, audio.lines().anyMatch(line -> line.startsWith("0,")));
    }

    @Test public void matroskaPreservesPictureRatioAndEmbeddedSubtitles() throws Exception {
        Path subtitles = directory.resolve("sub.srt"), movie = directory.resolve("movie.mkv");
        Files.writeString(subtitles, "1\n00:00:00,000 --> 00:00:03,000\nEmbedded subtitle survives casting\n", StandardCharsets.UTF_8);
        run("-f", "lavfi", "-i", "testsrc2=size=320x180:rate=20", "-i", subtitles.toString(), "-t", "8",
                "-c:v", "libx264", "-preset", "ultrafast", "-c:s", "srt", "-metadata:s:s:0", "language=zho", movie.toString());
        handler = request -> file(request, movie, "application/octet-stream");
        CastMediaRelay.Session session = open("/opaque");
        assertTrue(session.url(), session.url().endsWith(".mkv"));
        assertTrue(session.canByteSeek());
        assertEquals(Long.valueOf(Files.size(movie)), session.length());
        String frame = run("-i", session.url(), "-map", "0:v:0", "-frames:v", "1", "-f", "framehash", "-");
        assertTrue(frame, frame.contains("#dimensions 0: 320x180"));
        assertTrue(frame, frame.contains("#sar 0: 1/1"));
        String text = run("-i", session.url(), "-map", "0:s:0", "-f", "srt", "-");
        assertTrue(text, text.contains("Embedded subtitle survives casting"));
    }
}
