package com.fongmi.android.tv.server;

import com.fongmi.android.tv.App;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import fi.iki.elonen.NanoHTTPD;
import static fi.iki.elonen.NanoHTTPD.*;

/** Separate bounded JSON endpoint: credentials never enter NanoHTTPD's upload temp files. */
final class AiSetup {
    static Response serve(IHTTPSession request) {
        String path = request.getUri();
        if (path.equals("/ai-setup") && request.getMethod() == Method.GET) {
            try (var input = App.get().getAssets().open("ai-setup.html")) {
                var output = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                for (int read; (read = input.read(buffer)) != -1;) output.write(buffer, 0, read);
                Response response = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", new String(output.toByteArray(), StandardCharsets.UTF_8));
                response.addHeader("Content-Security-Policy", "default-src 'none'; script-src 'unsafe-inline'; style-src 'self' 'unsafe-inline'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
                return headers(response);
            } catch (Exception error) { return json(Response.Status.INTERNAL_ERROR, "unavailable"); }
        }
        if (path.equals("/ai-setup/pair") && request.getMethod() == Method.POST) {
            if (!sameOrigin(request)) return json(Response.Status.FORBIDDEN, "origin");
            try {
                JsonObject data = body(request);
                if (!data.has("code") || !data.get("code").isJsonPrimitive() || !data.getAsJsonPrimitive("code").isString())
                    throw new IllegalArgumentException("request");
                String token = AiPairingSession.pair(data.get("code").getAsString());
                JsonObject result = new JsonObject(); result.addProperty("token", token);
                return headers(newFixedLengthResponse(Response.Status.OK, "application/json", result.toString()));
            } catch (IllegalArgumentException error) {
                String code = error.getMessage();
                if ("expired".equals(code)) return json(Response.Status.GONE, code);
                if ("pairing_locked".equals(code)) return json(Response.Status.TOO_MANY_REQUESTS, code);
                return json(Response.Status.BAD_REQUEST, "pairing_code".equals(code) ? code : "request");
            } catch (Exception error) { return json(Response.Status.INTERNAL_ERROR, "unavailable"); }
        }
        AiPairingSession pairing = AiPairingSession.find(request.getHeaders().get("x-pairing-token"));
        if (pairing == null) return json(Response.Status.GONE, "expired");
        if (path.equals("/ai-setup/config") && request.getMethod() == Method.GET)
            return headers(newFixedLengthResponse(Response.Status.OK, "application/json", pairing.config().toString()));
        if (path.equals("/ai-setup/models") && request.getMethod() == Method.POST) {
            if (!sameOrigin(request)) return json(Response.Status.FORBIDDEN, "origin");
            try {
                JsonObject data = body(request);
                String base = com.fongmi.android.tv.setting.DanmakuAiSetting.normalizeBaseUrl(data.get("baseUrl").getAsString());
                String key = com.fongmi.android.tv.setting.DanmakuAiSetting.credentialFor(base, data.get("key").getAsString());
                var models = new com.fongmi.android.tv.playback.vod.DanmakuAiClient(base, "", key).models();
                if (AiPairingSession.find(request.getHeaders().get("x-pairing-token")) != pairing) return json(Response.Status.GONE, "expired");
                JsonObject result = new JsonObject(); result.add("models", com.fongmi.android.tv.playback.vod.DanmakuAiModels.json(models));
                return headers(newFixedLengthResponse(Response.Status.OK, "application/json", result.toString()));
            } catch (IllegalArgumentException error) {
                return json(Response.Status.BAD_REQUEST, "base_url".equals(error.getMessage()) ? "base_url" : "key");
            } catch (Exception error) {
                String code = error.getMessage();
                return json(Response.Status.INTERNAL_ERROR, "http_401".equals(code) || "http_403".equals(code) ? "models_auth"
                        : "models_empty".equals(code) ? "models_empty" : "models_unavailable");
            }
        }
        if (!path.equals("/ai-setup/save") || request.getMethod() != Method.POST)
            return json(Response.Status.NOT_FOUND, "not_found");
        if (!sameOrigin(request)) return json(Response.Status.FORBIDDEN, "origin");
        try {
            JsonObject data = body(request);
            pairing.save(data.get("baseUrl").getAsString().trim(), data.get("model").getAsString().trim(),
                    data.get("key").getAsString().trim(), data.get("enabled").getAsBoolean());
            // Dismissing the window closes its listener. Flush the saved response first.
            byte[] saved = "{\"code\":\"saved\"}".getBytes(StandardCharsets.UTF_8);
            return headers(new Response(Response.Status.OK, "application/json", new java.io.ByteArrayInputStream(saved), saved.length) {
                @Override protected void send(java.io.OutputStream output) {
                    try { super.send(output); } finally { pairing.savedResponseSent(); }
                }
            });
        } catch (IllegalArgumentException error) {
            String field = error.getMessage();
            return json(Response.Status.BAD_REQUEST, java.util.List.of("base_url", "model", "key").contains(field == null ? "" : field) ? field : "request");
        } catch (Exception error) { return json(Response.Status.INTERNAL_ERROR, "save_failed"); }
    }
    private static boolean sameOrigin(IHTTPSession request) {
        return ("http://" + request.getHeaders().get("host")).equals(request.getHeaders().get("origin"));
    }
    private static JsonObject body(IHTTPSession request) throws Exception {
        if (!sameOrigin(request) || !request.getHeaders().getOrDefault("content-type", "").startsWith("application/json"))
            throw new IllegalArgumentException("request");
        int length = Integer.parseInt(request.getHeaders().getOrDefault("content-length", "0"));
        if (length < 2 || length > 16384) throw new IllegalArgumentException("request");
        byte[] body = new byte[length]; int read = 0;
        while (read < length) {
            int n = request.getInputStream().read(body, read, length - read);
            if (n < 0) throw new IllegalArgumentException("request");
            read += n;
        }
        return JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
    }
    private static Response json(Response.Status status, String code) {
        JsonObject data = new JsonObject(); data.addProperty("code", code);
        return headers(newFixedLengthResponse(status, "application/json", data.toString()));
    }
    private static Response headers(Response response) {
        // Rejected requests intentionally leave their bodies unread; never reuse that socket.
        response.addHeader("Connection", "close");
        response.addHeader("Cache-Control", "no-store"); response.addHeader("Referrer-Policy", "no-referrer");
        response.addHeader("X-Content-Type-Options", "nosniff"); response.addHeader("X-Frame-Options", "DENY");
        return response;
    }
}
