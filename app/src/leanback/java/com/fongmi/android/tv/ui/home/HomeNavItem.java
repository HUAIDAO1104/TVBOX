package com.fongmi.android.tv.ui.home;

import com.fongmi.android.tv.bean.Class;

import java.util.Collections;
import java.util.List;

public record HomeNavItem(String id, String title, Class type, List<Class> overflow) {

    public static final String MORE_ID = "more";

    public HomeNavItem {
        overflow = overflow == null ? Collections.emptyList() : overflow;
    }

    public static HomeNavItem home(String title) {
        return new HomeNavItem(HomeState.HOME_ID, title, null, null);
    }

    public static HomeNavItem category(Class type) {
        return new HomeNavItem(type.getTypeId(), type.getTypeName(), type, null);
    }

    public static HomeNavItem more(String title, List<Class> overflow) {
        return new HomeNavItem(MORE_ID, title, null, overflow);
    }

    public boolean isHome() {
        return HomeState.HOME_ID.equals(id);
    }

    public boolean isMore() {
        return MORE_ID.equals(id);
    }

    public long stableId() {
        return id.hashCode();
    }
}
