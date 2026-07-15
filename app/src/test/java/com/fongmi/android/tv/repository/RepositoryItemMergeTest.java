package com.fongmi.android.tv.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fongmi.android.tv.bean.RepositoryItem;

import org.junit.Test;

import java.util.List;

public class RepositoryItemMergeTest {

    @Test
    public void preservesIdentityAndUserStateWhenStableItemMoves() {
        RepositoryItem old = item(41, "stable-a", "旧地址", "https://old/config.json", 0);
        old.setEnabled(false);
        old.setCheckStatus("AVAILABLE");
        old.setLastCheckedAt(1234);
        old.setErrorMessage("cached");
        old.setCreatedAt(99);
        RepositoryItem incoming = item(0, "stable-a", "新地址", "https://new/config.json", 0);

        List<Long> stale = RepositoryItemMerge.reconcile(List.of(old), List.of(incoming), "仓库");

        assertTrue(stale.isEmpty());
        assertEquals(41, incoming.getId());
        assertFalse(incoming.isEnabled());
        assertEquals("AVAILABLE", incoming.getCheckStatus());
        assertEquals(1234, incoming.getLastCheckedAt());
        assertEquals("cached", incoming.getErrorMessage());
        assertEquals(99, incoming.getCreatedAt());
    }

    @Test
    public void reportsOnlyRemovedRowsAndLeavesNewRowsUnassigned() {
        RepositoryItem retained = item(7, "a", "A", "https://host/a.json", 0);
        RepositoryItem removed = item(8, "b", "B", "https://host/b.json", 0);
        RepositoryItem same = item(0, "changed-id", "A2", "https://host/a.json", 0);
        RepositoryItem added = item(0, "c", "C", "https://host/c.json", 0);

        List<Long> stale = RepositoryItemMerge.reconcile(List.of(retained, removed), List.of(same, added), "仓库");

        assertEquals(List.of(8L), stale);
        assertEquals(7, same.getId());
        assertEquals(0, added.getId());
    }

    @Test
    public void keepsCustomNameForDirectRepositoryEntry() {
        RepositoryItem old = item(3, "direct", "我的默认源", "https://host/repo.json", 0);
        RepositoryItem incoming = item(0, "direct", "默认仓库", "https://host/repo.json", 0);

        RepositoryItemMerge.reconcile(List.of(old), List.of(incoming), "默认仓库");

        assertEquals("我的默认源", incoming.getName());
    }

    private static RepositoryItem item(long id, String itemId, String name, String url, int type) {
        RepositoryItem item = new RepositoryItem();
        item.setId(id);
        item.setRepositoryId(1);
        item.setItemId(itemId);
        item.setName(name);
        item.setUrl(url);
        item.setType(type);
        item.setEnabled(true);
        item.setCreatedAt(1);
        item.setUpdatedAt(2);
        return item;
    }
}
