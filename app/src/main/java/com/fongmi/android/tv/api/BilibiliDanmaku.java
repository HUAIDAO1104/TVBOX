package com.fongmi.android.tv.api;

import static com.fongmi.android.tv.api.MangoDanmaku.*;

import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.playback.vod.DanmakuMatch;
import com.fongmi.android.tv.playback.vod.DanmakuMatchContext;
import com.google.gson.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import okhttp3.*;

/** Public animation index -> authoritative season catalogue -> existing comment transport. */
public final class BilibiliDanmaku {
    public static final String SOURCE = "bilibili-direct";
    private static final Pattern VIDEO = Pattern.compile("^bilibili-danmaku://video/([1-9][0-9]{0,14})/([1-9][0-9]{0,17})/([1-9][0-9]{0,4})$");
    private static final ExecutorService SEARCH = Executors.newFixedThreadPool(2);
    private static final ExecutorService CACHE_LOOKUP = Executors.newSingleThreadExecutor();
    private static final ExecutorService COMMENTS = Executors.newFixedThreadPool(2);
    private static final Object REQUEST_START = new Object();
    private static long nextRequest;
    private enum Feed {
        SNAPSHOT, SEGMENT;
        long retryAfter;
    }
    private static final Pattern EDITION = Pattern.compile("\\s*[(（]?((?:19|20)\\d{2}版|重制版)[)）]?$");
    private record Work(String id, String title) {}
    private record Page(int total, List<Work> works) {}
    private record CachedPage(long time, Page page) {}
    private static final Map<String, CachedPage> PAGES = new LinkedHashMap<>();
    private record CachedCatalogue(long time, List<Danmaku> rows) {}
    private static final Map<String, CachedCatalogue> CATALOGUES = new LinkedHashMap<>();

    public static boolean supports(String source) {
        Matcher m = VIDEO.matcher(source == null ? "" : source);
        return m.matches() && Integer.parseInt(m.group(3)) <= 21600;
    }

    /** Revalidate saved IDs against one authoritative season before comparing its cuts. */
    public static NativeDanmaku.Job playbackCatalogue(String source, IqiyiDanmaku.Callback<List<Danmaku>> callback) {
        var job = new NativeDanmakuRequest(15000, "https://www.bilibili.com/", NativeDanmakuRequest.CLIENT);
        job.start(SEARCH, () -> {
            Matcher video = VIDEO.matcher(source);
            if (!supports(source) || !video.matches()) throw new IOException("Invalid Bilibili playback identity");
            synchronized (CATALOGUES) {
                for (CachedCatalogue value : CATALOGUES.values()) {
                    if (System.nanoTime() - value.time() < TimeUnit.MINUTES.toNanos(5)
                            && value.rows().stream().anyMatch(row -> row.getUrl().equals(source))) return copy(value.rows());
                }
            }
            String text = job.text("https://api.bilibili.com/pgc/view/web/season?ep_id=" + video.group(1));
            String id = str(obj(PlatformDanmaku.json(text), "result"), "season_id");
            if (!id.matches("[1-9][0-9]{0,11}")) throw new IOException("Missing Bilibili season identity");
            List<Danmaku> rows = catalogue(text, id);
            if (rows.stream().noneMatch(row -> row.getUrl().startsWith("bilibili-danmaku://video/" + video.group(1) + "/")))
                throw new IOException("Bilibili episode is absent from its season");
            synchronized (CATALOGUES) {
                CATALOGUES.put(id, new CachedCatalogue(System.nanoTime(), copy(rows)));
                while (CATALOGUES.size() > 24) CATALOGUES.remove(CATALOGUES.keySet().iterator().next());
            }
            return rows;
        }, callback);
        return job;
    }

    /** Only cuts of the exact work/season/year/episode may compete on duration. */
    public static List<Danmaku> timelineCandidates(DanmakuMatchContext context, List<Danmaku> catalogue) {
        Matcher edition = EDITION.matcher(context.getTitle());
        String requestedEdition = edition.find() ? edition.group(1) : "";
        String title = EDITION.matcher(context.getTitle()).replaceFirst("").trim();
        if (DanmakuMatch.episodeNumber(context.getResolvedEpisode()) == null) return List.of();
        List<Danmaku> rows = new ArrayList<>();
        for (Danmaku row : catalogue) {
            if (!supports(row.getUrl())) continue;
            if (!requestedEdition.isEmpty() && !row.getName().contains(requestedEdition)) continue;
            rows.add(row);
        }
        return DanmakuMatch.ranked(title, context.getYear(), context.getType(), context.getResolvedEpisode(), rows,
                row -> row.getName().replaceFirst("\\s*(?:(?:19|20)\\d{2}版|重制版)\\s*(?=[(（](?:19|20)\\d{2}[)）])", ""));
    }

