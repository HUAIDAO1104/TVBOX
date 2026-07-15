package com.fongmi.android.tv.ui.search;

import com.fongmi.android.tv.bean.Site;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** User-facing search source families. Repository/site identity remains untouched. */
public final class SearchSourcePreference {

    public static final List<String> DEFAULT_SOURCES = List.of("玩偶", "至臻", "虎斑", "木偶", "热播");
    public static final String ALL_OTHER_SOURCES = "all-other-sources";

    private SearchSourcePreference() {
    }

    public static Set<String> parse(String value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (value != null) {
            for (String item : value.split("\\n")) {
                String clean = SearchDisplayName.clean(item);
                if (!clean.isEmpty()) result.add(clean);
            }
        }
        if (result.isEmpty()) result.addAll(DEFAULT_SOURCES);
        return result;
    }

    public static String serialize(Set<String> selected) {
        LinkedHashSet<String> clean = new LinkedHashSet<>();
        if (selected != null) {
            for (String item : selected) {
                String value = SearchDisplayName.clean(item);
                if (!value.isEmpty()) clean.add(value);
            }
        }
        if (clean.isEmpty()) clean.addAll(DEFAULT_SOURCES);
        return String.join("\n", clean);
    }

    public static boolean isEnabled(Site site, Set<String> selected) {
        if (site == null) return false;
        return isEnabled(site.getName(), site.getConfigName(), site.getRepositoryName(), site.getKey(), selected);
    }

    static boolean isEnabled(String name, String configName, String repositoryName, String key,
                             Set<String> selected) {
        Set<String> safe = selected == null || selected.isEmpty()
                ? new LinkedHashSet<>(DEFAULT_SOURCES)
                : selected;
        if (safe.contains(ALL_OTHER_SOURCES)) return true;
        String haystack = normalize(String.join(" ",
                safeValue(name), safeValue(configName), safeValue(repositoryName), safeValue(key)));
        for (String source : safe) if (haystack.contains(normalize(source))) return true;
        return false;
    }

    public static List<String> choices(List<Site> sites) {
        LinkedHashSet<String> result = new LinkedHashSet<>(DEFAULT_SOURCES);
        // Keep the aggregate opt-in beside the five defaults so it is discoverable
        // before the potentially long list of repository-specific sites.
        result.add(ALL_OTHER_SOURCES);
        if (sites != null) {
            for (Site site : sites) {
                String label = SearchDisplayName.clean(site == null ? "" : site.getName());
                if (!label.isEmpty()) result.add(label);
            }
        }
        return new ArrayList<>(result);
    }

    public static boolean isFourKDefault(String sourceName) {
        String value = normalize(sourceName);
        return DEFAULT_SOURCES.subList(0, 4).stream().map(SearchSourcePreference::normalize).anyMatch(value::contains);
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}　]+", "");
    }

    private static String safeValue(String value) {
        return value == null ? "" : value;
    }
}
