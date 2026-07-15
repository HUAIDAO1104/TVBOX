package com.fongmi.android.tv.repository;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Repository;
import com.fongmi.android.tv.bean.RepositoryItem;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.utils.Prefers;
import com.github.catvod.utils.SecretRedactor;
import com.github.catvod.utils.Util;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

public class RepositoryManager {

    private static final String BOOTSTRAP_VERSION = "repository_bootstrap_version";

    private final RepositorySyncManager syncManager = new RepositorySyncManager();

    private RepositoryManager() {
    }

    public static RepositoryManager get() {
        return Loader.INSTANCE;
    }

    public void initialize() {
        Task.execute(() -> {
            bootstrap();
            syncEnabled();
        });
    }

    /** Called from the restore worker after the database transaction has committed. */
    public void reinitializeAfterRestore() {
        bootstrap();
        getAll();
        AppDatabase.get().getRepositoryItemDao().findAll();
        Config.vod();
        syncEnabled();
    }

    public List<Repository> getAll() {
        return AppDatabase.get().getRepositoryDao().findAll();
    }

    public List<Repository> getEnabled() {
        return AppDatabase.get().getRepositoryDao().findEnabled();
    }

    public List<RepositoryItem> getItems(long repositoryId) {
        return AppDatabase.get().getRepositoryItemDao().findEnabled(repositoryId);
    }

    public List<RepositoryItem> getAllItems(long repositoryId) {
        return AppDatabase.get().getRepositoryItemDao().findByRepository(repositoryId);
    }

    public int getItemCount(long repositoryId) {
        return AppDatabase.get().getRepositoryItemDao().count(repositoryId);
    }

    public int getMappingCount(long repositoryId) {
        int count = 0;
        for (RepositoryItem item : getAllItems(repositoryId)) {
            if (AppDatabase.get().getConfigDao().find(item.getUrl(), item.getType()) != null) count++;
        }
        return count;
    }

    public Repository save(Repository repository) {
        if (TextUtils.isEmpty(repository.getStableId())) repository.setStableId("user-" + Util.md5(repository.getUrl() + System.nanoTime()));
        repository.touch();
        if (repository.getId() == 0) repository.setId(AppDatabase.get().getRepositoryDao().insert(repository));
        else AppDatabase.get().getRepositoryDao().update(repository);
        return repository;
    }

    public void delete(Repository repository) {
        delete(repository, RepositoryDeleteMode.WITH_ITEMS);
    }

