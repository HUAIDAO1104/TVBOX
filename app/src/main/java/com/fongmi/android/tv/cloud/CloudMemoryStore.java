package com.fongmi.android.tv.cloud;

import com.fongmi.android.tv.server.Server;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class CloudMemoryStore {

    private static final long TTL_MS = 10 * 60 * 1000L;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();

    private CloudMemoryStore() {
    }

    public static String register(String content) {
        purgeExpired();
        byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        String token = token();
        ENTRIES.put(token, new Entry(bytes, System.currentTimeMillis() + TTL_MS));
        Server.get().start();
        return Server.get().getAddress("/credential-memory/" + token);
    }

    public static String read(String token) {
        purgeExpired();
        Entry entry = ENTRIES.get(token);
        if (entry == null || entry.expiresAt < System.currentTimeMillis()) return null;
        return new String(entry.bytes, StandardCharsets.UTF_8);
    }

    public static void clear() {
        for (Entry entry : ENTRIES.values()) Arrays.fill(entry.bytes, (byte) 0);
        ENTRIES.clear();
    }

    private static void purgeExpired() {
        long now = System.currentTimeMillis();
        ENTRIES.entrySet().removeIf(entry -> {
            if (entry.getValue().expiresAt >= now) return false;
            Arrays.fill(entry.getValue().bytes, (byte) 0);
            return true;
        });
    }

    private static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) value.append(String.format("%02x", item));
        Arrays.fill(bytes, (byte) 0);
        return value.toString();
    }

    private record Entry(byte[] bytes, long expiresAt) {
    }
}
