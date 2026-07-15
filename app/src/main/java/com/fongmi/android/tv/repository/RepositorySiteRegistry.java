package com.fongmi.android.tv.repository;

import com.fongmi.android.tv.bean.Site;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local registry for repository-scoped sites discovered by aggregate search.
 *
 * <p>The active {@code VodConfig} remains the source of truth for the home page.  This registry is
 * only a routing extension for search results, details and playback, so searching another
 * repository never replaces the user's active configuration or clears its Spider instances.</p>
 */
public final class RepositorySiteRegistry {

    private static final Map<String, Site> SITES = new ConcurrentHashMap<>();

    private RepositorySiteRegistry() {
    }

    public static Site register(Site site) {
        if (site != null && !site.getKey().isEmpty()) SITES.put(site.getKey(), site);
        return site;
    }

    public static Site find(String key) {
        return key == null ? null : SITES.get(key);
    }

    public static void removeRepository(long repositoryId) {
        if (repositoryId == 0) return;
        SITES.entrySet().removeIf(entry -> entry.getValue().getRepositoryId() == repositoryId);
    }

    static int size() {
        return SITES.size();
    }
}
