package com.fongmi.android.tv.api.loader;

import com.fongmi.android.tv.App;
import com.fongmi.chaquo.Loader;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class PyLoader {

    private final ConcurrentHashMap<String, Spider> spiders;
    private final Loader loader;
    private volatile String recent;

    public PyLoader() {
        spiders = new ConcurrentHashMap<>();
        loader = new Loader();
    }

    public void clear() {
        detach().forEach(Spider::destroy);
    }

    public List<Spider> detach() {
        List<Spider> detached = new ArrayList<>(spiders.values());
        spiders.clear();
        recent = null;
        return detached;
    }

    public void discard(String cacheKey, Spider spider) {
        spiders.remove(cacheKey, spider);
    }

    public void setRecent(String recent) {
        this.recent = recent;
    }

    public Spider getSpider(String key, String api, String ext) {
        return getSpider(key, key, api, ext);
    }

    public Spider getSpider(String cacheKey, String siteKey, String api, String ext) {
        return getSpider(cacheKey, siteKey, siteKey, api, ext);
    }

    public Spider getSpider(String cacheKey, String proxyKey, String siteKey, String api, String ext) {
        Spider cached = spiders.computeIfAbsent(cacheKey, k -> {
            try {
                Spider spider = loader.spider(api);
                spider.siteKey = siteKey;
                spider.proxyKey = proxyKey;
                spider.init(App.get(), ext);
                return spider;
            } catch (Throwable e) {
                com.github.catvod.crawler.SpiderDebug.log(e);
                if (com.fongmi.android.tv.App.isSearchProcess()) throw new IllegalStateException("来源初始化失败", e);
                return null;
            }
        });
        return cached == null ? new SpiderNull() : cached;
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        if (recent == null) return null;
        Spider spider = spiders.get(recent);
        return spider != null ? spider.proxy(params) : null;
    }
}
