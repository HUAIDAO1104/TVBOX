package com.fongmi.android.tv.player.danmaku;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DanmakuContentFilterTest {

    @Test
    public void blocksDecoratedGhostLabelButKeepsRealComments() {
        assertTrue(DanmakuContentFilter.isBlocked("小白弹幕"));
        assertTrue(DanmakuContentFilter.isBlocked("【小 白】·弹 幕"));
        assertTrue(DanmakuContentFilter.isBlocked("<b>小白</b>彈幕"));
        assertFalse(DanmakuContentFilter.isBlocked("小白终于找到师父了"));
        assertFalse(DanmakuContentFilter.isBlocked("这条弹幕不会被误删"));
    }
}
