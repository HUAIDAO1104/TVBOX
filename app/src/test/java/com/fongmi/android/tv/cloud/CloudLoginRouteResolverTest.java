package com.fongmi.android.tv.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.List;

public class CloudLoginRouteResolverTest {

    @Test
    public void findsConfigurationSpiderWithoutHardcodingRepositoryName() {
        List<CloudLoginRouteResolver.SiteDescriptor> result = CloudLoginRouteResolver.candidates(List.of(
                new CloudLoginRouteResolver.SiteDescriptor("movie", "热门电影", "csp_Movie"),
                new CloudLoginRouteResolver.SiteDescriptor("Wexconfig", "配置中心", "csp_WexConfigGuard"),
                new CloudLoginRouteResolver.SiteDescriptor("settings", "账号设置", "csp_Settings")
        ));

        assertEquals(2, result.size());
        assertEquals("Wexconfig", result.get(0).key());
    }

    @Test
    public void extractsCloudSetupTabsAndKeepsOwningSite() {
        List<CloudLoginRoute> routes = CloudLoginRouteResolver.routes("repo@one@Wexconfig", List.of(
                new CloudLoginRouteResolver.CategoryDescriptor("home", "首页"),
                new CloudLoginRouteResolver.CategoryDescriptor("1", "百度网盘设置"),
                new CloudLoginRouteResolver.CategoryDescriptor("2", "UC网盘设置"),
                new CloudLoginRouteResolver.CategoryDescriptor("3", "热门电影")
        ));

        assertEquals(2, routes.size());
        assertEquals("repo@one@Wexconfig", routes.get(0).siteKey());
        assertEquals(CloudProvider.BAIDU, routes.get(0).providerId());
        assertEquals(CloudProvider.UC, routes.get(1).providerId());
    }

    @Test
    public void selectsRealLoginActionInsteadOfManualOrClearCards() {
        CloudLoginRouteResolver.ActionDescriptor action = CloudLoginRouteResolver.findLoginAction(List.of(
                new CloudLoginRouteResolver.ActionDescriptor("webconfig", "同一网络投影仪", "手动输入 http://example"),
                new CloudLoginRouteResolver.ActionDescriptor("baidupanlogin", "百度网盘账号设置", "未扫码"),
                new CloudLoginRouteResolver.ActionDescriptor("baidupanclear", "百度网盘 cookie 清除", "点击清除 cookie")
        ));

        assertEquals("baidupanlogin", action.id());
        assertNull(CloudLoginRouteResolver.findLoginAction(List.of(
                new CloudLoginRouteResolver.ActionDescriptor("baidupanclear", "清除", "清除 cookie")
        )));
    }
}
