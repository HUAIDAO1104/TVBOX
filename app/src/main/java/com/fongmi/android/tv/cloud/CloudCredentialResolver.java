package com.fongmi.android.tv.cloud;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Centralized, side-effect free credential precedence resolver. */
public final class CloudCredentialResolver {

    private CloudCredentialResolver() {
    }

    public static Map<String, String> resolve(String configuration, Map<String, String> appAccounts, CloudCredentialPriority priority) {
        Map<String, String> result = new LinkedHashMap<>();
        Map<String, String> configValues = extract(configuration);
        for (CloudProvider provider : CloudProvider.ALL) {
            if (!CloudCredentialPreferences.references(provider.id(), configuration)
                    && !configValues.containsKey(provider.id())) continue;
            String app = clean(appAccounts == null ? null : appAccounts.get(provider.id()));
            String config = clean(configValues.get(provider.id()));
            String selected = priority == CloudCredentialPriority.CURRENT_CONFIG_FIRST
                    ? first(config, app)
                    : first(app, config);
            if (!selected.isEmpty()) result.put(provider.id(), selected);
        }
        return result;
    }

    static Map<String, String> extract(String value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value == null || value.isBlank()) return result;
        try {
            JsonElement root = JsonParser.parseString(value);
            extractJson(root, result);
        } catch (Throwable ignored) {
            extractText(value, result);
        }
        return result;
    }

    private static void extractJson(JsonElement element, Map<String, String> output) {
        if (element == null || element.isJsonNull() || element.isJsonPrimitive()) return;
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) extractJson(child, output);
            return;
        }
        JsonObject object = element.getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String provider = provider(entry.getKey());
            if (provider != null && entry.getValue().isJsonPrimitive()) {
                String credential = clean(entry.getValue().getAsString());
                if (!credential.isEmpty()) output.putIfAbsent(provider, credential);
            }
            extractJson(entry.getValue(), output);
        }
    }

    private static void extractText(String text, Map<String, String> output) {
        for (CloudProvider provider : CloudProvider.ALL) {
            for (String key : CloudCredentialPreferences.keys(provider.id())) {
                Pattern pattern = Pattern.compile("(?im)^\\s*[\"']?" + Pattern.quote(key) + "[\"']?\\s*[:=]\\s*[\"']?([^\\r\\n\"']+)");
                Matcher matcher = pattern.matcher(text);
                if (!matcher.find()) continue;
                String credential = clean(matcher.group(1));
                if (!credential.isEmpty()) output.putIfAbsent(provider.id(), credential);
            }
        }
    }

    private static String provider(String key) {
        for (CloudProvider provider : CloudProvider.ALL) {
            for (String alias : CloudCredentialPreferences.keys(provider.id())) {
                if (alias.equalsIgnoreCase(key)) return provider.id();
            }
        }
        return null;
    }

    private static String clean(String value) {
        if (value == null) return "";
        String clean = value.trim();
        if (clean.isEmpty() || clean.contains("${cloud.")) return "";
        return clean;
    }

    private static String first(String primary, String fallback) {
        return primary == null || primary.isEmpty() ? (fallback == null ? "" : fallback) : primary;
    }
}
