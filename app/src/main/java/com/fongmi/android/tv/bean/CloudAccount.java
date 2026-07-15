package com.fongmi.android.tv.bean;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity
public class CloudAccount {

    @PrimaryKey
    @NonNull
    private String provider = "";
    @NonNull
    private String accountId = "";
    @NonNull
    private String displayName = "";
    @NonNull
    private String encryptedCredential = "";
    @NonNull
    private String credentialType = "";
    @NonNull
    private String status = "CONFIGURED";
    @NonNull
    private String source = "MANUAL";
    private long updatedAt;
    private long expiresAt;
    private long lastVerifiedAt;

    @NonNull
    public String getProvider() {
        return provider;
    }

    public void setProvider(@NonNull String provider) {
        this.provider = provider;
    }

    @NonNull
    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(@NonNull String accountId) {
        this.accountId = accountId;
    }

    @NonNull
    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(@NonNull String displayName) {
        this.displayName = displayName;
    }

    @NonNull
    public String getEncryptedCredential() {
        return encryptedCredential;
    }

    public void setEncryptedCredential(@NonNull String encryptedCredential) {
        this.encryptedCredential = encryptedCredential;
    }

    @NonNull
    public String getCredentialType() {
        return credentialType;
    }

    public void setCredentialType(@NonNull String credentialType) {
        this.credentialType = credentialType;
    }

    @NonNull
    public String getStatus() {
        return status;
    }

    public void setStatus(@NonNull String status) {
        this.status = status;
    }

    @NonNull
    public String getSource() {
        return source;
    }

    public void setSource(@NonNull String source) {
        this.source = source;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(long expiresAt) {
        this.expiresAt = expiresAt;
    }

    public long getLastVerifiedAt() {
        return lastVerifiedAt;
    }

    public void setLastVerifiedAt(long lastVerifiedAt) {
        this.lastVerifiedAt = lastVerifiedAt;
    }
}
