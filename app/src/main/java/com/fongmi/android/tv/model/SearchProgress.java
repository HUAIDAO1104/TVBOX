package com.fongmi.android.tv.model;

import com.fongmi.android.tv.bean.Result;

/**
 * Immutable aggregate-search progress.  It deliberately contains counters only so raw Spider
 * errors never leak into the normal UI and a failed source cannot erase results from other sites.
 */
public record SearchProgress(
        int session,
        int total,
        int completed,
        int successful,
        int empty,
        int failed,
        int timedOut,
        int resultCount,
        boolean running,
        boolean cancelled) {

    public static SearchProgress idle() {
        return new SearchProgress(0, 0, 0, 0, 0, 0, 0, 0, false, false);
    }

    public static SearchProgress started(int session, int total) {
        int safeTotal = Math.max(0, total);
        return new SearchProgress(session, safeTotal, 0, 0, 0, 0, 0, 0, safeTotal > 0, false);
    }

    public SearchProgress result(Result result) {
        int count = result == null ? 0 : result.getList().size();
        boolean sourceError = count == 0 && result != null && result.hasMsg();
        int nextCompleted = Math.min(total, completed + 1);
        int nextSuccessful = successful + (count > 0 ? 1 : 0);
        int nextEmpty = empty + (count == 0 && !sourceError ? 1 : 0);
        return new SearchProgress(session, total, nextCompleted, nextSuccessful, nextEmpty,
                failed + (sourceError ? 1 : 0), timedOut,
                resultCount + count, nextCompleted < total, false);
    }

    /** Replaces one pending repository-config placeholder with its discovered site count. */
    public SearchProgress expand(int delta) {
        if (delta == 0 || cancelled) return this;
        int nextTotal = Math.max(completed, total + delta);
        return new SearchProgress(session, nextTotal, completed, successful, empty, failed, timedOut,
                resultCount, completed < nextTotal, false);
    }

    public SearchProgress failure(boolean timeout) {
        int nextCompleted = Math.min(total, completed + 1);
        return new SearchProgress(session, total, nextCompleted, successful, empty,
                failed + (timeout ? 0 : 1), timedOut + (timeout ? 1 : 0), resultCount,
                nextCompleted < total, false);
    }

    public SearchProgress cancel() {
        if (!running) return this;
        return new SearchProgress(session, total, completed, successful, empty, failed, timedOut, resultCount,
                false, true);
    }

    public int pending() {
        return Math.max(0, total - completed);
    }
}
