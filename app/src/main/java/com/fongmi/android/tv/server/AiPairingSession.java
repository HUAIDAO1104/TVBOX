package com.fongmi.android.tv.server;

import android.os.SystemClock;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.playback.vod.DanmakuAiMatcher;
import com.fongmi.android.tv.setting.DanmakuAiSetting;
import com.google.gson.JsonObject;
import java.security.SecureRandom;

/** One short-lived, TV-initiated configuration session. Never returns a saved credential. */
public final class AiPairingSession {
    public static final long LIFETIME_MS = 10 * 60 * 1000;
    private static AiPairingSession current;
    private final String token;
    private final String code;
    private int codeFailures;
    private volatile boolean connected;
    private final long expires = SystemClock.elapsedRealtime() + LIFETIME_MS;
    private final Runnable onSaved;
    private boolean closed;

    private AiPairingSession(Runnable onSaved) {
        this.onSaved = onSaved;
        byte[] bytes = new byte[24]; new SecureRandom().nextBytes(bytes);
        StringBuilder value = new StringBuilder();
        for (byte b : bytes) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        token = value.toString();
        code = String.format(java.util.Locale.ROOT, "%08d", new SecureRandom().nextInt(100_000_000));
    }
    public static synchronized AiPairingSession open(Runnable onSaved) {
        if (current != null) current.closed = true;
        return current = new AiPairingSession(onSaved);
    }
    public String token() { return token; }
    public String code() { return code; }
    public boolean connected() { return connected; }
    /** Browser fallback, bounded across clients for this TV-created session. QR access stays usable. */
    public static synchronized String pair(String code) {
        if (current == null || find(current.token) == null) throw new IllegalArgumentException("expired");
        if (current.codeFailures >= 5) throw new IllegalArgumentException("pairing_locked");
        if (code == null || !current.code.equals(code)) {
            current.codeFailures++;
            throw new IllegalArgumentException(current.codeFailures >= 5 ? "pairing_locked" : "pairing_code");
        }
        return current.token;
    }
    public static synchronized AiPairingSession find(String token) {
        return current != null && !current.closed && SystemClock.elapsedRealtime() < current.expires
                && current.token.equals(token) ? current : null;
    }
    public void close() { synchronized (AiPairingSession.class) { closed = true; if (current == this) current = null; } }
    public JsonObject config() {
        connected = true;
        JsonObject value = new JsonObject();
        value.addProperty("baseUrl", DanmakuAiSetting.baseUrl()); value.addProperty("model", DanmakuAiSetting.model());
        value.addProperty("hasKey", DanmakuAiSetting.hasKey()); value.addProperty("enabled", DanmakuAiSetting.enabled());
        return value;
    }
    public void save(String base, String model, String key, boolean enabled) {
        synchronized (AiPairingSession.class) {
            if (find(token) != this) throw new IllegalStateException("expired");
            if (key.length() > 4096 || key.contains("\n") || key.contains("\r")) throw new IllegalArgumentException("key");
            DanmakuAiSetting.save(base, model, key, enabled);
            closed = true; current = null;
        }
        DanmakuAiMatcher.cancel(); App.post(onSaved);
    }
}
