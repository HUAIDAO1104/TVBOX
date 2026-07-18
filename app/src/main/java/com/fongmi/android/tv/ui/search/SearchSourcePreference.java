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
    /** Legacy marker used by older builds. It is migrated to concrete source names at runtime. */
    public static final String ALL_SOURCES = "all-sources";
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
        if (result.isEmpty()) result.add(ALL_SOURCES);
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
        if (clean.isEmpty() || clean.contains(ALL_SOURCES)) {
            clean.clear();
            clean.add(ALL_SOURCES);
        }
        return String.join("\n", clean);
    }

    public static boolean isEnabled(Site site, Set<String> selected) {
        if (site == null) return false;
        return isEnabled(site.getName(), site.getConfigName(), site.getRepositoryName(), site.getKey(), selected);
    }

    static boolean isEnabled(String name, String configName, String repositoryName, String key,
                             Set<String> selected) {
        Set<String> safe = selected == null ? Set.of() : selected;
        if (safe.isEmpty() || safe.contains(ALL_SOURCES) || safe.contains(ALL_OTHER_SOURCES)) return false;
        String haystack = normalize(String.join(" ",
                safeValue(name), safeValue(configName), safeValue(repositoryName), safeValue(key)));
        for (String source : safe) if (haystack.contains(normalize(source))) return true;
        return false;
    }

    public static List<String> choices(List<Site> sites) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (sites != null) {
            for (Site site : sites) {
                if (site == null || !site.isSearchable()) continue;
                String label = SearchDisplayName.clean(site == null ? "" : site.getName());
                if (!label.isEmpty()) result.add(label);
            }
        }
        return new ArrayList<>(result);
    }

    /**
     * Resolves a warehouse-scoped preference to concrete source labels.
     *
     * <p>The visible "全部" lane is intentionally not represented here: it is the aggregate of
     * these checked sources. Older builds stored {@link #ALL_SOURCES}; migrating that value to the
     * preferred sources prevents a newly opened warehouse from accidentally launching hundreds of
     * third-party Spider searches.</p>
     */
    public static Set<String> resolveSelection(String persisted, List<Site> sites, Site home) {
        List<Site> searchable = new ArrayList<>();
        if (sites != null) for (Site site : sites) if (site != null && site.isSearchable()) searchable.add(site);
        Set<String> parsed = parse(persisted);
        LinkedHashSet<String> explicit = new LinkedHashSet<>(parsed);
        explicit.remove(ALL_SOURCES);
        explicit.remove(ALL_OTHER_SOURCES);
        LinkedHashSet<String> resolved = new LinkedHashSet<>();

        if (!explicit.isEmpty()) {
            for (Site site : searchable) {
                String label = SearchDisplayName.clean(site.getName());
                if (!label.isEmpty() && isEnabled(site, explicit)) resolved.add(label);
            }
        }

        if (parsed.contains(ALL_SOURCES) || resolved.isEmpty()) {
            resolved.clear();
            for (String preferred : DEFAULT_SOURCES) {
                for (Site site : searchable) {
                    String label = SearchDisplayName.clean(site.getName());
                    if (!label.isEmpty() && isEnabled(site, Set.of(preferred))) resolved.add(label);
                }
            }
            if (resolved.isEmpty() && home != null && home.isSearchable()) {
                String label = SearchDisplayName.clean(home.getName());
                if (!label.isEmpty()) resolved.add(label);
            }
            if (resolved.isEmpty() && !searchable.isEmpty()) {
                String label = SearchDisplayName.clean(searchable.get(0).getName());
                if (!label.isEmpty()) resolved.add(label);
            }
        }
        return resolved;
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
