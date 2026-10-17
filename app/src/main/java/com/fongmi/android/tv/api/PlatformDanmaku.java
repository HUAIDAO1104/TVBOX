package com.fongmi.android.tv.api;

import static com.fongmi.android.tv.api.MangoDanmaku.*;
import com.fongmi.android.tv.bean.Danmaku;
import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.IntConsumer;
import java.util.regex.*;
import okhttp3.*;

/** Resolves explicit public video links, never arbitrary hosts or invented video IDs. */
public final class PlatformDanmaku {
    public static final String SOURCE = "platform-link";
    private static final Pattern BILI = Pattern.compile("^https?://(?:www\\.|m\\.)?bilibili\\.com/(?:video/(BV[0-9A-Za-z]{10}|av[0-9]{1,15})/?|bangumi/play/(ep[0-9]{1,15}|ss[0-9]{1,15})/?)(?:[?#].*)?$");
    private static final Pattern YOUKU = Pattern.compile("^https?://v\\.youku\\.com/(?:v_show/id_([A-Za-z0-9=_-]{8,80})\\.html|video\\?vid=([A-Za-z0-9%=_-]{8,100})(?:&[^#]*)?)(?:[?#].*)?$");
    private static final Pattern TENCENT = Pattern.compile("^https?://(?:v|m)\\.qq\\.com/x/(?:cover/[A-Za-z0-9]{15}/|page/)([A-Za-z0-9]{11})\\.html(?:[?#].*)?$");
    private static final Pattern IQIYI = Pattern.compile("^https?://(?:www|m)\\.iqiyi\\.com/(?:v|w)_[A-Za-z0-9]+\\.html(?:[?#].*)?$");
    private static final Pattern GAMER = Pattern.compile("^https?://ani\\.gamer\\.com\\.tw/animeVideo\\.php\\?sn=([0-9]{1,12})(?:&[^#]*)?(?:#.*)?$");
    public static boolean supports(String value) {
        return value != null && (BILI.matcher(value).matches() || YOUKU.matcher(value).matches()
                || TENCENT.matcher(value).matches() || IQIYI.matcher(value).matches() || GAMER.matcher(value).matches());
    }
    record Video(String source, String title, String episode, String id, int duration) { }
    public static NativeDanmaku.Job search(String link, IqiyiDanmaku.Callback<List<Danmaku>> callback) {
        var job = new NativeDanmakuRequest(15000, referer(link), NativeDanmakuRequest.CLIENT);
        job.start(NativeDanmakuRequest.SEARCH, () -> {
            Video video = resolve(link, job);
            Danmaku row = new Danmaku(); row.setName(video.title() + " from " + video.source() + " - 【" + video.source() + "】 " + video.episode());
            row.setUrl(link); row.setSourceKey(SOURCE); return List.of(row);
        }, callback); return job;
    }
    public static NativeDanmaku.Job document(String link, IntConsumer progress, IqiyiDanmaku.Callback<byte[]> callback) {
        return document(link, progress, callback, NativeDanmakuRequest.CLIENT);
    }
    static NativeDanmaku.Job document(String link, IntConsumer progress, IqiyiDanmaku.Callback<byte[]> callback, OkHttpClient client) {
        // Cookies are anonymous, scoped to this one public Youku job, and never persisted.
        List<Cookie> cookies = Collections.synchronizedList(new ArrayList<>());
        if (YOUKU.matcher(link).matches()) client = client.newBuilder().cookieJar(new CookieJar() {
            public void saveFromResponse(HttpUrl url, List<Cookie> values) { synchronized (cookies) {
                for (Cookie c : values) { cookies.removeIf(old -> old.name().equals(c.name())); cookies.add(c); }
            }}
            public List<Cookie> loadForRequest(HttpUrl url) { synchronized (cookies) { return cookies.stream().filter(c -> c.matches(url)).toList(); } }
        }).build();
        var job = new NativeDanmakuRequest(75000, referer(link), client);
        job.start(NativeDanmakuRequest.DOCUMENTS, () -> {
            Video video = resolve(link, job);
            if (video.source().equals("bilibili")) return job.segments((video.duration() + 359) / 360, progress,
                    i -> biliComments(job.bytes("https://api.bilibili.com/x/v2/dm/web/seg.so?type=1&oid=" + video.id() + "&segment_index=" + (i + 1), 4_000_000)));
            if (video.source().equals("youku")) {
                job.text("https://acs.youku.com/h5/mtop.com.youku.aplatform.weakget/1.0/?jsv=2.5.1&appKey=24679788");
                String token; synchronized (cookies) { token = cookies.stream().filter(c -> c.name().equals("_m_h5_tk")).map(c -> c.value().split("_")[0]).findFirst().orElse(""); }
                if (!token.matches("[a-fA-F0-9]{32}")) throw new IOException("Youku anonymous token unavailable");
                return job.segments((video.duration() + 59) / 60, progress, i -> youkuComments(new String(job.bytes(youkuRequest(video.id(), i, token, System.currentTimeMillis()), 4_000_000), StandardCharsets.UTF_8)));
            }
            if (video.source().equals("gamer")) return IqiyiDanmakuCodec.document(gamerComments(job.text("https://api.gamer.com.tw/anime/v1/danmu.php?geo=TW%2CHK&videoSn=" + video.id())));
            // Existing adapters own these protocols; don't block a document worker waiting for another pool.
            throw new IOException("Unsupported direct document route");
        }, callback); return job;
    }
    static String nativeAlias(String link) {
        Matcher m = TENCENT.matcher(link == null ? "" : link);
        return m.matches() ? "tencent-danmaku://video/" + m.group(1) : "";
    }
    static boolean isIqiyi(String link) { return link != null && IQIYI.matcher(link).matches(); }
    static NativeDanmaku.Job iqiyiDocument(String link, IntConsumer progress, IqiyiDanmaku.Callback<byte[]> callback) {
        AtomicJob route = new AtomicJob(); var job = new NativeDanmakuRequest(12000, "https://www.iqiyi.com/", NativeDanmakuRequest.CLIENT);
        route.set(job);
        job.start(NativeDanmakuRequest.SEARCH, () -> resolve(link, job), new IqiyiDanmaku.Callback<>() {
            public void onSuccess(Video v) { if (!route.cancelled) route.set(IqiyiDanmaku.document("iqiyi-danmaku://video/" + v.id(), progress, callback)::cancel); }
            public void onFailure(IOException e) { if (!route.cancelled) callback.onFailure(e); }
        }); return route;
    }
    private static final class AtomicJob implements NativeDanmaku.Job {
        volatile boolean cancelled; NativeDanmaku.Job current;
        synchronized void set(NativeDanmaku.Job job) { if (cancelled) job.cancel(); else current = job; }
        public synchronized void cancel() { cancelled = true; if (current != null) current.cancel(); }
    }
    private static String referer(String link) {
        if (link != null && BILI.matcher(link).matches()) return "https://www.bilibili.com/";
        if (link != null && YOUKU.matcher(link).matches()) return "https://v.youku.com/";
        if (link != null && GAMER.matcher(link).matches()) return "https://ani.gamer.com.tw/";
        return "https://www.iqiyi.com/";
    }
    static Video resolve(String link, NativeDanmakuRequest job) throws IOException {
        Matcher m = BILI.matcher(link);
        if (m.matches()) {
            if (m.group(1) != null) {
                String id = m.group(1), param = id.startsWith("BV") ? "bvid=" + id : "aid=" + id.substring(2);
                JsonObject data = response(job.text("https://api.bilibili.com/x/web-interface/view?" + param), "code", "0");
                String p = HttpUrl.get(link).queryParameter("p"); int part = 1;
                try { if (p != null) part = Integer.parseInt(p); } catch (RuntimeException e) { throw new IOException("Invalid Bilibili part", e); }
                JsonArray pages = array(data, "pages"); if (part < 1 || part > pages.size()) throw new IOException("Missing Bilibili part");
                JsonObject page = pages.get(part - 1).getAsJsonObject();
                return video("bilibili", str(data, "title"), str(page, "part"), str(page, "cid"), number(page, "duration"));
            }
            String id = m.group(2), param = id.startsWith("ep") ? "ep_id=" : "season_id=";
            JsonObject root = json(job.text("https://api.bilibili.com/pgc/view/web/season?" + param + id.substring(2)));
            if (!"0".equals(str(root, "code"))) throw new IOException("Bilibili programme unavailable");
            JsonObject data = obj(root, "result"); List<JsonElement> episodes = new ArrayList<>(array(data, "episodes").asList());
            for (var section : array(data, "section")) episodes.addAll(array(section.getAsJsonObject(), "episodes").asList());
            for (var e : episodes) { JsonObject ep = e.getAsJsonObject();
                if (id.startsWith("ep") ? id.substring(2).equals(str(ep, "id")) : episodes.size() == 1)
                    return video("bilibili", str(data, "title"), str(ep, "show_title"), str(ep, "cid"), (number(ep, "duration") + 999) / 1000);
            }
            throw new IOException("Use an explicit Bilibili episode link");
        }
        m = YOUKU.matcher(link);
        if (m.matches()) {
            String id = m.group(1) == null ? HttpUrl.get(link).queryParameter("vid") : m.group(1);
            if (id == null || !id.matches("[A-Za-z0-9=_-]{8,80}")) throw new IOException("Invalid Youku video ID");
            JsonObject data = json(job.text("https://openapi.youku.com/v2/videos/show.json?client_id=53e6cc67237fc59a&package=com.huawei.hwvplayer.youku&ext=show&video_id=" + java.net.URLEncoder.encode(id, StandardCharsets.UTF_8)));
            if (!id.equals(str(data, "id"))) throw new IOException("Youku video unavailable");
            return video("youku", str(data, "title"), str(obj(data, "show"), "seq"), id, (int)Math.ceil(decimal(data, "duration")));
        }
        m = GAMER.matcher(link);
        if (m.matches()) {
            JsonObject root = json(job.text("https://api.gamer.com.tw/anime/v1/video.php?videoSn=" + m.group(1)));
            JsonObject data = obj(obj(root, "data"), "video"); if (str(data, "title").isEmpty()) throw new IOException("Gamer video unavailable");
            return new Video("gamer", str(data, "title"), "", m.group(1), 0);
        }
        m = TENCENT.matcher(link);
        if (m.matches()) return new Video("tencent", "腾讯视频", m.group(1), m.group(1), 0);
        if (isIqiyi(link)) {
            String id = iqiyiId(link);
            JsonObject data = response(job.text("https://pcw-api.iqiyi.com/video/video/baseinfo/" + id), "code", "A00000");
            if (!id.equals(str(data, "tvId"))) throw new IOException("iQiyi video identity mismatch");
            return new Video("iqiyi", str(data, "name"), str(data, "subtitle"), id, 0);
        }
        throw new IOException("Unsupported platform video link");
    }
    static String iqiyiId(String link) throws IOException {
        if (!isIqiyi(link)) throw new IOException("Invalid iQiyi link");
        try {
            HttpUrl url = HttpUrl.get(link);
            String shared = url.queryParameter("shareId");
            if (shared == null) shared = url.queryParameter("positiveId");
            long id;
            if (shared != null) id = Long.parseLong(new String(Base64.getDecoder().decode(shared), StandardCharsets.UTF_8));
            else {
                String path = url.encodedPath(); String value = path.substring(path.indexOf('_') + 1, path.indexOf(".html"));
                // Same reversible public video URL encoding used by iQiyi's web player.
                id = Long.parseLong(value, 36) ^ 0x75706971676cL;
                if (id < 900000) id = Math.multiplyExact(id + 900000, 100);
            }
            if (id < 10000) throw new IOException("Invalid iQiyi video ID");
            return Long.toString(id);
        } catch (RuntimeException e) { throw new IOException("Invalid iQiyi video ID", e); }
    }
    private static Video video(String source, String title, String episode, String id, int duration) throws IOException {
        if (title.isBlank() || id.isBlank() || duration < 1 || duration > 21600) throw new IOException("Invalid platform video metadata");
        return new Video(source, title, episode, id, duration);
    }
    static JsonObject json(String text) throws IOException {
        try { return JsonParser.parseString(text).getAsJsonObject(); } catch (RuntimeException e) { throw new IOException("Invalid platform JSON", e); }
    }
    static List<IqiyiDanmakuCodec.Comment> biliComments(byte[] bytes) throws IOException {
        List<IqiyiDanmakuCodec.Comment> rows = new ArrayList<>();
        var root = new IqiyiDanmakuCodec.Proto(bytes, 0, bytes.length);
        while (root.next()) {
            if (root.field != 1 || root.wire != 2) continue;
            var item = root.child(); long time = -1; int color = 0xffffff, mode = 1; String content = "";
            while (item.next()) {
                if (item.field == 2 && item.wire == 0) time = item.number;
                else if (item.field == 3 && item.wire == 0) mode = (int)item.number;
                else if (item.field == 5 && item.wire == 0) color = (int)item.number;
                else if (item.field == 7 && item.wire == 2) content = item.string();
            }
            if (time >= 0 && time <= 21600000 && mode >= 1 && mode <= 6 && !content.isBlank() && content.length() <= 500)
                rows.add(new IqiyiDanmakuCodec.Comment(time / 1000d, color & 0xffffff, content));
        } return rows;
    }
    static Request youkuRequest(String vid, int segment, String token, long time) throws IOException {
        JsonObject msg = new JsonObject(); msg.addProperty("ctime", time); msg.addProperty("ctype", 10004); msg.addProperty("cver", "v1.0");
        msg.addProperty("guid", ""); msg.addProperty("mat", segment); msg.addProperty("mcount", 1); msg.addProperty("pid", 0);
        msg.addProperty("sver", "3.1.0"); msg.addProperty("type", 1); msg.addProperty("vid", vid);
        String encoded = Base64.getEncoder().encodeToString(msg.toString().getBytes(StandardCharsets.UTF_8));
        msg.addProperty("msg", encoded); msg.addProperty("sign", md5(encoded + "MkmC9SoIw6xCkSKHhJ7b5D2r51kBiREr"));
        String data = msg.toString(); HttpUrl url = HttpUrl.get("https://acs.youku.com/h5/mopen.youku.danmu.list/1.0/").newBuilder()
                .addQueryParameter("jsv", "2.5.6").addQueryParameter("appKey", "24679788").addQueryParameter("t", Long.toString(time))
                .addQueryParameter("sign", md5(token + "&" + time + "&24679788&" + data)).addQueryParameter("api", "mopen.youku.danmu.list")
                .addQueryParameter("v", "1.0").addQueryParameter("type", "originaljson").addQueryParameter("dataType", "json").build();
        return new Request.Builder().url(url).post(new FormBody.Builder().add("data", data).build()).build();
    }
    static List<IqiyiDanmakuCodec.Comment> youkuComments(String text) throws IOException {
        JsonObject root = json(text), data = obj(root, "data");
        if (array(root, "ret").asList().stream().noneMatch(e -> e.getAsString().startsWith("SUCCESS"))) throw new IOException("Youku comment request rejected");
        JsonObject result = json(str(data, "result"));
        if (!"1".equals(str(result, "code")) || !obj(result, "data").has("result")) throw new IOException("Youku comment segment unavailable");
        List<IqiyiDanmakuCodec.Comment> rows = new ArrayList<>();
        for (var e : array(obj(result, "data"), "result")) {
            JsonObject item = e.getAsJsonObject(); double time = decimal(item, "playat") / 1000d; String content = str(item, "content"); int color = 0xffffff;
            try { JsonObject prop = json(str(item, "propertis")); if (prop.has("color")) color = number(prop, "color") & 0xffffff; } catch (IOException ignored) { }
            if (time >= 0 && time <= 21600 && !content.isBlank() && content.length() <= 500) rows.add(new IqiyiDanmakuCodec.Comment(time, color, content));
        } return rows;
    }
    static List<IqiyiDanmakuCodec.Comment> gamerComments(String text) throws IOException {
        JsonObject data = obj(json(text), "data"); if (!data.has("danmu")) throw new IOException("Gamer comments unavailable");
        List<IqiyiDanmakuCodec.Comment> rows = new ArrayList<>();
        for (var e : array(data, "danmu")) {
            JsonObject item = e.getAsJsonObject(); double time = decimal(item, "time") / 10d; String content = str(item, "text"); int color = 0xffffff;
            try { color = Integer.parseInt(str(item, "color").replace("#", ""), 16) & 0xffffff; } catch (RuntimeException ignored) { }
            if (time >= 0 && time <= 21600 && !content.isBlank() && content.length() <= 500) rows.add(new IqiyiDanmakuCodec.Comment(time, color, content));
        } return rows;
    }
    private PlatformDanmaku() { }
}
