package com.fongmi.android.tv.cloud;

import android.text.TextUtils;

import com.fongmi.android.tv.bean.CloudAccount;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.loader.BaseLoader;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.security.KeystoreCipher;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Prefers;

public class CloudAccountManager {

    public static final String STATUS_UNCONFIGURED = "UNCONFIGURED";
    public static final String STATUS_CONFIGURED = "CONFIGURED";
    public static final String STATUS_VALIDATING = "VALIDATING";
    public static final String STATUS_VALID = "VALID";
    public static final String STATUS_INVALID = "INVALID";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String STATUS_UNSUPPORTED = "UNSUPPORTED";
    public static final String STATUS_UNKNOWN = "UNKNOWN";
    public static final String STATUS_DECRYPT_FAILED = "DECRYPT_FAILED";
    public static final String STATUS_LOGIN_REQUIRED = "LOGIN_REQUIRED";

    private CloudAccountManager() {
    }

    public static void migrateLegacyCredentials() {
        CloudCredentialPreferences.clearRuntime();
        for (CloudProvider provider : CloudProvider.ALL) {
            if (get(provider.id()) != null) continue;
            String legacy = "";
            for (String key : CloudCredentialPreferences.keys(provider.id())) {
                legacy = Prefers.getString(key).trim();
                if (!legacy.isEmpty()) break;
            }
            if (legacy.isEmpty()) continue;
            try {
                CloudAccount account = new CloudAccount();
                account.setProvider(provider.id());
                account.setDisplayName("");
                account.setCredentialType(provider.credentialType());
                account.setEncryptedCredential(KeystoreCipher.encrypt(legacy));
                account.setSource("LEGACY_PREFERENCES");
                account.setStatus(STATUS_CONFIGURED);
                account.setUpdatedAt(System.currentTimeMillis());
                AppDatabase.get().getCloudAccountDao().insertOrUpdate(account);
                CloudCredentialPreferences.clear(provider.id());
            } catch (Throwable error) {
                SpiderDebug.log("Legacy cloud credential migration failed: " + error.getClass().getSimpleName());
            } finally {
                legacy = "";
            }
        }
    }

    public static CloudAccount get(String provider) {
        return AppDatabase.get().getCloudAccountDao().find(provider);
    }

    public static CloudAccount save(CloudProvider provider, String credential, String displayName, String source) {
        if (TextUtils.isEmpty(credential)) throw new IllegalArgumentException("Credential is empty");
        CloudAccount account = get(provider.id());
        if (account == null) account = new CloudAccount();
        account.setProvider(provider.id());
        account.setDisplayName(displayName == null ? "" : displayName.trim());
        account.setCredentialType(provider.credentialType());
        account.setEncryptedCredential(KeystoreCipher.encrypt(credential.trim()));
        account.setSource(TextUtils.isEmpty(source) ? "MANUAL" : source);
        account.setStatus(STATUS_CONFIGURED);
        account.setUpdatedAt(System.currentTimeMillis());
        AppDatabase.get().getCloudAccountDao().insertOrUpdate(account);
        CloudCredentialBridge.clearProvider(provider.id());
        BaseLoader.get().clear();
        return account;
    }

    public static String credential(String provider) {
        CloudAccount account = get(provider);
        if (account == null) return "";
        if (STATUS_DECRYPT_FAILED.equals(account.getStatus())) return "";
        try {
            return KeystoreCipher.decryptOrThrow(account.getEncryptedCredential());
        } catch (Throwable error) {
            account.setStatus(STATUS_DECRYPT_FAILED);
            account.setUpdatedAt(System.currentTimeMillis());
            AppDatabase.get().getCloudAccountDao().update(account);
            CloudCredentialBridge.clearProvider(provider);
            BaseLoader.get().clear();
            SpiderDebug.log("Cloud credential decrypt failed: " + error.getClass().getSimpleName());
            return "";
        }
    }

    public static boolean validateFormat(String provider) {
        CloudAccount account = get(provider);
        if (account == null) return false;
        String credential = credential(provider).trim();
        if (STATUS_DECRYPT_FAILED.equals(account.getStatus())) return false;
        boolean valid = credential.length() >= 12;
        if ("COOKIE".equals(account.getCredentialType())) valid = valid && credential.contains("=");
        account.setStatus(valid ? STATUS_CONFIGURED : STATUS_INVALID);
        account.setLastVerifiedAt(System.currentTimeMillis());
        account.setUpdatedAt(System.currentTimeMillis());
        AppDatabase.get().getCloudAccountDao().update(account);
        return valid;
    }

    public static void logout(String provider) {
        AppDatabase.get().getCloudAccountDao().delete(provider);
        CloudCredentialBridge.clearProvider(provider);
        BaseLoader.get().clear();
    }

    public static CloudCapability capability(String provider) {
        try {
            CloudProvider target = CloudProvider.find(provider);
            return CloudCapability.detect(target, VodConfig.get().getHome().getExt());
        } catch (Throwable ignored) {
            return CloudCapability.unknown();
        }
    }

    public static String mask(CloudAccount account) {
        // The account list only needs to communicate that a secret is present.
        // Do not decrypt it for display and do not reveal prefix/suffix fragments.
        if (account == null || account.getEncryptedCredential().isEmpty()) return "";
        return "••••••••";
    }
}
