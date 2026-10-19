package com.fongmi.android.tv.playback.vod;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;

public class DanmakuAiModelsTest {
    @Test public void ranksProviderIdsAndFiltersNonChatEndpoints() throws Exception {
        var models = DanmakuAiModels.parse("{\"data\": [{\"id\": \"qwen3.5-flash\"}, {\"id\": \"deepseek-v4.1-flash\", \"supported_endpoint_types\": [\"openai\"]}, {\"id\": \"deepseek-v4.1-flash\"}, {\"id\": \"qwen-tts\"}, {\"id\": \"text-embedding-3\"}, {\"id\": \"claude-only\", \"supported_endpoint_types\": [\"anthropic\"]}, {\"id\": 123}, {\"id\": \" \"}]}");
        assertEquals(List.of("deepseek-v4.1-flash", "qwen3.5-flash"), models.stream().map(DanmakuAiModels.Model::id).toList());
        assertEquals("DeepSeek V4.1 Flash", models.get(0).label());
    }
    @Test public void presetsBelongToTheirProviderAndKeepExistingChoice() {
        assertEquals("deepseek-v4.1-flash", DanmakuAiModels.presets("https://llm-api.mcisaas.com/v1", "").get(0).id());
        assertTrue(DanmakuAiModels.presets("https://other.invalid/v1", "").isEmpty());
        var saved = DanmakuAiModels.presets("https://other.invalid/v1", "private-model");
        assertEquals(1, saved.size()); assertEquals("private-model", saved.get(0).id());
        assertEquals(1, DanmakuAiModels.retaining(saved, "private-model").size());
    }
    @Test public void rejectsEmptyMalformedAndUnboundedCatalogues() {
        for (String body : List.of("{}", "{\"data\":[]}", "{\"data\":\"wrong\"}", "{\"data\":[{\"id\":\"qwen-tts\"}]}")) {
            try { DanmakuAiModels.parse(body); fail(body); } catch (java.io.IOException expected) { }
        }
        String body = "{\"data\":[" + String.join(",", java.util.Collections.nCopies(4097, "{\"id\":\"m\"}")) + "]}";
        try { DanmakuAiModels.parse(body); fail("Unbounded catalogue"); } catch (java.io.IOException expected) { }
    }
    @Test public void serializesIdsSeparatelyFromDisplayLabels() {
        var json = DanmakuAiModels.json(List.of(DanmakuAiModels.model(DanmakuAiModels.DEFAULT))).get(0).getAsJsonObject();
        assertEquals("deepseek-v4.1-flash", json.get("id").getAsString());
        assertEquals("DeepSeek V4.1 Flash", json.get("label").getAsString());
        assertTrue(json.get("recommended").getAsBoolean());
    }
}
