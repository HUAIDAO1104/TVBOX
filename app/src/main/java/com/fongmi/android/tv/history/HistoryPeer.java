package com.fongmi.android.tv.history;

import android.content.SharedPreferences;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.setting.Setting;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import fi.iki.elonen.NanoHTTPD;

public final class HistoryPeer {
    public static final String PATH = "/history-v2";
    static final String MISSING = HistoryReconcile.MISSING;
    static final int MAX_PACKET = 4_000_000;

    static SharedPreferences prefs() {
        return App.get().getSharedPreferences("paired_history", 0);
    }

    public static void startIfPaired() {
        if (!prefs().getString("receiverKey", "").isEmpty() && !Setting.isIncognito()) Server.get().start();
    }

    public static synchronized String pairingCode() {
        if (Setting.isIncognito() || !AccountSession.profile().isEmpty()) return "";
        Server.get().start();
        String address = Server.get().getAddress();
        if (address.isEmpty()) return "";
        String key = prefs().getString("receiverKey", "");
        if (key.isEmpty()) {
            key = HistoryCipher.newKey();
            if (!prefs().edit().putString("receiverKey", key).commit()) return "";
        }
        return address + PATH + "#" + key;
    }

    public static synchronized void forget() {
        prefs().edit().clear().commit();
    }

    static String revision(History item) {
        if (item == null) return MISSING;
        JsonObject row = App.gson().toJsonTree(item).getAsJsonObject();
        row.remove("cid");
        row.addProperty("key", HistoryKey.content(item.getKey()));
        // The mobile and TV release builds run R8 separately. Reflection field order is
        // not a wire format and must not turn identical records into two revisions.
        JsonObject canonical = new JsonObject();
        for (String field : new java.util.TreeSet<>(row.keySet())) canonical.add(field, row.get(field));
        return hash(canonical.toString());
    }

    static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP);
        } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    static JsonObject index(int cid) { return index("", cid); }

    static JsonObject index(String profile, int cid) {
        JsonObject result = new JsonObject();
        String after = "";
        while (true) {
            List<History> page = AppDatabase.get().getHistoryDao().pageScoped(profile, cid, after);
            for (History item : page) result.addProperty(HistoryKey.content(item.getKey()), revision(item));
            if (page.size() < 32) return result;
            after = page.get(page.size() - 1).getKey();
        }
    }

    static History read(int cid, String key) { return read("", cid, key); }

    static History read(String profile, int cid, String key) {
        return AppDatabase.get().getHistoryDao().findScoped(profile, cid, HistoryKey.local(key, cid));
    }

    // Both the comparison and the write run inside the caller's Room transaction.
    static boolean apply(int cid, String key, String expected, History value) {
        return apply("", cid, key, expected, value);
    }

    static boolean apply(String profile, int cid, String key, String expected, History value) {
        if (!revision(read(profile, cid, key)).equals(expected)) return false;
        if (value == null) AppDatabase.get().getHistoryDao().deleteScoped(profile, cid, HistoryKey.local(key, cid));
        else {
            if (!key.equals(HistoryKey.content(value.getKey())) || value.getVodName().isEmpty() || key.indexOf("@@@") <= 0
                    || key.endsWith("@@@")) throw new IllegalArgumentException("影片记录不完整");
            value.setProfile(profile);
            value.setKey(HistoryKey.local(key, cid));
            value.setEpisodeUrl(value.getEpisodeUrl());
            AppDatabase.get().getHistoryDao().insertOrUpdate(value.cid(cid));
        }
        return true;
    }

    static JsonObject handle(JsonObject request) {
        Config config = AppDatabase.get().getConfigDao().find(request.get("config").getAsString(), 0);
        JsonObject result = new JsonObject();
        if (config == null) {
            result.addProperty("error", "请先在电视上添加手机正在使用的点播订阅");
            return result;
        }
        int cid = config.getId();
        String op = request.get("op").getAsString();
        if (op.equals("index")) {
            result.add("index", index(cid));
        } else if (op.equals("read")) {
            result.add("row", App.gson().toJsonTree(read(cid, request.get("key").getAsString())));
        } else if (op.equals("write")) {
            String key = request.get("key").getAsString();
            History row = App.gson().fromJson(request.get("row"), History.class);
            boolean applied = AppDatabase.get().runInTransaction(() -> apply(cid, key, request.get("expected").getAsString(), row));
            result.addProperty("applied", applied);
            if (applied) RefreshEvent.history();
        } else throw new IllegalArgumentException();
        return result;
    }

    public static NanoHTTPD.Response serve(NanoHTTPD.IHTTPSession session) {
        try {
            if (session.getMethod() != NanoHTTPD.Method.POST) throw new IOException();
            int length = Integer.parseInt(session.getHeaders().get("content-length"));
            if (length < 28 || length > MAX_PACKET) throw new IOException();
            byte[] body = new byte[length];
            new DataInputStream(session.getInputStream()).readFully(body);
            synchronized (HistoryPeer.class) {
                String key = prefs().getString("receiverKey", "");
                if (Setting.isIncognito() || !AccountSession.profile().isEmpty() || key.isEmpty()) throw new IOException();
                String json = HistoryCipher.open(key, "request", new String(body, StandardCharsets.US_ASCII));
                JsonObject request = JsonParser.parseString(json).getAsJsonObject();
                JsonObject response = handle(request);
                String sealed = HistoryCipher.seal(key, request.get("nonce").getAsString(), response.toString());
                return Nano.ok(sealed);
            }
        } catch (Exception invalid) {
            return Nano.error(NanoHTTPD.Response.Status.BAD_REQUEST, "同步请求未通过，请检查配对状态");
        }
    }
}
