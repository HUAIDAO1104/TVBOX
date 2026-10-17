package com.fongmi.android.tv.server;

import com.github.catvod.utils.Asset;
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
            try (var input = Asset.open("ai-setup.html")) {
                var output = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                for (int read; (read = input.read(buffer)) != -1;) output.write(buffer, 0, read);
                Response response = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", new String(output.toByteArray(), StandardCharsets.UTF_8));
                response.addHeader("Content-Security-Policy", "default-src 'none'; script-src 'unsafe-inline'; style-src 'self' 'unsafe-inline'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
                return headers(response);
            } catch (Exception error) { return json(Response.Status.INTERNAL_ERROR, "unavailable"); }
        }
        AiPairingSession pairing = AiPairingSession.find(request.getHeaders().get("x-pairing-token"));
        if (pairing == null) return json(Response.Status.GONE, "expired");
        if (path.equals("/ai-setup/config") && request.getMethod() == Method.GET)
            return headers(newFixedLengthResponse(Response.Status.OK, "application/json", pairing.config().toString()));
        if (!path.equals("/ai-setup/save") || request.getMethod() != Method.POST)
            return json(Response.Status.NOT_FOUND, "not_found");
        String origin = request.getHeaders().get("origin"), host = request.getHeaders().get("host");
        if (origin == null || !origin.equals("http://" + host)) return json(Response.Status.FORBIDDEN, "origin");
        if (!request.getHeaders().getOrDefault("content-type", "").startsWith("application/json"))
            return json(Response.Status.BAD_REQUEST, "request");
        try {
            int length = Integer.parseInt(request.getHeaders().getOrDefault("content-length", "0"));
            if (length < 2 || length > 16384) return json(Response.Status.BAD_REQUEST, "request");
            byte[] body = new byte[length]; int read = 0;
            while (read < length) { int n = request.getInputStream().read(body, read, length - read); if (n < 0) return json(Response.Status.BAD_REQUEST, "request"); read += n; }
            JsonObject data = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
            pairing.save(data.get("baseUrl").getAsString().trim(), data.get("model").getAsString().trim(),
                    data.get("key").getAsString().trim(), data.get("enabled").getAsBoolean());
            return json(Response.Status.OK, "saved");
        } catch (IllegalArgumentException error) {
            String field = error.getMessage();
            return json(Response.Status.BAD_REQUEST, java.util.List.of("base_url", "model", "key").contains(field == null ? "" : field) ? field : "request");
        } catch (Exception error) { return json(Response.Status.INTERNAL_ERROR, "save_failed"); }
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
