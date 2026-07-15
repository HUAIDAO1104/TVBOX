package com.fongmi.android.tv.ui.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class HomeFeaturedPolicyTest {

    @Test
    public void previewAlwaysUsesFirstSixWithoutMutatingSource() {
        List<Integer> source = new ArrayList<>(List.of(1, 2, 3, 4, 5, 6, 7, 8));

        List<Integer> preview = HomeFeaturedPolicy.preview(source);

        assertEquals(List.of(1, 2, 3, 4, 5, 6), preview);
        assertEquals(8, source.size());
    }

    @Test
    public void previewKeepsShortListsIntact() {
        assertEquals(List.of("a", "b"), HomeFeaturedPolicy.preview(List.of("a", "b")));
        assertEquals(List.of(), HomeFeaturedPolicy.preview(List.of()));
    }

    @Test
    public void stableKeyIncludesSiteToAvoidCrossSiteCollisions() {
        String first = HomeFeaturedPolicy.stableKey("site-a", "vod-1");
        String second = HomeFeaturedPolicy.stableKey("site-b", "vod-1");

        assertNotEquals(first, second);
        assertNotEquals(HomeFeaturedPolicy.stableId(first), HomeFeaturedPolicy.stableId(second));
    }

    @Test
    public void resolvePositionKeepsFocusedItemAfterIncrementalInsertion() {
        String focused = HomeFeaturedPolicy.stableKey("site", "2");
        List<String> updated = List.of(
                HomeFeaturedPolicy.stableKey("site", "new"),
                HomeFeaturedPolicy.stableKey("site", "1"),
                focused,
                HomeFeaturedPolicy.stableKey("site", "3"));

        assertEquals(2, HomeFeaturedPolicy.resolvePosition(updated, focused, 1));
    }

    @Test
    public void resolvePositionClampsFallbackWhenFocusedItemDisappears() {
        assertEquals(1, HomeFeaturedPolicy.resolvePosition(List.of("a", "b"), "missing", 8));
        assertEquals(HomeFeaturedPolicy.NO_POSITION, HomeFeaturedPolicy.resolvePosition(List.of(), "missing", 0));
    }
}
