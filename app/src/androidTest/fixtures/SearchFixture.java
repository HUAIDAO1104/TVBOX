package com.github.catvod.spider;

/** Deliberately ignores interrupts to reproduce uncooperative third-party code. Test APK only. */
public final class SearchFixture extends com.github.catvod.crawler.Spider {
    @Override public String searchContent(String keyword, boolean quick) {
        if ("hang".equals(keyword)) {
            while (true) {
                java.util.concurrent.locks.LockSupport.parkNanos(10000000L);
                Thread.interrupted();
            }
        }
        return "{\"list\":[{\"vod_id\":\"fixture\",\"vod_name\":\"庆余年\"}]}";
    }
}
