package com.fongmi.android.tv.cloud;

/**
 * A cloud-drive login page exposed by a repository configuration Spider.
 *
 * <p>The route deliberately keeps the owning site key.  Opening a route must use the same Spider
 * instance that published it; otherwise identical action ids from different repositories could be
 * sent to the wrong implementation.</p>
 */
public record CloudLoginRoute(String siteKey, String typeId, String title, String providerId) {

    public String stableId() {
        return siteKey + '\u0000' + typeId + '\u0000' + providerId;
    }
}
