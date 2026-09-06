package com.github.catvod.spider;

/** Deliberately ignores interrupts to reproduce uncooperative third-party code. Test APK only. */
public final class SearchFixture extends com.github.catvod.crawler.Spider {
    private int searches;
    @Override public String searchContent(String keyword, boolean quick) {
        searches++;
        if ("init".equals(keyword)) return "{\"list\":[{\"vod_id\":\"fixture\",\"vod_name\":\"庆余年\",\"vod_remarks\":\"" + Init.attempts + "\"}]}";
        if ("proxy".equals(keyword)) {
            String body = com.github.catvod.net.OkHttp.string(com.github.catvod.Proxy.getUrl(true) + "?siteKey=" + proxyKey);
            return "{\"list\":[{\"vod_id\":\"proxy\",\"vod_name\":\"庆余年\",\"vod_remarks\":\"" + body + "\"}]}";
        }
        if ("hang".equals(keyword)) {
            while (true) {
                java.util.concurrent.locks.LockSupport.parkNanos(10000000L);
                Thread.interrupted();
            }
        }
        return "{\"list\":[{\"vod_id\":\"fixture\",\"vod_name\":\"庆余年\",\"vod_remarks\":\""
                + android.os.Process.myPid() + ":" + searches + "\"}]}";
    }

    @Override public Object[] proxy(java.util.Map<String, String> params) {
        return new Object[] {200, "text/plain", new java.io.ByteArrayInputStream("worker-proxy-ready".getBytes())};
    }
}
