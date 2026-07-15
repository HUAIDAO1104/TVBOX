package com.fongmi.android.tv.repository;

import com.fongmi.android.tv.bean.RepositoryItem;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Preserves row identity and user-owned state while applying a repository snapshot. */
final class RepositoryItemMerge {

    private RepositoryItemMerge() {
    }

    static List<Long> reconcile(List<RepositoryItem> cached, List<RepositoryItem> incoming, String repositoryName) {
        List<RepositoryItem> oldItems = cached == null ? List.of() : cached;
        List<RepositoryItem> newItems = incoming == null ? List.of() : incoming;
        Set<Long> retainedIds = new HashSet<>();
        for (RepositoryItem item : newItems) {
            RepositoryItem old = findExact(oldItems, retainedIds, item);
            if (old == null) old = findByStableItemId(oldItems, retainedIds, item);
            if (old == null) continue;
            retainedIds.add(old.getId());
            item.setId(old.getId());
            item.setEnabled(old.isEnabled());
            item.setCheckStatus(old.getCheckStatus());
            item.setLastCheckedAt(old.getLastCheckedAt());
            item.setErrorMessage(old.getErrorMessage());
            item.setCreatedAt(old.getCreatedAt());
            if ("direct".equals(item.getItemId())
                    && item.getName().equals(repositoryName)
                    && !old.getName().isEmpty()) {
                item.setName(old.getName());
            }
        }
        List<Long> staleIds = new ArrayList<>();
        for (RepositoryItem item : oldItems) {
            if (item.getId() != 0 && !retainedIds.contains(item.getId())) staleIds.add(item.getId());
        }
        return staleIds;
    }

    private static RepositoryItem findExact(List<RepositoryItem> cached, Set<Long> retainedIds, RepositoryItem incoming) {
        for (RepositoryItem old : cached) {
            if (retainedIds.contains(old.getId())) continue;
            if (old.getType() == incoming.getType() && old.getUrl().equals(incoming.getUrl())) return old;
        }
        return null;
    }

    private static RepositoryItem findByStableItemId(List<RepositoryItem> cached, Set<Long> retainedIds, RepositoryItem incoming) {
        if (incoming.getItemId().isEmpty()) return null;
        for (RepositoryItem old : cached) {
            if (retainedIds.contains(old.getId())) continue;
            if (old.getType() == incoming.getType() && old.getItemId().equals(incoming.getItemId())) return old;
        }
        return null;
    }
}
