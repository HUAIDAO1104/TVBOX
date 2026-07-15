package com.fongmi.android.tv.cloud;

import com.fongmi.android.tv.api.loader.BaseLoader;
import com.github.catvod.utils.Prefers;

public enum CloudCredentialPriority {

    APP_ACCOUNT_FIRST,
    CURRENT_CONFIG_FIRST;

    private static final String KEY = "cloud_credential_priority";

    public static CloudCredentialPriority get() {
        int value = Prefers.getInt(KEY, APP_ACCOUNT_FIRST.ordinal());
        return value == CURRENT_CONFIG_FIRST.ordinal() ? CURRENT_CONFIG_FIRST : APP_ACCOUNT_FIRST;
    }

    public static void set(CloudCredentialPriority priority) {
        Prefers.put(KEY, (priority == null ? APP_ACCOUNT_FIRST : priority).ordinal());
        CloudCredentialBridge.clear();
        BaseLoader.get().clear();
    }
}