    public static Danmaku chooseTimeline(List<Danmaku> candidates, long durationMs) {
        if (candidates.size() == 1) return candidates.get(0);
        if (durationMs <= 0) return officialCut(candidates);
        Danmaku best = null;
        long first = Long.MAX_VALUE, second = Long.MAX_VALUE;
        for (Danmaku row : candidates) {
            Matcher video = VIDEO.matcher(row.getUrl());
            if (!video.matches()) continue;
            long difference = Math.abs(durationMs - Long.parseLong(video.group(3)) * 1000);
            if (difference < first) { second = first; first = difference; best = row; }
            else second = Math.min(second, difference);
        }
        // Encoding/container rounding is small. Removed openings, merged episodes and equally
        // close cuts are not enough evidence to invent an episode mapping or stretch time.
        return first <= 5000 && second - first >= 3000 ? best : officialCut(candidates);
    }
    private static Danmaku officialCut(List<Danmaku> candidates) {
        // Duration is only a tie-breaker within an already verified episode. Missing,
        // edited intros or transport timing must not disable the official main catalogue.
        List<Danmaku> main = candidates.stream().filter(row -> !row.getName().split("(?i)\\s*from\\s+", 2)[0]
                .matches(".*(?:19|20)\\d{2}版.*|.*重制版.*")).toList();
        return main.size() == 1 ? main.get(0) : null;
    }

    public static NativeDanmaku.Job document(String source, java.util.function.IntConsumer progress,
                                              IqiyiDanmaku.Callback<byte[]> callback) {
        var job = new NativeDanmakuRequest(75000, "https://www.bilibili.com/", NativeDanmakuRequest.CLIENT);
        job.start(NativeDanmakuRequest.DOCUMENTS, () -> {
            Matcher m = VIDEO.matcher(source);
            if (!supports(source) || !m.matches()) throw new IOException("Invalid Bilibili document identity");
            int seconds = Integer.parseInt(m.group(3));
            return comments(job, m.group(2), seconds, progress);
        }, callback);
        return job;
    }

    static byte[] comments(NativeDanmakuRequest job, String cid, int seconds,
                           java.util.function.IntConsumer progress) throws Exception {
        awaitRequest(job, Feed.SNAPSHOT);
        List<IqiyiDanmakuCodec.Comment> snapshot;
        try {
            snapshot = BilibiliCommentPool.parse(job.bytes("https://comment.bilibili.com/" + cid + ".xml", 8 * 1024 * 1024), cid);
        } catch (IOException unavailable) {
            job.check();
            if (unavailable instanceof PlatformDanmaku.RejectedComments rejection) throw noteRefusal(Feed.SNAPSHOT, rejection);
            if (unavailable instanceof NativeDanmakuRequest.HttpFailure http &&
                    (http.code == 401 || http.code == 403 || http.code == 412 || http.code == 429 || http.code == 451)) throw unavailable;
            snapshot = List.of();
        }
        if (snapshot.isEmpty()) job.markFallbackOnly();
        return job.segments((seconds + 359) / 360, progress, index -> {
            awaitRequest(job, Feed.SEGMENT);
            try {
                return PlatformDanmaku.biliComments(job.bytes("https://api.bilibili.com/x/v2/dm/web/seg.so?type=1&oid="
                        + cid + "&segment_index=" + (index + 1), 4_000_000));
            } catch (PlatformDanmaku.RejectedComments rejection) {
                throw noteRefusal(Feed.SEGMENT, rejection);
            }
        }, COMMENTS, 2, snapshot, 150_000);
    }
    private static PlatformDanmaku.RejectedComments noteRefusal(Feed feed, PlatformDanmaku.RejectedComments rejection) {
        if (rejection.code.equals("-352") || rejection.code.equals("-412")) synchronized (REQUEST_START) {
            feed.retryAfter = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        }
        return rejection;
    }

    private static void awaitRequest(NativeDanmakuRequest job, Feed feed) throws IOException {
        long wait;
        synchronized (REQUEST_START) {
            long now = System.nanoTime();
            if (now < feed.retryAfter) throw new PlatformDanmaku.RejectedComments("temporary cooldown after service refusal");
            long start = Math.max(now, nextRequest);
            wait = start - now;
            nextRequest = start + TimeUnit.MILLISECONDS.toNanos(500);
        }
        try { TimeUnit.NANOSECONDS.sleep(wait); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.InterruptedIOException("Cancelled Bilibili request"); }
        job.check();
        synchronized (REQUEST_START) {
            if (System.nanoTime() < feed.retryAfter) throw new PlatformDanmaku.RejectedComments("temporary cooldown after service refusal");
        }
    }

