package com.fongmi.android.tv.repository;

import java.util.Collections;
import java.util.List;

/** Prevents a missing scoped context from silently falling back to the active/default config. */
public final class RepositoryPlaybackContext {

    private RepositoryPlaybackContext() {
    }

    public static <T> List<T> select(boolean scopedKey, boolean contextBound,
                                     List<T> repositoryValues, List<T> defaultValues) {
        if (!scopedKey) return safe(defaultValues);
        if (!contextBound) return Collections.emptyList();
        return safe(repositoryValues);
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? Collections.emptyList() : values;
    }
}
