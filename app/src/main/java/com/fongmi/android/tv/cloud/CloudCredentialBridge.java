package com.fongmi.android.tv.cloud;

import android.text.TextUtils;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class CloudCredentialBridge {

    private static final String CLOUD_DRIVE = "Cloud-drive";
    private static final Map<String, String> PLACEHOLDERS = placeholders();
    private static final Map<String, String> ALIASES = aliases();

    private CloudCredentialBridge() {
    }

    public static String resolve(String ext) {
        if (TextUtils.isEmpty(ext)) return ext;
        Map<String, String> appAccounts = appCredentials();
        Map<String, String> credentials = CloudCredentialResolver.resolve(ext, appAccounts, CloudCredentialPriority.get());
        if (credentials.isEmpty() && appAccounts.isEmpty()) return ext;
        CloudCredentialPreferences.syncForExt(ext, credentials);
        String replaced = replacePlaceholders(ext, credentials);
        try {
            JsonElement parsed = JsonParser.parseString(replaced);
            if (!parsed.isJsonObject()) return replaced;
            JsonObject object = parsed.getAsJsonObject();
            overlayAliases(object, credentials);
            if (object.has(CLOUD_DRIVE) && object.get(CLOUD_DRIVE).isJsonPrimitive()) {
                String reference = object.get(CLOUD_DRIVE).getAsString();
                object.addProperty(CLOUD_DRIVE, createRuntimeCloudDrive(reference, appAccounts));
            }
            return object.toString();
        } catch (Throwable ignored) {
            return replaced;
        }
    }

    public static void clear() {
        CloudMemoryStore.clear();
        Path.clear(new File(com.fongmi.android.tv.App.get().getNoBackupFilesDir(), "runtime_cloud_overlay"));
    }

    public static void clearProvider(String provider) {
        CloudCredentialPreferences.clear(provider);
        clear();
    }

    public static String replacePlaceholders(String value, Map<String, String> credentials) {
        String result = value == null ? "" : value;
        for (Map.Entry<String, String> entry : PLACEHOLDERS.entrySet()) {
            String credential = credentials.get(entry.getValue());
            if (!isEmpty(credential)) result = result.replace(entry.getKey(), credential);
        }
        return result;
    }

    private static void overlayAliases(JsonObject object, Map<String, String> credentials) {
        boolean currentConfigFirst = CloudCredentialPriority.get() == CloudCredentialPriority.CURRENT_CONFIG_FIRST;
        for (String key : object.keySet()) {
            String provider = ALIASES.get(key.toLowerCase(Locale.ROOT));
            if (provider == null || TextUtils.isEmpty(credentials.get(provider))) continue;
            if (currentConfigFirst && hasValue(object, key)) continue;
            object.addProperty(key, credentials.get(provider));
        }
        putIfPresent(object, "quark_cookie", CloudProvider.QUARK, credentials, currentConfigFirst);
        putIfPresent(object, "uc_cookie", CloudProvider.UC, credentials, currentConfigFirst);
        putIfPresent(object, "ali_token", CloudProvider.ALI, credentials, currentConfigFirst);
        putIfPresent(object, "ali_refresh_token", CloudProvider.ALI, credentials, currentConfigFirst);
        putIfPresent(object, "baidu_cookie", CloudProvider.BAIDU, credentials, currentConfigFirst);
        putIfPresent(object, "tianyi_cookie", CloudProvider.TIANYI, credentials, currentConfigFirst);
        putIfPresent(object, "thunder_token", CloudProvider.THUNDER, credentials, currentConfigFirst);
    }

    private static void putIfPresent(JsonObject object, String key, String provider, Map<String, String> credentials, boolean currentConfigFirst) {
        String credential = credentials.get(provider);
        if (currentConfigFirst && hasProviderValue(object, provider)) return;
        if (!TextUtils.isEmpty(credential)) object.addProperty(key, credential);
    }

    private static boolean hasProviderValue(JsonObject object, String provider) {
        for (String key : object.keySet()) {
            if (!provider.equals(ALIASES.get(key.toLowerCase(Locale.ROOT)))) continue;
            if (hasValue(object, key)) return true;
        }
        return false;
    }

    private static boolean hasValue(JsonObject object, String key) {
        try {
            String value = object.get(key).getAsString();
            return !TextUtils.isEmpty(value) && !value.contains("${cloud.");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String createRuntimeCloudDrive(String reference, Map<String, String> appAccounts) throws Exception {
        String source = fetchCloudDrive(reference);
        Map<String, String> credentials = CloudCredentialResolver.resolve(source, appAccounts, CloudCredentialPriority.get());
        String content = replacePlaceholders(source, credentials);
        StringBuilder output = new StringBuilder(content == null ? "" : content.trim());
        for (Map.Entry<String, String> alias : canonicalAliases().entrySet()) {
            String credential = credentials.get(alias.getValue());
            if (TextUtils.isEmpty(credential)) continue;
            String key = alias.getKey();
            String current = output.toString();
            String replaced = current.replaceAll("(?im)^\\s*" + java.util.regex.Pattern.quote(key) + "\\s*[:=].*$", key + "=" + java.util.regex.Matcher.quoteReplacement(credential));
            if (replaced.equals(current)) {
                if (output.length() > 0) output.append('\n');
                output.append(key).append('=').append(credential);
            } else {
                output.setLength(0);
                output.append(replaced);
            }
        }
        CloudCredentialPreferences.syncForExt(output.toString(), credentials);
        return CloudMemoryStore.register(output.toString());
    }

    private static String fetchCloudDrive(String reference) {
        if (TextUtils.isEmpty(reference)) return "";
        try {
            String url = reference;
            if (!reference.contains("://")) {
                String config = VodConfig.get().getConfig().getUrl();
                if (!TextUtils.isEmpty(config)) url = UrlUtil.resolve(config, reference);
            }
            return url.startsWith("http") ? OkHttp.string(url) : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static Map<String, String> appCredentials() {
        Map<String, String> result = new LinkedHashMap<>();
        for (CloudProvider provider : CloudProvider.ALL) {
            String value = CloudAccountManager.credential(provider.id());
            if (!TextUtils.isEmpty(value)) result.put(provider.id(), value);
        }
        return result;
    }

    private static Map<String, String> placeholders() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("${cloud.quark.cookie}", CloudProvider.QUARK);
        map.put("${cloud.uc.cookie}", CloudProvider.UC);
        map.put("${cloud.aliyun.refreshToken}", CloudProvider.ALI);
        map.put("${cloud.baidu.cookie}", CloudProvider.BAIDU);
        map.put("${cloud.tianyi.cookie}", CloudProvider.TIANYI);
        map.put("${cloud.thunder.token}", CloudProvider.THUNDER);
        return map;
    }

    private static Map<String, String> aliases() {
        Map<String, String> map = canonicalAliases();
        for (CloudProvider provider : CloudProvider.ALL) {
            for (String key : CloudCredentialPreferences.keys(provider.id())) map.put(key.toLowerCase(Locale.ROOT), provider.id());
        }
        return map;
    }

    private static Map<String, String> canonicalAliases() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("quark_cookie", CloudProvider.QUARK);
        map.put("uc_cookie", CloudProvider.UC);
        map.put("ali_token", CloudProvider.ALI);
        map.put("ali_refresh_token", CloudProvider.ALI);
        map.put("baidu_cookie", CloudProvider.BAIDU);
        map.put("tianyi_cookie", CloudProvider.TIANYI);
        map.put("thunder_token", CloudProvider.THUNDER);
        return map;
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }
}
