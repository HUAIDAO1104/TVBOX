package com.fongmi.quickjs.method;

import com.orhanobut.logger.Logger;
import com.github.catvod.utils.SecretRedactor;
import com.whl.quickjs.wrapper.QuickJSContext;

public class Console implements QuickJSContext.Console {

    private static final String TAG = "quickjs";

    @Override
    public void log(String info) {
        Logger.t(TAG).d(SecretRedactor.redact(info));
    }

    @Override
    public void info(String info) {
        Logger.t(TAG).i(SecretRedactor.redact(info));
    }

    @Override
    public void warn(String info) {
        Logger.t(TAG).w(SecretRedactor.redact(info));
    }

    @Override
    public void error(String info) {
        Logger.t(TAG).e(SecretRedactor.redact(info));
    }
}
