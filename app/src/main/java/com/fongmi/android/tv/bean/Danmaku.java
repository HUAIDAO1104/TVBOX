package com.fongmi.android.tv.bean;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.UrlUtil;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class Danmaku {

    private static final String BUILT_IN_HTTP_PREFIX = "http://danmu.xyy.red/";
    private static final String BUILT_IN_HTTPS_PREFIX = "https://danmu.xyy.red/";

    @SerializedName("name")
    private String name;
    @SerializedName("url")
    private String url;

    private boolean selected;

    public static List<Danmaku> arrayFrom(String str) {
        Type listType = TypeToken.getParameterized(List.class, Danmaku.class).getType();
        List<Danmaku> items = App.gson().fromJson(str, listType);
        if (items == null) return Collections.emptyList();
        List<Danmaku> result = new ArrayList<>();
        for (Danmaku item : items) {
            if (item == null || item.isBlockedSource()) continue;
            item.setUrl(normalizeSourceUrl(item.getUrl()));
            result.add(item);
        }
        return result;
    }

    public static Danmaku from(String path) {
        Danmaku danmaku = new Danmaku();
        danmaku.setName(path);
        danmaku.setUrl(normalizeSourceUrl(path));
        return danmaku;
    }

    /** Avoids the built-in provider's slow HTTP-to-HTTPS redirect on older TV firmware. */
    public static String normalizeSourceUrl(String value) {
        String url = Objects.toString(value, "").trim();
        return url.startsWith(BUILT_IN_HTTP_PREFIX)
                ? BUILT_IN_HTTPS_PREFIX + url.substring(BUILT_IN_HTTP_PREFIX.length())
                : url;
    }

    public static Danmaku empty() {
        return new Danmaku();
    }

    /**
     * Some repository spiders inject a non-actionable source named "小白弹幕" into the media
     * source list. It is rendered like a selectable action even though it cannot be loaded.
     * Reject it at the shared media boundary so it cannot reappear in TV/mobile controls or
     * source dialogs regardless of which repository supplied it.
     */
    public boolean isBlockedSource() {
        return isBlockedSourceLabel(Objects.toString(name, "") + " " + Objects.toString(url, ""));
    }

    public static boolean isBlockedSourceLabel(CharSequence label) {
        String value = Normalizer.normalize(Objects.toString(label, ""), Normalizer.Form.NFKC)
                .replaceAll("<[^>]*>", "")
                .replaceAll("[\\p{Cf}\\u200B-\\u200D\\u2060\\uFEFF]", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{P}\\p{S}]+", "");
        // Match the two semantic tokens rather than one contiguous literal.  Repositories have
        // emitted this pseudo line with HTML, decorations and inserted words between the tokens.
        return value.contains("小白") && (value.contains("弹幕") || value.contains("彈幕"));
    }

    public String getName() {
        return name == null || name.isEmpty() ? getUrl() : name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url == null || url.isEmpty() ? "" : url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public boolean isEmpty() {
        return getUrl().isEmpty();
    }

    public Uri getUri() {
        return isEmpty() ? null : UrlUtil.uri(getUrl());
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Danmaku it)) return false;
        return getUrl().equals(it.getUrl());
    }

    @NonNull
    @Override
    public String toString() {
        return App.gson().toJson(this);
    }
}
