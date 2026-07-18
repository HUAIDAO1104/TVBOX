package com.fongmi.android.tv.ui.search;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SearchDisplayNameTest {

    @Test
    public void removesOnlyPromotionalSuffixes() {
        assertEquals("王二小", SearchDisplayName.clean("王二小 · 秒播不卡"));
        assertEquals("电影线路", SearchDisplayName.clean("电影线路【极速秒播】"));
        assertEquals("漫长的季节", SearchDisplayName.clean("漫长的季节  -  多线路"));
        assertEquals("玩偶", SearchDisplayName.clean("💓玩偶 | 4K💓"));
        assertEquals("闪电", SearchDisplayName.clean("💥闪电 | 秒播💥"));
        assertEquals("闪电", SearchDisplayName.clean("💥️闪电 | 秒播💥️"));
        assertEquals("短剧", SearchDisplayName.clean("🍉短剧 | 好看🍉"));
    }

    @Test
    public void preservesLegitimateTitlesAndKeys() {
        assertEquals("极速车王", SearchDisplayName.clean("极速车王"));
        assertEquals("短剧 | 小薇", SearchDisplayName.clean("🍉短剧 | 小薇🍉"));
        assertEquals("site_key_01", SearchDisplayName.clean("site_key_01"));
    }

    @Test
    public void removesPromotionTokensJoinedToARealName() {
        assertEquals("闪电", SearchDisplayName.clean("闪电秒播"));
        assertEquals("", SearchDisplayName.clean("4K"));
    }

    @Test
    public void removesEmojiAnywhereInVisibleLabels() {
        assertEquals("玩偶 片库", SearchDisplayName.removeEmoji("玩偶 💓 片库"));
        assertEquals("配置中心", SearchDisplayName.removeEmoji("🐮配置中心🐮"));
    }
}