    public static NativeDanmaku.Job search(String query, String identity, String year, String type,
                                            String episode, IqiyiDanmaku.Callback<List<Danmaku>> callback) {
        return search(query, identity, year, type, episode, callback, NativeDanmakuRequest.CLIENT, true);
    }
    static NativeDanmaku.Job search(String query, String identity, String year, String type,
                                     String episode, IqiyiDanmaku.Callback<List<Danmaku>> callback,
                                     OkHttpClient client, boolean cache) {
        var job = new NativeDanmakuRequest(18000, "https://www.bilibili.com/", client);
        // Automatic discovery already fetched the complete official season. Reopening
        // manual search must not wait behind unrelated index scans in the worker queue.
        // Cached catalogues still require title/episode ranking. Never run that work inline
        // on the caller (normally the UI thread), or under CatalogueSearch's cancellation lock.
        CACHE_LOOKUP.execute(() -> {
            List<Danmaku> saved = cache ? cachedWork(query) : List.of();
            try { job.check(); } catch (IOException cancelled) { return; }
            if (!saved.isEmpty()) {
                if (!episode.isBlank() && !timelineCandidates(new DanmakuMatchContext(identity, year, type, episode), saved).isEmpty()) {
                    callback.onSuccess(saved);
                    return;
                }
                callback.onPartial(saved);
            }
            job.start(SEARCH, () -> {
                if (!type.isBlank() && !type.matches("(?i).*(动漫|动画|番剧|国漫|国创|日漫|卡通|年番|anime).*")) return List.of();
                String base = EDITION.matcher(query).replaceFirst("").trim();
                if (base.length() < 2) return List.of();
                Set<String> visited = new HashSet<>();
                Map<String, Danmaku> rows = new LinkedHashMap<>();
                int[] kinds = {4, 1}, totals = {100, 100};
                // Discover works through the platform's public animation catalogue.
                // Cache small index pages, not a list of hard-coded programmes or episode IDs.
                for (int page = 1; page <= 100; page++) {
                    boolean more = false;
                    for (int k = 0; k < kinds.length; k++) {
                        if ((page - 1) * 100 >= totals[k]) continue;
                        more = true;
                        Page index = index(job, kinds[k], page, cache);
                        totals[k] = index.total();
                        for (Work work : index.works()) {
                            if (!DanmakuMatch.isSameBaseWork(base, work.title()) || !visited.add(work.id())) continue;
                            List<Danmaku> catalogue = season(job, work.id(), cache);
                            for (Danmaku item : catalogue) {
                                rows.put(item.getUrl(), item);
                            }
                            job.check();
                            if (!rows.isEmpty()) callback.onPartial(new ArrayList<>(rows.values()));
                        }
                        // A matched catalogue contains the entire season, including episode 10+
                        // and explicit alternative editions. No need to scan unrelated programmes.
                        if (!rows.isEmpty() && (episode.isBlank() || !DanmakuMatch.ranked(identity, year, type, episode,
                                new ArrayList<>(rows.values()), Danmaku::getMatchName).isEmpty())) return new ArrayList<>(rows.values());
                    }
                    if (!more) break;
                }
                return new ArrayList<>(rows.values());
            }, callback);
        });
        return job;
    }
    private static List<Danmaku> cachedWork(String query) {
        String base = EDITION.matcher(query).replaceFirst("").trim();
        Map<String, Danmaku> rows = new LinkedHashMap<>();
        synchronized (CATALOGUES) {
            for (CachedCatalogue value : CATALOGUES.values()) {
                if (System.nanoTime() - value.time() >= TimeUnit.MINUTES.toNanos(5) || value.rows().isEmpty()
                        || !DanmakuMatch.isSameBaseWork(base, value.rows().get(0).getName())) continue;
                for (Danmaku row : value.rows()) rows.put(row.getUrl(), row);
            }
        }
        return copy(new ArrayList<>(rows.values()));
    }
    private static List<Danmaku> season(NativeDanmakuRequest job, String id, boolean cache) throws IOException {
        if (cache) synchronized (CATALOGUES) {
            CachedCatalogue value = CATALOGUES.get(id);
            if (value != null && System.nanoTime() - value.time() < TimeUnit.MINUTES.toNanos(5)) return copy(value.rows());
        }
        List<Danmaku> rows = catalogue(job.text("https://api.bilibili.com/pgc/view/web/season?season_id=" + id), id);
        if (cache && !rows.isEmpty()) synchronized (CATALOGUES) {
            CATALOGUES.put(id, new CachedCatalogue(System.nanoTime(), copy(rows)));
            while (CATALOGUES.size() > 24) CATALOGUES.remove(CATALOGUES.keySet().iterator().next());
        }
        return rows;
    }
    private static List<Danmaku> copy(List<Danmaku> source) {
        List<Danmaku> rows = new ArrayList<>();
        for (Danmaku item : source) {
            Danmaku row = new Danmaku(); row.setName(item.getName()); row.setUrl(item.getUrl()); row.setSourceKey(SOURCE);
            row.setVerifiedIdentity(item.hasVerifiedIdentity()); rows.add(row);
        }
        return rows;
    }
    private static Page index(NativeDanmakuRequest job, int kind, int page, boolean cache) throws IOException {
        String key = kind + ":" + page;
        if (cache) synchronized (PAGES) {
            CachedPage entry = PAGES.get(key);
            if (entry != null && System.nanoTime() - entry.time() < TimeUnit.HOURS.toNanos(6)) return entry.page();
        }
        String url = "https://api.bilibili.com/pgc/season/index/result?season_type=" + kind
                + "&type=1&st=" + kind + "&order=3&sort=0&page=" + page + "&pagesize=100";
        Page value = parseIndex(job.text(url));
        if (cache) synchronized (PAGES) {
            PAGES.put(key, new CachedPage(System.nanoTime(), value));
            while (PAGES.size() > 200) PAGES.remove(PAGES.keySet().iterator().next());
        }
        return value;
    }
    private static Page parseIndex(String text) throws IOException {
        JsonObject data = response(text, "code", "0");
        if (!data.has("total") || !data.has("list") || !data.get("list").isJsonArray())
            throw new IOException("Missing Bilibili index data");
        int total = number(data, "total");
        if (total < 0 || total > 10000) throw new IOException("Bilibili index exceeds limit");
        List<Work> works = new ArrayList<>();
        for (JsonElement element : array(data, "list")) {
            JsonObject row = element.getAsJsonObject();
            String id = str(row, "season_id"), title = str(row, "title");
            int kind = number(row, "season_type");
            if (id.matches("[1-9][0-9]{0,11}") && !title.isBlank() && (kind == 1 || kind == 4)) works.add(new Work(id, title));
        }
        if (works.size() > 100) throw new IOException("Bilibili index page exceeds limit");
        return new Page(total, List.copyOf(works));
    }
    static List<Danmaku> catalogue(String text, String expectedId) throws IOException {
        JsonObject root = PlatformDanmaku.json(text);
        if (!"0".equals(str(root, "code"))) throw new IOException("Bilibili catalogue unavailable: " + str(root, "code"));
        JsonObject work = obj(root, "result");
        if (!expectedId.equals(str(work, "season_id")) || !List.of(1, 4).contains(number(work, "type")))
            throw new IOException("Bilibili animation identity mismatch");
        String title = str(work, "title"), year = str(obj(work, "publish"), "pub_time");
        year = year.matches("(?:19|20)\\d{2}.*") ? year.substring(0, 4) : "";
        if (title.isBlank()) throw new IOException("Missing Bilibili work title");
        Map<String, Danmaku> rows = new LinkedHashMap<>();
        addEpisodes(rows, title, year, array(work, "episodes"), false);
        // Alternate cuts are separate work identities. Never mingle trailers, clips or an
        // older cut with the current official timeline just because the episode number agrees.
        for (JsonElement element : array(work, "section")) {
            JsonObject section = element.getAsJsonObject(); String edition = str(section, "title");
            if (edition.matches("(?:19|20)\\d{2}版|重制版")) addEpisodes(rows, title + " " + edition, year, array(section, "episodes"), true);
        }
        if (rows.size() > 2000) throw new IOException("Bilibili episode catalogue exceeds limit");
        return new ArrayList<>(rows.values());
    }
    private static void addEpisodes(Map<String, Danmaku> rows, String title, String year, JsonArray episodes, boolean alternate) {
        for (JsonElement element : episodes) {
            JsonObject ep = element.getAsJsonObject();
            String id = str(ep, "id"), cid = str(ep, "cid"), number = str(ep, "title"), extra = str(ep, "long_title");
            if (!id.matches("[1-9][0-9]{0,14}") || !cid.matches("[1-9][0-9]{0,17}") || !number.matches("0{0,3}[1-9][0-9]{0,3}")
                    || number(ep, "duration") < 1000 || number(ep, "duration") > 21600000 || (number(ep, "section_type") != 0 && !(alternate && number(ep, "section_type") == 2))
                    || (str(ep, "badge") + extra).matches(".*(预告|花絮|PV|先导|片段|解说|混剪).*")) continue;
            Danmaku row = new Danmaku();
            row.setName(title + "(" + year + ")【动漫】from bilibili - 【bilibili】 第" + number + "集" + (extra.isBlank() ? "" : "：" + extra));
            row.setSourceKey(SOURCE);
            row.setUrl("bilibili-danmaku://video/" + id + "/" + cid + "/" + ((number(ep, "duration") + 999) / 1000));
            row.setVerifiedIdentity(true);
            rows.putIfAbsent(row.getUrl(), row);
        }
    }
    private BilibiliDanmaku() {}
}
