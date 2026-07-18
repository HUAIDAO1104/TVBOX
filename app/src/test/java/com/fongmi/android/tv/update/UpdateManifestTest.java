package com.fongmi.android.tv.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.google.gson.Gson;

import org.junit.Test;

public class UpdateManifestTest {

    private static final String JSON = """
            {
              "versionCode": 570,
              "versionName": "5.5.19",
              "releaseNotes": "稳定性优化",
              "apks": {
                "leanback-armeabi_v7a": {
                  "url": "https://github.com/HUAIDAO1104/TVBOX/releases/latest/download/tv32.apk",
                  "sha256": "ABCDEF"
                },
                "mobile-arm64_v8a": {
                  "fileName": "mobile64.apk",
                  "sha256": "123456"
                }
              }
            }
            """;

    @Test
    public void parsesAndSelectsExactFlavorAsset() {
        UpdateManifest manifest = UpdateManifest.parse(new Gson(), JSON);
        UpdateManifest.Asset tv32 = manifest.assetFor("leanback", "armeabi_v7a");
        UpdateManifest.Asset mobile64 = manifest.assetFor("mobile", "arm64-v8a");

        assertEquals(570, manifest.versionCode());
        assertEquals("5.5.19", manifest.versionName());
        assertEquals("稳定性优化", manifest.releaseNotes());
        assertNotNull(tv32);
        assertEquals("abcdef", tv32.sha256());
        assertNotNull(mobile64);
        assertEquals("mobile64.apk", mobile64.fileName());
        assertNull(manifest.assetFor("leanback", "arm64_v8a"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsManifestWithoutVersionCode() {
        UpdateManifest.parse(new Gson(), "{\"versionName\":\"broken\"}");
    }
}
