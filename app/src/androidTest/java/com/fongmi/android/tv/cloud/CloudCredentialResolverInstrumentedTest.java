package com.fongmi.android.tv.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.github.catvod.utils.Prefers;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Map;

@RunWith(AndroidJUnit4.class)
public class CloudCredentialResolverInstrumentedTest {

    private static final String FAKE_APP_COOKIE = "app_sid=fake_app_value";
    private static final String FAKE_CONFIG_COOKIE = "config_sid=fake_config_value";

    @Test
    public void appAccountPriorityOverridesCurrentConfiguration() {
        Map<String, String> resolved = CloudCredentialResolver.resolve(
                "{\"quark_cookie\":\"" + FAKE_CONFIG_COOKIE + "\"}",
                Map.of(CloudProvider.QUARK, FAKE_APP_COOKIE),
                CloudCredentialPriority.APP_ACCOUNT_FIRST);

        assertEquals(FAKE_APP_COOKIE, resolved.get(CloudProvider.QUARK));
    }

    @Test
    public void currentConfigurationPriorityUsesConfigurationAndKeepsAppFallback() {
        Map<String, String> resolved = CloudCredentialResolver.resolve(
                "{\"quarkCookie\":\"" + FAKE_CONFIG_COOKIE + "\",\"uc_cookie\":\"\"}",
                Map.of(CloudProvider.QUARK, FAKE_APP_COOKIE, CloudProvider.UC, "uc=fake"),
                CloudCredentialPriority.CURRENT_CONFIG_FIRST);

        assertEquals(FAKE_CONFIG_COOKIE, resolved.get(CloudProvider.QUARK));
        assertEquals("uc=fake", resolved.get(CloudProvider.UC));
    }

    @Test
    public void placeholderSelectsOnlyReferencedProvider() {
        Map<String, String> resolved = CloudCredentialResolver.resolve(
                "{\"cookie\":\"${cloud.uc.cookie}\"}",
                Map.of(CloudProvider.UC, "uc=fake", CloudProvider.QUARK, FAKE_APP_COOKIE),
                CloudCredentialPriority.APP_ACCOUNT_FIRST);

        assertEquals(Map.of(CloudProvider.UC, "uc=fake"), resolved);
        assertFalse(resolved.containsKey(CloudProvider.QUARK));
    }

    @Test
    public void aliasesAndCapabilityModesMatchDeclaredSpiderRequirements() {
        assertTrue(CloudCredentialPreferences.keys(CloudProvider.ALI).contains("aliyun_refresh_token"));
        assertTrue(CloudCredentialPreferences.keys(CloudProvider.THUNDER).contains("xunlei_cookie"));

        CloudCapability capability = CloudCapability.detect(
                CloudProvider.find(CloudProvider.QUARK),
                "{\"quark_cookie\":\"\",\"Cloud-drive\":\"memory://fixture\",\"login_action\":\"qrcode\"}");

        assertEquals(CloudCapability.Support.SUPPORTED, capability.support());
        assertTrue(capability.has(CloudCapability.Mode.COOKIE_INJECTION_AVAILABLE));
        assertTrue(capability.has(CloudCapability.Mode.CLOUD_DRIVE_AVAILABLE));
        assertTrue(capability.has(CloudCapability.Mode.LEGACY_PREFERS_AVAILABLE));
        assertFalse(capability.has(CloudCapability.Mode.ACTION_LOGIN_AVAILABLE));
    }

    @Test
    public void legacyPrefersBridgeIsProcessOnlyAndClearsWithoutDiskResidue() {
        String key = "quark_cookie";
        Prefers.getPrefers().edit().remove(key).commit();

        CloudCredentialPreferences.syncForExt("{\"quark_cookie\":\"\"}", Map.of(CloudProvider.QUARK, FAKE_APP_COOKIE));

        assertEquals(FAKE_APP_COOKIE, Prefers.getString(key));
        assertFalse(Prefers.getPrefers().contains(key));

        Prefers.put(key, "app_sid=rotated_fake_value");
        assertEquals("app_sid=rotated_fake_value", Prefers.getString(key));
        assertFalse(Prefers.getPrefers().contains(key));

        CloudCredentialPreferences.clearRuntime();
        assertEquals("", Prefers.getString(key));
        assertFalse(Prefers.getPrefers().contains(key));
    }
}
