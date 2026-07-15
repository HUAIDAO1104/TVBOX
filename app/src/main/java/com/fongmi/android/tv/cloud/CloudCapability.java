package com.fongmi.android.tv.cloud;

import java.util.EnumSet;
import java.util.Locale;

public final class CloudCapability {

    public enum Support {SUPPORTED, UNSUPPORTED, UNKNOWN}

    public enum Mode {
        ACTION_LOGIN_AVAILABLE,
        COOKIE_INJECTION_AVAILABLE,
        CLOUD_DRIVE_AVAILABLE,
        LEGACY_PREFERS_AVAILABLE,
        LEGACY_FILE_REQUIRED
    }

    private final Support support;
    private final EnumSet<Mode> modes;

    private CloudCapability(Support support, EnumSet<Mode> modes) {
        this.support = support;
        this.modes = modes.clone();
    }

    public static CloudCapability detect(CloudProvider provider, String ext) {
        if (provider == null || ext == null || ext.isEmpty()) return unknown();
        String lower = ext.toLowerCase(Locale.ROOT);
        EnumSet<Mode> modes = EnumSet.noneOf(Mode.class);
        boolean providerDeclared = CloudCredentialPreferences.references(provider.id(), lower);
        if (providerDeclared) modes.add(Mode.COOKIE_INJECTION_AVAILABLE);
        if (lower.contains("cloud-drive")) modes.add(Mode.CLOUD_DRIVE_AVAILABLE);
        if (CloudCredentialPreferences.referencesLegacyKey(provider.id(), lower)) modes.add(Mode.LEGACY_PREFERS_AVAILABLE);
        // Spider.action(String) is intentionally opaque: neither the app API nor the
        // configuration schema defines a QR-login request/response contract.  Do not
        // advertise an actionable login capability from similarly named ext fields.
        // A future provider adapter may add this mode only after it implements and
        // validates an explicit protocol (start, poll, cancel and credential result).
        Support support = providerDeclared ? Support.SUPPORTED : Support.UNKNOWN;
        return new CloudCapability(support, modes);
    }

    public static CloudCapability unknown() {
        return new CloudCapability(Support.UNKNOWN, EnumSet.noneOf(Mode.class));
    }

    public Support support() {
        return support;
    }

    public boolean has(Mode mode) {
        return modes.contains(mode);
    }
}
