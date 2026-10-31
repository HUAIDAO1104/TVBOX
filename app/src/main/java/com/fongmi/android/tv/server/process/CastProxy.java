package com.fongmi.android.tv.server.process;

import com.fongmi.android.tv.server.CastMediaRelay;
import com.fongmi.android.tv.server.impl.Process;
import com.github.catvod.net.OkHttp;

import java.util.Map;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;

public final class CastProxy implements Process {
    private static class Holder {
        static final CastMediaRelay RELAY = new CastMediaRelay(OkHttp.player());
    }

    public static CastMediaRelay relay() { return Holder.RELAY; }

    @Override public boolean isRequest(IHTTPSession session, String url) {
        return url.startsWith(CastMediaRelay.PREFIX);
    }

    @Override public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        return relay().serve(session);
    }
}
