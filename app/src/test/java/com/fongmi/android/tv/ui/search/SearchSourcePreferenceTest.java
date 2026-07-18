package com.fongmi.android.tv.ui.search;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.fongmi.android.tv.bean.Site;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class SearchSourcePreferenceTest {

    @Test
    public void legacyAllMigratesToConcretePreferredSources() {
        List<Site> sites = List.of(namedSite("💗玩偶 | 4K💗"), namedSite("热播"), namedSite("文采影视"));

        Set<String> defaults = SearchSourcePreference.resolveSelection("", sites, null);

        assertEquals(Set.of("玩偶", "热播"), defaults);
        assertTrue(enabled("玩偶 | 4K", defaults));
        assertFalse(enabled("文采影视", defaults));
    }

    @Test
    public void selectedExtraSourceIsPersistedWithoutDecoration() {
        Set<String> selected = new LinkedHashSet<>();
        selected.add("💥光影 | 4K💥");
        Set<String> restored = SearchSourcePreference.parse(SearchSourcePreference.serialize(selected));
        assertEquals(Set.of("光影"), restored);
        assertTrue(enabled("光影影视", restored));
        assertFalse(enabled("玩偶", restored));
    }

    @Test
    public void aggregateAllMarkerNeverActsAsBackendWildcard() {
        assertFalse(enabled("任意来源", Set.of(SearchSourcePreference.ALL_SOURCES)));
    }

    @Test
    public void fallsBackToHomeWhenWarehouseHasNoPreferredSource() {
        Site home = namedSite("当前首页源");
        Set<String> resolved = SearchSourcePreference.resolveSelection("", List.of(
                namedSite("仓库普通源"), home), home);
        assertEquals(Set.of("当前首页源"), resolved);
    }

    @Test
    public void choicesOnlyContainSitesFromTheSuppliedActiveWarehouse() {
        Site currentA = namedSite("玩偶 | 4K");
        Site currentB = namedSite("当前仓独有源");

        List<String> choices = SearchSourcePreference.choices(List.of(currentA, currentB));

        assertEquals(List.of("玩偶", "当前仓独有源"), choices);
        assertFalse(choices.contains("至臻"));
        assertFalse(choices.contains(SearchSourcePreference.ALL_SOURCES));
        assertFalse(choices.contains(SearchSourcePreference.ALL_OTHER_SOURCES));
    }

    @Test
    public void serializingAllSourcesDropsStaleWarehouseFamilies() {
        Set<String> mixed = new LinkedHashSet<>(List.of(
                SearchSourcePreference.ALL_SOURCES, "旧仓库来源"));

        assertEquals(Set.of(SearchSourcePreference.ALL_SOURCES),
                SearchSourcePreference.parse(SearchSourcePreference.serialize(mixed)));
    }

    private static boolean enabled(String name, Set<String> selected) {
        return SearchSourcePreference.isEnabled(name, "", "", name, selected);
    }

    private static Site namedSite(String name) {
        return new Site() {
            @Override
            public String getName() {
                return name;
            }

            @Override public String getConfigName() { return ""; }
            @Override public String getRepositoryName() { return ""; }
            @Override public String getKey() { return name; }

            @Override
            public boolean isSearchable() {
                return true;
            }
        };
    }
}
