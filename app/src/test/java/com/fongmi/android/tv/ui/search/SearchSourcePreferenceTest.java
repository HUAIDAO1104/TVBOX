package com.fongmi.android.tv.ui.search;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class SearchSourcePreferenceTest {

    @Test
    public void defaultsContainOnlyFiveRequestedFamilies() {
        Set<String> defaults = SearchSourcePreference.parse("");
        assertEquals(new LinkedHashSet<>(SearchSourcePreference.DEFAULT_SOURCES), defaults);
        assertTrue(enabled("💗玩偶 | 4K💗", defaults));
        assertTrue(enabled("热播", defaults));
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
    public void otherEnabledSourcesOptionIncludesDynamicallyDiscoveredRepositories() {
        Set<String> selected = new LinkedHashSet<>(SearchSourcePreference.DEFAULT_SOURCES);
        selected.add(SearchSourcePreference.ALL_OTHER_SOURCES);
        assertTrue(enabled("稍后从其他仓库发现的站点", selected));
    }

    @Test
    public void otherSourcesChoiceFollowsTheFiveDefaults() {
        List<String> choices = SearchSourcePreference.choices(List.of());
        assertEquals(SearchSourcePreference.ALL_OTHER_SOURCES, choices.get(5));
    }

    private static boolean enabled(String name, Set<String> selected) {
        return SearchSourcePreference.isEnabled(name, "", "", name, selected);
    }
}
