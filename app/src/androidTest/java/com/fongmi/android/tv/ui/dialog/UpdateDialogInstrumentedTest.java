package com.fongmi.android.tv.ui.dialog;

import static org.junit.Assert.*;
import android.view.View;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.impl.UpdateListener;
import com.fongmi.android.tv.ui.activity.HomeActivity;
import com.fongmi.android.tv.utils.Util;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class UpdateDialogInstrumentedTest {
    @Test public void speedRetryAndSystemBackRemainUsable() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        AtomicReference<UpdateDialog> current = new AtomicReference<>(); AtomicInteger cancelled = new AtomicInteger();
        var drawn = new java.util.concurrent.CountDownLatch(1);
        try (var scenario = ActivityScenario.launch(HomeActivity.class)) {
            scenario.onActivity(activity -> {
                current.set(UpdateDialog.create().title("v5.5.77").desc("下载与搜索性能优化").listener(new UpdateListener() {
                    public void onConfirm(View button) { current.get().setDownloading(); }
                    public void onCancel(View button) { cancelled.incrementAndGet(); }
                }).show(activity));
                activity.getSupportFragmentManager().executePendingTransactions();
                current.get().setDownloading(); current.get().setProgress(60);
                current.get().setStatus("gh-proxy.com · 9.9 MB/秒 · 28.2 MB/47.1 MB");
                current.get().getDialog().getWindow().getDecorView().getViewTreeObserver().addOnDrawListener(drawn::countDown);
            });
            assertTrue(drawn.await(3, java.util.concurrent.TimeUnit.SECONDS));
            // Wait for the dialog window's enter animation to reach the compositor.
            android.os.SystemClock.sleep(250);
            instrumentation.waitForIdleSync();
            awaitFocus(current);
            var bitmap = instrumentation.getUiAutomation().takeScreenshot();
            var target = new java.io.File(instrumentation.getTargetContext().getExternalFilesDir(null), "576-update-dialog.png");
            try (var out = new java.io.FileOutputStream(target)) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); }
            bitmap.recycle();
            scenario.onActivity(activity -> {
                assertTrue("The update dialog must own input focus before testing Back", current.get().getDialog().getWindow().getDecorView().hasWindowFocus());
                current.get().setError("下载中断，请重试");
                View confirm = Util.isLeanback() ? current.get().getDialog().findViewById(activity.getResources().getIdentifier("confirm", "id", activity.getPackageName()))
                        : ((androidx.appcompat.app.AlertDialog) current.get().getDialog()).getButton(-1);
                assertTrue(confirm.isEnabled()); confirm.performClick(); assertFalse(confirm.isEnabled());
            });
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            instrumentation.waitForIdleSync(); assertEquals(1, cancelled.get());
            assertTrue(current.get().getDialog() == null || !current.get().getDialog().isShowing());
        }
    }
    @Test public void checkingShowsItsOwnStageAndCanBeCancelled() throws Exception {
        AtomicReference<UpdateDialog> current = new AtomicReference<>(); AtomicInteger cancelled = new AtomicInteger();
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        try (var scenario = ActivityScenario.launch(HomeActivity.class)) {
            scenario.onActivity(activity -> {
                current.set(UpdateDialog.create().title("检查更新").desc("检查新版本").checking().listener(new UpdateListener() {
                    public void onConfirm(View v) { fail("Checking must not start a download"); }
                    public void onCancel(View v) { cancelled.incrementAndGet(); }
                }).show(activity));
                activity.getSupportFragmentManager().executePendingTransactions();
            });
            instrumentation.waitForIdleSync(); android.os.SystemClock.sleep(300);
            awaitFocus(current);
            scenario.onActivity(activity -> {
                android.widget.TextView status = current.get().getDialog().findViewById(R.id.status);
                assertEquals(activity.getString(R.string.update_checking_status), status.getText().toString());
                View cancel = Util.isLeanback() ? current.get().getDialog().findViewById(activity.getResources().getIdentifier("cancel", "id", activity.getPackageName()))
                        : ((androidx.appcompat.app.AlertDialog) current.get().getDialog()).getButton(-2);
                assertEquals(View.VISIBLE,cancel.getVisibility()); assertTrue(cancel.isEnabled());
            });
            var bitmap = instrumentation.getUiAutomation().takeScreenshot();
            try (var out = new java.io.FileOutputStream(new java.io.File(instrumentation.getTargetContext().getExternalFilesDir(null), "577-update-checking.png"))) {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            }
            bitmap.recycle();
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            instrumentation.waitForIdleSync(); assertEquals(1,cancelled.get());
        }
    }

    private static void awaitFocus(AtomicReference<UpdateDialog> current) {
        long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
        AtomicBoolean focused = new AtomicBoolean();
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> focused.set(current.get().getDialog() != null
                    && current.get().getDialog().getWindow().getDecorView().hasWindowFocus()));
            if (focused.get()) return;
            android.os.SystemClock.sleep(50);
        }
        fail("Update window did not receive input focus");
    }

}
