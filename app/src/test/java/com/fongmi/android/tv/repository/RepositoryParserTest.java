package com.fongmi.android.tv.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.fongmi.android.tv.bean.Repository;
import com.fongmi.android.tv.bean.RepositoryItem;

import org.junit.Test;

import java.util.List;

public class RepositoryParserTest {

    @Test
    public void parsesStandardRepositoryAndDeduplicates() {
        Repository repository = repository();
        String json = "{\"urls\":[{\"name\":\"A\",\"url\":\"a.json\"},{\"name\":\"A2\",\"url\":\"a.json\"},{\"name\":\"Live\",\"url\":\"https://cdn.example/live.json\",\"type\":1}]}";

        List<RepositoryItem> items = RepositoryParser.parse(repository, json);

        assertEquals(2, items.size());
        assertEquals("https://example.com/repos/a.json", items.get(0).getUrl());
        assertEquals(7, items.get(0).getRepositoryId());
        assertEquals(1, items.get(1).getType());
    }

    @Test
    public void acceptsDynamicSpiderConfigurationAsSingleItem() {
        Repository repository = repository();
        List<RepositoryItem> items = RepositoryParser.parse(repository, "{\"name\":\"Dynamic fixture\",\"spider\":\"./csp_DouDouGuard.jar\",\"sites\":[{\"key\":\"one\"}]}");

        assertEquals(1, items.size());
        assertEquals("Dynamic fixture", items.get(0).getName());
        assertEquals("spider-direct", items.get(0).getItemId());
        assertEquals(repository.getUrl(), items.get(0).getUrl());
    }

    @Test
    public void replacesPromotionalRepositoryLabelsWithoutDroppingTheConfiguration() {
        Repository repository = repository();
        String json = "{\"urls\":[{\"name\":\"关注王二小领取免费接口\",\"url\":\"provider.json\"}]}";

        List<RepositoryItem> items = RepositoryParser.parse(repository, json);

        assertEquals(1, items.size());
        assertEquals("provider.json", items.get(0).getName());
        assertEquals("https://example.com/repos/provider.json", items.get(0).getUrl());
    }

    @Test
    public void preservesPrimitiveObjectOrderingEnabledStateAndCustomId() {
        String json = "{\"urls\":[\"../root.json\",{\"id\":\"disabled-item\",\"url\":\"assets://local.json\",\"name\":\"Local\",\"enabled\":false,\"order\":12}]}";

        List<RepositoryItem> items = RepositoryParser.parse(repository(), json);

        assertEquals(2, items.size());
        assertEquals("https://example.com/root.json", items.get(0).getUrl());
        assertEquals("../root.json", items.get(0).getName());
        assertTrue(items.get(0).isEnabled());
        assertEquals("disabled-item", items.get(1).getItemId());
        assertEquals(12, items.get(1).getSortOrder());
        assertFalse(items.get(1).isEnabled());
    }

    @Test
    public void rejectsMalformedRootsAndTooManyItems() {
        assertThrows(IllegalArgumentException.class, () -> RepositoryParser.parse(repository(), "[]"));
        assertThrows(IllegalArgumentException.class, () -> RepositoryParser.parse(repository(), "{\"urls\":{}}"));

        StringBuilder json = new StringBuilder("{\"urls\":[");
        for (int index = 0; index <= RepositoryParser.MAX_ITEMS; index++) {
            if (index > 0) json.append(',');
            json.append('\"').append("fixture-").append(index).append(".json\"");
        }
        json.append("]}");
        assertThrows(IllegalArgumentException.class, () -> RepositoryParser.parse(repository(), json.toString()));
    }

    @Test
    public void rejectsUnusableRepository() {
        assertThrows(IllegalArgumentException.class, () -> RepositoryParser.parse(repository(), "{\"urls\":[]}"));
    }

    private Repository repository() {
        Repository repository = new Repository();
        repository.setId(7);
        repository.setName("dynamic");
        repository.setUrl("https://example.com/repos/list.json");
        return repository;
    }
}
