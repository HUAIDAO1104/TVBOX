package com.fongmi.android.tv.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.util.Map;

public class CloudCredentialResolverExtractTest {

    @Test
    public void extractsNestedJsonAliasesForAllProviders() {
        Map<String, String> extracted = CloudCredentialResolver.extract("{\"nested\":[{\"quarkCookie\":\"q=fake\"},{\"cookieUC\":\"u=fake\"},{\"aliyun_refresh_token\":\"a-fake\"},{\"cookie_baidu\":\"b=fake\"},{\"cloud189_cookie\":\"t=fake\"},{\"xunlei_token\":\"x-fake\"}]}");

        assertEquals("q=fake", extracted.get(CloudProvider.QUARK));
        assertEquals("u=fake", extracted.get(CloudProvider.UC));
        assertEquals("a-fake", extracted.get(CloudProvider.ALI));
        assertEquals("b=fake", extracted.get(CloudProvider.BAIDU));
        assertEquals("t=fake", extracted.get(CloudProvider.TIANYI));
        assertEquals("x-fake", extracted.get(CloudProvider.THUNDER));
    }

    @Test
    public void extractsLegacyTextAndIgnoresPlaceholderValues() {
        Map<String, String> extracted = CloudCredentialResolver.extract(
                "quark_cookie=${cloud.quark.cookie}\nucCookie: uc=fake\naliRefreshToken='ali-fake'\n");

        assertFalse(extracted.containsKey(CloudProvider.QUARK));
        assertEquals("uc=fake", extracted.get(CloudProvider.UC));
        assertEquals("ali-fake", extracted.get(CloudProvider.ALI));
    }
}
