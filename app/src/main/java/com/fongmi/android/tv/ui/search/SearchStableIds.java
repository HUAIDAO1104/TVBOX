package com.fongmi.android.tv.ui.search;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class SearchStableIds {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private SearchStableIds() {
    }

    static String create(String prefix, String seed) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(prefix).append('_');
            for (int i = 0; i < 12; i++) {
                int value = digest[i] & 0xff;
                result.append(HEX[value >>> 4]).append(HEX[value & 15]);
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return prefix + '_' + Integer.toUnsignedString(seed.hashCode(), 16);
        }
    }
}
