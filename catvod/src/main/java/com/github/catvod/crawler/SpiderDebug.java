package com.github.catvod.crawler;

import android.text.TextUtils;

import com.orhanobut.logger.Logger;
import com.github.catvod.utils.SecretRedactor;

import java.io.PrintWriter;
import java.io.StringWriter;

public class SpiderDebug {

    private static final String TAG = SpiderDebug.class.getSimpleName();
    /* Never run Android's native ICU matcher over an unbounded provider response. */
    private static final int MAX_LOG_INPUT = 8192;

    public static void log(Throwable th) {
        if (th == null) return;
        StringWriter writer = new StringWriter();
        th.printStackTrace(new PrintWriter(writer));
        Logger.t(TAG).e(safe(writer.toString()));
    }

    public static void log(String msg) {
        if (!TextUtils.isEmpty(msg)) Logger.t(TAG).d(safe(msg));
    }

    public static void log(String tag, String msg, Object... args) {
        if (TextUtils.isEmpty(msg)) return;
        String formatted;
        try {
            formatted = args == null || args.length == 0 ? msg : String.format(msg, args);
        } catch (Throwable ignored) {
            formatted = msg;
        }
        Logger.t(tag).d(safe(formatted));
    }

    private static String safe(String value) {
        if (value == null || value.isEmpty()) return "";
        if (value.length() <= MAX_LOG_INPUT) return SecretRedactor.redact(value);
        String head = value.substring(0, MAX_LOG_INPUT);
        return SecretRedactor.redact(head) + "\n… [truncated " + (value.length() - MAX_LOG_INPUT) + " chars]";
    }
}
