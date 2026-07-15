package com.fongmi.android.tv.server.process;

import com.fongmi.android.tv.cloud.CloudMemoryStore;
import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.impl.Process;

import java.util.Map;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Method;
import fi.iki.elonen.NanoHTTPD.Response;

public class CredentialMemory implements Process {

    private static final String PREFIX = "/credential-memory/";

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.startsWith(PREFIX);
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        String remote = session.getRemoteIpAddress();
        boolean loopback = "127.0.0.1".equals(remote) || "::1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote);
        if (session.getMethod() != Method.GET || !loopback) return Nano.error(Response.Status.FORBIDDEN, "Forbidden");
        String content = CloudMemoryStore.read(url.substring(PREFIX.length()));
        if (content == null) return Nano.error(Response.Status.NOT_FOUND, "Expired");
        Response response = Nano.ok(content);
        response.addHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        response.addHeader("Pragma", "no-cache");
        response.addHeader("X-Content-Type-Options", "nosniff");
        return response;
    }
}
