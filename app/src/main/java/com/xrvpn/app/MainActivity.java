package com.xrvpn.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ScrollView;
import android.widget.TextView;
import android.util.TypedValue;

import java.io.PrintWriter;
import java.io.StringWriter;

public class MainActivity extends Activity {

    private static final int VPN_REQUEST_CODE = 1;
    private String pendingConfig = null;
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            hideSystemBars();

            webView = new WebView(this);
            setContentView(webView);

            WebSettings s = webView.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setAllowFileAccess(true);
            s.setCacheMode(WebSettings.LOAD_DEFAULT);
            if (Build.VERSION.SDK_INT >= 21) {
                s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            }

            webView.addJavascriptInterface(new XRVpnBridge(), "XRVpn");
            webView.setWebViewClient(new WebViewClient());
            webView.loadUrl("file:///android_asset/index.html");

        } catch (Throwable e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            TextView tv = new TextView(this);
            tv.setText("CRASH:\n\n" + sw);
            tv.setTextColor(Color.RED);
            tv.setBackgroundColor(Color.BLACK);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            tv.setPadding(16, 16, 16, 16);
            ScrollView sv = new ScrollView(this);
            sv.addView(tv);
            setContentView(sv);
        }
    }

    private void hideSystemBars() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(0xFF050505);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webView != null) webView.destroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                startVpn(pendingConfig);
            } else {
                runOnUiThread(() ->
                    webView.evaluateJavascript("if(window.onVpnDenied) onVpnDenied();", null)
                );
            }
            pendingConfig = null;
        }
    }

    private void requestAndStart(String configJson) {
        Intent intent = VpnService.prepare(this);
        if (intent != null) {
            pendingConfig = configJson;
            startActivityForResult(intent, VPN_REQUEST_CODE);
        } else {
            startVpn(configJson);
        }
    }

    private void startVpn(String configJson) {
        Intent i = new Intent(this, XrVpnService.class);
        i.setAction(XrVpnService.ACTION_CONNECT);
        i.putExtra(XrVpnService.EXTRA_CONFIG, configJson);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        runOnUiThread(() ->
            webView.evaluateJavascript("if(window.onVpnStarted) onVpnStarted();", null)
        );
    }

    private void stopVpn() {
        Intent i = new Intent(this, XrVpnService.class);
        i.setAction(XrVpnService.ACTION_DISCONNECT);
        startService(i);
        runOnUiThread(() ->
            webView.evaluateJavascript("if(window.onVpnStopped) onVpnStopped();", null)
        );
    }

    private class XRVpnBridge {
        @JavascriptInterface
        public void connect(String configJson) {
            runOnUiThread(() -> requestAndStart(configJson));
        }

        @JavascriptInterface
        public void disconnect() {
            runOnUiThread(() -> stopVpn());
        }

        @JavascriptInterface
        public String getStatus() {
            return XrVpnService.isRunning() ? "connected" : "disconnected";
        }
    }
}
