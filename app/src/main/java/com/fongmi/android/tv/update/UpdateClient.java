package com.fongmi.android.tv.update;

import com.fongmi.android.tv.App;
import com.github.catvod.net.OkHttp;

import java.io.IOException;

import okhttp3.Response;
import okhttp3.ResponseBody;

public final class UpdateClient {

    private static final long SOURCE_TIMEOUT_MS = 7_000L;

    private UpdateClient() {
    }

    public static UpdateManifest fetch() throws IOException {
        IOException last = null;
        for (String url : UpdateSource.manifestCandidates()) {
            try (Response response = OkHttp.newCall(OkHttp.client(SOURCE_TIMEOUT_MS), url).execute()) {
                ResponseBody body = response.body();
                if (!response.isSuccessful() || body == null) throw new IOException("HTTP " + response.code());
                return UpdateManifest.parse(App.gson(), body.string());
            } catch (Exception e) {
                last = new IOException("Update source unavailable", e);
            }
        }
        throw last == null ? new IOException("No update source configured") : last;
    }
}
