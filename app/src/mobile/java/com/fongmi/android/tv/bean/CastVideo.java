package com.fongmi.android.tv.bean;

import com.fongmi.android.tv.server.Server;
import com.github.catvod.utils.Path;

import java.util.Map;

public record CastVideo(String name, String url, long position, Map<String, String> headers) {

    public CastVideo {
        if (url.startsWith("file")) url = Server.get().getAddress() + "/" + url.replace(Path.rootPath(), "").replace("://", "");
        // Keep loopback URLs: the phone relay, not the TV, opens plugin-local sources.
        headers = headers == null ? java.util.Collections.emptyMap() : new java.util.HashMap<>(headers);
    }
}
