package com.fongmi.android.tv.utils;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Test;

public class PosterResolverTest {

    @After
    public void tearDown() {
        PosterResolver.clearForTest();
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
