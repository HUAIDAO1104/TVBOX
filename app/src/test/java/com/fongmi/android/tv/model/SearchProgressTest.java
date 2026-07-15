package com.fongmi.android.tv.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Vod;

import org.junit.Test;

import java.util.List;

public class SearchProgressTest {

    @Test
    public void aggregatesPartialResultsWithoutLosingFailures() {
        SearchProgress progress = SearchProgress.started(8, 3)
                .result(Result.list(List.of(new Vod(), new Vod())))
                .failure(true)
                .failure(false);

        assertEquals(3, progress.completed());
        assertEquals(1, progress.successful());
        assertEquals(1, progress.timedOut());
        assertEquals(1, progress.failed());
        assertEquals(2, progress.resultCount());
        assertFalse(progress.running());
    }

    @Test
    public void cancellationKeepsCompletedResults() {
        SearchProgress progress = SearchProgress.started(3, 4)
                .result(Result.list(List.of(new Vod())))
                .cancel();

        assertTrue(progress.cancelled());
        assertFalse(progress.running());
        assertEquals(1, progress.resultCount());
        assertEquals(3, progress.pending());
    }

    @Test
    public void zeroSourceSearchIsImmediatelyTerminal() {
        SearchProgress progress = SearchProgress.started(9, 0);

        assertFalse(progress.running());
        assertEquals(0, progress.pending());
        assertEquals(9, progress.session());
    }

    @Test
    public void partialSuccessSurvivesTimeoutAndFailure() {
        SearchProgress progress = SearchProgress.started(10, 3)
                .result(Result.list(List.of(new Vod())))
                .failure(true)
                .failure(false);

        assertFalse(progress.running());
        assertEquals(1, progress.successful());
        assertEquals(1, progress.timedOut());
        assertEquals(1, progress.failed());
        assertEquals(1, progress.resultCount());
        assertEquals(0, progress.pending());
    }

    @Test
    public void completionAndPendingCountersNeverOverflow() {
        SearchProgress progress = SearchProgress.started(11, 1)
                .failure(false)
                .failure(true);

        assertEquals(1, progress.completed());
        assertEquals(0, progress.pending());
        assertFalse(progress.running());
    }

    @Test
    public void repositoryPlaceholderExpandsToDiscoveredSites() {
        SearchProgress progress = SearchProgress.started(12, 2).expand(3);

        assertEquals(5, progress.total());
        assertEquals(5, progress.pending());
        assertTrue(progress.running());
    }

    @Test
    public void catalogPlaceholderPreventsTerminalFlickerAfterActiveSitesFinish() {
        SearchProgress activeFinished = SearchProgress.started(13, 2)
                .result(Result.list(List.of(new Vod())));
        SearchProgress catalogExpanded = activeFinished.expand(2);

        assertTrue(activeFinished.running());
        assertEquals(1, activeFinished.pending());
        assertTrue(catalogExpanded.running());
        assertEquals(3, catalogExpanded.pending());
    }

}
