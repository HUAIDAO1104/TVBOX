package com.fongmi.android.tv.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class UpdateSourceTest {

    @Test
    public void putsDomesticMirrorsBeforeDirectGithub() {
        List<String> candidates = UpdateSource.manifestCandidates();

        assertEquals(4, candidates.size());
        assertTrue(candidates.get(0).startsWith("https://ghfast.top/"));
        assertTrue(candidates.get(1).startsWith("https://gh-proxy.com/"));
        assertTrue(candidates.get(2).startsWith("https://ghproxy.net/"));
        assertEquals("https://github.com/HUAIDAO1104/TVBOX/releases/latest/download/latest.json", candidates.get(3));
    }

    @Test
    public void declaredMirrorsTakePriorityAndDuplicatesAreRemoved() {
        String github = "https://github.com/HUAIDAO1104/TVBOX/releases/latest/download/app.apk";
        List<String> candidates = UpdateSource.mirrorFirst(github, List.of("https://download.example.com/app.apk", "https://download.example.com/app.apk"));

        assertEquals("https://download.example.com/app.apk", candidates.get(0));
        assertEquals(5, candidates.size());
        assertEquals(github, candidates.get(candidates.size() - 1));
    }
}
