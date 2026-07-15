package com.github.catvod.crawler;

import android.text.TextUtils;

import com.orhanobut.logger.Logger;
import com.github.catvod.utils.SecretRedactor;

import java.io.PrintWriter;
import java.io.StringWriter;

public class SpiderDebug {

    private static final String TAG = SpiderDebug.class.getSimpleName();

    public static void log(Throwable th) {
        if (th == null) return;
        StringWriter writer = new StringWriter();
        th.printStackTrace(new PrintWriter(writer));
        Logger.t(TAG).e(SecretRedactor.redact(writer.toString()));
    }

    public static void log(String msg) {
        if (!TextUtils.isEmpty(msg)) Logger.t(TAG).d(SecretRedactor.redact(msg));
    }

    public static void log(String tag, String msg, Object... args) {
        if (TextUtils.isEmpty(msg)) return;
        String formatted;
        try {
            formatted = args == null || args.length == 0 ? msg : String.format(msg, args);
        } catch (Throwable ignored) {
            formatted = msg;
        }
        Logger.t(tag).d(SecretRedactor.redact(formatted));
    }
}
