package com.fongmi.android.tv.bean;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
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

    @Test
    public void blocksObfuscatedGhostLabelsAtTheFinalUiBoundary() {
        assertTrue(Danmaku.isBlockedSourceLabel("小白\u200B弹幕"));
        assertTrue(Danmaku.isBlockedSourceLabel("<b>小白</b> · 弹幕"));
        assertTrue(Danmaku.isBlockedSourceLabel("小白　弹幕"));
        assertTrue(Danmaku.isBlockedSourceLabel("小白彈幕"));
        assertTrue(Danmaku.isBlockedSourceLabel("小白播放器专用弹幕入口"));
        assertFalse(Danmaku.isBlockedSourceLabel("弹幕设置"));
        assertFalse(Danmaku.isBlockedSourceLabel("弹幕开"));
    }

    @Test
    public void retiredCommentIdsAreDetectedWithoutUnsafeHostRewriting() {
        assertEquals("http://danmu.xyy.red/api/v2/comment/274234?format=xml",
                Danmaku.normalizeSourceUrl("http://danmu.xyy.red/api/v2/comment/274234?format=xml"));
        assertTrue(Danmaku.isRetiredSourceUrl(
                "https://danmu.xyy.red/api/v2/comment/274234?format=xml"));
        assertFalse(Danmaku.isRetiredSourceUrl(
                "https://dm.ljiaovm.com/luosen/api/v2/comment/1?format=xml"));
        assertEquals("http://example.com/comments.xml",
                Danmaku.normalizeSourceUrl("http://example.com/comments.xml"));
    }
}
