package com.fongmi.android.tv.history;

import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class HistoryCipher {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int FLAGS = Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING;

    static String newKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.encodeToString(bytes, FLAGS);
    }

    static String seal(String key, String context, String json) throws GeneralSecurityException {
        byte[] iv = new byte[12];
        RANDOM.nextBytes(iv);
        Cipher cipher = cipher(key, context, iv, Cipher.ENCRYPT_MODE);
        byte[] encrypted = cipher.doFinal(json.getBytes(StandardCharsets.UTF_8));
        byte[] packet = Arrays.copyOf(iv, iv.length + encrypted.length);
        System.arraycopy(encrypted, 0, packet, iv.length, encrypted.length);
        return Base64.encodeToString(packet, FLAGS);
    }

    static String open(String key, String context, String body) throws GeneralSecurityException {
        if (body == null || body.length() > 4_000_000) throw new GeneralSecurityException();
        byte[] packet = Base64.decode(body, FLAGS);
        if (packet.length < 28) throw new GeneralSecurityException();
        Cipher cipher = cipher(key, context, Arrays.copyOf(packet, 12), Cipher.DECRYPT_MODE);
        return new String(cipher.doFinal(packet, 12, packet.length - 12), StandardCharsets.UTF_8);
    }

    private static Cipher cipher(String key, String context, byte[] iv, int mode) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(Base64.decode(key, FLAGS), "AES"), new GCMParameterSpec(128, iv));
        cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
