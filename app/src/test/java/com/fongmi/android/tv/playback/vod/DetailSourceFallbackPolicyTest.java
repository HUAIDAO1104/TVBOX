package com.fongmi.android.tv.playback.vod;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

public class DetailSourceFallbackPolicyTest {

    @Test
    public void selectedSourceIsFirstAndDuplicatesAreRemoved() {
        List<String> result = DetailSourceFallbackPolicy.prioritize(
                "second", List.of("first", "second", "third", "first"), value -> value, 8);

        assertEquals(List.of("second", "first", "third"), result);
    }

    @Test
    public void eachRemainingSourceIsOfferedOnce() {
        DetailSourceFallbackPolicy policy = new DetailSourceFallbackPolicy(3, 0);

        assertEquals(1, policy.nextIndex());
        assertEquals(2, policy.nextIndex());
        assertEquals(-1, policy.nextIndex());
        assertEquals(-1, policy.nextIndex());
    }

    @Test
    public void candidateCountIsBoundedForIntentSafety() {
        List<String> result = DetailSourceFallbackPolicy.prioritize(
                "0", List.of("0", "1", "2", "3"), value -> value, 3);

        assertEquals(List.of("0", "1", "2"), result);
    }

    @Test
    public void restoredMiddlePositionNeverRetriesPreviousSources() {
        DetailSourceFallbackPolicy policy = new DetailSourceFallbackPolicy(4, 2);

        assertEquals(3, policy.nextIndex());
        assertEquals(-1, policy.nextIndex());
    }

    @Test
    public void emptyCandidateSetHasNoFallback() {
        DetailSourceFallbackPolicy policy = new DetailSourceFallbackPolicy(0, 0);

        assertEquals(-1, policy.nextIndex());
    }
}
