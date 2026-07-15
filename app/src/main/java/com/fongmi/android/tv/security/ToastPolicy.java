package com.fongmi.android.tv.security;

/** Keeps repository code from bypassing the app's filtered notification path. */
public final class ToastPolicy {

    private static final ThreadLocal<Integer> TRUSTED_DEPTH = ThreadLocal.withInitial(() -> 0);

    private ToastPolicy() {
    }

    public static void runTrusted(Runnable action) {
        int depth = TRUSTED_DEPTH.get();
        TRUSTED_DEPTH.set(depth + 1);
        try {
            action.run();
        } finally {
            if (depth == 0) TRUSTED_DEPTH.remove();
            else TRUSTED_DEPTH.set(depth);
        }
    }

    public static boolean shouldRejectPackageLookup() {
        return TRUSTED_DEPTH.get() == 0 && containsToastShow(Thread.currentThread().getStackTrace());
    }

    static boolean containsToastShow(StackTraceElement[] stack) {
        if (stack == null) return false;
        for (StackTraceElement frame : stack) {
            if ("android.widget.Toast".equals(frame.getClassName()) && "show".equals(frame.getMethodName())) return true;
        }
        return false;
    }
}
