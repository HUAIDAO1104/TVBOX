package com.fongmi.android.tv.repository;

import com.fongmi.android.tv.bean.Parse;
import com.fongmi.android.tv.bean.Repository;
import com.fongmi.android.tv.bean.RepositoryItem;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.utils.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parses one VOD configuration without mutating the active {@code VodConfig} singleton. */
public final class RepositorySiteParser {

    private RepositorySiteParser() {
    }

    public static List<Site> parse(Repository repository, RepositoryItem item, String content) {
        if (repository == null || item == null || content == null || content.isBlank()) return List.of();
        JsonElement rootElement = Json.parse(content);
        if (!rootElement.isJsonObject()) return List.of();
        JsonObject root = rootElement.getAsJsonObject();
        if (!root.has("sites")) return List.of();
        String spider = Json.safeString(root, "spider");
        if (!spider.isEmpty()) spider = UrlUtil.convert(spider);
        List<String> flags = Json.safeListString(root, "flags");
        List<Parse> parses = Json.safeListElement(root, "parses").stream()
                .map(Parse::objectFrom)
                .distinct()
                .toList();
        Map<String, Site> unique = new LinkedHashMap<>();
        for (JsonElement element : Json.safeListElement(root, "sites")) {
            Site site = Site.objectFrom(element, spider);
            String originKey = site.getKey();
            // Search discovery filters non-searchable sites at the session boundary. Keep them in
            // the parsed catalog so an older History/Keep entry can still be rehydrated if the
            // publisher later disables search for that site.
            if (originKey.isEmpty()) continue;
            String scope = scopeKey(repository, item, originKey);
            site.setOriginKey(originKey);
            site.setKey(scope);
            site.setRepositoryId(repository.getId());
            site.setRepositoryName(repository.getName());
            site.setRepositoryPriority(repository.getPriority());
            site.setConfigName(item.getName());
            site.setConfigUrl(item.getUrl());
            site.setRepositoryPlaybackContext(flags, parses);
            // Registration is deliberately owned by the active search session. Parsing may
            // finish after cancellation, and an obsolete task must not mutate global routing.
            unique.putIfAbsent(scope, site);
        }
        return new ArrayList<>(unique.values());
    }

    public static String scopeKey(Repository repository, RepositoryItem item, String siteKey) {
        // Publisher-provided item ids are frequently copied between mirrors. Repository identity,
        // item id, type and resolved config URL all participate in the namespace.
        return RepositorySiteKey.scope(repository, item, siteKey);
    }
}
