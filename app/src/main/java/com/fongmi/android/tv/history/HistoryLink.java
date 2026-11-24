package com.fongmi.android.tv.history;

import android.content.SharedPreferences;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.setting.Setting;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

public final class HistoryLink {
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean SAVE_QUEUED = new AtomicBoolean();
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static final OkHttpClient NET = new OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
            .followRedirects(false).followSslRedirects(false).callTimeout(10, TimeUnit.SECONDS).build();
    private static final String MISSING = HistoryPeer.MISSING;
    private static final Runnable TICK = HistoryLink::tick;
    private static volatile String status = "";
    private static volatile Conflict conflict;

    public record Conflict(String key, String config, String phone, String tv, String description) { }

    public static String status() { return status; }
    public static Conflict conflict() { return conflict; }
    public static boolean paired() { return !HistoryPeer.prefs().getString("peer", "").isEmpty(); }

    static synchronized void accountChanged() {
        conflict = null;
        status = "";
    }

    private static String identity() {
        String profile = AccountSession.profile();
        return profile.isEmpty() ? HistoryPeer.prefs().getString("peer", "") : "account/" + profile;
    }

    public static synchronized void forget() {
        HistoryPeer.forget();
        conflict = null;
        status = "";
    }

    private static URI endpoint(String code) throws Exception {
        URI uri = new URI(code);
        String host = uri.getHost();
        if (!"http".equals(uri.getScheme()) || host == null || !host.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")
                || !InetAddress.getByName(host).isSiteLocalAddress() || uri.getUserInfo() != null
                || uri.getPort() < 1 || uri.getPort() > 65535 || !HistoryPeer.PATH.equals(uri.getRawPath())
                || uri.getQuery() != null || uri.getRawFragment() == null || !uri.getRawFragment().matches("[A-Za-z0-9_-]{43}"))
            throw new IOException("请扫描电视观看历史页的配对码");
        return uri;
    }

    public static void pair(String code, Consumer<String> done) {
        WORK.execute(() -> {
            try {
                URI uri = endpoint(code);
                Config cfg = current();
                // Prove that the TV holds the scanned key before replacing an existing pairing.
                call(uri, cfg.getUrl(), "index", new JsonObject());
                synchronized (HistoryLink.class) {
                    if (!AccountSession.profile().isEmpty()) throw new IOException("当前正在使用账号续播");
                    if (!code.equals(HistoryPeer.prefs().getString("peer", ""))) forget();
                    if (!HistoryPeer.prefs().edit().putString("peer", code).commit()) throw new IOException("未能保存配对");
                }
                run(null, false, done);
            } catch (Exception e) { finish(message(e), done); }
        });
    }

    private static Config current() throws IOException {
        Config cfg = VodConfig.get().getConfig();
        if (cfg == null || cfg.getUrl() == null || cfg.getUrl().isEmpty()) throw new IOException("点播订阅正在加载，请稍后重试");
        if (Setting.isIncognito()) throw new IOException("无痕模式下暂停历史同步");
        return cfg;
    }

    private static JsonObject call(URI uri, String config, String op, JsonObject request) throws Exception {
        if (Setting.isIncognito()) throw new IOException("无痕模式下暂停历史同步");
        String nonce = UUID.randomUUID().toString();
        request.addProperty("nonce", nonce);
        request.addProperty("op", op);
        request.addProperty("config", config);
        String sealed = HistoryCipher.seal(uri.getRawFragment(), "request", request.toString());
        String url = uri.getScheme() + "://" + uri.getRawAuthority() + HistoryPeer.PATH;
        Request http = new Request.Builder().url(url).post(RequestBody.create(MediaType.get("text/plain"), sealed)).build();
        try (var response = NET.newCall(http).execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IOException("电视未接受同步，请检查配对或无痕模式");
            var body = response.body();
            if (body.contentLength() > HistoryPeer.MAX_PACKET || body.source().request(HistoryPeer.MAX_PACKET + 1L)) throw new IOException("电视返回的历史清单过大");
            JsonObject result = JsonParser.parseString(HistoryCipher.open(uri.getRawFragment(), nonce, body.string())).getAsJsonObject();
            if (result.has("error")) throw new IOException(result.get("error").getAsString());
            return result;
        }
    }

    private static JsonObject exchange(AccountHistoryRemote cloud, URI uri, String config, String op, JsonObject data) throws Exception {
        if (Setting.isIncognito()) throw new IOException("无痕模式下暂停历史同步");
        return cloud == null ? call(uri, config, op, data) : cloud.call(op, data);
    }

    private static String rev(JsonObject index, String key) {
        return index.has(key) ? index.get(key).getAsString() : MISSING;
    }

