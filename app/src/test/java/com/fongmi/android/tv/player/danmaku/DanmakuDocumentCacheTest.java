package com.fongmi.android.tv.player.danmaku;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class DanmakuDocumentCacheTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

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

    @Test
    public void usableDocumentMustContainAnActualCommentEntry() throws Exception {
        File valid = temporaryFolder.newFile("valid.xml");
        File empty = temporaryFolder.newFile("empty.xml");
        File error = temporaryFolder.newFile("error.xml");
        Files.writeString(valid.toPath(), "<?xml version=\"1.0\"?><i><d p=\"0,1\">hello</d></i>",
                StandardCharsets.UTF_8);
        Files.writeString(empty.toPath(), "<?xml version=\"1.0\"?><i></i>                         ",
                StandardCharsets.UTF_8);
        Files.writeString(error.toPath(), "<error>upstream unavailable</error>                     ",
                StandardCharsets.UTF_8);

        assertTrue(DanmakuDocumentCache.isUsable(valid));
        assertFalse(DanmakuDocumentCache.isUsable(empty));
        assertFalse(DanmakuDocumentCache.isUsable(error));
    }
}
