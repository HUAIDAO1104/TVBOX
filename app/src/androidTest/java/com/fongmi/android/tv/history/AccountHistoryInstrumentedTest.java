package com.fongmi.android.tv.history;

import android.os.SystemClock;
import android.widget.EditText;

import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.ui.activity.PddAccountActivity;

import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

public class AccountHistoryInstrumentedTest {
    @Test public void guestImportAndAccountSwitch() throws Exception {
        assumeTrue(InstrumentationRegistry.getArguments().getString("accountTest", "").equals("1"));
        assertTrue(com.fongmi.android.tv.BuildConfig.ACCOUNT_SERVICE_URL.isEmpty());
        AccountSession.logout();
        String name = "user_" + UUID.randomUUID().toString().substring(0, 8);
        VodConfig.get().config(Config.find("https://fixture.invalid/account-switch", 0).update());
        int cid = VodConfig.getCid();
        String key = HistoryKey.local("fixture@@@" + name, cid);
        History guest = new History();
        guest.setKey(key);
        guest.setVodName("账号隔离用例");
        guest.setPosition(24000);
        guest.setDuration(120000);
        guest.setCreateTime(System.currentTimeMillis());
        guest.save(cid);
        try (var screen = ActivityScenario.launch(PddAccountActivity.class)) {
            submit(screen, name, true);
            assertNull(History.find(key));
            AccountSession.importGuestHistory();
            History owned = History.find(key);
            assertNotNull(owned);
            assertEquals(24000, owned.getPosition());
            screen.onActivity(a -> a.findViewById(R.id.logout).performClick());
            waitUser("");
            assertEquals("", History.find(key).getProfile());
            submit(screen, name + "b", true);
            owned.setPosition(10000);
            owned.setCreateTime(System.currentTimeMillis());
            owned.save();
            assertNull(History.find(key));
            screen.onActivity(a -> a.findViewById(R.id.logout).performClick());
            waitUser("");
            submit(screen, name, false);
            assertEquals(10000, History.find(key).getPosition());
        } finally { AccountSession.logout(); }
    }

    private void submit(ActivityScenario<PddAccountActivity> screen, String name, boolean register) {
        // A session is committed before the UI posts completion. Wait for the form to enable again.
        for (int i = 0; i < 100; i++) {
            java.util.concurrent.atomic.AtomicBoolean submitted = new java.util.concurrent.atomic.AtomicBoolean();
            screen.onActivity(a -> {
                var button = a.findViewById(register ? R.id.register : R.id.login);
                if (!button.isShown() || !button.isEnabled()) return;
                ((EditText) a.findViewById(R.id.server)).setText("http://127.0.0.1:18782");
                ((EditText) a.findViewById(R.id.username)).setText(name);
                ((EditText) a.findViewById(R.id.password)).setText("fixture-" + name);
                button.performClick();
                submitted.set(true);
            });
            if (submitted.get()) break;
            SystemClock.sleep(100);
        }
        waitUser(name);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private void waitUser(String name) {
        for (int i = 0; i < 100 && !AccountSession.username().equals(name); i++) SystemClock.sleep(100);
        assertEquals(name, AccountSession.username());
    }
}