    private static void run(Conflict choice, boolean preferPhone, Consumer<String> done) {
        try {
            String code, profile;
            AccountSession.Login login;
            synchronized (HistoryLink.class) {
                code = identity();
                profile = AccountSession.profile();
                login = AccountSession.current();
            }
            URI uri = login == null ? endpoint(code) : null;
            Config cfg = current();
            String config = cfg.getUrl();
            int cid = cfg.getId();
            AccountHistoryRemote cloud = login == null ? null : new AccountHistoryRemote(login, config);
            SharedPreferences prefs = login == null ? HistoryPeer.prefs() : AccountSession.prefs();
            String commonKey = "common." + profile + HistoryPeer.hash(config);
            JsonObject common = JsonParser.parseString(prefs.getString(commonKey, "{}")).getAsJsonObject();
            JsonObject remote = exchange(cloud, uri, config, "index", new JsonObject()).getAsJsonObject("index");
            JsonObject local = HistoryPeer.index(profile, cid);
            Set<String> keys = new LinkedHashSet<>(local.keySet());
            keys.addAll(remote.keySet());
            keys.addAll(common.keySet());
            int conflicts = 0, changed = 0, pending = 0;
            Conflict first = null;
            boolean dirty = false;
            for (String key : keys) {
                checkPeer(code);
                String phone = rev(local, key), tv = rev(remote, key);
                var decision = HistoryReconcile.decide(rev(common, key), phone, tv);
                JsonObject data = new JsonObject();
                data.addProperty("key", key);
                History here = null, there = null;
                if (decision != HistoryReconcile.Decision.SAME) {
                    here = HistoryPeer.read(profile, cid, key);
                    if (!HistoryPeer.revision(here).equals(phone)) { pending++; continue; }
                    if (decision != HistoryReconcile.Decision.PUSH) {
                        there = App.gson().fromJson(exchange(cloud, uri, config, "read", data.deepCopy()).get("row"), History.class);
                        if (!HistoryPeer.revision(there).equals(tv)) { pending++; continue; }
                    }
                }
                if (decision == HistoryReconcile.Decision.CONFLICT) {
                    Conflict found = new Conflict(key, config, phone, tv, (here == null ? there : here).getVodName()
                            + (cloud == null ? "\n手机 · " : "\n本设备 · ") + describe(here) + (cloud == null ? "\n电视 · " : "\n账号 · ") + describe(there));
                    if (found.equals(choice)) decision = preferPhone ? HistoryReconcile.Decision.PUSH : HistoryReconcile.Decision.PULL;
                    else {
                        if (first == null) first = found;
                        conflicts++;
                        continue;
                    }
                }
                String agreed = phone;
                if (decision == HistoryReconcile.Decision.PUSH) {
                    data.addProperty("expected", tv);
                    data.add("row", App.gson().toJsonTree(here));
                    if (!exchange(cloud, uri, config, "write", data).get("applied").getAsBoolean()) { pending++; continue; }
                    changed++;
                } else if (decision == HistoryReconcile.Decision.PULL) {
                    History value = there;
                    synchronized (HistoryLink.class) {
                        checkPeer(code);
                        if (!AppDatabase.get().runInTransaction(() -> HistoryPeer.apply(profile, cid, key, phone, value))) { pending++; continue; }
                    }
                    RefreshEvent.history();
                    agreed = tv;
                    changed++;
                }
                if (!agreed.equals(rev(common, key))) {
                    if (MISSING.equals(agreed)) common.remove(key); else common.addProperty(key, agreed);
                    dirty = true;
                }
                if (dirty && changed > 0) {
                    persist(code, prefs, commonKey, common);
                    dirty = false;
                }
            }
            synchronized (HistoryLink.class) {
                checkPeer(code);
                if (dirty) persist(code, prefs, commonKey, common);
                conflict = first;
                finish(conflicts > 0 ? conflicts + " 部影片在两端都有改动，请选择续播进度"
                        : pending > 0 ? "播放进度正在变化，稍后继续同步" : "同步完成 · " + changed + " 条变更", done);
            }
        } catch (Exception e) { finish(message(e), done); }
    }

    private static synchronized void persist(String code, SharedPreferences prefs, String key, JsonObject common) throws IOException {
        checkPeer(code);
        if (!prefs.edit().putString(key, common.toString()).commit()) throw new IOException("未能保存同步状态");
    }

    private static void checkPeer(String code) throws IOException {
        if (Setting.isIncognito() || !code.equals(identity())) throw new IOException("配对或隐私设置已变更");
    }

    private static String describe(History row) {
        if (row == null) return "已删除";
        long seconds = Math.max(0L, row.getPosition()) / 1000;
        return row.getVodRemarks() + "  " + seconds / 60 + "分" + seconds % 60 + "秒";
    }

    private static String message(Exception e) {
        return e instanceof java.net.SocketException || e instanceof java.io.InterruptedIOException
                ? (AccountSession.profile().isEmpty() ? "电视暂时离线，连接同一局域网后会自动重试" : "账号服务暂时离线，联网后会自动重试") : "同步未完成 · " + (e.getMessage() == null ? "请重新扫描电视配对码" : e.getMessage());
    }

    private static void finish(String text, Consumer<String> done) {
        status = text;
        if (done != null) App.post(() -> done.accept(text));
    }

    public static void sync(Consumer<String> done) {
        if (identity().isEmpty()) return;
        if (!RUNNING.compareAndSet(false, true)) {
            if (done != null) App.post(() -> done.accept("正在同步观看历史"));
            return;
        }
        WORK.execute(() -> {
            try { run(null, false, done); } finally { RUNNING.set(false); }
        });
    }

    // An exchange already in flight may have indexed the history before the final save.
    // Always queue one more pass for an exit, even when the app is leaving the foreground.
    public static void afterSave() {
        if (identity().isEmpty() || !SAVE_QUEUED.compareAndSet(false, true)) return;
        WORK.execute(() -> {
            SAVE_QUEUED.set(false);
            if (!identity().isEmpty() && !Setting.isIncognito()) run(null, false, null);
        });
    }

    public static void resolve(Conflict item, boolean preferPhone, Consumer<String> done) {
        WORK.execute(() -> run(item, preferPhone, done));
    }

    public static void foreground(boolean visible) {
        if (visible) App.post(TICK, 650);
        else App.removeCallbacks(TICK);
    }

    private static void tick() {
        if (!identity().isEmpty() && !Setting.isIncognito()) sync(null);
        App.post(TICK, 15000);
    }
}
