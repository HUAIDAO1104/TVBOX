package com.fongmi.android.tv.ui.home;

import android.text.TextUtils;

import com.fongmi.android.tv.bean.Class;
import com.fongmi.android.tv.security.PromotionFilter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class HomeNavigationController {

    private HomeNavigationController() {
    }

    public static List<HomeNavItem> build(String homeTitle, String moreTitle, List<Class> source, int maxPrimary) {
        List<Class> categories = sanitize(source);
        List<HomeNavItem> result = new ArrayList<>();
        result.add(HomeNavItem.home(homeTitle));
        if (categories.size() <= maxPrimary) {
            categories.forEach(item -> result.add(HomeNavItem.category(item)));
        } else {
            int directCount = Math.max(1, maxPrimary - 1);
            categories.subList(0, directCount).forEach(item -> result.add(HomeNavItem.category(item)));
            result.add(HomeNavItem.more(moreTitle, new ArrayList<>(categories.subList(directCount, categories.size()))));
        }
        return result;
    }

    public static List<Class> sanitize(List<Class> source) {
        List<Class> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        if (source == null) return result;
        for (Class item : source) {
            if (item == null) continue;
            String id = item.getTypeId().trim();
            String name = item.getTypeName().trim();
            String normalized = normalize(name);
            if (TextUtils.isEmpty(id) || TextUtils.isEmpty(name)) continue;
            if (PromotionFilter.shouldSuppress(name)) continue;
            if (!ids.add(id) || !names.add(normalized)) continue;
            result.add(item);
        }
        return result;
    }

    private static String normalize(String text) {
        return text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
