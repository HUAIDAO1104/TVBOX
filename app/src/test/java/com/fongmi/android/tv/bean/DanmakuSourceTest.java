package com.fongmi.android.tv.bean;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DanmakuSourceTest {

    @Test
    public void blocksRepositoryInjectedGhostSourceOnly() {
        Danmaku ghost = new Danmaku();
        ghost.setName("小白 · 弹幕");
        ghost.setUrl("https://example.invalid/ghost");
        Danmaku normal = new Danmaku();
        normal.setName("欢天喜地七仙女 from 360 第2集");
        normal.setUrl("https://example.invalid/2.xml");

        assertTrue(ghost.isBlockedSource());
        assertFalse(normal.isBlockedSource());
    }
}
