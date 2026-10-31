package com.fongmi.android.tv.server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.Call;
import okhttp3.Dispatcher;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Streams the phone's authenticated media to ordinary DLNA renderers. No Android dependencies. */
public final class CastMediaRelay {
    public static final String PREFIX = "/cast-media/";
    private static final int MAX_PLAYLIST_BYTES = 2 * 1024 * 1024;
    private static final int MAX_RESOURCES = 8192;
    private static final Pattern URI_ATTRIBUTE = Pattern.compile("(?i)(\\bURI\\s*=\\s*\")([^\"]*)(\")");
    private static final Set<String> OMIT_HEADERS = Set.of("host", "connection", "content-length",
            "transfer-encoding", "accept-encoding", "range", "if-range", "keep-alive", "te", "trailer",
            "upgrade", "proxy-authorization", "proxy-connection");
    private final OkHttpClient client;
    private volatile Session current;

    public CastMediaRelay(OkHttpClient playerClient) {
        // Independent calls share the player's DNS, proxy, interceptors and connection pool.
        client = playerClient.newBuilder().dispatcher(new Dispatcher()).callTimeout(0, TimeUnit.MILLISECONDS).build();
    }

    public synchronized Session open(String address, String url, Map<String, String> headers) {
        HttpUrl source = HttpUrl.parse(url);
        if (source == null) throw new IllegalArgumentException("当前视频地址不支持 DLNA 投屏");
        if (HttpUrl.parse(address) == null) throw new IllegalArgumentException("请连接与电视相同的局域网后重试");
        stop();
        current = new Session(address, source, headers);
        return current;
    }

    /** Check the same authenticated source before telling the phone player to stop. */
    public void prepare(Session session) throws IOException {
        Call call = request(session, session.source, "GET", Collections.emptyMap());
        try (Response response = call.execute()) {
            if (!response.isSuccessful() || response.body() == null)
                throw new IOException("投屏视频源暂不可用（HTTP " + response.code() + "）");
            session.mime = contentType(response);
            if (session.mime.equals("text/html") || session.mime.contains("json"))
                throw new IOException("视频源返回了错误页面，请更换线路后重试");
            if (session != current) throw new IOException("投屏已取消");
        } finally {
            session.calls.remove(call);
            call.cancel();
        }
    }

    public synchronized void close(Session session) {
        if (session == current) stop();
    }

    public synchronized void stop() {
        Session previous = current;
        current = null;
        if (previous != null) {
            for (Call call : previous.calls) call.cancel();
            previous.calls.clear();
            previous.clear();
        }
    }

    public boolean isActive() { return current != null; }

