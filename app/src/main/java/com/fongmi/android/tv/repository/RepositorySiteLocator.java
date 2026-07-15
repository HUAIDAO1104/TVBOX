package com.fongmi.android.tv.repository;

import com.fongmi.android.tv.bean.Repository;
import com.fongmi.android.tv.bean.RepositoryItem;

import java.util.List;

/** Finds the repository item encoded in a scoped key without loading every remote config. */
final class RepositorySiteLocator {

    private RepositorySiteLocator() {
    }

    static Match find(String scopedKey, List<Repository> repositories, ItemSource itemSource) {
        String originKey = RepositorySiteKey.originKey(scopedKey);
        if (originKey.isEmpty() || repositories == null || itemSource == null) return null;
        for (Repository repository : repositories) {
            List<RepositoryItem> items = itemSource.get(repository.getId());
            if (items == null) continue;
            for (RepositoryItem item : items) {
                // Disabled repositories and items are intentionally retained for history/keep
                // restoration. Only non-VOD entries and empty locations are ineligible.
                if (item.getType() != 0 || item.getUrl().isBlank()) continue;
                if (RepositorySiteKey.matches(scopedKey, repository, item, originKey)) {
                    return new Match(repository, item, originKey);
                }
            }
        }
        return null;
    }

    interface ItemSource {
        List<RepositoryItem> get(long repositoryId);
    }

    record Match(Repository repository, RepositoryItem item, String originKey) {
    }
}
