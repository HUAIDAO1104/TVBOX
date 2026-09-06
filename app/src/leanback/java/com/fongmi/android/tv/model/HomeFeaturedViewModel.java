package com.fongmi.android.tv.model;

import android.text.TextUtils;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.github.catvod.net.OkHttp;
import com.google.common.net.HttpHeaders;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Resolves sparse index cards against a playable source without blocking the home screen. */
public class HomeFeaturedViewModel extends ViewModel {

    private static final List<String> SOURCE_PRIORITY = List.of("二小", "玩偶", "NewZhiZhen", "NewGuanYing");
    private static final int SOURCE_LIMIT = 4;

    private final com.google.common.util.concurrent.ListeningExecutorService editorialExecutor =
            com.google.common.util.concurrent.MoreExecutors.listeningDecorator(java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "home-editorial");
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            }));
    private final MutableLiveData<Result> result = new MutableLiveData<>();
    private final ViewModelTaskRunner<TaskType> tasks = new ViewModelTaskRunner<>(TaskType.class);

    public LiveData<Result> getResult() {
        return result;
    }

    public void resolve(Vod seed) {
        if (seed == null || seed.getName().isEmpty()) return;
        tasks.execute(TaskType.FEATURED, 12000, editorialExecutor, () -> resolveInternal(seed), result::postValue, error -> result.postValue(Result.vod(seed)));
    }

    private Result resolveInternal(Vod seed) {
        Vod editorial = doubanDetail(seed);
        if (hasEditorialDetail(editorial)) return Result.vod(editorial);
        if (Thread.currentThread().isInterrupted()) return Result.vod(seed);
        Vod direct = directDetail(seed);
        if (hasEditorialDetail(direct)) return resolved(seed, direct, direct.getSite());
        for (Site site : detailSources(seed)) {
            if (Thread.currentThread().isInterrupted()) break;
            try {
                Vod match = bestMatch(search(site, seed.getName()).getList(), seed.getName());
                if (match == null || match.getId().isEmpty()) continue;
                Vod detail = detail(site, match.getId()).getVod();
                if (detail.getId().isEmpty()) detail = match;
                if (!hasEditorialDetail(detail) && !hasEditorialDetail(match)) continue;
                return resolved(seed, detail, site);
            } catch (Exception ignored) {
            }
        }
        return Result.vod(direct == null ? seed : direct);
    }

    private Result search(Site site, String title) throws Exception {
        return site.getType() == 3 ? com.fongmi.android.tv.search.IsolatedSpiderSearch.search(site, title, false, "1")
                : SiteApi.searchContent(site, title, false, "1");
    }

    private Result detail(Site site, String id) throws Exception {
        return site.getType() == 3 ? com.fongmi.android.tv.search.IsolatedSpiderSearch.detail(site, id)
                : SiteApi.detailContent(site.getKey(), id);
    }

    private Vod doubanDetail(Vod seed) {
        if (!seed.getId().matches("msearch:[0-9]+")) return null;
        try {
            String subjectId = seed.getId().substring(seed.getId().indexOf(':') + 1);
            String json = OkHttp.string("https://m.douban.com/rexxar/api/v2/movie/" + subjectId, Map.of(
                    HttpHeaders.USER_AGENT, "Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36 Chrome/140 Safari/537.36",
                    HttpHeaders.REFERER, "https://movie.douban.com/"
            ));
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            Vod detail = new Vod();
            detail.setId(seed.getId());
            detail.setName(value(object, "title", seed.getName()));
            detail.setPic(seed.getPic());
            detail.setYear(value(object, "year", ""));
            detail.setArea(join(object.getAsJsonArray("countries"), " "));
            detail.setTypeName(join(object.getAsJsonArray("genres"), " / "));
            detail.setContent(value(object, "intro", ""));
            detail.setDirector(joinNames(object.getAsJsonArray("directors")));
            detail.setRemarks(seed.getRemarks());
            detail.setSite(seed.getSite());
            return detail;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String value(JsonObject object, String key, String fallback) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? fallback : element.getAsString();
    }

    private String join(JsonArray array, String separator) {
        if (array == null || array.isEmpty()) return "";
        List<String> values = new ArrayList<>();
        for (JsonElement element : array) if (!element.isJsonNull()) values.add(element.getAsString());
        return TextUtils.join(separator, values);
    }

    private String joinNames(JsonArray array) {
        if (array == null || array.isEmpty()) return "";
        List<String> values = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            String name = value(element.getAsJsonObject(), "name", "");
            if (!name.isEmpty()) values.add(name);
        }
        return TextUtils.join(" / ", values);
    }

    private Vod directDetail(Vod seed) {
        if (seed.getId().isEmpty() || seed.getSiteKey().isEmpty()) return null;
        try {
            Vod detail = detail(VodConfig.get().getSite(seed.getSiteKey()), seed.getId()).getVod();
            if (detail.getId().isEmpty()) return null;
            detail.setSite(seed.getSite());
            return detail;
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<Site> detailSources(Vod seed) {
        List<Site> sites = new ArrayList<>();
        for (Site site : VodConfig.get().getSites()) {
            if (!site.isSearchable() || site.isHide() || site.isIndex() || site.getKey().equals(seed.getSiteKey())) continue;
            sites.add(site);
        }
        sites.sort(Comparator.comparingInt(this::sourceRank));
        return sites.subList(0, Math.min(SOURCE_LIMIT, sites.size()));
    }

    private int sourceRank(Site site) {
        int index = SOURCE_PRIORITY.indexOf(site.getKey());
        return index >= 0 ? index : SOURCE_PRIORITY.size() + 1;
    }

    private Vod bestMatch(List<Vod> items, String requestedName) {
        if (items == null || items.isEmpty()) return null;
        String requested = normalize(requestedName);
        Vod partial = null;
        for (Vod item : items) {
            String candidate = normalize(item.getName());
            if (candidate.equals(requested)) return item;
            if (partial == null && requested.length() >= 3 && (candidate.contains(requested) || requested.contains(candidate))) partial = item;
        }
        return partial;
    }

    private String normalize(String value) {
        if (TextUtils.isEmpty(value)) return "";
        return value.toLowerCase(Locale.ROOT).replaceAll("[\\s·:：—_\\-（）()【】\\[\\]《》]", "");
    }

    private boolean hasEditorialDetail(Vod item) {
        return item != null && (!item.getContent().isEmpty() || !item.getYear().isEmpty() || !item.getArea().isEmpty() || !item.getTypeName().isEmpty());
    }

    private Result resolved(Vod seed, Vod detail, Site site) {
        if (detail == null) return Result.vod(seed);
        detail.checkName(seed.getName());
        if (!seed.getRemarks().isEmpty() && !seed.getRemarks().matches(".*(?:豆瓣|评分)[:：\\s]*0(?:\\.0+)?.*")) {
            String remarks = detail.getRemarks();
            detail.setRemarks(seed.getRemarks() + (remarks.isEmpty() ? "" : " · " + remarks));
        }
        if (!seed.getPic().isEmpty()) detail.setPic(seed.getPic());
        else detail.checkPic(seed.getPic());
        if (site != null && !site.isEmpty()) detail.setSite(site);
        return Result.vod(detail);
    }

    public void cancel() {
        tasks.cancelAll();
    }

    @Override
    protected void onCleared() {
        tasks.cancelAll();
        editorialExecutor.shutdownNow();
    }

    private enum TaskType {FEATURED}
}
