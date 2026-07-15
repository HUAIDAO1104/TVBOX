package com.fongmi.android.tv.ui.search;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class SearchStableIds {

    private SearchStableIds() {
    }

    static String create(String prefix, String seed) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(prefix).append('_');
            for (int i = 0; i < 12; i++) result.append(String.format("%02x", digest[i]));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return prefix + '_' + Integer.toUnsignedString(seed.hashCode(), 16);
        }
    }
}
