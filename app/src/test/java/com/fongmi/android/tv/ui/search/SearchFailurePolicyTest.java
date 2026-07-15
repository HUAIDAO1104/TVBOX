package com.fongmi.android.tv.ui.search;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SearchFailurePolicyTest {

    @Test
    public void classifiesEmptyJsonPayloadThroughWrappedCause() {
        Throwable error = new IllegalStateException("wrapper",
                new RuntimeException("End of input at character 0 of"));

        assertTrue(SearchFailurePolicy.isEmptyPayload(error));
    }

    @Test
    public void leavesRealParserAndNetworkFailuresVisible() {
        assertFalse(SearchFailurePolicy.isEmptyPayload(new RuntimeException("Expected ':' at character 12")));
        assertFalse(SearchFailurePolicy.isEmptyPayload(new RuntimeException("timeout")));
    }
}
