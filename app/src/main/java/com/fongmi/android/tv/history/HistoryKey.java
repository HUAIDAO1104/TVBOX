package com.fongmi.android.tv.history;

/** Persisted keys include the device-local subscription id. Peer identities never include it. */
public final class HistoryKey {
    private static final String SEP = "@@@";

    public static String content(String stored) {
        int first = stored.indexOf(SEP);
        int last = stored.lastIndexOf(SEP);
        if (first < 0 || last == first) return stored;
        String suffix = stored.substring(last + SEP.length());
        if (suffix.matches("-?[0-9]+")) return stored.substring(0, last);
        return stored;
    }

    public static String local(String content, int cid) {
        return content + SEP + cid;
    }

    public static String vodId(String stored) {
        String content = content(stored);
        int sep = content.indexOf(SEP);
        return sep < 0 ? content : content.substring(sep + SEP.length());
    }
}
