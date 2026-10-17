package com.fongmi.android.tv.update;

import okhttp3.OkHttpClient;

/** Official updates use system DNS/TLS/proxy settings, independent of video source overrides. */
public final class UpdateNetwork {
    private static final OkHttpClient CLIENT = new OkHttpClient();
    private UpdateNetwork() { }
    public static OkHttpClient client() { return CLIENT; }
}
