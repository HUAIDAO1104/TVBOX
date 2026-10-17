package com.fongmi.android.tv.api;

import static org.junit.Assert.*;
import org.junit.Test;

public class NativeDanmakuTargetTest {
    @Test public void onlyConflictingKnownMetadataSkipsCatalogueExpansion() {
        assertTrue(NativeDanmaku.acceptsWork("现在就出发第四季", "2026", "综艺", "现在就出发第4季", "2026", "综艺"));
        assertFalse(NativeDanmaku.acceptsWork("现在就出发第四季", "2026", "综艺", "现在就出发第3季", "2025", "综艺"));
        assertFalse(NativeDanmaku.acceptsWork("喜剧之王", "2026", "电视剧", "喜剧之王", "1999", "电影"));
        assertTrue(NativeDanmaku.acceptsWork("哪吒2", "2025", "动画电影", "哪吒2", "2025", "动漫"));
        assertFalse(NativeDanmaku.acceptsWork("哪吒2", "2025", "电影", "哪吒2", "2025", "电视剧"));
        assertTrue(NativeDanmaku.acceptsWork("披荆斩棘2026", "2026", "综艺", "披荆斩棘2026", "", ""));
    }
}
