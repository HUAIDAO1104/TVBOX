package com.fongmi.android.tv.update;

import android.os.Build;

/** Chooses the install hand-off that is reliable for the current Android generation. */
public final class UpdateInstallPolicy {

    private UpdateInstallPolicy() {
    }

    /**
     * Android 11 and older TV firmware commonly drops PackageInstaller session callbacks after
     * the requesting activity leaves the foreground. Hand the verified payload directly to the
     * visible system installer there. Android 12+ keeps the session path for its stricter install
     * confirmation rules.
     */
    public static boolean useSystemInstaller(int sdkInt) {
        return sdkInt <= Build.VERSION_CODES.R;
    }
}
