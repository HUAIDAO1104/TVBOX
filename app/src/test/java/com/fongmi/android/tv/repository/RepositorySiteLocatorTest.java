package com.fongmi.android.tv.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.fongmi.android.tv.bean.Repository;
import com.fongmi.android.tv.bean.RepositoryItem;

import org.junit.Test;

import java.util.List;

public class RepositorySiteLocatorTest {

    @Test
    public void disabledRepositoryAndItemRemainResolvableForHistory() {
        Repository repository = repository(false);
        RepositoryItem item = item(false, 0);
        String key = RepositorySiteKey.scope(repository, item, "origin@site");

        RepositorySiteLocator.Match match = RepositorySiteLocator.find(
                key, List.of(repository), ignored -> List.of(item));

        assertNotNull(match);
        assertSame(repository, match.repository());
        assertSame(item, match.item());
        assertEquals("origin@site", match.originKey());
    }

    @Test
    public void legacyScopedKeyCanStillLocateItsVodConfig() {
        Repository repository = repository(false);
        RepositoryItem item = item(false, 0);
        String key = RepositorySiteKey.legacyScope(repository, item, "legacy-site");

        RepositorySiteLocator.Match match = RepositorySiteLocator.find(
                key, List.of(repository), ignored -> List.of(item));

        assertNotNull(match);
        assertEquals("legacy-site", match.originKey());
    }

    @Test
    public void nonVodRepositoryItemsAreNotTreatedAsSiteConfigs() {
        Repository repository = repository(true);
        RepositoryItem live = item(true, 1);
        String key = RepositorySiteKey.scope(repository, live, "live-site");

        assertNull(RepositorySiteLocator.find(
                key, List.of(repository), ignored -> List.of(live)));
    }

    private static Repository repository(boolean enabled) {
        Repository repository = new Repository();
        repository.setId(7);
        repository.setStableId("stable-repository");
        repository.setUrl("https://repo.example/index.json");
        repository.setEnabled(enabled);
        return repository;
    }

    private static RepositoryItem item(boolean enabled, int type) {
        RepositoryItem item = new RepositoryItem();
        item.setRepositoryId(7);
        item.setItemId("config");
        item.setName("Config");
        item.setUrl("https://repo.example/config.json");
        item.setType(type);
        item.setEnabled(enabled);
        return item;
    }
}
