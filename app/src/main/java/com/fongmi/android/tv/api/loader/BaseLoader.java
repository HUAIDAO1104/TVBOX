package com.fongmi.android.tv.api.loader;

import android.text.TextUtils;

import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.cloud.CloudCredentialBridge;
import com.fongmi.android.tv.cloud.CloudCredentialPreferences;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;
import com.github.catvod.utils.Util;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dalvik.system.DexClassLoader;

public class BaseLoader {

    private final JarLoader jarLoader;
    private volatile PyLoader pyLoader;
    private volatile JsLoader jsLoader;
    private final SpiderCacheEpoch cacheEpoch;
    private final Object cacheLock;

    private BaseLoader() {
        jarLoader = new JarLoader();
        cacheEpoch = new SpiderCacheEpoch();
        cacheLock = new Object();
    }

    private synchronized PyLoader python() {
        if (pyLoader == null) pyLoader = new PyLoader();
        return pyLoader;
    }

    private synchronized JsLoader javascript() {
        if (jsLoader == null) jsLoader = new JsLoader();
        return jsLoader;
    }

    public static BaseLoader get() {
        return Loader.INSTANCE;
    }

    private static boolean isJs(String api) {
        return api.contains(".js");
    }

    private static boolean isPy(String api) {
        return api.contains(".py");
    }

    private static boolean isCsp(String api) {
        return api.startsWith("csp_");
    }

    public void clear() {
        List<Spider> detached = new ArrayList<>();
        synchronized (cacheLock) {
            cacheEpoch.advance();
            CloudCredentialPreferences.clearRuntime();
            CloudCredentialBridge.clear();
            detached.addAll(jarLoader.detach());
            if (pyLoader != null) detached.addAll(pyLoader.detach());
            if (jsLoader != null) detached.addAll(jsLoader.detach());
        }
        Task.execute(() -> detached.forEach(Spider::destroy));
    }

    public Spider getSpider(String key, String api, String ext, String jar) {
        return getSpider(key, key, api, ext, jar);
    }

    public Spider getSpider(String cacheKey, String siteKey, String api, String ext, String jar) {
        while (true) {
            long epoch;
            String scopedCacheKey;
            String resolvedExt = CloudCredentialBridge.resolve(ext);
            synchronized (cacheLock) {
                epoch = cacheEpoch.current();
                scopedCacheKey = cacheEpoch.scope(epoch, definitionKey(cacheKey, api, resolvedExt, jar));
            }
            Spider spider;
            if (isPy(api)) spider = python().getSpider(scopedCacheKey, cacheKey, siteKey, api, resolvedExt);
            else if (isJs(api)) spider = javascript().getSpider(scopedCacheKey, cacheKey, siteKey, api, resolvedExt, jar);
            else if (isCsp(api)) spider = jarLoader.getSpider(scopedCacheKey, cacheKey, siteKey, api, resolvedExt, jar);
            else return new SpiderNull();
            if (cacheEpoch.isCurrent(epoch)) return spider;
            discard(scopedCacheKey, api, jar, spider);
        }
    }

    private void discard(String cacheKey, String api, String jar, Spider spider) {
        if (isPy(api)) python().discard(cacheKey, spider);
        else if (isJs(api)) javascript().discard(cacheKey, spider);
        else if (isCsp(api)) jarLoader.discard(cacheKey, jar, spider);
        Task.execute(spider::destroy);
    }

    public Spider getSpider(String key) {
        Site site = VodConfig.get().getSite(key);
        Live live = LiveConfig.get().getLive(key);
        if (!site.isEmpty()) return site.spider();
        if (!live.isEmpty()) return live.spider();
        return new SpiderNull();
    }

    public void setRecent(String key, String api, String jar) {
        setRecent(key, api, "", jar);
    }

    public void setRecent(String key, String api, String ext, String jar) {
        synchronized (cacheLock) {
            String resolvedExt = CloudCredentialBridge.resolve(ext);
            String scopedKey = cacheEpoch.scope(cacheEpoch.current(), definitionKey(key, api, resolvedExt, jar));
            if (isJs(api)) javascript().setRecent(scopedKey);
            else if (isPy(api)) python().setRecent(scopedKey);
            else if (isCsp(api)) jarLoader.setRecent(Util.md5(jar));
        }
    }

    private String definitionKey(String routingKey, String api, String ext, String jar) {
        return routingKey + "#" + Util.md5(api + '\n' + ext + '\n' + jar);
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        if (params.containsKey("siteKey")) return getSpider(params.get("siteKey")).proxy(params);
        if ("js".equals(params.get("do"))) return javascript().proxy(params);
        if ("py".equals(params.get("do"))) return python().proxy(params);
        return jarLoader.proxy(params);
    }

    public void parseJar(String jar, boolean recent) {
        if (TextUtils.isEmpty(jar)) return;
        String key = Util.md5(jar);
        jarLoader.parseJar(key, jar);
        if (recent) jarLoader.setRecent(key);
    }

    public DexClassLoader dex(String jar) {
        return jarLoader.dex(jar);
    }

    public JSONObject jsonExt(String key, LinkedHashMap<String, String> jxs, String url) throws Throwable {
        return jarLoader.jsonExt(key, jxs, url);
    }

    public JSONObject jsonExtMix(String flag, String key, String name, LinkedHashMap<String, HashMap<String, String>> jxs, String url) throws Throwable {
        return jarLoader.jsonExtMix(flag, key, name, jxs, url);
    }

    private static class Loader {
        static volatile BaseLoader INSTANCE = new BaseLoader();
    }
}
