package com.fongmi.android.tv.history;

import java.util.Objects;

/** The common revision is recorded only after both copies acknowledge the same state. */
public final class HistoryReconcile {
    public enum Decision { SAME, PUSH, PULL, CONFLICT }
    static final String MISSING = "-";

    public static Decision decide(String common, String phone, String tv) {
        if (Objects.equals(phone, tv)) return Decision.SAME;
        if (Objects.equals(tv, common)) return Decision.PUSH;
        if (Objects.equals(phone, common)) return Decision.PULL;
        return Decision.CONFLICT;
    }
}