    public boolean delete(Repository repository, RepositoryDeleteMode mode) {
        if (repository == null) return false;
        try {
            List<RepositoryItem> items = getAllItems(repository.getId());
            boolean currentWillBeRemoved = mode == RepositoryDeleteMode.WITH_MAPPINGS && items.stream().anyMatch(item -> item.getType() == 0 && item.getUrl().equals(VodConfig.getUrl()) && AppDatabase.get().getRepositoryItemDao().countShared(repository.getId(), item.getUrl(), item.getType()) == 0);
            AppDatabase.get().runInTransaction(() -> {
                if (mode == RepositoryDeleteMode.REPOSITORY_ONLY) {
                    for (RepositoryItem item : items) Config.find(item.getUrl(), item.getName(), item.getType()).save();
                } else if (mode == RepositoryDeleteMode.WITH_MAPPINGS) {
                    for (RepositoryItem item : items) {
                        if (AppDatabase.get().getRepositoryItemDao().countShared(repository.getId(), item.getUrl(), item.getType()) == 0) {
                            AppDatabase.get().getConfigDao().delete(item.getUrl(), item.getType());
                        }
                    }
                }
                AppDatabase.get().getRepositoryDao().delete(repository.getId());
            });
            if (currentWillBeRemoved) VodConfig.load(Config.vod(), new Callback());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public void setEnabled(Repository repository, boolean enabled) {
        repository.setEnabled(enabled);
        repository.setStatus(enabled ? RepositoryStatus.IDLE : RepositoryStatus.DISABLED);
        repository.touch();
        AppDatabase.get().getRepositoryDao().update(repository);
        if (enabled && repository.isAutoSync()) sync(repository, null);
    }

    public void move(Repository repository, int delta) {
        List<Repository> items = getAll();
        int index = items.indexOf(repository);
        if (index < 0) {
            for (int i = 0; i < items.size(); i++) if (items.get(i).getId() == repository.getId()) index = i;
        }
        int target = Math.clamp(index + delta, 0, items.size() - 1);
        if (index < 0 || index == target) return;
        Collections.swap(items, index, target);
        for (int i = 0; i < items.size(); i++) {
            items.get(i).setPriority(i);
            items.get(i).touch();
            AppDatabase.get().getRepositoryDao().update(items.get(i));
        }
    }

    public void setItemEnabled(RepositoryItem item, boolean enabled) {
        if (item == null) return;
        item.setEnabled(enabled);
        item.setUpdatedAt(System.currentTimeMillis());
        AppDatabase.get().getRepositoryItemDao().update(item);
    }

    public void moveItem(RepositoryItem item, int delta) {
        if (item == null) return;
        List<RepositoryItem> items = getAllItems(item.getRepositoryId());
        int index = -1;
        for (int i = 0; i < items.size(); i++) if (items.get(i).getId() == item.getId()) index = i;
        int target = Math.clamp(index + delta, 0, items.size() - 1);
        if (index < 0 || target == index) return;
        Collections.swap(items, index, target);
        for (int i = 0; i < items.size(); i++) {
            items.get(i).setSortOrder(i);
            items.get(i).setUpdatedAt(System.currentTimeMillis());
            AppDatabase.get().getRepositoryItemDao().update(items.get(i));
        }
    }

    public boolean deleteItem(Repository repository, RepositoryItem item) {
        if (repository == null || item == null || repository.isBuiltIn()) return false;
        AppDatabase.get().getRepositoryItemDao().delete(item.getId());
        return true;
    }

    public void updateItemCheck(RepositoryItem item, boolean success, String errorMessage) {
        if (item == null) return;
        item.setCheckStatus(success ? "AVAILABLE" : "FAILED");
        item.setLastCheckedAt(System.currentTimeMillis());
        item.setErrorMessage(success ? "" : sanitizeError(errorMessage));
        item.setUpdatedAt(System.currentTimeMillis());
        AppDatabase.get().getRepositoryItemDao().update(item);
    }

    public void sync(Repository repository, RepositorySyncManager.Listener listener) {
        syncManager.sync(repository, listener);
    }

    public void syncEnabled() {
        for (Repository repository : getEnabled()) if (repository.isAutoSync()) sync(repository, null);
    }

    public void syncAll(RepositorySyncManager.Listener listener) {
        for (Repository repository : getEnabled()) sync(repository, listener);
    }

    public void importLegacy(Config config, String json) {
        Repository repository = AppDatabase.get().getRepositoryDao().findByUrl(config.getUrl());
        if (repository == null) {
            repository = new Repository();
            repository.setStableId("legacy-" + Util.md5(config.getUrl()));
            repository.setName(config.getDesc());
            repository.setUrl(config.getUrl());
            repository.setEnabled(true);
            repository.setAutoSync(true);
            repository.setPriority(getAll().size());
            repository.setStatus(RepositoryStatus.SUCCESS);
            save(repository);
        }
        List<RepositoryItem> items = RepositoryParser.parse(repository, json);
        List<RepositoryItem> cached = AppDatabase.get().getRepositoryItemDao().findByRepository(repository.getId());
        List<Long> staleIds = RepositoryItemMerge.reconcile(cached, items, repository.getName());
        AppDatabase.get().runInTransaction(() -> {
            for (long id : staleIds) AppDatabase.get().getRepositoryItemDao().delete(id);
            AppDatabase.get().getRepositoryItemDao().insertOrUpdate(items);
        });
        long now = System.currentTimeMillis();
        repository.setLastSyncAt(now);
        repository.setLastSuccessAt(now);
        repository.setStatus(RepositoryStatus.SUCCESS);
        repository.setErrorMessage("");
        repository.touch();
        AppDatabase.get().getRepositoryDao().update(repository);
    }

    private void bootstrap() {
        try (InputStreamReader reader = new InputStreamReader(App.get().getAssets().open("repositories.json"), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            int version = root.has("version") ? root.get("version").getAsInt() : 1;
            JsonArray repositories = root.has("repositories") ? root.getAsJsonArray("repositories") : new JsonArray();
            int order = 0;
            for (JsonElement element : repositories) importBuiltIn(element.getAsJsonObject(), order++);
            if (Prefers.getInt(BOOTSTRAP_VERSION) < version) Prefers.put(BOOTSTRAP_VERSION, version);
        } catch (Throwable ignored) {
        }
    }

    private void importBuiltIn(JsonObject object, int fallbackPriority) {
        String stableId = string(object, "stableId");
        if (stableId.isEmpty()) return;
        Repository repository = AppDatabase.get().getRepositoryDao().findByStableId(stableId);
        boolean existing = repository != null;
        if (!existing) repository = new Repository();
        repository.setStableId(stableId);
        repository.setName(string(object, "name"));
        repository.setUrl(string(object, "url"));
        repository.setBuiltIn(true);
        repository.setAutoSync(bool(object, "autoSync", true));
        repository.setPriority(integer(object, "priority", fallbackPriority));
        if (!existing) {
            repository.setEnabled(bool(object, "enabled", false));
            repository.setStatus(repository.isEnabled() ? RepositoryStatus.IDLE : RepositoryStatus.DISABLED);
        }
        save(repository);
        if (object.has("items")) {
            try {
                JsonObject seed = new JsonObject();
                seed.add("urls", object.getAsJsonArray("items"));
                List<RepositoryItem> items = RepositoryParser.parse(repository, seed.toString());
                List<RepositoryItem> cached = AppDatabase.get().getRepositoryItemDao().findByRepository(repository.getId());
                for (RepositoryItem item : items) {
                    for (RepositoryItem old : cached) {
                        if (old.getType() != item.getType() || !old.getUrl().equals(item.getUrl())) continue;
                        item.setId(old.getId());
                        item.setCreatedAt(old.getCreatedAt());
                        break;
                    }
                }
                AppDatabase.get().getRepositoryItemDao().insertOrUpdate(items);
            } catch (Throwable ignored) {
            }
        }
    }

    private String string(JsonObject object, String key) {
        try {
            return object.has(key) ? object.get(key).getAsString().trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private int integer(JsonObject object, String key, int fallback) {
        try {
            return object.has(key) ? object.get(key).getAsInt() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private boolean bool(JsonObject object, String key, boolean fallback) {
        try {
            return object.has(key) ? object.get(key).getAsBoolean() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private String sanitizeError(String message) {
        String safe = SecretRedactor.redact(message);
        return safe.length() > 160 ? safe.substring(0, 160) : safe;
    }

    private static class Loader {
        static volatile RepositoryManager INSTANCE = new RepositoryManager();
    }
}
