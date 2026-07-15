package com.fongmi.android.tv.ui.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SearchRelevanceTest {

    private final SearchRelevance relevance = new SearchRelevance();

    @Test
    public void acceptsCanonicalAndClearlyContainedMatches() {
        assertTrue(relevance.isRelevant("庆余年", "慶餘年 第二部【4K】"));
        assertTrue(relevance.isRelevant("漫长的季节", "漫长的季节 完结"));
    }

    @Test
    public void rejectsUnrelatedServerNoise() {
        assertFalse(relevance.isRelevant("庆余年", "长相思 第二季"));
        assertFalse(relevance.isRelevant("三体", "三大队"));
    }

    @Test
    public void specifiedInstallmentMustMatch() {
        assertTrue(relevance.isRelevant("庆余年2", "庆余年第二部"));
        assertFalse(relevance.isRelevant("庆余年2", "庆余年第一部"));
        assertFalse(relevance.isRelevant("庆余年2", "庆余年"));
    }

    @Test
    public void explicitMediaVariantDoesNotMatchAnotherEdition() {
        assertEquals(0, relevance.score("三体电影版", "三体电视剧版"));
    }

    @Test
    public void keepsMainTitlesInstallmentsAndReleaseLabels() {
        assertTrue(relevance.isRelevant("野狗骨头", "野狗骨头"));
        assertTrue(relevance.isRelevant("野狗骨头", "野狗骨头 第二季"));
        assertTrue(relevance.isRelevant("野狗骨头", "野狗骨头第2部"));
        assertTrue(relevance.isRelevant("野狗骨头", "野狗骨头【4K 国语】全集"));
        assertTrue(relevance.isRelevant("野狗骨头", "野狗骨头正片"));
    }

    @Test
    public void rejectsUnrequestedNewsClipsAndPromotionalDerivatives() {
        assertFalse(relevance.isRelevant("野狗骨头", "野狗骨头发布会"));
        assertFalse(relevance.isRelevant("野狗骨头", "野狗骨头演员采访"));
        assertFalse(relevance.isRelevant("野狗骨头", "野狗骨头精彩片段"));
        assertFalse(relevance.isRelevant("野狗骨头", "三分钟看完野狗骨头解说"));
        assertFalse(relevance.isRelevant("野狗骨头", "野狗骨头预告"));
        assertFalse(relevance.isRelevant("野狗骨头", "野狗骨头幕后花絮"));
        assertFalse(relevance.isRelevant("野狗骨头", "野狗骨头 OST"));
        assertFalse(relevance.isRelevant("野狗骨头", "野狗骨头 MV"));
        assertFalse(relevance.isRelevant("野狗骨头", "野狗骨头横版视频"));
        assertFalse(relevance.isRelevant("野狗骨头", "野狗骨头 1~19集上(横屏)"));
        assertFalse(relevance.isRelevant("野狗骨头", "《野狗骨头》作者:休屠城.txt"));
        assertFalse(relevance.isRelevant("野狗骨头", "对对对《野狗骨头》[mp3_lrc]"));
        assertFalse(relevance.isRelevant("野狗骨头", "野狗骨头拍黄体破裂好真实"));
        assertFalse(relevance.isRelevant("野狗骨头", "今日热点某演员谈到野狗骨头引发网友关注"));
        assertFalse(relevance.isRelevant("野狗骨头", "野狗"));
        assertFalse(relevance.isRelevant("野狗骨头", "野狗骨头预约"));
        assertFalse(relevance.isRelevant("野狗骨头", "野狗骨头待播"));
        assertFalse(relevance.isRelevant("野狗骨头", "野狗骨头敬请期待"));
        assertFalse(relevance.isRelevant("野狗骨头", "野狗骨头暂无片源"));
        assertFalse(relevance.isRelevant("野狗骨头", "野狗骨头已下架"));
    }

    @Test
    public void explicitDerivativeQueriesStillFindTheirRequestedContent() {
        assertTrue(relevance.isRelevant("野狗骨头预告", "野狗骨头预告"));
        assertTrue(relevance.isRelevant("野狗骨头演员采访", "野狗骨头演员采访"));
        assertTrue(relevance.isRelevant("野狗骨头 OST", "野狗骨头 OST"));
    }

    @Test
    public void doesNotBlanketRejectNormalLongTitlesOrLexicalMarkerWords() {
        assertTrue(relevance.isRelevant("山海情", "山海情之永远的家园"));
        assertTrue(relevance.isRelevant("女王", "新闻女王"));
        assertTrue(relevance.isRelevant("人生", "采访人生"));
        assertTrue(relevance.isRelevant("爱情", "预约爱情"));
        assertTrue(relevance.isRelevant("野狗骨头预约", "野狗骨头预约"));
    }

    @Test
    public void audioCatalogSourcesDoNotPolluteAWorkSearchUnlessAudioWasRequested() {
        SearchSource audio = SearchSource.builder()
                .repositoryId("repo").siteKey("audio").siteName("易听┃音乐")
                .vodId("1").title("乐瑶 - 野狗骨头").build();
        SearchSource requestedAudio = SearchSource.builder()
                .repositoryId("repo").siteKey("audio").siteName("易听┃音乐")
                .vodId("2").title("野狗骨头音乐").build();
        SearchSource video = SearchSource.builder()
                .repositoryId("repo").siteKey("video").siteName("奶酪")
                .vodId("3").title("野狗骨头").build();

        assertEquals(0, relevance.score("野狗骨头", audio));
        assertTrue(relevance.isRelevant("野狗骨头音乐", requestedAudio));
        assertTrue(relevance.isRelevant("野狗骨头", video));
    }
}
