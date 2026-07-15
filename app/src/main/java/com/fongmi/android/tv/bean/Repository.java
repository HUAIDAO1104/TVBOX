package com.fongmi.android.tv.bean;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(indices = {
        @Index(value = "stableId", unique = true),
        @Index(value = "priority")
})
public class Repository {

    @PrimaryKey(autoGenerate = true)
    private long id;
    @NonNull
    private String stableId = "";
    @NonNull
    private String name = "";
    @NonNull
    private String url = "";
    private boolean enabled;
    private int priority;
    private boolean autoSync;
    private boolean builtIn;
    private long lastSyncAt;
    private long lastSuccessAt;
    @ColumnInfo(defaultValue = "0")
    private long lastFailureAt;
    @NonNull
    private String etag = "";
    @NonNull
    private String lastModified = "";
    @NonNull
    private String status = "IDLE";
    @NonNull
    private String errorMessage = "";
    private long createdAt;
    private long updatedAt;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    @NonNull
    public String getStableId() {
        return stableId;
    }

    public void setStableId(@NonNull String stableId) {
        this.stableId = stableId;
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

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public boolean isAutoSync() {
        return autoSync;
    }

    public void setAutoSync(boolean autoSync) {
        this.autoSync = autoSync;
    }

    public boolean isBuiltIn() {
        return builtIn;
    }

    public void setBuiltIn(boolean builtIn) {
        this.builtIn = builtIn;
    }

    public long getLastSyncAt() {
        return lastSyncAt;
    }

    public void setLastSyncAt(long lastSyncAt) {
        this.lastSyncAt = lastSyncAt;
    }

    public long getLastSuccessAt() {
        return lastSuccessAt;
    }

    public void setLastSuccessAt(long lastSuccessAt) {
        this.lastSuccessAt = lastSuccessAt;
    }

    public long getLastFailureAt() {
        return lastFailureAt;
    }

    public void setLastFailureAt(long lastFailureAt) {
        this.lastFailureAt = lastFailureAt;
    }

    @NonNull
    public String getEtag() {
        return etag;
    }

    public void setEtag(@NonNull String etag) {
        this.etag = etag;
    }

    @NonNull
    public String getLastModified() {
        return lastModified;
    }

    public void setLastModified(@NonNull String lastModified) {
        this.lastModified = lastModified;
    }

    @NonNull
    public String getStatus() {
        return status;
    }

    public void setStatus(@NonNull String status) {
        this.status = status;
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

    public Repository touch() {
        long now = System.currentTimeMillis();
        if (createdAt == 0) createdAt = now;
        updatedAt = now;
        return this;
    }
}
