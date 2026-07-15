package com.fongmi.android.tv.security;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import com.fongmi.android.tv.bean.Backup;
import com.github.catvod.utils.SecretRedactor;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class SensitiveDataTest {

    @Test
    public void backupFilterExcludesSecrets() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("theme", "dark");
        input.put("quark_cookie", "secret");
        input.put("Authorization", "Bearer secret");
        input.put("cloud.thunder.token", "secret");

        Map<String, ?> filtered = SensitiveData.filterPreferences(input);

        assertTrue(filtered.containsKey("theme"));
        assertFalse(filtered.containsKey("quark_cookie"));
        assertFalse(filtered.containsKey("Authorization"));
        assertFalse(filtered.containsKey("cloud.thunder.token"));
    }

    @Test
    public void serializedBackupRemovesNestedSecretFields() {
        String safe = SensitiveData.sanitizeJson("{\"theme\":\"dark\",\"header\":{\"Cookie\":\"sid=secret\",\"User-Agent\":\"tv\"},\"refresh_token\":\"secret\"}");

        assertFalse(safe.contains("sid=secret"));
        assertFalse(safe.contains("refresh_token"));
        assertTrue(safe.contains("User-Agent"));
        assertEquals("dark", com.google.gson.JsonParser.parseString(safe).getAsJsonObject().get("theme").getAsString());
    }

    @Test
    public void recognizesCaseAndSeparatorVariants() {
        assertTrue(SensitiveData.isSensitiveKey("Refresh-Token"));
        assertTrue(SensitiveData.isSensitiveKey("HTTP_AUTHORIZATION"));
        assertTrue(SensitiveData.isSensitiveKey("Cloud.Thunder.SessionToken"));
        assertFalse(SensitiveData.isSensitiveKey("repository.lastSuccessAt"));
    }

    @Test
    public void nestedArraysAndEmbeddedJsonStringsAreScrubbed() {
        String source = "{\"items\":[{\"name\":\"safe\",\"access-token\":\"fake-secret\"},\"{\\\"cookie\\\":\\\"sid=fake\\\",\\\"label\\\":\\\"safe\\\"}\"]}";
        String safe = SensitiveData.sanitizeJson(source);

        assertFalse(safe.contains("fake-secret"));
        assertFalse(safe.contains("sid=fake"));
        assertTrue(safe.contains("safe"));
    }

    @Test
    public void backupModelHasNoCredentialOrCloudAccountPayload() {
        for (java.lang.reflect.Field field : Backup.class.getDeclaredFields()) {
            String name = field.getName().toLowerCase(Locale.ROOT);
            assertFalse("Backup field must not persist credentials: " + field.getName(),
                    name.contains("cookie") || name.contains("token") || name.contains("credential") || name.contains("cloudaccount"));
        }
    }

    @Test
    public void runtimeLogsRedactCommonCredentialShapes() {
        String source = "Cookie: sid=secret; uid=42 Authorization=Bearer access-secret "
                + "https://example.test/play?refresh_token=refresh-secret&safe=1 "
                + "{\"credential\":\"json-secret\"}";

        String safe = SecretRedactor.redact(source);

        assertFalse(safe.contains("sid=secret"));
        assertFalse(safe.contains("access-secret"));
        assertFalse(safe.contains("refresh-secret"));
        assertFalse(safe.contains("json-secret"));
    }
}
