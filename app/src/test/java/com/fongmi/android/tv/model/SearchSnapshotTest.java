package com.fongmi.android.tv.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.fongmi.android.tv.bean.Result;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class SearchSnapshotTest {

    @Test
    public void keepsAnImmutableCopyOfAllCompletedSourceResults() {
        List<Result> source = new ArrayList<>();
        source.add(Result.empty());
        SearchSnapshot snapshot = new SearchSnapshot(7, source);
        source.add(Result.empty());

        assertEquals(7, snapshot.session());
        assertEquals(1, snapshot.results().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.results().add(Result.empty()));
    }
}
