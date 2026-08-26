package com.fongmi.android.tv.player.danmaku;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DanmakuDocumentCacheTest {

    @Test
    public void onlyMaterialisesImmutableCommentDocuments() {
        assertTrue(DanmakuDocumentCache.isCacheable(
                "https://example.com/api/v2/comment/123?format=xml"));
        assertTrue(DanmakuDocumentCache.isCacheable(
                "https://example.com/api/v1/comment/123.xml"));
        assertFalse(DanmakuDocumentCache.isCacheable(
                "https://example.com/api/v2/search/episodes?anime=123"));
        assertFalse(DanmakuDocumentCache.isCacheable(
                "https://example.com/danmaku/native?id=123"));
    }

    @Test
    public void cacheKeyIsStableAndSourceSpecific() {
        String first = DanmakuDocumentCache.cacheKey("https://example.com/comment/1.xml");
        assertEquals(first, DanmakuDocumentCache.cacheKey(
                "https://example.com/comment/1.xml"));
        assertFalse(first.equals(DanmakuDocumentCache.cacheKey(
                "https://example.com/comment/2.xml")));
        assertEquals(64, first.length());
    }
}
