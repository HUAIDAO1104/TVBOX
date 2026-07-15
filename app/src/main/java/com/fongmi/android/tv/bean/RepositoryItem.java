package com.fongmi.android.tv.bean;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        foreignKeys = @ForeignKey(entity = Repository.class, parentColumns = "id", childColumns = "repositoryId", onDelete = ForeignKey.CASCADE),
        indices = {
                @Index(value = "repositoryId"),
                @Index(value = {"repositoryId", "url", "type"}, unique = true)
        })
public class RepositoryItem {

    @PrimaryKey(autoGenerate = true)
    private long id;
    private long repositoryId;
    @NonNull
    private String itemId = "";
    @NonNull
    private String name = "";
    @NonNull
    private String url = "";
    private int type;
    private int sortOrder;
    private boolean enabled;
    @NonNull
    @ColumnInfo(defaultValue = "'UNCHECKED'")
    private String checkStatus = "UNCHECKED";
    @ColumnInfo(defaultValue = "0")
    private long lastCheckedAt;
    @NonNull
    @ColumnInfo(defaultValue = "''")
    private String errorMessage = "";
    private long createdAt;
    private long updatedAt;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getRepositoryId() {
        return repositoryId;
    }

    public void setRepositoryId(long repositoryId) {
        this.repositoryId = repositoryId;
    }

    @NonNull
    public String getItemId() {
        return itemId;
    }

    public void setItemId(@NonNull String itemId) {
        this.itemId = itemId;
    }

    @NonNull
    public String getName() {
        return name;
    }

    public void setName(@NonNull String name) {
        this.name = name;
    }

    @NonNull
    public String getUrl() {
        return url;
    }

    public void setUrl(@NonNull String url) {
        this.url = url;
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @NonNull
    public String getCheckStatus() {
        return checkStatus;
    }

    public void setCheckStatus(@NonNull String checkStatus) {
        this.checkStatus = checkStatus;
    }

    public long getLastCheckedAt() {
        return lastCheckedAt;
    }

    public void setLastCheckedAt(long lastCheckedAt) {
        this.lastCheckedAt = lastCheckedAt;
    }

    @NonNull
    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(@NonNull String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
