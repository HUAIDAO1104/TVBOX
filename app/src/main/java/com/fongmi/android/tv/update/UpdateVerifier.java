package com.fongmi.android.tv.update;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class UpdateVerifier {

    private UpdateVerifier() {
    }

    public static boolean checksumMatches(File file, String expected) {
        if (expected == null || expected.isBlank()) return false;
        try {
            return expected.trim().equalsIgnoreCase(sha256(file));
        } catch (Exception e) {
            return false;
        }
    }

    public static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[16_384];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    @SuppressWarnings("deprecation")
    public static boolean hasSameSigner(Context context, File apk) {
        try {
            PackageManager manager = context.getPackageManager();
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? PackageManager.GET_SIGNING_CERTIFICATES
                    : PackageManager.GET_SIGNATURES;
            PackageInfo installed = manager.getPackageInfo(context.getPackageName(), flags);
            PackageInfo candidate = manager.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
            if (candidate == null || !context.getPackageName().equals(candidate.packageName)) return false;
            Set<String> installedSigners = signerDigests(installed);
            Set<String> candidateSigners = signerDigests(candidate);
            installedSigners.retainAll(candidateSigners);
            return !installedSigners.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static Set<String> signerDigests(PackageInfo info) throws Exception {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
            signatures = info.signingInfo.hasMultipleSigners()
                    ? info.signingInfo.getApkContentsSigners()
                    : info.signingInfo.getSigningCertificateHistory();
        } else {
            signatures = info.signatures;
        }
        Set<String> result = new HashSet<>();
        if (signatures == null) return result;
        for (Signature signature : signatures) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder value = new StringBuilder(64);
            for (byte item : digest.digest(signature.toByteArray())) value.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            result.add(value.toString());
        }
        return result;
    }
}
