package com.fongmi.android.tv.repository;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Builds safe, deterministic download candidates for repository mirror URLs. */
public final class RepositoryUrlResolver {

    private static final String RAW_GITHUB = "https://raw.githubusercontent.com/";
    private static final String[] GITHUB_MIRRORS = {
            "https://gh.llkk.cc/",
            "https://gh-proxy.com/",
            "https://gh-proxy.org/"
    };

    private RepositoryUrlResolver() {
    }

    public static List<String> candidates(String url) {
        Set<String> result = new LinkedHashSet<>();
        String value = url == null ? "" : url.trim();
        if (value.isEmpty()) return new ArrayList<>();
        for (String mirror : GITHUB_MIRRORS) {
            String prefix = mirror + RAW_GITHUB;
            if (!value.startsWith(prefix)) continue;
            // Projectors often cannot reach a community proxy even though raw GitHub works.
            // Try the real origin first, but retain the configured mirror as a fallback.
            result.add(value.substring(mirror.length()));
            break;
        }
        result.add(value);
        return new ArrayList<>(result);
    }
}
