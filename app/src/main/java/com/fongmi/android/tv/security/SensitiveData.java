package com.fongmi.android.tv.security;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.github.catvod.utils.SecretRedactor;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SensitiveData {

    private static final Set<String> MARKERS = Set.of(
            "cookie", "token", "authorization", "credential", "secret", "password", "passwd",
            "refresh_token", "accesstoken", "cloud.quark", "cloud.uc", "cloud.aliyun",
            "cloud.baidu", "cloud.tianyi", "cloud.thunder", "quark_cookie", "uc_cookie",
            "ali_token", "baidu_cookie", "tianyi_cookie", "thunder_token"
    );

    private SensitiveData() {
    }

    public static boolean isSensitiveKey(String key) {
        if (key == null) return false;
        String normalized = key.toLowerCase(Locale.ROOT).replace('-', '_');
        for (String marker : MARKERS) if (normalized.contains(marker)) return true;
        return false;
    }

    public static Map<String, ?> filterPreferences(Map<String, ?> preferences) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (preferences == null) return safe;
        for (Map.Entry<String, ?> entry : preferences.entrySet()) {
            if (!isSensitiveKey(entry.getKey())) safe.put(entry.getKey(), entry.getValue());
        }
        return safe;
    }

    public static String sanitizeJson(String json) {
        if (json == null || json.isEmpty()) return json == null ? "" : json;
        try {
            JsonElement root = JsonParser.parseString(json);
            sanitize(root);
            return root.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void sanitize(JsonElement element) {
        if (element == null || element.isJsonNull() || element.isJsonPrimitive()) return;
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int index = 0; index < array.size(); index++) {
                JsonElement child = array.get(index);
                if (child.isJsonPrimitive() && child.getAsJsonPrimitive().isString()) {
                    array.set(index, new JsonPrimitive(sanitizeString(child.getAsString())));
                } else {
                    sanitize(child);
                }
            }
            return;
        }
        JsonObject object = element.getAsJsonObject();
        for (String key : Set.copyOf(object.keySet())) {
            if (isSensitiveKey(key)) object.remove(key);
            else {
                JsonElement child = object.get(key);
                if (child != null && child.isJsonPrimitive() && child.getAsJsonPrimitive().isString()) {
                    object.addProperty(key, sanitizeString(child.getAsString()));
                } else {
                    sanitize(child);
                }
            }
        }
    }

    private static String sanitizeString(String value) {
        if (value == null || value.isEmpty()) return value == null ? "" : value;
        String trimmed = value.trim();
        if ((trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            try {
                JsonElement nested = JsonParser.parseString(trimmed);
                sanitize(nested);
                return nested.toString();
            } catch (Throwable ignored) {
            }
        }
        return SecretRedactor.redact(value);
    }
}
