package com.fongmi.android.tv.history;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.ImageView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.ui.activity.PddAccountActivity;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

public class DeviceLoginTest {
    private static final String LOCAL="http://127.0.0.1:18782";
    private final Instrumentation inst=InstrumentationRegistry.getInstrumentation();

    @Test public void pairedScreens() throws Exception {
        String stage=InstrumentationRegistry.getArguments().getString("deviceLogin","");
        assumeTrue(stage.equals("phone") || stage.equals("tv"));
        assertTrue(BuildConfig.DEBUG && BuildConfig.ACCOUNT_SERVICE_URL.isEmpty());
        AccountSession.logout();
        try(var screen=ActivityScenario.launch(PddAccountActivity.class)) {
            screen.onActivity(a->((EditText)a.findViewById(R.id.server)).setText(LOCAL));
            if(stage.equals("tv")) {
                screen.onActivity(a->a.findViewById(R.id.refreshQr).performClick());
                String address = readCode(screen);
                assertTrue(address.matches("http://127[.]0[.]0[.]1:18782/tv#[A-Za-z0-9_-]{43}"));
                Files.write(file("pair-display").toPath(),address.getBytes(StandardCharsets.UTF_8));
                screenshot("pair-tv-ui");
                waitLogin(95000);
            } else {
                screen.onActivity(a->{
                    ((EditText)a.findViewById(R.id.username)).setText("personal_fixture");
                    ((EditText)a.findViewById(R.id.password)).setText("fixture-local-accounts-test");
                    a.findViewById(R.id.login).performClick();
                });
                waitLogin(20000);
                String address=new String(Files.readAllBytes(file("pair-display").toPath()),StandardCharsets.UTF_8);
                var monitor=inst.addMonitor("com.fongmi.android.tv.ui.activity.ScanActivity",
                    new Instrumentation.ActivityResult(Activity.RESULT_OK,new Intent().putExtra("address",address)),true);
                try {
                    screen.onActivity(a->a.findViewById(R.id.scanTv).performClick());
                    boolean confirmed=false;
                    for(int i=0; i<80 && !confirmed; i++) {
                        var root=inst.getUiAutomation().getRootInActiveWindow();
                        if(root!=null)for(var node:root.findAccessibilityNodeInfosByText("确认电视登录"))
                            if(node.isClickable()) confirmed=node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        if(!confirmed)SystemClock.sleep(100);
                    }
                    assertTrue("explicit approval button received scan",confirmed);
                    SystemClock.sleep(1200);
                    assertEquals(1,monitor.getHits());
                } finally {inst.removeMonitor(monitor);}
            }
            assertEquals("personal_fixture",AccountSession.username());
        }
    }

    private File file(String name){return new File(inst.getTargetContext().getFilesDir(),name);}

    private void screenshot(String name)throws Exception {
        Bitmap image=inst.getUiAutomation().takeScreenshot();
        if(image!=null)try(var output=new java.io.FileOutputStream(file(name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,output);image.recycle();}
    }

    private String readCode(ActivityScenario<PddAccountActivity> screen)throws Exception {
        Bitmap[] image={null};
        for(int i=0;i<120 && image[0]==null;i++){
            SystemClock.sleep(100);
            screen.onActivity(a->{
                var qr=(ImageView)a.findViewById(R.id.qr);
                if(qr.getDrawable() instanceof BitmapDrawable drawable)image[0]=drawable.getBitmap();
            });
        }
        assertNotNull("TV rendered a login QR",image[0]);
        int w=image[0].getWidth(), h=image[0].getHeight();int[] pixels=new int[w*h];
        image[0].getPixels(pixels,0,w,0,0,w,h);
        return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(w,h,pixels)))).getText();
    }

    private void waitLogin(long limit) {
        long until=SystemClock.elapsedRealtime()+limit;
        while(AccountSession.profile().isEmpty() && SystemClock.elapsedRealtime()<until)SystemClock.sleep(100);
        assertFalse("account session committed",AccountSession.profile().isEmpty());
        inst.waitForIdleSync();
    }
}
