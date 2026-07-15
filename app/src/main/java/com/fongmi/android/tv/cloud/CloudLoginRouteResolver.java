package com.fongmi.android.tv.cloud;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Locates repository-provided cloud login actions without depending on a repository name. */
public final class CloudLoginRouteResolver {

    private static final int MIN_SITE_SCORE = 6;
    private static final int MIN_ACTION_SCORE = 10;

    private CloudLoginRouteResolver() {
    }

    public record SiteDescriptor(String key, String name, String api) {
    }

    public record CategoryDescriptor(String id, String name) {
    }

    public record ActionDescriptor(String id, String name, String remarks) {
    }

    public static List<SiteDescriptor> candidates(List<SiteDescriptor> sites) {
        if (sites == null || sites.isEmpty()) return Collections.emptyList();
        List<SiteDescriptor> result = new ArrayList<>();
        for (SiteDescriptor site : sites) if (siteScore(site) >= MIN_SITE_SCORE) result.add(site);
        result.sort(Comparator.comparingInt(CloudLoginRouteResolver::siteScore).reversed());
        return Collections.unmodifiableList(result);
    }

    public static int siteScore(SiteDescriptor site) {
        if (site == null || clean(site.key()).isEmpty()) return 0;
        String key = lower(site.key());
        String name = lower(site.name());
        String api = lower(site.api());
        int score = 0;
        if (key.contains("wexconfig")) score += 18;
        if (api.contains("wexconfig")) score += 16;
        if (key.contains("config")) score += 7;
        if (api.contains("config")) score += 6;
        if (name.contains("配置")) score += 8;
        if (name.contains("设置")) score += 4;
        if (name.contains("中心")) score += 2;
        if (key.contains("setting") || api.contains("setting")) score += 4;
        return score;
    }

    public static List<CloudLoginRoute> routes(String siteKey, List<CategoryDescriptor> categories) {
        if (clean(siteKey).isEmpty() || categories == null || categories.isEmpty()) return Collections.emptyList();
        Map<String, CloudLoginRoute> unique = new LinkedHashMap<>();
        for (CategoryDescriptor category : categories) {
            String id = clean(category == null ? null : category.id());
            String name = clean(category == null ? null : category.name());
            if (!isCloudSetupCategory(id, name)) continue;
            String providerId = providerId(id + " " + name);
            String identity = "unknown".equals(providerId) ? lower(id + " " + name) : providerId;
            unique.putIfAbsent(identity, new CloudLoginRoute(siteKey, id, name, providerId));
        }
        return Collections.unmodifiableList(new ArrayList<>(unique.values()));
    }

    public static ActionDescriptor findLoginAction(List<ActionDescriptor> actions) {
        if (actions == null || actions.isEmpty()) return null;
        ActionDescriptor best = null;
        int bestScore = Integer.MIN_VALUE;
        for (ActionDescriptor action : actions) {
            int score = actionScore(action);
            if (score > bestScore) {
                best = action;
                bestScore = score;
            }
        }
        return bestScore >= MIN_ACTION_SCORE ? best : null;
    }

    public static String providerId(String value) {
        String text = lower(value).replace(" ", "");
        if (text.contains("夸克") || text.contains("quark")) return CloudProvider.QUARK;
        if (text.contains("uc网盘") || text.contains("ucpan") || text.startsWith("uc")) return CloudProvider.UC;
        if (text.contains("百度") || text.contains("baidu")) return CloudProvider.BAIDU;
        if (text.contains("天翼") || text.contains("cloud189") || text.contains("pan189")) return CloudProvider.TIANYI;
        if (text.contains("阿里") || text.contains("aliyun") || text.contains("alipan")) return CloudProvider.ALI;
        if (text.contains("迅雷") || text.contains("thunder")) return CloudProvider.THUNDER;
        if (text.contains("115")) return "115";
        if (text.contains("123")) return "123";
        if (text.contains("光鸭") || text.contains("guangya")) return "guangya";
        return "unknown";
    }

    private static boolean isCloudSetupCategory(String id, String name) {
        String text = lower(id + " " + name);
        boolean provider = text.contains("网盘") || text.contains("云盘")
                || !"unknown".equals(providerId(text));
        boolean setup = text.contains("设置") || text.contains("配置") || text.contains("登录")
                || text.contains("登入") || text.contains("账号") || text.contains("账户")
                || text.contains("account") || text.contains("login");
        return provider && setup;
    }

    private static int actionScore(ActionDescriptor action) {
        if (action == null) return Integer.MIN_VALUE;
        String id = lower(action.id());
        String name = lower(action.name());
        String remarks = lower(action.remarks());
        String all = id + " " + name + " " + remarks;
        if (id.isEmpty()) return Integer.MIN_VALUE;
        if (all.contains("clear") || all.contains("清除") || all.contains("注销")) return Integer.MIN_VALUE;
        if (id.contains("webconfig") || all.contains("手动输入") || all.contains("投影仪")) return Integer.MIN_VALUE;
        int score = 0;
        if (id.contains("login")) score += 20;
        if (id.contains("qrcode") || id.contains("qr") || id.contains("scan")) score += 16;
        if (name.contains("账号设置") || name.contains("账户设置")) score += 16;
        if (name.contains("登录") || name.contains("登入")) score += 12;
        if (name.contains("扫码") || name.contains("二维码")) score += 14;
        if (remarks.contains("未扫码") || remarks.contains("扫码")) score += 12;
        if (remarks.contains("未登录") || remarks.contains("登录")) score += 8;
        return score;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String lower(String value) {
        return clean(value).toLowerCase(Locale.ROOT);
    }
}
