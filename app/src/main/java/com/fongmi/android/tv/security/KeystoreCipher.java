package com.fongmi.android.tv.security;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class KeystoreCipher {

    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final String ALIAS = "fongmi.cloud.credential.v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private KeystoreCipher() {
    }

    public static String encrypt(String value) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return "v1:" + encode(cipher.getIV()) + ":" + encode(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to protect credential", e);
        }
    }

    public static String decrypt(String value) {
        try {
            return decryptOrThrow(value);
        } catch (Exception e) {
            return "";
        }
    }

    public static String decryptOrThrow(String value) throws Exception {
        String[] parts = value == null ? new String[0] : value.split(":", 3);
        if (parts.length != 3 || !"v1".equals(parts[0])) throw new IllegalArgumentException("Unsupported credential envelope");
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, decode(parts[1])));
        return new String(cipher.doFinal(decode(parts[2])), StandardCharsets.UTF_8);
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance(ANDROID_KEY_STORE);
        store.load(null);
        java.security.Key existing = store.getKey(ALIAS, null);
        if (existing instanceof SecretKey secretKey) return secretKey;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE);
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private static String encode(byte[] value) {
        return Base64.encodeToString(value, Base64.NO_WRAP);
    }

    private static byte[] decode(String value) {
        return Base64.decode(value, Base64.NO_WRAP);
    }
}
