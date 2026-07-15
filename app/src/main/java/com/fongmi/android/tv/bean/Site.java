package com.fongmi.android.tv.bean;

import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.api.loader.BaseLoader;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.gson.ExtAdapter;
import com.fongmi.android.tv.gson.HeaderAdapter;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Trans;
import com.google.gson.JsonElement;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Entity
public class Site implements Parcelable {

    @NonNull
    @PrimaryKey
    @SerializedName("key")
    private String key;

    @Ignore
    @SerializedName("name")
    private String name;

    @Ignore
    @SerializedName("api")
    private String api;

    @Ignore
    @SerializedName("ext")
    @JsonAdapter(ExtAdapter.class)
    private String ext;

    @Ignore
    @SerializedName("jar")
    private String jar;

    @Ignore
    @SerializedName("click")
    private String click;

    @Ignore
    @SerializedName("playUrl")
    private String playUrl;

    @Ignore
    @SerializedName("type")
    private Integer type;

    @Ignore
    @SerializedName("hide")
    private Integer hide;

    @Ignore
    @SerializedName("indexs")
    private Integer indexs;

    @Ignore
    @SerializedName("timeout")
    private Integer timeout;

    @SerializedName("searchable")
    private Integer searchable;

    @SerializedName("changeable")
    private Integer changeable;

    @Ignore
    @SerializedName("quickSearch")
    private Integer quickSearch;

    @Ignore
    @SerializedName("categories")
    private List<String> categories;

    @Ignore
    @SerializedName("header")
    @JsonAdapter(HeaderAdapter.class)
    private Map<String, String> header;

    @Ignore
    @SerializedName("style")
    private Style style;

    @Ignore
    private boolean selected;

    /**
     * Runtime-only routing metadata used by aggregate search.  A repository-scoped key is exposed
     * through {@link #getKey()} so two repositories may safely contain the same original site key,
     * while {@code originKey} is still passed to third-party Spider implementations that depend on
     * the key declared by their own configuration.
     */
    @Ignore
    private String originKey;

    @Ignore
    private long repositoryId;

    @Ignore
    private String repositoryName;

    @Ignore
    private String configName;

    @Ignore
    private String configUrl;

    @Ignore
    private int repositoryPriority;

    /** Playback settings from the repository config that declared this scoped site. */
    @Ignore
    private transient boolean repositoryPlaybackContextBound;

    @Ignore
    private transient List<String> repositoryFlags;

    @Ignore
    private transient List<Parse> repositoryParses;

    public Site() {
    }

    protected Site(Parcel in) {
        this.key = in.readString();
        this.name = in.readString();
        this.api = in.readString();
        this.ext = in.readString();
        this.jar = in.readString();
        this.click = in.readString();
        this.playUrl = in.readString();
        this.type = (Integer) in.readValue(Integer.class.getClassLoader());
        this.hide = (Integer) in.readValue(Integer.class.getClassLoader());
        this.indexs = (Integer) in.readValue(Integer.class.getClassLoader());
        this.timeout = (Integer) in.readValue(Integer.class.getClassLoader());
        this.searchable = (Integer) in.readValue(Integer.class.getClassLoader());
        this.changeable = (Integer) in.readValue(Integer.class.getClassLoader());
        this.quickSearch = (Integer) in.readValue(Integer.class.getClassLoader());
        this.categories = in.createStringArrayList();
        this.header = new HashMap<>();
        in.readMap(this.header, String.class.getClassLoader());
        this.style = in.readParcelable(Style.class.getClassLoader());
        this.selected = in.readByte() != 0;
        this.originKey = in.readString();
        this.repositoryId = in.readLong();
        this.repositoryName = in.readString();
        this.configName = in.readString();
        this.configUrl = in.readString();
        this.repositoryPriority = in.readInt();
        this.repositoryPlaybackContextBound = in.readByte() != 0;
        this.repositoryFlags = immutable(in.createStringArrayList());
        this.repositoryParses = parseRepositoryParses(in.readString());
    }

