package com.fongmi.android.tv.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class RepositoryPlaybackContextTest {

    @Test
    public void activeSiteUsesDefaultConfigValues() {
        assertEquals(List.of("default"), RepositoryPlaybackContext.select(
                false, false, List.of("repository"), List.of("default")));
    }

    @Test
    public void scopedSiteUsesOnlyItsBoundValues() {
        assertEquals(List.of("repository"), RepositoryPlaybackContext.select(
                true, true, List.of("repository"), List.of("default")));
    }

    @Test
    public void missingScopedContextNeverLeaksDefaultValues() {
        assertTrue(RepositoryPlaybackContext.select(
                true, false, List.of(), List.of("default")).isEmpty());
    }

    @Test
    public void explicitlyEmptyScopedContextRemainsEmpty() {
        assertTrue(RepositoryPlaybackContext.select(
                true, true, List.of(), List.of("default")).isEmpty());
    }
}
