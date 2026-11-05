package com.fongmi.android.tv.offline;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Private download metadata; never include request headers or URLs in UI/error messages. */
@androidx.annotation.Keep
public final class OfflineMetadata {
    public String title = "视频";
    public String episode = "";
    public String showId = "", poster = "", sourceName = "";
    public long addedAt;
    public int episodeOrder;
    public String groupKey() { return showId == null || showId.isEmpty() ? "legacy:" + title : showId; }
    public String sourceId = "";
    public String danmaku = "";
    public Map<String, String> headers = new TreeMap<>();
    public List<Subtitle> subtitles = new ArrayList<>();

    @androidx.annotation.Keep
    public static final class Subtitle {
        public String uri, mime, language, label;
        public int selectionFlags;
    }

    public byte[] encode() {
        return new Gson().toJson(this).getBytes(StandardCharsets.UTF_8);
    }

    public static OfflineMetadata decode(byte[] data) {
        try {
            OfflineMetadata value = new Gson().fromJson(new String(data, StandardCharsets.UTF_8), OfflineMetadata.class);
            if (value != null && value.headers != null && value.subtitles != null) {
                if (value.title == null) value.title = "视频";
                if (value.episode == null) value.episode = "";
                if (value.danmaku == null) value.danmaku = "";
                if (value.sourceId == null) value.sourceId = "";
                if (value.showId == null) value.showId = "";
                if (value.poster == null) value.poster = "";
                if (value.sourceName == null) value.sourceName = "";
                return value;
            }
        } catch (RuntimeException ignored) { }
        return new OfflineMetadata();
    }

    public static String id(String uri, Map<String, String> headers) {
        try {
            byte[] bytes = (uri + "\n" + new Gson().toJson(new TreeMap<>(headers))).getBytes(StandardCharsets.UTF_8);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder();
            for (byte b : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public static String requireId(String id) {
        if (id == null || !id.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid download id");
        return id;
    }
}
