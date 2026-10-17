package com.fongmi.android.tv.setting;

import android.content.Context;
import android.content.SharedPreferences;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.security.KeystoreCipher;
import okhttp3.HttpUrl;

/** Device-local configuration, deliberately outside exported app preferences. */
public final class DanmakuAiSetting {
    public static final String DEFAULT_BASE_URL = "https://llm-api.mcisaas.com/v1";
    public static final String DEFAULT_MODEL = "qwen3.5-flash";

    private static SharedPreferences prefs() {
        return App.get().getSharedPreferences("danmaku_ai_private", Context.MODE_PRIVATE);
    }

    public static String baseUrl() { return prefs().getString("base_url", DEFAULT_BASE_URL); }
    public static String model() { return prefs().getString("model", DEFAULT_MODEL); }
    public static boolean hasKey() { return !prefs().getString("credential", "").isEmpty(); }
    public static boolean enabled() { return prefs().getBoolean("enabled", false) && hasKey(); }
    public static String key() { return KeystoreCipher.decrypt(prefs().getString("credential", "")); }

    public static String normalizeBaseUrl(String value) {
        HttpUrl url = HttpUrl.parse(value.trim());
        if (url == null || !url.isHttps() || !url.username().isEmpty() || !url.password().isEmpty()
                || url.query() != null || url.fragment() != null) throw new IllegalArgumentException("base_url");
        return url.toString().replaceAll("/+$", "");
    }

    /** Must run off the UI thread: Android Keystore may touch disk. */
    public static void save(String baseUrl, String model, String replacementKey, boolean enabled) {
        String normalized = normalizeBaseUrl(baseUrl);
        if (model.isBlank() || model.length() > 120) throw new IllegalArgumentException("model");
        if (enabled && replacementKey.isBlank() && !hasKey()) throw new IllegalArgumentException("key");
        // A saved secret belongs to its endpoint. Changing hosts requires an explicit new key.
        if (replacementKey.isBlank() && hasKey() && !normalized.equals(baseUrl()))
            throw new IllegalArgumentException("key");
        var editor = prefs().edit().putString("base_url", normalized).putString("model", model.trim())
                .putBoolean("enabled", enabled);
        if (!replacementKey.isBlank()) editor.putString("credential", KeystoreCipher.encrypt(replacementKey.trim()));
        if (!editor.commit()) throw new IllegalStateException("save");
    }

    public static void clear() { prefs().edit().clear().apply(); }
}
