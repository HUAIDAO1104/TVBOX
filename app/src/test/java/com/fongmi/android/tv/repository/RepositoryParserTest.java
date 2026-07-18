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

    @Test
    public void parsesTomorrowMultiRepositoryWithoutFlatteningOrDroppingChildren() {
        String json = "{\"urls\":["
                + "{\"url\":\"https://8815.kstore.vip/tvbox/wmz\",\"name\":\"网络接口\"},"
                + "{\"url\":\"http://肥猫.net/tv\",\"name\":\"肥猫\"},"
                + "{\"url\":\"https://9280.kstore.vip/newwex.json\",\"name\":\"线路 03\"},"
                + "{\"url\":\"https://9877.kstore.space/ONE/one.json\",\"name\":\"潇洒\"},"
                + "{\"url\":\"http://www.小不点.com\",\"name\":\"摸鱼\"},"
                + "{\"url\":\"https://www.饭太硬.cc/tv\",\"name\":\"饭太硬\"},"
                + "{\"url\":\"https://16151.kstore.space\",\"name\":\"东篱\"},"
                + "{\"url\":\"https://example.com/8\",\"name\":\"小米\"},"
                + "{\"url\":\"https://example.com/9\",\"name\":\"巧记\"},"
                + "{\"url\":\"https://example.com/10\",\"name\":\"小虎斑\"},"
                + "{\"url\":\"https://example.com/11\",\"name\":\"欧歌\"},"
                + "{\"url\":\"https://example.com/12\",\"name\":\"南风\"},"
                + "{\"url\":\"https://example.com/13\",\"name\":\"PG\"},"
                + "{\"url\":\"https://example.com/14\",\"name\":\"真心\"},"
                + "{\"url\":\"https://gitlab.com/duomv/dzhipy/-/raw/main/index.json\",\"name\":\"道长\"}]}";
        Repository repository = repository();
        repository.setUrl("https://gh.llkk.cc/https://raw.githubusercontent.com/tushen6/Tomorrow/master/lmw.json");

        List<RepositoryItem> items = RepositoryParser.parse(repository, json);

        assertEquals(15, items.size());
        assertEquals("网络接口", items.get(0).getName());
        assertEquals("http://肥猫.net/tv", items.get(1).getUrl());
        assertEquals("道长", items.get(14).getName());
        assertEquals(7, items.get(14).getRepositoryId());
        assertEquals(14, items.get(14).getSortOrder());
    }

    private Repository repository() {
        Repository repository = new Repository();
        repository.setId(7);
        repository.setName("dynamic");
        repository.setUrl("https://example.com/repos/list.json");
        return repository;
    }
}
