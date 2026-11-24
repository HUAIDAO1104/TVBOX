package com.fongmi.android.tv.ui.activity;

import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.fongmi.android.tv.history.AccountSession;
import com.google.gson.JsonObject;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

public class AccountWebLoginTest {
    @Test public void webFormReturnsIndependentSession() throws Exception {
        assumeTrue("1".equals(InstrumentationRegistry.getArguments().getString("webAccountTest")));
        String server="http://127.0.0.1:18782";
        AtomicReference<JsonObject> result=new AtomicReference<>();
        AtomicReference<WebView> web=new AtomicReference<>();
        try(var screen=ActivityScenario.launch(PddAccountActivity.class)) {
            screen.onActivity(a->{
                AccountWebLogin dialog=new AccountWebLogin(a,server,result::set);
                dialog.show();
                web.set(findWeb(dialog.findViewById(android.R.id.content)));
            });
            assertNotNull(web.get());
            for(int i=0;i<160 && result.get()==null;i++) {
                screen.onActivity(a->{
                    WebView v=web.get();
                    if(result.get()!=null || !(server+"/app-login").equals(v.getUrl()))return;
                    v.evaluateJavascript("(function(){var f=document.forms[0];if(!f||window.submitted)return;window.submitted=true;f.elements.username.value='personal_fixture';f.elements.password.value='fixture-local-accounts-test';f.submit();})()",null);
                });
                SystemClock.sleep(150);
            }
            assertNotNull("official form handed its response back",result.get());
            assertFalse("web form accepted the test credentials",result.get().has("error"));
            AccountSession.accept(server,result.get());
            assertEquals("personal_fixture",AccountSession.username());
            AccountSession.logout();
        }
    }
    private WebView findWeb(View v) {
        if(v instanceof WebView w)return w;
        if(v instanceof ViewGroup g)for(int i=0;i<g.getChildCount();i++) {
            WebView w=findWeb(g.getChildAt(i));if(w!=null)return w;
        }
        return null;
    }
}
