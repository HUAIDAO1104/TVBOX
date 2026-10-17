package com.fongmi.android.tv.api;

import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.playback.vod.DanmakuMatch;
import com.google.gson.*;
import java.io.IOException;
import java.util.*;

/** Tencent public catalogue and comment formats. No login, video extraction or model-generated IDs. */
public final class TencentDanmakuCodec {
    public static final String SOURCE = "tencent-direct";
    private static final String PREFIX = "tencent-danmaku://video/";
    public record Work(String id, String title, String year, String type, String matchTitle, List<Danmaku> rows) { }
    public record Page(List<Danmaku> rows, List<String> contexts) { }

    public static boolean supports(String value) {
        return value != null && value.startsWith(PREFIX) && value.substring(PREFIX.length()).matches("[a-zA-Z0-9]{11}");
    }
    public static String videoId(String source) throws IOException {
        if (!supports(source)) throw new IOException("Invalid Tencent video ID");
        return source.substring(PREFIX.length());
    }

    public static List<Work> works(String json, String query) throws IOException {
        JsonObject data = response(json);
        List<JsonElement> items = new ArrayList<>();
        items.addAll(array(object(data, "normalList"), "itemList").asList());
        for (JsonElement box : array(data, "areaBoxList")) items.addAll(array(box.getAsJsonObject(), "itemList").asList());
        Map<String, Work> result = new LinkedHashMap<>();
        for (JsonElement item : items) {
            JsonObject info = object(item.getAsJsonObject(), "videoInfo");
            String id = string(object(item.getAsJsonObject(), "doc"), "id");
            String title = clean(string(info, "title")), year = string(info, "year"), type = string(info, "typeName");
            if (!id.matches("[a-zA-Z0-9]{15}") || !year.matches("(?:19|20)\\d{2}")
                    || !List.of("电影", "电视剧", "综艺", "综艺节目", "动漫", "纪录片").contains(type)
                    || "2".equals(string(info, "playFlag"))) continue;
            String matchTitle = title;
            String hints = clean(string(info, "hintWords"));
            if (hints.matches("^(?:又名|别名)\\s*[：:].+")) {
                for (String alias : hints.replaceFirst("^(?:又名|别名)\\s*[：:]", "").split("[、,，/|]")) {
                    if (DanmakuMatch.isSameWork(query, alias.trim())) matchTitle = alias.trim();
                }
            }
            if (!DanmakuMatch.isSameBaseWork(query, matchTitle)) continue;
            Work work = new Work(id, title, year, type, matchTitle, new ArrayList<>());
            Map<String, Danmaku> rows = new LinkedHashMap<>();
            for (String field : List.of("episodeSites", "playSites")) for (JsonElement site : array(info, field)) {
                JsonObject s = site.getAsJsonObject();
                if (!"qq".equals(string(s, "enName"))) continue;
                for (JsonElement element : array(s, "episodeInfoList")) {
                    JsonObject ep = element.getAsJsonObject();
                    String label = clean(string(ep, "title"));
                    // A generic play button can point to a trailer. Only movies with a full
                    // feature duration may use it; variety uses the explicit episode list.
                    if ("电影".equals(type)) {
                        if (integer(ep, "duration") < 1200) continue;
                        label = "正片";
                    } else if (label.isBlank() || "立即播放".equals(label)) continue;
                    add(rows, work, string(ep, "id"), label);
                }
            }
            work.rows().addAll(rows.values());
            result.putIfAbsent(id, work);
            if (result.size() >= 6) break;
        }
        return List.copyOf(result.values());
    }

    public static Page episodes(String json, Work work) throws IOException {
        JsonObject data = response(json);
        Map<String, Danmaku> rows = new LinkedHashMap<>();
        Set<String> contexts = new LinkedHashSet<>();
        for (JsonElement group : array(data, "module_list_datas")) for (JsonElement element : array(group.getAsJsonObject(), "module_datas")) {
            JsonObject module = element.getAsJsonObject(), params = object(module, "module_params");
            for (JsonElement item : array(object(module, "item_data_lists"), "item_datas")) {
                JsonObject ep = object(item.getAsJsonObject(), "item_params");
                if (!work.id().equals(string(ep, "cid")) || "1".equals(string(ep, "is_trailer"))) continue;
                String label = clean(string(ep, "union_title"));
                if (label.isBlank()) label = clean(string(ep, "title"));
                if ("电影".equals(work.type())) { if (integer(ep, "duration") < 1200) continue; label = "正片"; }
                else if (work.type().startsWith("综艺")) label = string(ep, "tag_right_text") + " " + label;
                else if (label.matches("\\d{1,4}")) label = "第" + label + "集";
                add(rows, work, string(ep, "vid"), label);
            }
            if ("true".equals(string(params, "has_next"))) addContext(contexts, string(params, "next_page_context"));
            String tabs = string(params, "tabs");
            if (!tabs.isBlank()) try {
                for (JsonElement tab : JsonParser.parseString(tabs).getAsJsonArray()) addContext(contexts, string(tab.getAsJsonObject(), "page_context"));
            } catch (RuntimeException e) { throw new IOException("Invalid Tencent pages", e); }
        }
        return new Page(List.copyOf(rows.values()), List.copyOf(contexts));
    }