    public boolean isIdle(long timeoutMs) {
        Session session = current;
        return session == null || (session.calls.isEmpty()
                && TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - session.lastAccess) > timeoutMs);
    }

    public NanoHTTPD.Response serve(NanoHTTPD.IHTTPSession request) {
        Session session = current;
        String[] path = request.getUri().split("/", 5);
        if (session == null || path.length < 4 || !path[2].equals(session.token)) return error(404, "Cast session expired");
        HttpUrl source = session.resource(path[3]);
        if (source == null) return error(404, "Unknown media resource");
        String method = request.getMethod().name();
        if (!method.equals("GET") && !method.equals("HEAD")) return error(405, "Method not allowed");
        session.lastAccess = System.nanoTime();
        Call call = null;
        Response upstream = null;
        try {
            boolean head = method.equals("HEAD");
            boolean hls = mimeFor(source).contains("mpegurl") || (path[3].equals("0") && session.mime.contains("mpegurl"));
            call = request(session, source, head && hls ? "GET" : method, request.getHeaders());
            upstream = call.execute();
            // Some media CDNs reject HEAD although GET works. Never buffer the fallback body.
            if (method.equals("HEAD") && (upstream.code() == 405 || upstream.code() == 501)) {
                upstream.close();
                session.calls.remove(call);
                call = request(session, source, "GET", request.getHeaders());
                upstream = call.execute();
            }
            if (session != current) throw new IOException("Cast cancelled");
            ResponseBody body = upstream.body();
            if (body == null) throw new IOException("Empty upstream response");
            boolean hasBody = upstream.request().method().equals("GET");
            String mime = hasBody ? contentType(upstream) : headerType(upstream);
            boolean playlist = hasBody && upstream.isSuccessful() && mime.equals("application/vnd.apple.mpegurl");
            NanoHTTPD.Response result;
            if (playlist) {
                byte[] bytes = rewrite(session, upstream.request().url(), readPlaylist(body.byteStream())).getBytes(StandardCharsets.UTF_8);
                result = NanoHTTPD.newFixedLengthResponse(status(200), mime,
                        new ByteArrayInputStream(head ? new byte[0] : bytes), bytes.length);
                result.addHeader("Cache-Control", "no-store");
            } else if (head) {
                // Supply an empty stream explicitly: NanoHTTPD 2.3.1 can send fixed bodies on HEAD.
                result = headResponse(upstream, mime);
                copyHeaders(upstream, result);
            } else {
                final Response ownedResponse = upstream;
                final Call ownedCall = call;
                InputStream stream = new FilterInputStream(body.byteStream()) {
                    @Override public int read(byte[] b, int off, int len) throws IOException {
                        int count = super.read(b, off, len);
                        if (count > 0 && ownedResponse.isSuccessful()) session.markMediaRead();
                        return count;
                    }
                    @Override public void close() throws IOException {
                        try { super.close(); }
                        finally {
                            ownedResponse.close();
                            ownedCall.cancel();
                            session.calls.remove(ownedCall);
                            session.lastAccess = System.nanoTime();
                        }
                    }
                };
                long length = body.contentLength();
                result = length >= 0 ? NanoHTTPD.newFixedLengthResponse(status(upstream.code()), mime, stream, length)
                        : NanoHTTPD.newChunkedResponse(status(upstream.code()), mime, stream);
                copyHeaders(upstream, result);
                // The server owns this stream until completion or client disconnect.
                upstream = null;
                call = null;
            }
            result.addHeader("transferMode.dlna.org", "Streaming");
            boolean byteSeek = "bytes".equalsIgnoreCase(result.getHeader("Accept-Ranges")) || result.getHeader("Content-Range") != null;
            result.addHeader("contentFeatures.dlna.org", "DLNA.ORG_OP=" + (byteSeek ? "01" : "00") + ";DLNA.ORG_CI=0");
            return result;
        } catch (Exception e) {
            // Do not expose source URLs, cookies or authorization headers to LAN clients/logs.
            return error(502, "Media source unavailable");
        } finally {
            if (upstream != null) upstream.close();
            if (call != null) { call.cancel(); session.calls.remove(call); }
        }
    }

    private Call request(Session session, HttpUrl url, String method, Map<String, String> incoming) throws IOException {
        if (session != current) throw new IOException("Cast cancelled");
        Request.Builder request = new Request.Builder().url(url).method(method, null);
        for (Map.Entry<String, String> header : session.headers.entrySet()) {
            if (!OMIT_HEADERS.contains(header.getKey().toLowerCase(Locale.ROOT))) request.header(header.getKey(), header.getValue());
        }
        request.header("Accept-Encoding", "identity");
        // Plugin HTTP servers can incorrectly send a body on HEAD. Do not reuse that socket.
        if (method.equals("HEAD")) request.header("Connection", "close");
        // The receiver's range wins over the phone player's previous playback position.
        boolean playlist = mimeFor(url).contains("mpegurl") || (url.equals(session.source) && session.mime.contains("mpegurl"));
        for (String name : new String[]{"range", "if-range"}) {
            String value = incoming.get(name);
            // Rewritten manifests have different offsets/lengths from their upstream representation.
            if (value != null && !playlist) request.header(name, value);
        }
        Call call = client.newCall(request.build());
        session.calls.add(call);
        if (session != current) { session.calls.remove(call); call.cancel(); throw new IOException("Cast cancelled"); }
        return call;
    }

    private static String contentType(Response response) throws IOException {
        ResponseBody body = response.body();
        if (body != null && body.source().request(7)) {
            String prefix = body.source().buffer().clone().readUtf8(7);
            if (prefix.startsWith("#EXTM3U")) return "application/vnd.apple.mpegurl";
        }
        return headerType(response);
    }

    private static NanoHTTPD.Response headResponse(Response response, String mime) {
        long length = -1;
        try { length = Long.parseLong(response.header("Content-Length", "-1")); }
        catch (NumberFormatException ignored) { }
        InputStream empty = new ByteArrayInputStream(new byte[0]);
        if (length >= 0) return NanoHTTPD.newFixedLengthResponse(status(response.code()), mime, empty, length);
        // Unknown size is not a zero-byte video. HEAD is allowed to omit Content-Length.
        return new NanoHTTPD.Response(status(response.code()), mime, empty, 0) {
            @Override protected long sendContentLengthHeaderIfNotAlreadyPresent(PrintWriter writer, long size) { return 0; }
        };
    }

    private static String headerType(Response response) {
        String type = response.header("Content-Type", "").split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (type.contains("mpegurl")) return "application/vnd.apple.mpegurl";
        if (!type.isEmpty() && !type.equals("application/octet-stream")) return type;
        return mimeFor(response.request().url());
    }

    private static String mimeFor(HttpUrl url) {
        String path = url.encodedPath().toLowerCase(Locale.ROOT);
        if (path.endsWith(".m3u8") || path.endsWith(".m3u")) return "application/vnd.apple.mpegurl";
        if (path.endsWith(".ts")) return "video/mp2t";
        if (path.endsWith(".mkv")) return "video/x-matroska";
        if (path.endsWith(".webm")) return "video/webm";
        if (path.endsWith(".vtt")) return "text/vtt";
        if (path.endsWith(".aac")) return "audio/aac";
        if (path.endsWith(".key")) return "application/octet-stream";
        return "video/mp4";
    }

    private static byte[] readPlaylist(InputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            if (out.size() + count > MAX_PLAYLIST_BYTES) throw new IOException("Playlist too large");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    private static String rewrite(Session session, HttpUrl base, byte[] bytes) throws IOException {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        if (!text.trim().startsWith("#EXTM3U")) throw new IOException("Invalid HLS playlist");
        StringBuilder output = new StringBuilder();
        for (String line : text.split("\\r?\\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                Matcher matcher = URI_ATTRIBUTE.matcher(line);
                StringBuffer replaced = new StringBuffer();
                while (matcher.find()) matcher.appendReplacement(replaced, Matcher.quoteReplacement(matcher.group(1)
                        + relayUri(session, base, matcher.group(2)) + matcher.group(3)));
                matcher.appendTail(replaced);
                output.append(replaced);
            } else if (!trimmed.isEmpty()) output.append(relayUri(session, base, trimmed));
            output.append('\n');
        }
        return output.toString();
    }

    private static String relayUri(Session session, HttpUrl base, String value) throws IOException {
        // Inline keys and DRM schemes are not HTTP resources and must retain their semantics.
        if (value.matches("(?i)^[a-z][a-z0-9+.-]*:.*") && !value.startsWith("http://") && !value.startsWith("https://")) return value;
        HttpUrl resolved = base.resolve(value);
        if (resolved == null) throw new IOException("Invalid HLS resource");
        return session.register(resolved);
    }

    private static void copyHeaders(Response from, NanoHTTPD.Response to) {
        for (String name : new String[]{"Content-Range", "Accept-Ranges", "ETag", "Last-Modified", "Content-Encoding"}) {
            String value = from.header(name);
            if (value != null) to.addHeader(name, value);
        }
    }

    private static NanoHTTPD.Response error(int code, String message) {
        return NanoHTTPD.newFixedLengthResponse(status(code), "text/plain", message);
    }

    private static NanoHTTPD.Response.IStatus status(int code) {
        NanoHTTPD.Response.Status known = NanoHTTPD.Response.Status.lookup(code);
        if (known != null) return known;
        return new NanoHTTPD.Response.IStatus() {
            @Override public int getRequestStatus() { return code; }
            @Override public String getDescription() { return code + " Media response"; }
        };
    }

    public static final class Session {
        private final String token = UUID.randomUUID().toString();
        private final String address;
        private final HttpUrl source;
        private final Map<String, String> headers;
        private final Map<String, HttpUrl> resources = new LinkedHashMap<>();
        private final Map<HttpUrl, String> ids = new HashMap<>();
        private final Set<Call> calls = ConcurrentHashMap.newKeySet();
        private final CountDownLatch mediaRead = new CountDownLatch(1);
        private volatile boolean receivedMedia;
        private volatile long lastAccess = System.nanoTime();
        private volatile String mime;
        private int nextId;

        private Session(String address, HttpUrl source, Map<String, String> headers) {
            this.address = address.replaceAll("/+$", "");
            this.source = source;
            this.headers = headers == null ? Collections.emptyMap() : new HashMap<>(headers);
            this.mime = mimeFor(source);
            register(source);
        }

        public String url() { return address + PREFIX + token + "/0/" + (mime.contains("mpegurl") ? "video.m3u8" : "video.mp4"); }
        public String mimeType() { return mime; }
        public boolean awaitMedia(long timeoutMs) throws InterruptedException {
            return mediaRead.await(timeoutMs, TimeUnit.MILLISECONDS) && receivedMedia;
        }
        private void markMediaRead() { receivedMedia = true; mediaRead.countDown(); }

        private synchronized HttpUrl resource(String id) { return resources.get(id); }

        private synchronized String register(HttpUrl url) {
            String id = ids.get(url);
            if (id == null) {
                if (resources.size() >= MAX_RESOURCES) {
                    String oldest = resources.keySet().stream().filter(key -> !key.equals("0")).findFirst().get();
                    ids.remove(resources.remove(oldest));
                }
                id = Integer.toString(nextId++);
                resources.put(id, url);
                ids.put(url, id);
            }
            String suffix = url.encodedPath().toLowerCase(Locale.ROOT).endsWith(".m3u8") ? "index.m3u8" : "media";
            return address + PREFIX + token + "/" + id + "/" + suffix;
        }

        private synchronized void clear() { resources.clear(); ids.clear(); mediaRead.countDown(); }
    }
}
