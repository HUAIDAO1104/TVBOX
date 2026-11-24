package com.fongmi.android.tv.history;

import android.content.SharedPreferences;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.security.KeystoreCipher;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

/** Session secrets are stored separately from exportable app preferences. */
public final class AccountSession {
    static final class Login {
        final String server, id, username, token, dataKey;
        Login(String server, JsonObject data) {
            this.server = server;
            id = java.util.UUID.fromString(data.get("id").getAsString()).toString();
            username = data.get("username").getAsString();
            token = data.get("token").getAsString();
            dataKey = data.get("dataKey").getAsString();
            if (!username.matches("[a-z0-9_]{4,32}") || !token.matches("[A-Za-z0-9_-]{43}") ||
                    !dataKey.matches("[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException();
        }
    }

    private static final OkHttpClient NET = new OkHttpClient.Builder().followRedirects(false)
            .followSslRedirects(false).callTimeout(15, TimeUnit.SECONDS).build();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static volatile Login cached;

    private AccountSession() { }

    static SharedPreferences prefs() { return App.get().getSharedPreferences("pdd_account", 0); }

    public static String profile() {
        return App.get() == null ? "" : prefs().getString("profile", "");
    }

    public static String username() { return prefs().getString("username", ""); }

    public static String server() {
        return BuildConfig.ACCOUNT_SERVICE_URL.isEmpty() ? prefs().getString("server", "") : BuildConfig.ACCOUNT_SERVICE_URL;
    }

    public static String normalize(String input) throws IOException {
        HttpUrl url = HttpUrl.parse(input.trim());
        if (url == null || !url.username().isEmpty() || !url.password().isEmpty() || url.query() != null || url.fragment() != null ||
                !(url.isHttps() || (BuildConfig.DEBUG && url.host().equals("127.0.0.1") && url.scheme().equals("http"))))
            throw new IOException("请输入有效的 HTTPS 账号服务地址");
        return url.toString().replaceAll("/+$", "");
    }

    static Login current() throws IOException {
        Login active = cached;
        if (active != null) return active;
        synchronized (HistoryLink.class) {
            if (cached == null && !profile().isEmpty()) {
                try {
                    String raw = KeystoreCipher.decryptOrThrow(prefs().getString("secret", ""));
                    cached = new Login(normalize(prefs().getString("server", "")), JsonParser.parseString(raw).getAsJsonObject());
                } catch (Exception e) { throw new IOException("请退出后重新登录片多多账号"); }
            }
            return cached;
        }
    }

    public static void login(String endpoint, String name, String password, boolean register) throws IOException {
        login(endpoint, name, password, register, "");
    }

    public static void login(String endpoint, String name, String password, boolean register, String activation) throws IOException {
        String server = normalize(BuildConfig.ACCOUNT_SERVICE_URL.isEmpty() ? endpoint : BuildConfig.ACCOUNT_SERVICE_URL);
        String user = name.trim().toLowerCase(Locale.ROOT);
        if (!user.matches("[a-z0-9_]{4,32}")) throw new IOException("账号名需为 4 至 32 位字母、数字或下划线");
        if (password.length() < 8 || password.length() > 128) throw new IOException("密码需为 8 至 128 个字符");
        JsonObject credentials = new JsonObject();
        credentials.addProperty("username", user);
        credentials.addProperty("password", password);
        if (register && !activation.isEmpty() && BuildConfig.PERSONAL_ACCOUNT_URL.equals(server)) credentials.addProperty("activation", activation);
        JsonObject result = post(server, null, register ? "register" : "login", credentials);
        Login login = new Login(server, result);
        synchronized (HistoryLink.class) {
            String id = HistoryPeer.hash(server + "/" + login.id);
            if (!prefs().edit().putString("profile", id).putString("username", login.username)
                    .putString("server", server).putString("secret", KeystoreCipher.encrypt(result.toString())).commit())
                throw new IOException("无法保存登录状态");
            cached = login;
            HistoryLink.accountChanged();
        }
        RefreshEvent.history();
        HistoryLink.sync(null);
    }

    public static void importGuestHistory() throws IOException {
        synchronized (HistoryLink.class) {
            String profile = profile();
            if (profile.isEmpty()) throw new IOException("请先登录");
            com.fongmi.android.tv.db.AppDatabase.get().getHistoryDao().importGuest(profile);
        }
        RefreshEvent.history();
        HistoryLink.afterSave();
    }

    public static void logout() throws IOException {
        Login old;
        synchronized (HistoryLink.class) {
            try { old = current(); } catch (IOException lostKey) { old = null; }
            if (!prefs().edit().remove("profile").remove("username").remove("secret").commit())
                throw new IOException("无法退出登录");
            cached = null;
            HistoryLink.accountChanged();
        }
        RefreshEvent.history();
        if (old != null) {
            try { post(old.server, old.token, "logout", new JsonObject()); }
            catch (IOException offline) { /* The local session has been removed. Remote sessions expire. */ }
        }
    }

    static JsonObject post(String server, String token, String route, JsonObject data) throws IOException {
        Request.Builder request = new Request.Builder().url(server + "/v1/" + route)
                .post(RequestBody.create(JSON, data.toString()));
        if (token != null) request.header("Authorization", "Bearer " + token);
        try (var response = NET.newCall(request.build()).execute()) {
            var body = response.body();
            if (body == null || body.contentLength() > HistoryPeer.MAX_PACKET || body.source().request(HistoryPeer.MAX_PACKET + 1L))
                throw new IOException("账号服务响应异常");
            JsonObject value = JsonParser.parseString(body.string()).getAsJsonObject();
            if (!response.isSuccessful()) {
                String error = value.has("error") ? value.get("error").getAsString() : "账号服务暂不可用";
                throw new IOException(error.length() > 100 ? "账号服务暂不可用" : error);
            }
            return value;
        } catch (RuntimeException e) { throw new IOException("账号服务响应异常"); }
    }
}
