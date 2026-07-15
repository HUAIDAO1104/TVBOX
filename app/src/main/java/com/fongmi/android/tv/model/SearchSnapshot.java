package com.fongmi.android.tv.model;

import com.fongmi.android.tv.bean.Result;

import java.util.List;

/** Durable aggregate-search snapshot; the latest LiveData value always contains every result. */
public record SearchSnapshot(int session, List<Result> results) {

    public SearchSnapshot {
        results = results == null ? List.of() : List.copyOf(results);
    }
}
