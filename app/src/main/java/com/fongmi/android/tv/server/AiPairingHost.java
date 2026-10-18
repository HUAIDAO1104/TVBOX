package com.fongmi.android.tv.server;

import com.fongmi.android.tv.App;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import fi.iki.elonen.NanoHTTPD;

/** The open pairing window owns this listener, independently of playback and plugin cleanup. */
public final class AiPairingHost implements AutoCloseable {
    public enum Status { READY, NO_NETWORK, FAILED }
    public record State(String address, Status status) { }
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "ai-pairing-host"); thread.setDaemon(true); return thread;
    });
    private final AtomicBoolean checking = new AtomicBoolean();
    private volatile boolean closed;
    private volatile Listener listener;
    private String verifiedAddress = "";
    private long verifiedAt;

    public void check(Consumer<State> callback) {
        if (closed || !checking.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                try { State state = checkNow(); if (!closed) callback.accept(state); }
                finally { checking.set(false); }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) { checking.set(false); }
    }

    private State checkNow() {
        String ip = LanAddress.current();
        if (closed || ip.isEmpty()) return new State("", Status.NO_NETWORK);
        for (int attempt = 0; attempt < 2 && !closed; attempt++) {
            try {
                Listener active = listener;
                if (active == null || !active.isAlive()) {
                    if (active != null) active.stop();
                    verifiedAddress = "";
                    listener = active = new Listener();
                    active.start(10_000);
                    if (closed) { active.stop(); break; }
                }
                String address = "http://" + ip + ":" + active.getListeningPort() + "/ai-setup";
                long now = android.os.SystemClock.elapsedRealtime();
                if (!address.equals(verifiedAddress) || now - verifiedAt >= 10_000) {
                    verify(active, address);
                    verifiedAddress = address; verifiedAt = now;
                }
                return new State(address, Status.READY);
            } catch (Exception error) {
                // One bounded restart repairs a dead listener. Never publish an unchecked URL.
                verifiedAddress = "";
                Listener failed = listener;
                if (failed != null) failed.stop();
                listener = null;
            }
        }
        return new State("", Status.FAILED);
    }

    private void verify(Listener active, String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection(Proxy.NO_PROXY);
        try {
            connection.setConnectTimeout(1000); connection.setReadTimeout(1000);
            connection.setUseCaches(false); connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Connection", "close");
            if (connection.getResponseCode() != 200 || !active.identity.equals(connection.getHeaderField("X-Pairing-Host")))
                throw new java.io.IOException("pairing_page_unavailable");
            try (var input = connection.getInputStream()) {
                if (input.read() < 0) throw new java.io.IOException("empty_pairing_page");
            }
        } finally { connection.disconnect(); }
    }

    @Override public void close() {
        closed = true; worker.shutdownNow();
        Listener active = listener;
        if (active != null) active.stop();
    }

    private static final class Listener extends NanoHTTPD {
        final String identity = UUID.randomUUID().toString();
        // The OS assigns a free port; main-process and search-worker ports cannot collide.
        Listener() { super("0.0.0.0", 0); }
        @Override public Response serve(IHTTPSession request) {
            Response response;
            if (request.getMethod() == Method.GET && request.getUri().equals("/css/ui.css")) {
                try {
                    response = newChunkedResponse(Response.Status.OK, "text/css; charset=utf-8", App.get().getAssets().open("css/ui.css"));
                    response.addHeader("Cache-Control", "no-store"); response.addHeader("Connection", "close");
                } catch (Exception error) { response = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "unavailable"); }
            } else if (request.getUri().equals("/ai-setup") || request.getUri().startsWith("/ai-setup/")) {
                response = AiSetup.serve(request);
            } else response = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not_found");
            response.addHeader("X-Pairing-Host", identity);
            return response;
        }
    }
}
