package com.fongmi.android.tv.cloud;

import android.text.TextUtils;

import com.github.catvod.utils.Prefers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CloudCredentialPreferences {

    private static final Map<String, List<String>> KEYS = keys();
    private static final String RUNTIME_KEYS = "cloud_runtime_pref_keys";

    private CloudCredentialPreferences() {
    }

    public static void syncForExt(String ext, Map<String, String> credentials) {
        if (TextUtils.isEmpty(ext) || credentials == null || credentials.isEmpty()) return;
        String lower = ext.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> credential : credentials.entrySet()) {
            String provider = credential.getKey();
            String value = credential.getValue();
            if (TextUtils.isEmpty(value) || !references(provider, lower)) continue;
            for (String key : keys(provider)) if (lower.contains(key.toLowerCase(Locale.ROOT))) putRuntime(key, value);
        }
    }

    public static synchronized void clear(String provider) {
        android.content.SharedPreferences.Editor editor = Prefers.getPrefers().edit();
        for (String key : keys(provider)) {
            Prefers.removeRuntimeString(key);
            editor.remove(key);
        }
        editor.apply();
    }

    public static synchronized void clearRuntime() {
        // Remove values written by older builds before switching to the
        // process-only bridge.  New runtime credentials are never persisted.
        String legacyKeys = Prefers.getPrefers().getString(RUNTIME_KEYS, "");
        android.content.SharedPreferences.Editor editor = Prefers.getPrefers().edit();
        if (!legacyKeys.isEmpty()) for (String key : legacyKeys.split("\n")) if (!key.isBlank()) editor.remove(key);
        editor.remove(RUNTIME_KEYS).apply();
        Prefers.clearRuntimeStrings();
    }

    public static boolean references(String provider, String value) {
        if (value == null || value.isEmpty()) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("${cloud." + placeholderName(provider))) return true;
        for (String key : keys(provider)) if (lower.contains(key.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    public static boolean referencesLegacyKey(String provider, String value) {
        if (value == null || value.isEmpty()) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        for (String key : keys(provider)) if (lower.contains(key.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    public static List<String> keys(String provider) {
        return KEYS.getOrDefault(provider, List.of());
    }

    private static synchronized void putRuntime(String key, String value) {
        Prefers.putRuntimeString(key, value);
    }

    private static String placeholderName(String provider) {
        return CloudProvider.ALI.equals(provider) ? "aliyun" : provider;
    }

    private static Map<String, List<String>> keys() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put(CloudProvider.QUARK, List.of("quark_cookie", "quarkCookie", "cookie_quark", "cookieQuark"));
        map.put(CloudProvider.UC, List.of("uc_cookie", "ucCookie", "cookie_uc", "cookieUC"));
        map.put(CloudProvider.ALI, List.of("ali_token", "aliToken", "ali_refresh_token", "aliRefreshToken", "aliyun_refresh_token"));
        map.put(CloudProvider.BAIDU, List.of("baidu_cookie", "baiduCookie", "cookie_baidu"));
        map.put(CloudProvider.TIANYI, List.of("tianyi_cookie", "tianyiCookie", "cloud189_cookie"));
        map.put(CloudProvider.THUNDER, List.of("thunder_token", "thunderToken", "xunlei_token", "xunlei_cookie"));
        return map;
    }
}
