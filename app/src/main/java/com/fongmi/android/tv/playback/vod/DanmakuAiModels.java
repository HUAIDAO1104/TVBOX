package com.fongmi.android.tv.playback.vod;

import com.google.gson.*;
import java.io.IOException;
import java.util.*;

/** Model IDs come from the selected provider; display names never become API IDs. */
public final class DanmakuAiModels {
    public static final String DEFAULT = "deepseek-v4.1-flash";
    private static final List<String> COMMON = List.of(DEFAULT, "deepseek-v4-flash", "qwen3.5-flash", "deepseek-v4-pro", "qwen3.6-plus");
    public record Model(String id, String label) { }

    public static Model model(String id) {
        return new Model(id, switch (id) {
            case "deepseek-v4.1-flash" -> "DeepSeek V4.1 Flash";
            case "deepseek-flash" -> "DeepSeek Flash";
            case "deepseek-v4-flash" -> "DeepSeek V4 Flash";
            case "deepseek-v4-pro" -> "DeepSeek V4 Pro";
            case "qwen3.5-flash" -> "Qwen 3.5 Flash";
            case "qwen3.6-plus" -> "Qwen 3.6 Plus";
            default -> id;
        });
    }

    public static List<Model> presets(String base, String current) {
        List<Model> result = new ArrayList<>();
        if ("https://llm-api.mcisaas.com/v1".equals(base)) for (String id : COMMON) result.add(model(id));
        return retaining(result, current);
    }

    public static List<Model> retaining(List<Model> models, String current) {
        List<Model> result = new ArrayList<>(models);
        if (current != null && !current.isBlank() && result.stream().noneMatch(m -> m.id().equals(current))) result.add(model(current));
        return result;
    }

    public static JsonArray json(List<Model> models) {
        JsonArray data = new JsonArray();
        for (Model model : models) {
            JsonObject item = new JsonObject(); item.addProperty("id", model.id()); item.addProperty("label", model.label());
            item.addProperty("recommended", DEFAULT.equals(model.id())); data.add(item);
        }
        return data;
    }

    public static List<Model> parse(String body) throws IOException {
        try {
            var data = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("data");
            if (data == null || data.size() > 4096) throw new IOException("response");
            Set<String> ids = new TreeSet<>();
            for (var item : data) {
                if (!item.isJsonObject()) continue;
                var object = item.getAsJsonObject(); var value = object.get("id");
                if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) continue;
                String id = value.getAsString().trim(), lower = id.toLowerCase(Locale.ROOT);
                if (id.isEmpty() || id.length() > 120 || id.chars().anyMatch(Character::isISOControl)) continue;
                if (lower.matches(".*(?:embedding|rerank|tts|asr|whisper|realtime|dall-e|image|sora|video|transcription|audio|stable-diffusion).*")) continue;
                if (object.has("supported_endpoint_types")) {
                    var endpoints = object.get("supported_endpoint_types");
                    if (!endpoints.isJsonArray()) continue;
                    boolean chat = false;
                    for (var endpoint : endpoints.getAsJsonArray()) if (endpoint.isJsonPrimitive() && "openai".equals(endpoint.getAsString())) chat = true;
                    if (!chat) continue;
                }
                ids.add(id);
            }
            if (ids.isEmpty()) throw new IOException("models_empty");
            return ids.stream().sorted(Comparator.comparingInt(DanmakuAiModels::rank).thenComparing(String::compareTo))
                    .limit(512).map(DanmakuAiModels::model).toList();
        } catch (RuntimeException error) { throw new IOException("response"); }
    }
    private static int rank(String id) { int index = COMMON.indexOf(id); return index < 0 ? COMMON.size() : index; }
}
