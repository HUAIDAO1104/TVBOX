package com.fongmi.android.tv.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.fongmi.android.tv.bean.Repository;
import com.fongmi.android.tv.bean.RepositoryItem;

import org.junit.Test;

public class RepositorySiteParserTest {

    @Test
    public void configUrlPreventsCopiedPublisherIdsFromColliding() {
        Repository repository = repository();
        RepositoryItem first = item("copied-id", "https://one.example/config.json");
        RepositoryItem second = item("copied-id", "https://two.example/config.json");

        assertNotEquals(RepositorySiteParser.scopeKey(repository, first, "same-site"),
                RepositorySiteParser.scopeKey(repository, second, "same-site"));
    }

    @Test
    public void scopeIsDeterministicForTheSameRepositoryConfigAndSite() {
        Repository repository = repository();
        RepositoryItem item = item("stable", "https://one.example/config.json");

        assertEquals(RepositorySiteParser.scopeKey(repository, item, "site"),
                RepositorySiteParser.scopeKey(repository, item, "site"));
    }

    @Test
    public void configTypeParticipatesInScope() {
        Repository repository = repository();
        RepositoryItem vod = item("stable", "https://one.example/config.json");
        RepositoryItem live = item("stable", "https://one.example/config.json");
        live.setType(1);

        assertNotEquals(RepositorySiteParser.scopeKey(repository, vod, "site"),
                RepositorySiteParser.scopeKey(repository, live, "site"));
    }

    @Test
    public void originKeyMayContainAtCharacters() {
        Repository repository = repository();
        RepositoryItem item = item("stable", "https://one.example/config.json");
        String scoped = RepositorySiteParser.scopeKey(repository, item, "site@variant");

        assertEquals("site@variant", RepositorySiteKey.originKey(scoped));
    }

    @Test
    public void scopeFingerprintHasStableGoldenValue() {
        Repository repository = repository();
        RepositoryItem item = item("stable", "https://one.example/config.json");

        assertEquals("repo@fb75c1d040e4d537c378f4ce578d3125@site",
                RepositorySiteParser.scopeKey(repository, item, "site"));
    }

    private static Repository repository() {
        Repository repository = new Repository();
        repository.setId(9);
        repository.setStableId("repo-stable");
        return repository;
    }

    private static RepositoryItem item(String id, String url) {
        RepositoryItem item = new RepositoryItem();
        item.setItemId(id);
        item.setUrl(url);
        return item;
    }
}
