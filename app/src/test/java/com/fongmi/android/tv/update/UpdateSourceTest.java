package com.fongmi.android.tv.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class UpdateSourceTest {

    @Test
    public void putsDomesticMirrorsBeforeDirectGithub() {
        List<String> candidates = UpdateSource.manifestCandidates();

        assertEquals(6, candidates.size());
        assertTrue(candidates.get(0).startsWith("https://v4.gh-proxy.org/"));
        assertTrue(candidates.get(1).startsWith("https://axisnow.gh-proxy.org/"));
        assertEquals("https://github.com/HUAIDAO1104/TVBOX/releases/latest/download/latest.json", candidates.get(5));
    }

    @Test
    public void slowFreshManifestWinsAgainstCachedMirrorAndUnreachableEndpoint() throws Exception {
        var gson = new com.google.gson.Gson();
        var cached = UpdateManifest.parse(gson, "{\"versionCode\":651,\"versionName\":\"5.5.100\"}");
        var published = UpdateManifest.parse(gson, "{\"versionCode\":652,\"versionName\":\"5.5.101\"}");
        var mirrorReturned = new java.util.concurrent.CountDownLatch(1);
        var result = UpdateClient.newestAvailable(List.of(
                () -> { mirrorReturned.countDown(); return cached; },
                () -> { mirrorReturned.await(); return published; },
                () -> { throw new java.io.IOException("offline"); },
                () -> { new java.util.concurrent.CountDownLatch(1).await(); return cached; }
        ), 1000);
        assertEquals(652, result.versionCode());
    }

    @Test
    public void declaredMirrorsTakePriorityAndDuplicatesAreRemoved() {
        String github = "https://github.com/HUAIDAO1104/TVBOX/releases/latest/download/app.apk";
        List<String> candidates = UpdateSource.mirrorFirst(github, List.of("https://download.example.com/app.apk", "https://download.example.com/app.apk"));

        assertEquals("https://download.example.com/app.apk", candidates.get(0));
        assertEquals(7, candidates.size());
        assertEquals(github, candidates.get(candidates.size() - 1));
    }
}
