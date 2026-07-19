package com.fongmi.android.tv.update;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Build;

import org.junit.Test;

public class UpdateInstallPolicyTest {

    @Test
    public void androidNineProjectorUsesVisibleSystemInstaller() {
        assertTrue(UpdateInstallPolicy.useSystemInstaller(Build.VERSION_CODES.P));
    }

    @Test
    public void androidElevenStillUsesVisibleSystemInstaller() {
        assertTrue(UpdateInstallPolicy.useSystemInstaller(Build.VERSION_CODES.R));
    }

    @Test
    public void androidTwelveUsesPackageInstallerSession() {
        assertFalse(UpdateInstallPolicy.useSystemInstaller(Build.VERSION_CODES.S));
    }
}
