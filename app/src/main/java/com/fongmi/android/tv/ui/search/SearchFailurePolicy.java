package com.fongmi.android.tv.ui.search;

import java.util.Locale;

/** Classifies source failures that are safe to retry without exposing a false page-level error. */
public final class SearchFailurePolicy {

    private SearchFailurePolicy() {
    }

    public static boolean isEmptyPayload(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.trim().toLowerCase(Locale.ROOT);
                if (normalized.startsWith("end of input at character 0")
                        || normalized.equals("unexpected end of json input")
                        || normalized.equals("empty response body")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
