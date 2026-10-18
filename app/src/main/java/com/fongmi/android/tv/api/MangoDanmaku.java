package com.fongmi.android.tv.api;

import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.playback.vod.DanmakuMatch;
import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.IntConsumer;
import java.util.regex.*;
import okhttp3.*;

/** Mango public search, monthly catalogue, and millisecond comment segments. */
public final class MangoDanmaku {
    public static final String SOURCE = "mango-direct";
    private static final Pattern ID = Pattern.compile("^mango-danmaku://video/(\\d{3,12})/(\\d{4,12})$");
    private static final Pattern PAGE = Pattern.compile("^https?://(?:www|w)\\.mgtv\\.com/b/(\\d{3,12})/(\\d{4,12})\\.html(?:[?#].*)?$");
    record Work(String cid, String vid, String title, String year, String type, String matchTitle, List<Danmaku> preview) {
        Work(String cid, String vid, String title, String year, String type, String matchTitle) {
            this(cid, vid, title, year, type, matchTitle, List.of());
        }
    }
    private record Cached(long created, List<Danmaku> rows) { }
    private static final Map<String, Cached> CACHE = new LinkedHashMap<>();
    public static boolean supports(String value) { return value != null && (ID.matcher(value).matches() || PAGE.matcher(value).matches()); }
    static String[] ids(String value) throws IOException {
        Matcher m = ID.matcher(value); if (!m.matches()) m = PAGE.matcher(value);
        if (!m.matches()) throw new IOException("Invalid Mango video URL");
        return new String[]{m.group(1), m.group(2)};
    }
    public static NativeDanmaku.Job search(String query, IqiyiDanmaku.Callback<List<Danmaku>> callback) {
        return search(query, callback, NativeDanmakuRequest.CLIENT);
    }
    public static NativeDanmaku.Job searchTarget(String query, String year, String type, IqiyiDanmaku.Callback<List<Danmaku>> callback) {
        return search(query, year, type, "", callback, NativeDanmakuRequest.CLIENT);
    }
    public static NativeDanmaku.Job searchTarget(String query, String year, String type, String episode, IqiyiDanmaku.Callback<List<Danmaku>> callback) {
        return search(query, year, type, episode, callback, NativeDanmakuRequest.CLIENT);
    }
    static NativeDanmaku.Job search(String query, IqiyiDanmaku.Callback<List<Danmaku>> callback, OkHttpClient client) {
        return search(query, "", "", "", callback, client);
    }
    static NativeDanmaku.Job search(String query, String year, String type, String episode, IqiyiDanmaku.Callback<List<Danmaku>> callback, OkHttpClient client) {
        String cacheKey = query + "|" + year + "|" + type + "|" + episode;
        var job = new NativeDanmakuRequest(18000, "https://so.mgtv.com/", client);
        job.start(NativeDanmakuRequest.SEARCH, () -> {
            synchronized (CACHE) {
                Cached cached = CACHE.get(cacheKey);
                if (cached != null && System.nanoTime() - cached.created() < java.util.concurrent.TimeUnit.MINUTES.toNanos(5)) return copy(cached.rows());
            }
            if (supports(query)) {
                var ids = ids(query); JsonObject info = obj(response(job.text(infoUrl(ids)), "code", "200"), "info");
                Work work = new Work(ids[0], ids[1], str(info, "clipName"), str(obj(info, "detail"), "releaseTime").replaceFirst("-.*", ""), str(info, "fstlvlType"), str(info, "clipName"));
                return List.of(row(work, ids[1], str(info, "videoName").replace(work.title(), "").trim()));
            }
            Map<String, Danmaku> rows = new LinkedHashMap<>();
            List<Work> eligible = new ArrayList<>();
            for (Work work : works(job.text(searchUrl(query)), query)) {
                if (NativeDanmaku.acceptsWork(query, year, type, work.matchTitle(), work.year(), work.type())) eligible.add(work);
            }
            // Search already includes authoritative video IDs and broadcast/part labels.
            // Do not hold these behind a separate catalogue host or an older season's pages.
            for (Work work : eligible) for (Danmaku item : work.preview()) rows.put(item.getUrl(), item);
            job.check();
            if (!rows.isEmpty()) callback.onPartial(copy(List.copyOf(rows.values())));
            Map<Work, JsonObject> firstPages = new LinkedHashMap<>();
            for (Work work : eligible) {
                JsonObject first = response(job.text(listUrl(work.cid(), "", 1)), "code", "200");
                firstPages.put(work, first);
                addPage(rows, work, first);
                job.check();
                if (!rows.isEmpty()) callback.onPartial(copy(List.copyOf(rows.values())));
                // Recent episodes are already in this authoritative page. Loading every
                // previous month first wastes the TV's catalogue budget for no extra certainty.
                if (eligible.size() == 1 && !episode.isEmpty() && !DanmakuMatch.ranked(query, year, type, episode,
                        new ArrayList<>(rows.values()), Danmaku::getMatchName).isEmpty()) return List.copyOf(rows.values());
            }
            for (var entry : firstPages.entrySet()) {
                Work work = entry.getKey(); JsonObject first = entry.getValue();
                Set<String> months = new LinkedHashSet<>();
                String current = str(obj(first, "cur"), "m");
                for (JsonElement m : array(first, "tab_m")) {
                    String month = str(m.getAsJsonObject(), "m");
                    if (month.matches("(?:19|20)\\d{4}")) months.add(month);
                }
                if (months.size() > 24) throw new IOException("Mango catalogue exceeds month limit");
                if (months.isEmpty()) months.add(current);
                for (String month : months) {
                    JsonObject page = month.equals(current) ? first : response(job.text(listUrl(work.cid(), month, 1)), "code", "200");
                    if (!month.equals(current)) {
                        addPage(rows, work, page);
                        job.check();
                        if (!rows.isEmpty()) callback.onPartial(copy(List.copyOf(rows.values())));
                    }
                    int total = number(page, "total");
                    if (total > 600) throw new IOException("Mango catalogue exceeds page limit");
                    for (int pn = 2; pn <= (total + 59) / 60; pn++) {
                        addPage(rows, work, response(job.text(listUrl(work.cid(), month, pn)), "code", "200"));
                        job.check();
                        if (!rows.isEmpty()) callback.onPartial(copy(List.copyOf(rows.values())));
                    }
                }
            }
            List<Danmaku> result = List.copyOf(rows.values());
            job.check();
            if (!result.isEmpty()) synchronized (CACHE) {
                CACHE.put(cacheKey, new Cached(System.nanoTime(), copy(result)));
                while (CACHE.size() > 12) CACHE.remove(CACHE.keySet().iterator().next());
            }
            return result;
        }, callback); return job;
    }
    static String searchUrl(String query) throws IOException {
        Map<String, String> p = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        p.put("src", "mgtv"); p.put("did", UUID.randomUUID().toString()); p.put("timestamp", java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString());
        p.put("signVersion", "1"); p.put("signNonce", UUID.randomUUID().toString().replace("-", ""));
        p.put("q", com.github.catvod.utils.Trans.t2s(query)); p.put("pn", "1"); p.put("pc", "10"); p.put("corr", "1");
        StringJoiner signed = new StringJoiner("&");
        for (var e : p.entrySet()) signed.add(e.getKey() + "=" + encodeUri(e.getValue()));
        p.put("signature", md5("xHAa3YZflWLogZUOzl" + signed + "xHAa3YZflWLogZUOzl"));
        HttpUrl.Builder url = HttpUrl.get("https://mobileso.bz.mgtv.com/pc/search/v2").newBuilder();
        for (var e : p.entrySet()) url.addQueryParameter(e.getKey(), e.getValue()); return url.build().toString();
    }
    private static String encodeUri(String text) {
        StringBuilder out = new StringBuilder();
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 255;
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || ";,/?:@&=+$#-_.!~*'()".indexOf(c) >= 0) out.append((char)c);
            else out.append(String.format(Locale.ROOT, "%%%02X", c));
        } return out.toString();
    }
    static String md5(String value) throws IOException {
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255)); return out.toString();
        } catch (Exception e) { throw new IOException(e); }
    }
    static List<Work> works(String json, String query) throws IOException {
        List<Work> result = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (var c : array(response(json, "code", "200"), "contents")) {
            JsonObject box = c.getAsJsonObject();
            String kind = str(box, "type");
            if (!List.of("program", "serial", "movie").contains(kind)) continue;
            JsonObject data = obj(box, "data");
            List<JsonElement> editions = kind.equals("program") ? array(data, "yearList").asList() : List.of(data);
            for (var y : editions) {
                JsonObject year = y.getAsJsonObject(); String title = str(year, "title"), type = "";
                for (var d : array(year, "desc")) if ("类型".equals(str(d.getAsJsonObject(), "label"))) type = str(d.getAsJsonObject(), "text");
                String matchTitle = title, editionYear = str(year, "year");
                if (editionYear.isEmpty()) editionYear = str(year, "playTime").replaceFirst("[-/].*$", "");
                if (!editionYear.matches("(?:19|20)\\d{2}")) editionYear = "";
                if (type.equals("综艺") && editionYear.matches("(?:19|20)\\d{2}") && title.endsWith(editionYear)
                        && DanmakuMatch.isSameWork(query, title.substring(0, title.length() - 4).trim())) matchTitle = query;
                if (!DanmakuMatch.isSameBaseWork(query, matchTitle)) continue;
                for (var s : array(year, "sourceList")) {
                    JsonObject source = s.getAsJsonObject(); if (!"imgo".equals(str(source, "source"))) continue;
                    Matcher cid = Pattern.compile("/(?:h|b)/(\\d{3,12})(?:\\.html|/)").matcher(str(source, "url"));
                    if (!cid.find() || !seen.add(cid.group(1))) continue;
                    Work work = new Work(cid.group(1), str(source, "vid"), title, editionYear, type, matchTitle);
                    List<Danmaku> preview = new ArrayList<>();
                    for (var video : array(source, "videoList")) {
                        JsonObject item = video.getAsJsonObject();
                        String id = str(item, "vid"), link = str(item, "url");
                        if (link.startsWith("//")) link = "https:" + link;
                        Matcher page = PAGE.matcher(link);
                        if (!page.matches() || !work.cid().equals(page.group(1)) || !id.equals(page.group(2))) continue;
                        String label = str(item, "title");
                        if (label.isBlank()) label = str(item, "subtitle");
                        if (!label.isBlank()) preview.add(row(work, id, label));
                    }
                    result.add(new Work(work.cid(), work.vid(), title, editionYear, type, matchTitle, List.copyOf(preview)));
                    if (result.size() >= 4) return result;
                }
            }
        } return result;
    }
    static List<Danmaku> catalogue(String json, Work work) throws IOException {
        Map<String, Danmaku> rows = new LinkedHashMap<>(); addPage(rows, work, response(json, "code", "200")); return List.copyOf(rows.values());
    }
    private static void addPage(Map<String, Danmaku> rows, Work work, JsonObject page) {
        for (var e : array(page, "list")) {
            JsonObject item = e.getAsJsonObject(); String id = str(item, "video_id");
            if (!id.matches("\\d{4,12}") || !work.cid().equals(str(item, "clip_id")) || !"1".equals(str(item, "isIntact"))) continue;
            String title = str(item, "t3"); if (title.startsWith(work.title())) title = title.substring(work.title().length()).trim();
            if (title.isBlank()) title = str(item, "t1");
            String label = "电影".equals(work.type())
                    ? DanmakuMatch.isEpisodeCompatible("正片", title) ? "正片" : title
                    : str(item, "t2") + " " + title;
            Danmaku row = row(work, id, label); rows.put(row.getUrl(), row);
        }
    }
    private static Danmaku row(Work w, String id, String label) {
        Danmaku row = new Danmaku(); row.setName(w.title() + "(" + w.year() + ")【" + w.type() + "】from mango - 【mgtv】 " + label);
        row.setMatchName(w.matchTitle() + "(" + w.year() + ")【" + w.type() + "】from mango - 【mgtv】 " + label);
        row.setUrl("mango-danmaku://video/" + w.cid() + "/" + id); row.setSourceKey(SOURCE); return row;
    }
    private static List<Danmaku> copy(List<Danmaku> rows) {
        List<Danmaku> result = new ArrayList<>();
        for (Danmaku row : rows) { Danmaku item = new Danmaku(); item.setName(row.getName()); item.setMatchName(row.getMatchName());
            item.setUrl(row.getUrl()); item.setSourceKey(row.getSourceKey()); result.add(item); }
        return result;
    }
    private static String listUrl(String cid, String month, int page) { return "https://pcweb.api.mgtv.com/list/master?cid=" + cid + "&month=" + month + "&pn=" + page + "&ps=60&_support=10000000&platform=4&src=mgtv"; }
    private static String infoUrl(String[] ids) { return "https://pcweb.api.mgtv.com/video/info?cid=" + ids[0] + "&vid=" + ids[1]; }
    public static NativeDanmaku.Job document(String source, IntConsumer progress, IqiyiDanmaku.Callback<byte[]> callback) {
        return document(source, progress, callback, NativeDanmakuRequest.CLIENT);
    }
    static NativeDanmaku.Job document(String source, IntConsumer progress, IqiyiDanmaku.Callback<byte[]> callback, OkHttpClient client) {
        // A long variety episode contains hundreds of minute requests. Keep a finite budget
        // below automatic matching's 90-second limit, with room for slower television links.
        var job = new NativeDanmakuRequest(75000, "https://www.mgtv.com/", client);
        job.start(NativeDanmakuRequest.DOCUMENTS, () -> {
            String[] ids = ids(source); JsonObject info = obj(response(job.text(infoUrl(ids)), "code", "200"), "info");
            if (!ids[1].equals(str(info, "videoId")) || !ids[0].equals(str(info, "clipId"))) throw new IOException("Mango video identity mismatch");
            int duration = duration(str(info, "time"));
            return job.segments((duration + 59) / 60, progress, i -> comments(job.text("https://galaxy.bz.mgtv.com/rdbarrage?vid=" + ids[1] + "&cid=" + ids[0] + "&time=" + i * 60000)));
        }, callback); return job;
    }
    static int duration(String value) throws IOException {
        try { int total = 0; for (String n : value.split(":")) total = Math.addExact(Math.multiplyExact(total, 60), Integer.parseInt(n));
            if (total < 1 || total > 21600) throw new IOException("Invalid video duration"); return total;
        } catch (RuntimeException e) { throw new IOException("Invalid video duration", e); }
    }
    static List<IqiyiDanmakuCodec.Comment> comments(String json) throws IOException {
        JsonObject data = response(json, "status", "0");
        if (!data.has("items")) throw new IOException("Missing Mango segment");
        List<IqiyiDanmakuCodec.Comment> rows = new ArrayList<>();
        for (var e : array(data, "items")) {
            JsonObject item = e.getAsJsonObject(); double time = decimal(item, "time") / 1000d; String text = str(item, "content");
            if (time >= 0 && time <= 21600 && !text.isBlank() && text.length() <= 500) rows.add(new IqiyiDanmakuCodec.Comment(time, 0xffffff, text));
        } return rows;
    }
    static JsonObject response(String json, String field, String code) throws IOException {
        try { JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!code.equals(str(root, field)) || !root.has("data") || !root.get("data").isJsonObject()) throw new IOException("Platform rejected response: " + str(root, field));
            return root.getAsJsonObject("data");
        } catch (RuntimeException e) { throw new IOException("Invalid platform response", e); }
    }
    static JsonObject obj(JsonObject o, String key) { return o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : new JsonObject(); }
    static JsonArray array(JsonObject o, String key) { return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray(); }
    static String str(JsonObject o, String key) { return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : ""; }
    static int number(JsonObject o, String key) { try { return Integer.parseInt(str(o, key)); } catch (RuntimeException e) { return 0; } }
    static double decimal(JsonObject o, String key) { try { return Double.parseDouble(str(o, key)); } catch (RuntimeException e) { return -1; } }
    private MangoDanmaku() { }
}
