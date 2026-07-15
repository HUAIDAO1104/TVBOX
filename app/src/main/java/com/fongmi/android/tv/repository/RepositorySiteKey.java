package com.fongmi.android.tv.repository;

import com.fongmi.android.tv.bean.Repository;
import com.fongmi.android.tv.bean.RepositoryItem;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Pure-Java identity helpers for sites declared by repository configuration items. */
public final class RepositorySiteKey {

    private static final String PREFIX = "repo@";
    private static final int MD5_HEX_LENGTH = 32;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private RepositorySiteKey() {
    }

    public static String scope(Repository repository, RepositoryItem item, String siteKey) {
        return scope(repositoryIdentity(repository), item.getItemId(), item.getUrl(), item.getType(), siteKey);
    }

    static String scope(String repositoryIdentity, String itemId, String configUrl, int type,
                        String siteKey) {
        String fingerprint = value(repositoryIdentity) + '\n'
                + value(itemId) + '\n'
                + type + '\n'
                + value(configUrl);
        return PREFIX + md5(fingerprint) + "@" + value(siteKey);
    }

    /** Key shape emitted before the config type was added to the namespace. */
    static String legacyScope(Repository repository, RepositoryItem item, String siteKey) {
        String fingerprint = legacyRepositoryIdentity(repository) + '\n'
                + item.getItemId() + '\n'
                + item.getUrl();
        return PREFIX + md5(fingerprint) + "@" + value(siteKey);
    }

    public static boolean matches(String scopedKey, Repository repository, RepositoryItem item,
                                  String siteKey) {
        return scope(repository, item, siteKey).equals(scopedKey)
                || legacyScope(repository, item, siteKey).equals(scopedKey);
    }

    public static boolean isScoped(String key) {
        if (key == null || !key.startsWith(PREFIX)) return false;
        int separator = key.indexOf('@', PREFIX.length());
        return separator == PREFIX.length() + MD5_HEX_LENGTH && separator + 1 < key.length();
    }

    public static String originKey(String scopedKey) {
        if (!isScoped(scopedKey)) return "";
        return scopedKey.substring(scopedKey.indexOf('@', PREFIX.length()) + 1);
    }

    static String fingerprint(String scopedKey) {
        if (!isScoped(scopedKey)) return "";
        return scopedKey.substring(PREFIX.length(), scopedKey.indexOf('@', PREFIX.length()));
    }

    private static String repositoryIdentity(Repository repository) {
        if (repository == null) return "";
        if (!repository.getStableId().isBlank()) return repository.getStableId();
        if (!repository.getUrl().isBlank()) return repository.getUrl();
        return String.valueOf(repository.getId());
    }

    private static String legacyRepositoryIdentity(Repository repository) {
        if (repository == null) return "";
        return repository.getStableId().isEmpty()
                ? String.valueOf(repository.getId())
                : repository.getStableId();
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static String md5(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            char[] result = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                int octet = digest[i] & 0xff;
                result[i * 2] = HEX[octet >>> 4];
                result[i * 2 + 1] = HEX[octet & 0x0f];
            }
            return new String(result);
        } catch (Exception e) {
            throw new IllegalStateException("MD5 is unavailable", e);
        }
    }
}