    public static Site objectFrom(JsonElement element, String spider) {
        try {
            Site site = App.gson().fromJson(element, Site.class);
            if (site.getJar().isEmpty()) site.setJar(spider);
            site.setApi(UrlUtil.convert(site.getApi()));
            site.setExt(UrlUtil.convert(site.getExt()));
            return site.trans();
        } catch (Exception e) {
            return new Site();
        }
    }

    public static Site get(String key, String name) {
        Site site = new Site();
        site.setKey(key);
        site.setName(name);
        return site;
    }

    public static List<Site> findAll() {
        return AppDatabase.get().getSiteDao().findAll();
    }

    public String getKey() {
        return TextUtils.isEmpty(key) ? "" : key;
    }

    public void setKey(@NonNull String key) {
        this.key = key;
    }

    public String getOriginKey() {
        return TextUtils.isEmpty(originKey) ? getKey() : originKey;
    }

    public void setOriginKey(String originKey) {
        this.originKey = originKey;
    }

    public long getRepositoryId() {
        return repositoryId;
    }

    public void setRepositoryId(long repositoryId) {
        this.repositoryId = repositoryId;
    }

    public String getRepositoryName() {
        return TextUtils.isEmpty(repositoryName) ? "" : repositoryName;
    }

    public void setRepositoryName(String repositoryName) {
        this.repositoryName = repositoryName;
    }

    public String getConfigName() {
        return TextUtils.isEmpty(configName) ? "" : configName;
    }

    public void setConfigName(String configName) {
        this.configName = configName;
    }

    public String getConfigUrl() {
        return TextUtils.isEmpty(configUrl) ? "" : configUrl;
    }

    public void setConfigUrl(String configUrl) {
        this.configUrl = configUrl;
    }

    public int getRepositoryPriority() {
        return repositoryPriority;
    }

    public void setRepositoryPriority(int repositoryPriority) {
        this.repositoryPriority = repositoryPriority;
    }

    public boolean hasRepositoryPlaybackContext() {
        return repositoryPlaybackContextBound;
    }

    public List<String> getRepositoryFlags() {
        return repositoryFlags == null ? Collections.emptyList() : repositoryFlags;
    }

    public List<Parse> getRepositoryParses() {
        return repositoryParses == null ? Collections.emptyList() : repositoryParses;
    }

    public void setRepositoryPlaybackContext(List<String> flags, List<Parse> parses) {
        this.repositoryPlaybackContextBound = true;
        this.repositoryFlags = immutable(flags);
        this.repositoryParses = immutable(parses);
    }

    private static <T> List<T> immutable(List<T> items) {
        if (items == null || items.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(items));
    }

