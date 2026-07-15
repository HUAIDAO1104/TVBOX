package com.fongmi.android.tv.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.fongmi.android.tv.bean.CloudAccount;

import org.junit.Test;

public class CloudAccountSecurityTest {

    @Test
    public void storedCredentialDisplayNeverRevealsEnvelopeFragments() {
        CloudAccount account = new CloudAccount();
        account.setEncryptedCredential("v1:visible-iv:visible-ciphertext");

        String display = CloudAccountManager.mask(account);

        assertEquals("••••••••", display);
        assertFalse(display.contains("visible"));
        assertEquals("", CloudAccountManager.mask(null));
    }

    @Test
    public void credentialInjectionMetadataDoesNotImplyQrLogin() {
        String ext = "{\"quark_cookie\":\"${cloud.quark.cookie}\",\"qrLogin\":true,\"action\":\"login\"}";
        CloudCapability capability = CloudCapability.detect(CloudProvider.find(CloudProvider.QUARK), ext);

        assertFalse(capability.has(CloudCapability.Mode.ACTION_LOGIN_AVAILABLE));
    }

    @Test
    public void noProviderAdvertisesQrWithoutACompleteProtocolAdapter() {
        for (CloudProvider provider : CloudProvider.ALL) {
            CloudCapability capability = CloudCapability.detect(provider, "qr login scan poll confirm token");
            assertFalse(provider.name(), capability.has(CloudCapability.Mode.ACTION_LOGIN_AVAILABLE));
        }
    }
}
