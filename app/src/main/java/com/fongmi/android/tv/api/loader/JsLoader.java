package com.fongmi.android.tv.api.loader;

import com.fongmi.quickjs.crawler.Loader;
import com.fongmi.quickjs.utils.Module;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class JsLoader {

    private final ConcurrentHashMap<String, Spider> spiders;
    private final Loader loader;
    private volatile String recent;

    public JsLoader() {
        spiders = new ConcurrentHashMap<>();
        loader = new Loader();
    }

    public void clear() {
        detach().forEach(Spider::destroy);
    }

    public List<Spider> detach() {
        List<Spider> detached = new ArrayList<>(spiders.values());
        Module.get().clear();
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

    public Spider getSpider(String key, String api, String ext, String jar) {
        return getSpider(key, key, api, ext, jar);
    }

    public Spider getSpider(String cacheKey, String siteKey, String api, String ext, String jar) {
        return getSpider(cacheKey, siteKey, siteKey, api, ext, jar);
    }

    public Spider getSpider(String cacheKey, String proxyKey, String siteKey, String api, String ext, String jar) {
        return spiders.computeIfAbsent(cacheKey, k -> {
            try {
                Spider spider = loader.spider(api, BaseLoader.get().dex(jar));
                spider.siteKey = siteKey;
                spider.proxyKey = proxyKey;
                spider.init(ProviderContext.get(), ext);
                return spider;
            } catch (Throwable e) {
                com.github.catvod.crawler.SpiderDebug.log(e);
                return new SpiderNull();
            }
        });
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        if (recent == null) return null;
        Spider spider = spiders.get(recent);
        return spider != null ? spider.proxy(params) : null;
    }
}
