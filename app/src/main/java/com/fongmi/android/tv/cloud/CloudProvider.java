package com.fongmi.android.tv.cloud;

import java.util.List;

public record CloudProvider(String id, String name, String credentialType, String hint) {

    public static final String QUARK = "quark";
    public static final String UC = "uc";
    public static final String ALI = "ali";
    public static final String BAIDU = "baidu";
    public static final String TIANYI = "tianyi";
    public static final String THUNDER = "thunder";

    public static final List<CloudProvider> ALL = List.of(
            new CloudProvider(QUARK, "夸克网盘", "COOKIE", "Cookie"),
            new CloudProvider(UC, "UC 网盘", "COOKIE", "Cookie"),
            new CloudProvider(ALI, "阿里云盘", "REFRESH_TOKEN", "Refresh Token"),
            new CloudProvider(BAIDU, "百度网盘", "COOKIE", "Cookie"),
            new CloudProvider(TIANYI, "天翼云盘", "COOKIE", "Cookie"),
            new CloudProvider(THUNDER, "迅雷云盘", "TOKEN", "Token / Cookie")
    );

    public static CloudProvider find(String id) {
        for (CloudProvider item : ALL) if (item.id().equals(id)) return item;
        return new CloudProvider(id, id, "CREDENTIAL", "Cookie / Token");
    }
}
