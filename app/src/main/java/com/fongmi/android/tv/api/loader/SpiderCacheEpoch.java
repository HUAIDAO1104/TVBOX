package com.fongmi.android.tv.api.loader;

import java.util.concurrent.atomic.AtomicLong;

/** Makes stale Spider creations unreachable immediately after a credential/config refresh. */
final class SpiderCacheEpoch {

    private final AtomicLong value = new AtomicLong();

    long current() {
        return value.get();
    }

    long advance() {
        return value.incrementAndGet();
    }

    boolean isCurrent(long epoch) {
        return value.get() == epoch;
    }

    String scope(long epoch, String cacheKey) {
        return epoch + ":" + cacheKey;
    }
}
