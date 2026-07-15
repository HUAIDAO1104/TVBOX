package com.fongmi.android.tv.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PromotionFilterTest {

    @Test
    public void suppressesSolicitationButKeepsFunctionalMessages() {
        assertTrue(PromotionFilter.shouldSuppress("关注gzh：本接口免费！更新快！不迷路！"));
        assertTrue(PromotionFilter.shouldSuppress("加入QQ群，免费接口推广"));
        assertTrue(PromotionFilter.shouldSuppress("请关注王二小获取最新配置"));
        assertTrue(PromotionFilter.shouldSuppress("请关注王小二"));
        assertTrue(PromotionFilter.shouldSuppress("请关注公众号"));
        assertTrue(PromotionFilter.shouldSuppress("关注gzh获取最新消息"));
        assertTrue(PromotionFilter.shouldSuppress("本接口完全免费，请勿上当"));
        assertTrue(PromotionFilter.shouldSuppress("王二小 · 免费线路"));
        assertTrue(PromotionFilter.shouldSuppress("请扫码加群"));
        assertFalse(PromotionFilter.shouldSuppress("配置加载失败，请重试"));
        assertFalse(PromotionFilter.shouldSuppress("仓库刷新失败，已使用缓存"));
        assertFalse(PromotionFilter.shouldSuppress("扫码接口获取失败，请检查网络"));
        assertFalse(PromotionFilter.shouldSuppress("报道关注了公众号行业的发展"));
        assertFalse(PromotionFilter.shouldSuppress("《关注者》是一部剧情电影"));
        assertEquals("", PromotionFilter.sanitizeMessage("请关注王小二"));
        assertEquals("扫码接口获取失败，请检查网络", PromotionFilter.sanitizeMessage("扫码接口获取失败，请检查网络"));
    }

    @Test
    public void removesPromotionAtSentenceBoundaryButKeepsSynopsis() {
        String text = "关注gzh：本接口免费！更新快！不迷路！两位年轻人在故乡重逢。";
        assertEquals("两位年轻人在故乡重逢。", PromotionFilter.sanitizeDisplayText(text));
        assertEquals("本片讲述免费教育推广计划中的师生故事。",
                PromotionFilter.sanitizeDisplayText("本片讲述免费教育推广计划中的师生故事。"));
    }

    @Test
    public void sourceLabelsAreSanitizedWithoutChangingWorkTitles() {
        assertEquals("默认内容源", PromotionFilter.sanitizeSourceLabel("🐮【王二小放牛娃】🐮", "默认内容源"));
        assertEquals("默认内容源", PromotionFilter.sanitizeSourceLabel("王小二", "默认内容源"));
        assertEquals("点我切源", PromotionFilter.sanitizeSourceLabel("领取免费容量", "点我切源"));
        assertEquals("王二小", PromotionFilter.sanitizeDisplayText("王二小"));
    }

    @Test
    public void detectsOnlyStandalonePromotionalHomeEntries() {
        assertTrue(PromotionFilter.isPromotionalEntry("公告", "", "关注公众号，接口免费更新"));
        assertFalse(PromotionFilter.isPromotionalEntry("关注者", "2024", "一部关于媒体行业的电影"));
    }
}
