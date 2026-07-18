package com.fongmi.android.tv.setting;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DanmakuSettingCompatibilityTest {

    @Test
    public void localClampBoundsAllSupportedNumberTypes() {
        assertEquals(0.5f, DanmakuSetting.clamp(0.1f, 0.5f, 3.0f), 0.0f);
        assertEquals(2.0f, DanmakuSetting.clamp(2.0f, 0.5f, 3.0f), 0.0f);
        assertEquals(3.0f, DanmakuSetting.clamp(4.0f, 0.5f, 3.0f), 0.0f);

        assertEquals(10, DanmakuSetting.clamp(2, 10, 500));
        assertEquals(120, DanmakuSetting.clamp(120, 10, 500));
        assertEquals(500, DanmakuSetting.clamp(800, 10, 500));

        assertEquals(-300_000L, DanmakuSetting.clamp(-500_000L, -300_000L, 300_000L));
        assertEquals(12_000L, DanmakuSetting.clamp(12_000L, 3_000L, 15_000L));
        assertEquals(15_000L, DanmakuSetting.clamp(30_000L, 3_000L, 15_000L));
    }

    @Test
    public void danmakuApiUsesUserThenRepositoryThenBuiltInDefault() {
        String user = "https://user.example/danmaku?name={name}&episode={episode}";
        String repository = "https://repository.example/danmaku?name={name}&episode={episode}";

        assertEquals(user, DanmakuSetting.resolveApiUrl(user, repository));
        assertEquals(repository, DanmakuSetting.resolveApiUrl("", repository));
        assertEquals(DanmakuSetting.DEFAULT_API_URL, DanmakuSetting.resolveApiUrl("  ", null));
    }
}
