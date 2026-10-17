package com.fongmi.android.tv.update;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Updates must start even while a cold TV is restoring repositories and plugin files. */
public final class UpdateExecutor {
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private UpdateExecutor() { }
    public static Future<?> submit(Runnable task) { return EXECUTOR.submit(task); }
}
