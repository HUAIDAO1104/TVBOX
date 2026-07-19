package com.fongmi.android.tv.update;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class UpdateSource {

    private static final String RELEASE_ROOT = "https://github.com/HUAIDAO1104/TVBOX/releases/latest/download/";
    private static final String[] MIRROR_PREFIXES = {
            "https://ghfast.top/",
            "https://gh-proxy.com/",
            "https://ghproxy.net/"
    };

    private UpdateSource() {
    }

    public static List<String> manifestCandidates() {
        return mirrorFirst(RELEASE_ROOT + "latest.json", List.of());
    }

    public static List<String> assetCandidates(UpdateManifest.Asset asset) {
        String url = asset.url();
        if (url.isEmpty() && !asset.fileName().isEmpty()) url = RELEASE_ROOT + asset.fileName();
        return mirrorFirst(url, asset.mirrors());
    }

    static List<String> mirrorFirst(String url, List<String> declaredMirrors) {
        Set<String> candidates = new LinkedHashSet<>();
        if (declaredMirrors != null) {
            for (String mirror : declaredMirrors) add(candidates, mirror);
        }
        if (isGithubUrl(url)) {
            for (String prefix : MIRROR_PREFIXES) add(candidates, prefix + url);
        }
        add(candidates, url);
        return new ArrayList<>(candidates);
    }

    private static boolean isGithubUrl(String url) {
        return url != null && (url.startsWith("https://github.com/") || url.startsWith("https://raw.githubusercontent.com/"));
    }

    private static void add(Set<String> values, String value) {
        if (value != null && !value.isBlank() && value.startsWith("https://")) values.add(value.trim());
    }
}
