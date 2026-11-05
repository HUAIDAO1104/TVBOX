package com.fongmi.android.tv.offline;

import org.junit.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class OfflineMetadataTest {
    @Test public void identityIsStableAcrossHeaderOrderingButIsolatesCredentialsAndSources() {
        Map<String,String> first = new LinkedHashMap<>(); first.put("Referer", "https://example.com"); first.put("Authorization", "first");
        Map<String,String> reversed = new LinkedHashMap<>(); reversed.put("Authorization", "first"); reversed.put("Referer", "https://example.com");
        String id = OfflineMetadata.id("https://example.com/video", first);
        assertEquals(id, OfflineMetadata.id("https://example.com/video", reversed));
        reversed.put("Authorization", "second");
        assertNotEquals(id, OfflineMetadata.id("https://example.com/video", reversed));
        assertNotEquals(id, OfflineMetadata.id("https://example.com/other", first));
        assertEquals(id, OfflineMetadata.requireId(id));
    }
    @Test public void metadataRoundTripPreservesUnicodeHeadersDanmakuAndSubtitles() {
        OfflineMetadata source = new OfflineMetadata(); source.title = "离线播放测试"; source.episode = "第 8 集";
        source.danmaku = "https://example.com/danmaku.xml"; source.headers.put("Cookie", "source-session");
        OfflineMetadata.Subtitle sub = new OfflineMetadata.Subtitle(); sub.uri = "https://example.com/sub.vtt"; sub.mime = "text/vtt"; source.subtitles.add(sub);
        OfflineMetadata copy = OfflineMetadata.decode(source.encode());
        assertEquals(source.title, copy.title); assertEquals(source.episode, copy.episode); assertEquals(source.danmaku, copy.danmaku);
        assertEquals(source.headers, copy.headers); assertEquals(sub.uri, copy.subtitles.get(0).uri);
    }
    @Test public void directoryTraversalAndInvalidIdsAreRejected() {
        for (String id : new String[]{null, "", "../media", "abc/def", "A".repeat(64), "f".repeat(63)}) {
            try { OfflineMetadata.requireId(id); fail("Accepted invalid id"); } catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void damagedMetadataDoesNotCrashTheManager() {
        assertNotNull(OfflineMetadata.decode(new byte[]{1,2,3}).headers);
        assertNotNull(OfflineMetadata.decode("null".getBytes()).subtitles);
        assertNotNull(OfflineMetadata.decode("{\"headers\":null}".getBytes()).headers);
    }
}
