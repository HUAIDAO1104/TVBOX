package com.fongmi.android.tv.model;

import static org.junit.Assert.assertEquals;

import com.fongmi.android.tv.bean.Site;

import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class SiteViewModelSearchScopeTest {

    @Test
    public void keepsDistinctRoutingKeysEvenWhenBackendDeclarationsMatch() {
        Site first = site("warehouse@source-a", "csp_Test", "plugin.jar", "config-a", true);
        Site duplicate = site("warehouse@source-b", "csp_Test", "plugin.jar", "config-a", true);
        Site distinctExtension = site("warehouse@source-c", "csp_Test", "plugin.jar", "config-b", true);
        Site disabled = site("warehouse@disabled", "csp_Disabled", "plugin.jar", "", false);

        Map<String, Site> selected = SiteViewModel.uniqueSearchableSites(
                List.of(first, duplicate, distinctExtension, disabled), value -> true);

        assertEquals(3, selected.size());
        assertEquals(List.of(first, duplicate, distinctExtension), List.copyOf(selected.values()));
    }

    @Test
    public void checkedSourcePredicateIsAppliedBeforeSearchTasksAreCreated() {
        Site enabled = site("warehouse@enabled", "csp_Enabled", "plugin.jar", "a", true);
        Site unchecked = site("warehouse@unchecked", "csp_Unchecked", "plugin.jar", "b", true);

        Map<String, Site> selected = SiteViewModel.uniqueSearchableSites(
                List.of(enabled, unchecked), site -> site.getKey().endsWith("enabled"));

        assertEquals(List.of(enabled), List.copyOf(selected.values()));
    }

    private static Site site(String key, String api, String jar, String ext, boolean searchable) {
        return new Site() {
            @Override public String getKey() { return key; }
            @Override public String getApi() { return api; }
            @Override public String getJar() { return jar; }
            @Override public String getExt() { return ext; }
            @Override public Integer getType() { return 3; }
            @Override public Map<String, String> getHeader() { return Collections.emptyMap(); }
            @Override public boolean isSearchable() { return searchable; }
        };
    }
}
