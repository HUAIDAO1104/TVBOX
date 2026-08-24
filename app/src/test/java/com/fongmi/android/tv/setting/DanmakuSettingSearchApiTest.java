package com.fongmi.android.tv.setting;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class DanmakuSettingSearchApiTest {

    @Test
    public void manualSearchKeepsConfiguredProviderOrderAndRemovesDuplicates() {
        assertEquals(
                Arrays.asList("https://user", "https://repo", "https://default"),
                DanmakuSetting.resolveSearchApiUrls(" https://user ", "https://repo", "https://default"));
        assertEquals(
                Arrays.asList("https://same", "https://default"),
                DanmakuSetting.resolveSearchApiUrls("https://same", " https://same ", "https://default"));
        assertEquals(
                Collections.singletonList("https://default"),
                DanmakuSetting.resolveSearchApiUrls("", null, "https://default"));
    }

    @Test
    public void retiredBuiltInEndpointCannotBeRestoredByOldPreferencesOrRepository() {
        String retired = "https://danmu.xyy.red/api/v2/fongmi/danmaku?name={name}&episode={episode}";
        assertEquals(
                Collections.singletonList("https://default"),
                DanmakuSetting.resolveSearchApiUrls(retired, "http://danmu.xyy.red/danmaku", "https://default"));
        assertEquals(DanmakuSetting.DEFAULT_API_URL,
                DanmakuSetting.resolveApiUrl(retired, "http://danmu.xyy.red/danmaku"));
    }
}
