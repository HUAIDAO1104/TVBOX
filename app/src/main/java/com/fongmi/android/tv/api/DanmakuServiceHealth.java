package com.fongmi.android.tv.api;

import java.io.IOException;
import java.net.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Brief, process-local backoff after repeated endpoint outages; empty searches are not outages. */
final class DanmakuServiceHealth {
    private record State(int failures, long retryAt, IOException error) {}
    private static final Map<String, State> states = new LinkedHashMap<>();
    static synchronized IOException unavailable(String source, long now) {
        State state = states.get(source);
        return state != null && now < state.retryAt ? state.error : null;
    }
    static synchronized void success(String source) { states.remove(source); }
    static synchronized void failure(String source, IOException error, long now) {
        // Never back off identity/no-results responses, authentication, or cancellation.
        boolean transport = error instanceof DanmakuCatalogueSearch.HttpFailure http
                && (http.code == 408 || http.code == 429 || http.code >= 500);
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            transport |= cause instanceof SocketTimeoutException || cause instanceof UnknownHostException
                    || cause instanceof ConnectException || cause instanceof javax.net.ssl.SSLException
                    || cause instanceof java.io.InterruptedIOException && "timeout".equals(cause.getMessage());
        }
        if (!transport) return;
        State previous = states.get(source);
        int count = previous == null ? 1 : previous.failures + 1;
        states.put(source, new State(count, count >= 2 ? now + TimeUnit.SECONDS.toNanos(60) : 0, error));
        while (states.size() > 64) states.remove(states.keySet().iterator().next());
    }
    private DanmakuServiceHealth() {}
}
