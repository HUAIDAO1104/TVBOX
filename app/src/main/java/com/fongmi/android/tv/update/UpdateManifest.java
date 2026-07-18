package com.fongmi.android.tv.update;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class UpdateManifest {

    @SerializedName(value = "versionCode", alternate = {"code"})
    private int versionCode;
    @SerializedName(value = "versionName", alternate = {"name"})
    private String versionName;
    @SerializedName(value = "releaseNotes", alternate = {"notes", "desc"})
    private String releaseNotes;
    @SerializedName("mandatory")
    private boolean mandatory;
    @SerializedName("apks")
    private Map<String, Asset> apks;

    public static UpdateManifest parse(Gson gson, String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Empty update manifest");
        UpdateManifest manifest = gson.fromJson(text, UpdateManifest.class);
        if (manifest == null || manifest.versionCode <= 0 || manifest.versionName().isEmpty()) {
            throw new IllegalArgumentException("Invalid update manifest");
        }
        return manifest;
    }

    public int versionCode() {
        return versionCode;
    }

    public String versionName() {
        return value(versionName);
    }

    public String releaseNotes() {
        return value(releaseNotes);
    }

    public boolean mandatory() {
        return mandatory;
    }

    public Asset assetFor(String mode, String abi) {
        if (apks == null || apks.isEmpty()) return null;
        String exact = normalize(mode + "-" + abi);
        String abiOnly = normalize(abi);
        for (Map.Entry<String, Asset> entry : apks.entrySet()) {
            if (normalize(entry.getKey()).equals(exact)) return valid(entry.getValue());
        }
        for (Map.Entry<String, Asset> entry : apks.entrySet()) {
            if (normalize(entry.getKey()).equals(abiOnly)) return valid(entry.getValue());
        }
        return null;
    }

    private static Asset valid(Asset asset) {
        return asset != null && (!asset.url().isEmpty() || !asset.fileName().isEmpty()) ? asset : null;
    }

    private static String normalize(String value) {
        return value(value).toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }

    public static class Asset {

        @SerializedName("url")
        private String url;
        @SerializedName("fileName")
        private String fileName;
        @SerializedName("sha256")
        private String sha256;
        @SerializedName("size")
        private long size;
        @SerializedName("mirrors")
        private List<String> mirrors;

        public String url() {
            return value(url);
        }

        public String fileName() {
            return value(fileName);
        }

        public String sha256() {
            return value(sha256).toLowerCase(Locale.ROOT);
        }

        public long size() {
            return Math.max(0, size);
        }

        public List<String> mirrors() {
            return mirrors == null ? Collections.emptyList() : mirrors;
        }
    }
}
