package com.fongmi.android.tv.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

public class CloudCredentialBridgeTest {

    @Test
    public void replacesOnlyAvailablePlaceholders() {
        String source = "q=${cloud.quark.cookie};a=${cloud.aliyun.refreshToken};u=${cloud.uc.cookie}";
        String result = CloudCredentialBridge.replacePlaceholders(source, Map.of(
                CloudProvider.QUARK, "k=v",
                CloudProvider.ALI, "refresh-token"
        ));

        assertEquals("q=k=v;a=refresh-token;u=${cloud.uc.cookie}", result);
        assertFalse(result.contains("${cloud.quark.cookie}"));
    }

    @Test
    public void replacesAllSupportedProviderPlaceholdersWithoutTouchingUnknownText() {
        String source = String.join("|",
                "${cloud.quark.cookie}",
                "${cloud.uc.cookie}",
                "${cloud.aliyun.refreshToken}",
                "${cloud.baidu.cookie}",
                "${cloud.tianyi.cookie}",
                "${cloud.thunder.token}",
                "${cloud.future.token}");

        String result = CloudCredentialBridge.replacePlaceholders(source, Map.of(
                CloudProvider.QUARK, "quark=fake",
                CloudProvider.UC, "uc=fake",
                CloudProvider.ALI, "ali-fake",
                CloudProvider.BAIDU, "baidu=fake",
                CloudProvider.TIANYI, "tianyi=fake",
                CloudProvider.THUNDER, "thunder-fake"));

        assertEquals("quark=fake|uc=fake|ali-fake|baidu=fake|tianyi=fake|thunder-fake|${cloud.future.token}", result);
        assertTrue(result.endsWith("${cloud.future.token}"));
    }

    @Test
    public void nullInputAndBlankCredentialAreSafe() {
        assertEquals("", CloudCredentialBridge.replacePlaceholders(null, Map.of()));
        assertEquals("${cloud.quark.cookie}", CloudCredentialBridge.replacePlaceholders(
                "${cloud.quark.cookie}", Map.of(CloudProvider.QUARK, "")));
    }
}
