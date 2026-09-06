package com.fongmi.android.tv.api.loader;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.Download;
import com.github.catvod.utils.Util;
import java.io.*;
import java.nio.channels.*;
import java.util.concurrent.*;
import java.util.zip.ZipFile;

/** Shared immutable downloads; native runtime state stays private to each process. */
public final class JarArtifactCache {
    private static final long TTL_MS = TimeUnit.HOURS.toMillis(6);
    private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<>();
    private JarArtifactCache() { }

    public static File get(String url, String expectedMd5) throws Exception {
        File root = App.get().getBaseContext().getCacheDir();
        File directory = new File(root, "source-jars");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建来源缓存");
        String key = Util.md5(url + "#" + expectedMd5);
        File target = new File(directory, key + ".jar");
        synchronized (LOCKS.computeIfAbsent(key, ignored -> new Object())) {
            try (RandomAccessFile lock = new RandomAccessFile(new File(directory, key + ".lock"), "rw")) {
                FileLock acquired;
                while ((acquired = lock.getChannel().tryLock()) == null) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                    Thread.sleep(40);
                }
                try (FileLock held = acquired) {
                    if (valid(target, expectedMd5) && System.currentTimeMillis() - target.lastModified() < TTL_MS) return target;
                    File temporary = new File(directory, key + ".part");
                    temporary.delete();
                    try {
                        File existing = new File(new File(root, "jar"), Util.md5(url) + ".jar");
                        if (valid(existing, expectedMd5) && System.currentTimeMillis() - existing.lastModified() < TTL_MS)
                            copy(existing, temporary);
                        else Download.create(url, temporary).timeout(15000).get();
                        if (!valid(temporary, expectedMd5)) throw new IOException("来源组件下载不完整或校验失败");
                        if (!temporary.setReadOnly() || !temporary.renameTo(target)) throw new IOException("无法保存来源组件");
                        return target;
                    } finally { temporary.delete(); }
                }
            }
        }
    }

    private static void copy(File source, File target) throws IOException {
        try (InputStream input = new FileInputStream(source); OutputStream output = new FileOutputStream(target)) {
            byte[] bytes = new byte[16384]; int count;
            while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
        }
    }

    private static boolean valid(File file, String expectedMd5) {
        if (!file.isFile() || file.length() == 0) return false;
        if (!expectedMd5.isEmpty()) {
            try (InputStream input = new FileInputStream(file)) {
                var digest = java.security.MessageDigest.getInstance("MD5");
                byte[] data = new byte[16384]; int count;
                while ((count = input.read(data)) != -1) digest.update(data, 0, count);
                StringBuilder hex = new StringBuilder();
                for (byte b : digest.digest()) { hex.append(Character.forDigit((b & 255) >>> 4, 16)); hex.append(Character.forDigit(b & 15, 16)); }
                if (!hex.toString().equalsIgnoreCase(expectedMd5)) return false;
            } catch (Exception error) { return false; }
        }
        try (ZipFile zip = new ZipFile(file)) { return zip.getEntry("classes.dex") != null; }
        catch (IOException error) { return false; }
    }
}
