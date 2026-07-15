package com.fongmi.android.tv.bean;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.security.SensitiveData;
import com.github.catvod.utils.Prefers;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import com.google.gson.annotations.SerializedName;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Backup {

    @SerializedName("site")
    private List<Site> site;
    @SerializedName("live")
    private List<Live> live;
    @SerializedName("keep")
    private List<Keep> keep;
    @SerializedName("config")
    private List<Config> config;
    @SerializedName("history")
    private List<History> history;
    @SerializedName("repository")
    private List<Repository> repository;
    @SerializedName("repositoryItem")
    private List<RepositoryItem> repositoryItem;
    @SerializedName("prefers")
    private Map<String, ?> prefers;

    public static Backup create() {
        Backup backup = new Backup();
        backup.setPrefers(SensitiveData.filterPreferences(Prefers.getPrefers().getAll()));
        backup.setSite(AppDatabase.get().getSiteDao().findAll());
        backup.setLive(AppDatabase.get().getLiveDao().findAll());
        backup.setKeep(AppDatabase.get().getKeepDao().findAll());
        backup.setConfig(AppDatabase.get().getConfigDao().findAll());
        backup.setHistory(AppDatabase.get().getHistoryDao().findAll());
        backup.setRepository(AppDatabase.get().getRepositoryDao().findAll());
        backup.setRepositoryItem(AppDatabase.get().getRepositoryItemDao().findAll());
        return backup;
    }

    public static Backup objectFrom(String json) {
        try {
            Gson gson = new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LAZILY_PARSED_NUMBER).create();
            Backup backup = gson.fromJson(json, Backup.class);
            return backup == null ? new Backup() : backup;
        } catch (Exception e) {
            return new Backup();
        }
    }

    public void restore() {
        AppDatabase.get().runInTransaction(() -> {
            AppDatabase.get().getRepositoryItemDao().deleteAll();
            AppDatabase.get().getRepositoryDao().deleteAll();
            AppDatabase.get().getSiteDao().deleteAll();
            AppDatabase.get().getLiveDao().deleteAll();
            AppDatabase.get().getKeepDao().deleteAll();
            AppDatabase.get().getConfigDao().deleteAll();
            AppDatabase.get().getHistoryDao().deleteAll();
            AppDatabase.get().getSiteDao().insertOrUpdate(getSite());
            AppDatabase.get().getLiveDao().insertOrUpdate(getLive());
            AppDatabase.get().getKeepDao().insertOrUpdate(getKeep());
            AppDatabase.get().getConfigDao().insertOrUpdate(getConfig());
            AppDatabase.get().getHistoryDao().insertOrUpdate(getHistory());
            AppDatabase.get().getRepositoryDao().insertOrUpdate(getRepository());
            AppDatabase.get().getRepositoryItemDao().insertOrUpdate(getRepositoryItem());
        });
        for (Map.Entry<String, ?> entry : SensitiveData.filterPreferences(getPrefers()).entrySet()) Prefers.put(entry.getKey(), entry.getValue());
    }

    public List<Site> getSite() {
        return site == null ? Collections.emptyList() : site;
    }

    public void setSite(List<Site> site) {
        this.site = site;
    }

    public List<Live> getLive() {
        return live == null ? Collections.emptyList() : live;
    }

    public void setLive(List<Live> live) {
        this.live = live;
    }

    public List<Keep> getKeep() {
        return keep == null ? Collections.emptyList() : keep;
    }

    public void setKeep(List<Keep> keep) {
        this.keep = keep;
    }

    public List<Config> getConfig() {
        return config == null ? Collections.emptyList() : config;
    }

    public void setConfig(List<Config> config) {
        this.config = config;
    }

    public List<History> getHistory() {
        return history == null ? Collections.emptyList() : history;
    }

    public void setHistory(List<History> history) {
        this.history = history;
    }

    public List<Repository> getRepository() {
        return repository == null ? Collections.emptyList() : repository;
    }

    public void setRepository(List<Repository> repository) {
        this.repository = repository;
    }

    public List<RepositoryItem> getRepositoryItem() {
        return repositoryItem == null ? Collections.emptyList() : repositoryItem;
    }

    public void setRepositoryItem(List<RepositoryItem> repositoryItem) {
        this.repositoryItem = repositoryItem;
    }

    public Map<String, ?> getPrefers() {
        return prefers == null ? new HashMap<>() : prefers;
    }

    public void setPrefers(Map<String, ?> prefers) {
        this.prefers = prefers;
    }

    @NonNull
    @Override
    public String toString() {
        return SensitiveData.sanitizeJson(App.gson().toJson(this));
    }
}
