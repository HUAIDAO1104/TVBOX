package com.fongmi.android.tv.repository;

import com.fongmi.android.tv.bean.Repository;
import com.fongmi.android.tv.bean.RepositoryItem;
import com.fongmi.android.tv.security.PromotionFilter;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RepositoryParser {

    public static final int MAX_ITEMS = 500;

    private RepositoryParser() {
    }

    public static List<RepositoryItem> parse(Repository repository, String json) {
        JsonElement element = JsonParser.parseString(json);
        if (!element.isJsonObject()) throw new IllegalArgumentException("Repository root must be an object");
        JsonObject root = element.getAsJsonObject();
        List<RepositoryItem> result = root.has("urls") ? parseUrls(repository, root.get("urls")) : parseDirect(repository, root);
        if (result.isEmpty()) throw new IllegalArgumentException("Repository contains no usable configuration");
        return result;
    }

    private static List<RepositoryItem> parseUrls(Repository repository, JsonElement element) {
        if (!element.isJsonArray()) throw new IllegalArgumentException("Repository urls must be an array");
        JsonArray array = element.getAsJsonArray();
        if (array.size() > MAX_ITEMS) throw new IllegalArgumentException("Repository exceeds " + MAX_ITEMS + " items");
        Map<String, RepositoryItem> unique = new LinkedHashMap<>();
        int order = 0;
        for (JsonElement entry : array) {
            RepositoryItem item = parseItem(repository, entry, order++);
            if (item == null) continue;
            unique.putIfAbsent(item.getType() + "\n" + item.getUrl(), item);
        }
        return new ArrayList<>(unique.values());
    }

    private static RepositoryItem parseItem(Repository repository, JsonElement element, int order) {
        String url;
        String name;
        String itemId = "";
        int type = 0;
        boolean enabled = true;
        int sortOrder = order;
        if (element.isJsonPrimitive()) {
            url = element.getAsString();
            name = url;
        } else if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            url = string(object, "url");
            name = string(object, "name");
            itemId = string(object, "id");
            type = integer(object, "type", 0);
            enabled = bool(object, "enabled", true);
            sortOrder = integer(object, "order", order);
        } else {
            return null;
        }
        if (isEmpty(url)) return null;
        url = resolve(repository.getUrl(), url);
        if (isEmpty(name)) name = name(url);
        name = PromotionFilter.sanitizeSourceLabel(name, name(url));
        if (isEmpty(itemId)) itemId = Integer.toHexString((type + "\n" + url).hashCode());
        return create(repository.getId(), itemId, name, url, type, sortOrder, enabled);
    }

    private static List<RepositoryItem> parseDirect(Repository repository, JsonObject root) {
        List<RepositoryItem> result = new ArrayList<>();
        if (!root.has("sites") && !root.has("spider")) return result;
        String name = string(root, "name");
        if (isEmpty(name)) name = repository.getName();
        name = PromotionFilter.sanitizeSourceLabel(name, repository.getName());
        result.add(create(repository.getId(), root.has("spider") ? "spider-direct" : "direct", name, repository.getUrl(), 0, 0, true));
        return result;
    }

    private static RepositoryItem create(long repositoryId, String itemId, String name, String url, int type, int order, boolean enabled) {
        long now = System.currentTimeMillis();
        RepositoryItem item = new RepositoryItem();
        item.setRepositoryId(repositoryId);
        item.setItemId(itemId);
        item.setName(name);
        item.setUrl(url);
        item.setType(type);
        item.setSortOrder(order);
        item.setEnabled(enabled);
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        return item;
    }

    private static String resolve(String base, String reference) {
        if (reference.contains("://") || reference.startsWith("file:") || reference.startsWith("assets:")) return reference;
        try {
            return URI.create(base).resolve(reference).toString();
        } catch (Exception e) {
            return reference;
        }
    }

    private static String name(String url) {
        try {
            URI uri = URI.create(url);
            String path = uri.getPath();
            if (path != null && !path.isEmpty()) {
                int slash = path.lastIndexOf('/');
                String name = path.substring(slash + 1);
                if (!name.isEmpty()) return name;
            }
            if (uri.getHost() != null && !uri.getHost().isEmpty()) return uri.getHost();
        } catch (Exception ignored) {
        }
        return url;
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    private static String string(JsonObject object, String key) {
        try {
            return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString().trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static int integer(JsonObject object, String key, int fallback) {
        try {
            return object.has(key) ? object.get(key).getAsInt() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        try {
            return object.has(key) ? object.get(key).getAsBoolean() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }
}
