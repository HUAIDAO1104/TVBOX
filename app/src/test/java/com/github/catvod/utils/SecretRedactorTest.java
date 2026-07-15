package com.github.catvod.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SecretRedactorTest {

    @Test
    public void removesHeadersJsonBearerAndQuerySecrets() {
        String source = "{\"cookie\":\"sid=abc\",\"token\":\"xyz\"} Authorization: Bearer top.secret https://a/?access_token=query-secret";
        String redacted = SecretRedactor.redact(source);

        assertFalse(redacted.contains("sid=abc"));
        assertFalse(redacted.contains("top.secret"));
        assertFalse(redacted.contains("query-secret"));
        assertTrue(redacted.contains("[REDACTED]"));
    }

    @Test
    public void redactsMixedCaseHeadersUserInfoAndHyphenatedTokens() {
        String source = "CoOkIe: sid=fake; Authorization=Bearer fake.jwt https://fixture:fake-password@example.invalid/path?refresh_token=fake-query {\"access-token\":\"fake-json\"}";
        String redacted = SecretRedactor.redact(source);

        assertFalse(redacted.contains("sid=fake"));
        assertFalse(redacted.contains("fake.jwt"));
        assertFalse(redacted.contains("fake-password"));
        assertFalse(redacted.contains("fake-query"));
        assertFalse(redacted.contains("fake-json"));
    }

    @Test
    public void leavesHarmlessDiagnosticsReadable() {
        String source = "Repository refresh failed at https://fixture.invalid/list.json with status 503";
        assertEquals(source, SecretRedactor.redact(source));
    }
}
