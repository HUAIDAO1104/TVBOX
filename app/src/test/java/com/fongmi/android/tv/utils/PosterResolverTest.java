package com.fongmi.android.tv.utils;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Test;

public class PosterResolverTest {

    @After
    public void tearDown() {
        PosterResolver.clearForTest();
    }

    @Test public void leastRecentlyUsedTitlesAreEvicted() {
        for (int i = 0; i < 512; i++) PosterResolver.remember("独立作品" + i, "https://example.test/" + i);
        assertEquals("https://example.test/0", PosterResolver.resolve("独立作品0", ""));
        PosterResolver.remember("新增作品", "https://example.test/new");
        assertEquals("", PosterResolver.resolve("独立作品1", ""));
        assertEquals("https://example.test/0", PosterResolver.resolve("独立作品0", ""));
    }

    @Test
    public void missingPosterBorrowsFromSameNormalizedWork() {
        PosterResolver.remember("庆余年 第二季 4K", "https://img.example/season2.jpg");

        assertEquals("https://img.example/season2.jpg",
                PosterResolver.resolve("庆余年第2季", ""));
    }

    @Test
    public void differentSeasonsNeverSharePoster() {
        PosterResolver.remember("庆余年第一季", "https://img.example/season1.jpg");

        assertEquals("", PosterResolver.resolve("庆余年第二季", ""));
    }

    @Test
    public void failedPosterFallsBackToAnotherRealSourceForSameWork() {
        PosterResolver.remember("庆余年 第二季", "https://img.example/first.jpg");
        PosterResolver.remember("庆余年第2季 4K", "https://img.example/second.jpg");

        PosterResolver.forget("庆余年 第二季", "https://img.example/second.jpg");

        assertEquals("https://img.example/first.jpg",
                PosterResolver.resolve("庆余年第2季", ""));
    }
}