    private static List<Parse> parseRepositoryParses(String json) {
        if (TextUtils.isEmpty(json)) return Collections.emptyList();
        try {
            Parse[] parses = App.gson().fromJson(json, Parse[].class);
            return parses == null ? Collections.emptyList() : immutable(Arrays.asList(parses));
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    public boolean isRepositoryScoped() {
        return repositoryId != 0 || !TextUtils.isEmpty(originKey);
    }

    public String getName() {
        return TextUtils.isEmpty(name) ? "" : name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getApi() {
        return TextUtils.isEmpty(api) ? "" : api;
    }

    public void setApi(String api) {
        this.api = api;
    }

    public String getExt() {
        return TextUtils.isEmpty(ext) ? "" : ext;
    }

    public void setExt(String ext) {
        this.ext = ext.trim();
    }

    public String getJar() {
        return TextUtils.isEmpty(jar) ? "" : jar;
    }

    public void setJar(String jar) {
        this.jar = jar;
    }

    public String getClick() {
        return TextUtils.isEmpty(click) ? "" : click;
    }

    public String getPlayUrl() {
        return TextUtils.isEmpty(playUrl) ? "" : playUrl;
    }

    public Integer getType() {
        return type == null ? 0 : type;
    }

    public Integer getHide() {
        return hide == null ? 0 : hide;
    }

    public Integer getIndexs() {
        return indexs == null ? 0 : indexs;
    }

    public long getTimeout() {
        return timeout == null ? Constant.TIMEOUT_PLAY : TimeUnit.SECONDS.toMillis(Math.max(timeout, 1));
    }

    public Integer getSearchable() {
        return searchable == null ? 1 : searchable;
    }

    public Integer getChangeable() {
        return changeable == null ? 1 : changeable;
    }

    public Integer getQuickSearch() {
        return quickSearch == null ? 1 : quickSearch;
    }

    public List<String> getCategories() {
        return categories == null ? Collections.emptyList() : categories;
    }

    public void setCategories(List<String> categories) {
        this.categories = categories;
    }

    public Map<String, String> getHeader() {
        return header == null ? new HashMap<>() : header;
    }

    public Style getStyle() {
        return style;
    }

    public Style getStyle(Style style) {
        return getStyle() != null ? getStyle() : style != null ? style : Style.rect();
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public void setSelected(Site item) {
        this.selected = item.equals(this);
    }

    public boolean isHide() {
        return getHide() == 1;
    }

    public boolean isIndex() {
        return getIndexs() == 1;
    }

    public boolean isSearchable() {
        return getSearchable() == 1;
    }

    public void setSearchable(Integer searchable) {
        this.searchable = searchable;
    }

    public Site setSearchable(boolean searchable) {
        if (getSearchable() != 0) setSearchable(searchable ? 1 : 2);
        return this;
    }

    public boolean isChangeable() {
        return getChangeable() == 1;
    }

    public void setChangeable(Integer changeable) {
        this.changeable = changeable;
    }

    public Site setChangeable(boolean changeable) {
        if (getChangeable() != 0) setChangeable(changeable ? 1 : 2);
        return this;
    }

    public boolean isQuickSearch() {
        return getQuickSearch() == 1;
    }

    public boolean isEmpty() {
        return getKey().isEmpty() && getName().isEmpty();
    }

    public Site fetchExt() {
        if (!getExt().startsWith("http")) return this;
        String extend = OkHttp.string(getExt());
        if (!extend.isEmpty()) setExt(extend);
        return this;
    }

    public Site trans() {
        if (Trans.pass()) return this;
        this.name = Trans.s2t(name);
        setCategories(getCategories().stream().map(Trans::s2t).toList());
        return this;
    }

    public Site sync(Site item) {
        if (item == null) return this;
        if (getChangeable() != 0) setChangeable(Math.max(1, item.getChangeable()));
        if (getSearchable() != 0) setSearchable(Math.max(1, item.getSearchable()));
        return this;
    }

    public Site recent() {
        BaseLoader.get().setRecent(getKey(), getApi(), getExt(), getJar());
        return this;
    }

    public Spider spider() {
        return BaseLoader.get().getSpider(getKey(), getOriginKey(), getApi(), getExt(), getJar());
    }

    public void save() {
        AppDatabase.get().getSiteDao().insertOrUpdate(this);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Site it)) return false;
        return Objects.equals(getKey(), it.getKey());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getKey());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(this.key);
        dest.writeString(this.name);
        dest.writeString(this.api);
        dest.writeString(this.ext);
        dest.writeString(this.jar);
        dest.writeString(this.click);
        dest.writeString(this.playUrl);
        dest.writeValue(this.type);
        dest.writeValue(this.hide);
        dest.writeValue(this.indexs);
        dest.writeValue(this.timeout);
        dest.writeValue(this.searchable);
        dest.writeValue(this.changeable);
        dest.writeValue(this.quickSearch);
        dest.writeStringList(this.categories);
        dest.writeMap(this.header);
        dest.writeParcelable(this.style, flags);
        dest.writeByte(this.selected ? (byte) 1 : (byte) 0);
        dest.writeString(this.originKey);
        dest.writeLong(this.repositoryId);
        dest.writeString(this.repositoryName);
        dest.writeString(this.configName);
        dest.writeString(this.configUrl);
        dest.writeInt(this.repositoryPriority);
        dest.writeByte(this.repositoryPlaybackContextBound ? (byte) 1 : (byte) 0);
        dest.writeStringList(getRepositoryFlags());
        dest.writeString(App.gson().toJson(getRepositoryParses()));
    }

    public static final Creator<Site> CREATOR = new Creator<>() {
        @Override
        public Site createFromParcel(Parcel source) {
            return new Site(source);
        }

        @Override
        public Site[] newArray(int size) {
            return new Site[size];
        }
    };
}
