package com.fongmi.android.tv.playback.vod;

import com.fongmi.android.tv.setting.DanmakuAiSetting;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

/** Dedicated, bounded client. Never logs prompts, responses or credentials. */
public final class DanmakuAiClient {
    // The legacy scraper client trusts arbitrary certificates. Credentials require the
    // platform trust store and hostname verification, without scraper interceptors.
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().dns(OkHttp.dns())
            .dispatcher(new okhttp3.Dispatcher()).followRedirects(false).followSslRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            // Recover a pooled socket closed by the gateway within this same 12-second
            // budget. Disabling recovery made the second model request fail immediately.
            .callTimeout(12, TimeUnit.SECONDS).retryOnConnectionFailure(true).build();
    private final String baseUrl, model, key;
    private volatile Call call;
    private volatile boolean cancelled;

    public DanmakuAiClient(String baseUrl, String model, String key) {
        this.baseUrl = baseUrl; this.model = model; this.key = key;
    }

    public static DanmakuAiClient configured() {
        return new DanmakuAiClient(DanmakuAiSetting.baseUrl(), DanmakuAiSetting.model(), DanmakuAiSetting.key());
    }

    public String complete(JsonObject input) throws IOException {
        if (cancelled) throw new IOException("cancelled");
        if (key.isBlank()) throw new IOException("credential");
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", 0);
        body.addProperty("max_tokens", 180);
        body.addProperty("stream", false);
        if (model.toLowerCase(java.util.Locale.ROOT).contains("deepseek")) {
            JsonObject thinking = new JsonObject(); thinking.addProperty("type", "disabled"); body.add("thinking", thinking);
        } else body.addProperty("enable_thinking", false);
        JsonArray messages = new JsonArray();
        messages.add(message("system", DanmakuAiProtocol.SYSTEM));
        messages.add(message("user", input.toString()));
        body.add("messages", messages);
        Request request = new Request.Builder().url(baseUrl + "/chat/completions")
                .header("Authorization", "Bearer " + key)
                .post(RequestBody.create(MediaType.get("application/json; charset=utf-8"), body.toString())).build();
        call = CLIENT.newCall(request);
        if (cancelled) call.cancel();
        try (var response = call.execute()) {
            if (!response.isSuccessful()) throw new IOException("http_" + response.code());
            if (response.body() == null) throw new IOException("response");
            byte[] bytes = com.fongmi.android.tv.utils.InputStreamCompat.readUpTo(response.body().byteStream(), 65537);
            if (bytes.length > 65536) throw new IOException("response");
            return DanmakuAiProtocol.completion(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException e) { throw new IOException("response"); }
    }

    public void cancel() { cancelled = true; Call active = call; if (active != null) active.cancel(); }

    public java.util.List<DanmakuAiModels.Model> models() throws IOException {
        if (cancelled) throw new IOException("cancelled");
        if (key.isBlank()) throw new IOException("credential");
        call = CLIENT.newCall(new Request.Builder().url(baseUrl + "/models").header("Authorization", "Bearer " + key).build());
        call.timeout().timeout(8, TimeUnit.SECONDS);
        if (cancelled) call.cancel();
        try (var response = call.execute()) {
            if (!response.isSuccessful()) throw new IOException("http_" + response.code());
            if (response.body() == null) throw new IOException("response");
            byte[] bytes = com.fongmi.android.tv.utils.InputStreamCompat.readUpTo(response.body().byteStream(), 524289);
            if (bytes.length > 524288) throw new IOException("response");
            return DanmakuAiModels.parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject(); message.addProperty("role", role); message.addProperty("content", content); return message;
    }
}
