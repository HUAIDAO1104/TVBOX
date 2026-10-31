package com.fongmi.android.tv.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import okhttp3.HttpUrl;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Bounded, non-consuming format detection for receivers that trust MIME and file extensions. */
final class CastMediaType {
    static final String HLS = "application/vnd.apple.mpegurl";
    static final String BINARY = "application/octet-stream";

    static String fromPath(HttpUrl url) {
        String path = url.encodedPath().toLowerCase(Locale.ROOT);
        if (path.endsWith(".m3u8") || path.endsWith(".m3u")) return HLS;
        if (path.endsWith(".ts") || path.endsWith(".m2ts")) return "video/mp2t";
        if (path.endsWith(".mkv")) return "video/x-matroska";
        if (path.endsWith(".webm")) return "video/webm";
        if (path.endsWith(".mp4") || path.endsWith(".m4s") || path.endsWith(".m4v")) return "video/mp4";
        if (path.endsWith(".vtt")) return "text/vtt";
        if (path.endsWith(".aac")) return "audio/aac";
        if (path.endsWith(".m4a")) return "audio/mp4";
        if (path.endsWith(".mp3")) return "audio/mpeg";
        return BINARY;
    }

    static String extension(String mime) {
        return switch (mime) {
            case HLS -> ".m3u8";
            case "video/mp2t" -> ".ts";
            case "video/x-matroska" -> ".mkv";
            case "video/webm" -> ".webm";
            case "video/mp4" -> ".mp4";
            case "audio/mp4" -> ".m4a";
            case "audio/aac" -> ".aac";
            case "audio/mpeg" -> ".mp3";
            case "text/vtt" -> ".vtt";
            default -> ".bin";
        };
    }

    static String header(Response response, String hint) {
        if (response.isSuccessful() && response.request().method().equals("HEAD") && !BINARY.equals(hint)) return hint;
        String type = response.header("Content-Type", "").split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (type.contains("mpegurl")) return HLS;
        if (type.equals("video/mkv") || type.equals("application/x-matroska")) return "video/x-matroska";
        if (type.startsWith("video/") || type.startsWith("audio/") || type.equals("text/vtt")
                || type.equals("text/html") || type.contains("json")) return type;
        if (!BINARY.equals(hint)) return hint;
        String pathType = fromPath(response.request().url());
        return !BINARY.equals(pathType) ? pathType : type.isEmpty() ? BINARY : type;
    }

    static String detect(Response response, String hint) throws IOException {
        ResponseBody body = response.body();
        if (body == null) return header(response, hint);
        // A range from the middle of a movie has no container header. Keep the type
        // verified from byte zero, even when a CDN labels every response as MP4.
        if (!BINARY.equals(hint) && response.header("Content-Range", "").matches("bytes [1-9][0-9]*-.*")) return hint;
        // At most three TS packet headers; never read a whole segment or movie to identify it.
        body.source().request(377);
        byte[] prefix = body.source().buffer().clone().readByteArray(Math.min(377, body.source().buffer().size()));
        String text = new String(prefix, StandardCharsets.UTF_8).replaceFirst("^\\uFEFF", "").trim();
        if (text.startsWith("#EXTM3U")) return HLS;
        if (text.startsWith("WEBVTT")) return "text/vtt";
        if (prefix.length >= 377 && prefix[0] == 0x47 && prefix[188] == 0x47 && prefix[376] == 0x47) return "video/mp2t";
        if (prefix.length >= 8) {
            String box = new String(prefix, 4, 4, StandardCharsets.US_ASCII);
            if (box.equals("ftyp") || box.equals("styp") || box.equals("moof")) return "audio/mp4".equals(hint) ? hint : "video/mp4";
            if (prefix[0] == 0x1a && prefix[1] == 0x45 && prefix[2] == (byte) 0xdf && prefix[3] == (byte) 0xa3)
                return "video/webm".equals(header(response, hint)) || text.contains("webm") ? "video/webm" : "video/x-matroska";
        }
        return header(response, hint);
    }

    static String resourceName(HttpUrl url, String hint, boolean key) {
        if (key) return "key.bin";
        String path = url.encodedPath().toLowerCase(Locale.ROOT);
        String type = fromPath(url);
        // Fragmented MP4 extensions are significant to strict HLS demuxers.
        if (path.endsWith(".m4s")) return "segment.m4s";
        return "media" + extension(BINARY.equals(type) ? hint : type);
    }
}
