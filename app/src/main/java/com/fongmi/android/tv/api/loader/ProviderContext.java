package com.fongmi.android.tv.api.loader;

import android.content.Context;
import android.content.ContextWrapper;

import com.fongmi.android.tv.App;

/**
 * Context handed to untrusted provider implementations.
 *
 * <p>Repository spiders occasionally call {@code Toast.makeText} directly from their init code,
 * bypassing the app's notification filter. A null operation package makes Android reject those
 * provider-owned toast requests before they enter the system queue. App-owned status and error
 * messages continue to use the normal application context through {@code Notify}.</p>
 */
final class ProviderContext extends ContextWrapper {

    private static volatile ProviderContext instance;

    static Context get() {
        ProviderContext value = instance;
        if (value == null) {
            synchronized (ProviderContext.class) {
                value = instance;
                if (value == null) instance = value = new ProviderContext(App.get());
            }
        }
        return value;
    }

    private ProviderContext(Context base) {
        super(base);
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }

    @Override
    public String getOpPackageName() {
        return null;
    }
}
