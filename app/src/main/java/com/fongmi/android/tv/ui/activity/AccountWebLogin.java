package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;

import com.fongmi.android.tv.BuildConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.function.Consumer;

/** A user-initiated first-party login. Only the exact HTTPS form result can complete it. */
final class AccountWebLogin extends Dialog {
    private final Consumer<JsonObject> result;
    private final String origin;
    private WebView web;
    private boolean delivered;

    AccountWebLogin(Activity activity, Consumer<JsonObject> result) {
        this(activity, BuildConfig.PERSONAL_ACCOUNT_URL, result);
    }

    AccountWebLogin(Activity activity, String origin, Consumer<JsonObject> result) {
        super(activity, android.R.style.Theme_Material_NoActionBar);
        if (!origin.equals(BuildConfig.PERSONAL_ACCOUNT_URL) && !(BuildConfig.DEBUG && origin.equals("http://127.0.0.1:18782")))
            throw new IllegalArgumentException("Untrusted account origin");
        this.origin = origin;
        this.result = result;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout panel = new LinearLayout(getContext());
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.rgb(16,19,24));
        Button back = new Button(getContext());
        back.setText("返回片多多");
        back.setOnClickListener(v -> dismiss());
        panel.addView(back, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        web = new WebView(getContext());
        panel.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(panel);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String url = req.getUrl().toString();
                return req.isForMainFrame() && !url.equals(origin + "/app-login") && !url.equals(origin + "/v1/browser-login");
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (!url.equals(origin + "/v1/browser-login") || !url.equals(view.getUrl()) || delivered) return;
                view.evaluateJavascript("(function(){var n=document.getElementById('pdd-result');return n?n.textContent:null})()", raw -> {
                    if (!isShowing() || delivered || !url.equals(view.getUrl())) return;
                    JsonObject value;
                    try { value = JsonParser.parseString(JsonParser.parseString(raw).getAsString()).getAsJsonObject(); }
                    catch (RuntimeException e) {
                        value = new JsonObject();
                        value.addProperty("error","网页未能完成登录，请检查网络后重试");
                    }
                    delivered = true;
                    dismiss();
                    result.accept(value);
                });
            }
        });
        web.loadUrl(origin + "/app-login");
    }

    @Override public void dismiss() {
        super.dismiss();
        if (web != null) {
            web.stopLoading();
            ((ViewGroup) web.getParent()).removeView(web);
            web.destroy();
            web = null;
        }
    }
}
