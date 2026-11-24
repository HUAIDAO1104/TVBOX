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
        accept(server, result);
    }

    public static void accept(String server, JsonObject result) throws IOException {
        Login login;
        try { login = new Login(server, result); }
        catch (RuntimeException e) { throw new IOException("登录回复缺少有效的账号信息，请重试"); }
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

    public static boolean hasDeviceLogin(String server) {
        return BuildConfig.PERSONAL_ACCOUNT_URL.equals(server) ||
                (BuildConfig.DEBUG && server.startsWith("http://127.0.0.1:"));
    }

    public static JsonObject deviceRequest(String server, String operation, String key, String value) throws IOException {
        if (!hasDeviceLogin(server)) throw new IOException("该账号服务不支持扫码登录");
        JsonObject input = new JsonObject();
        if (key != null) input.addProperty(key, value);
        return post(server, null, "pair/" + operation, input);
    }

    /** Revoke a ticket redeemed just as its TV screen closed or switched login methods. */
    public static void discardDeviceSession(String server, JsonObject result) {
        if (!hasDeviceLogin(server)) return;
        try {
            Login unused = new Login(server, result);
            post(server, unused.token, "logout", new JsonObject());
        } catch (IOException | RuntimeException ignored) { }
    }

    public static void approve(String scan) throws IOException {
        Login login = current();
        if (login == null) throw new IOException("请先登录片多多账号");
        JsonObject data = new JsonObject();
        data.addProperty("scan", scan);
        post(login.server, login.token, "pair/approve", data);
    }

    public static final class ServiceError extends IOException {
        public final int status;
        ServiceError(int status, String message) { super(message); this.status = status; }
    }

    static JsonObject post(String server, String token, String route, JsonObject data) throws IOException {
        Request.Builder request = new Request.Builder().url(server + "/v1/" + route).header("Accept", "application/json")
                .post(RequestBody.create(JSON, data.toString()));
        if (token != null) request.header("Authorization", "Bearer " + token);
        try (var response = NET.newCall(request.build()).execute()) {
            int code = response.code();
            var body = response.body();
            String content = response.header("Content-Type", "").toLowerCase(Locale.ROOT);
            if (body == null || !content.contains("json")) {
                String message = code == 429 ? "请求过于频繁，请稍后再试"
                        : code == 403 || code == 451 ? "当前网络的登录请求被网关拒绝"
                        : code >= 500 ? "账号服务暂时不可用，请稍后重试" : "网络返回了网页，请检查网络或使用网页登录";
                throw new ServiceError(code, message + "（HTTP " + code + "）");
            }
            if (body.contentLength() > HistoryPeer.MAX_PACKET || body.source().request(HistoryPeer.MAX_PACKET + 1L))
                throw new ServiceError(code, "账号服务返回的数据过大");
            JsonObject value;
            try { value = JsonParser.parseString(body.string()).getAsJsonObject(); }
            catch (RuntimeException e) { throw new ServiceError(code, "账号服务返回的数据不完整（HTTP " + code + "）"); }
            if (!response.isSuccessful()) {
                String error = value.has("error") && value.get("error").isJsonPrimitive() ? value.get("error").getAsString() : "账号服务暂不可用";
                throw new ServiceError(code, error.length() > 100 ? "账号服务暂不可用" : error);
            }
            return value;
        }
    }
}
