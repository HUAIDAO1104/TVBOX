package com.fongmi.android.tv.api;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import okhttp3.FormBody;
import okhttp3.Request;

/** Encodes title/episode values without allowing '&', '#', '+' or '?' to change the URL. */
final class DanmakuSearchRequest {
    static Request build(String endpoint, String name, String episode, String tag) {
        name = Objects.toString(name, "");
        episode = Objects.toString(episode, "");
        Request.Builder builder = new Request.Builder().tag(tag);
        if (endpoint.contains("{name}") || endpoint.contains("{episode}")) {
            return builder.url(endpoint.replace("{name}", encode(name))
                    .replace("{episode}", encode(episode))).build();
        }
        return builder.url(endpoint).post(new FormBody.Builder()
                .add("name", name).add("episode", episode).build()).build();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