    private static void addContext(Set<String> values, String value) {
        if (!value.isBlank() && value.length() <= 2048) values.add(value);
    }
    private static void add(Map<String, Danmaku> rows, Work work, String id, String label) {
        if (!id.matches("[a-zA-Z0-9]{11}") || label.isBlank() || label.contains("预告")) return;
        String suffix = "(" + work.year() + ")【" + work.type() + "】from tencent - 【qq】 " + label.trim();
        Danmaku row = new Danmaku(); row.setUrl(PREFIX + id); row.setName(work.title() + suffix);
        row.setMatchName(work.matchTitle() + suffix); row.setSourceKey(SOURCE); rows.putIfAbsent(row.getUrl(), row);
    }

    public static List<String> segments(String json, String id) throws IOException {
        videoId(PREFIX + id);
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("segment_index") || !root.get("segment_index").isJsonObject()) throw new IOException("Missing Tencent segment index");
            TreeMap<Long, String> ordered = new TreeMap<>();
            for (var entry : root.getAsJsonObject("segment_index").entrySet()) {
                JsonObject segment = entry.getValue().getAsJsonObject();
                String path = string(segment, "segment_name");
                if (!path.matches("[a-zA-Z0-9]+/v[0-9]+/[0-9]+/[0-9]+")) throw new IOException("Invalid Tencent segment path");
                long start = Long.parseLong(string(segment, "segment_start"));
                if (start < 0 || start > 21_600_000L || ordered.size() >= 720) throw new IOException("Tencent timeline exceeds limit");
                ordered.put(start, "https://dm.video.qq.com/barrage/segment/" + id + "/" + path);
            }
            if (ordered.isEmpty()) throw new IOException("Empty Tencent comments");
            return List.copyOf(ordered.values());
        } catch (RuntimeException e) { throw new IOException("Invalid Tencent segment index", e); }
    }

    public static List<IqiyiDanmakuCodec.Comment> comments(String json, int limit) throws IOException {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("barrage_list") || !root.get("barrage_list").isJsonArray()) throw new IOException("Missing Tencent comments");
            List<IqiyiDanmakuCodec.Comment> result = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (JsonElement element : array(root, "barrage_list")) {
                JsonObject row = element.getAsJsonObject();
                String text = string(row, "content"), id = string(row, "id");
                double seconds = Double.parseDouble(string(row, "time_offset")) / 1000d;
                if (!Double.isFinite(seconds) || seconds < 0 || seconds > 21600 || text.isBlank() || text.length() > 500) continue;
                if (!seen.add(id.isEmpty() ? seconds + "|" + text : id)) continue;
                int color = 0xffffff;
                try {
                    String value = string(JsonParser.parseString(string(row, "content_style")).getAsJsonObject(), "color").replace("#", "");
                    if (value.matches("[a-fA-F0-9]{6}")) color = Integer.parseInt(value, 16);
                } catch (RuntimeException ignored) { }
                result.add(new IqiyiDanmakuCodec.Comment(seconds, color, text));
            }
            // Sample uniformly within each segment, preserving the entire episode timeline.
            if (result.size() <= limit) return result;
            List<IqiyiDanmakuCodec.Comment> sampled = new ArrayList<>();
            for (int i = 0; i < limit; i++) sampled.add(result.get((int) ((long) i * result.size() / limit)));
            return sampled;
        } catch (RuntimeException e) { throw new IOException("Invalid Tencent comments", e); }
    }

    private static JsonObject response(String json) throws IOException {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!"0".equals(string(root, "ret")) || !root.has("data") || !root.get("data").isJsonObject()) throw new IOException("Tencent catalogue unavailable");
            JsonObject data = root.getAsJsonObject("data");
            if (data.has("errcode") && !"0".equals(string(data, "errcode"))) throw new IOException("Tencent catalogue rejected");
            return data;
        } catch (RuntimeException e) { throw new IOException("Invalid Tencent catalogue", e); }
    }
    private static JsonObject object(JsonObject root, String key) {
        return root.has(key) && root.get(key).isJsonObject() ? root.getAsJsonObject(key) : new JsonObject();
    }
    private static JsonArray array(JsonObject root, String key) {
        return root.has(key) && root.get(key).isJsonArray() ? root.getAsJsonArray(key) : new JsonArray();
    }
    private static String string(JsonObject root, String key) {
        return root.has(key) && root.get(key).isJsonPrimitive() ? root.get(key).getAsString() : "";
    }
    private static int integer(JsonObject root, String key) {
        try { return Integer.parseInt(string(root, key)); } catch (NumberFormatException e) { return 0; }
    }
    private static String clean(String value) { return value.replaceAll("<[^>]*>", "").trim(); }
    private TencentDanmakuCodec() { }
}
