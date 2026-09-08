package com.xrvpn.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

/**
 * Fullscreen WebView UI + JS bridge.
 * Connect requests VPN permission; system then treats the app as active VPN.
 */
public class MainActivity extends Activity {
    public static final int REQ_VPN = 1001;
    private WebView web;
    private String pendingJson;
    private volatile boolean pendingResult;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        web.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "XRVpn");
        web.loadUrl("file:///android_asset/index.html");
    }

    private void notifyJs(boolean ok) {
        final String js = "window.__xrVpnNativeResult && window.__xrVpnNativeResult(" + ok + ")";
        web.post(() -> web.evaluateJavascript(js, null));
    }

    private void startServiceConnect(String json) {
        Intent i = new Intent(this, XrVpnService.class);
        i.setAction(XrVpnService.ACTION_CONNECT);
        i.putExtra(XrVpnService.EXTRA_CONFIG, json);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    private void startServiceDisconnect() {
        Intent i = new Intent(this, XrVpnService.class);
        i.setAction(XrVpnService.ACTION_DISCONNECT);
        startService(i);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_VPN) return;
        if (resultCode == RESULT_OK && pendingJson != null) {
            startServiceConnect(pendingJson);
            notifyJs(true);
            Toast.makeText(this, "VPN connected", Toast.LENGTH_SHORT).show();
        } else {
            notifyJs(false);
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show();
        }
        pendingJson = null;
    }

    public class Bridge {
        @JavascriptInterface
        public boolean connect(String json) {
            pendingJson = json;
            Intent prepare = VpnService.prepare(MainActivity.this);
            if (prepare != null) {
                // System VPN consent dialog — app becomes VPN provider after OK
                runOnUiThread(() -> startActivityForResult(prepare, REQ_VPN));
                return true;
            }
            // Already authorized
            startServiceConnect(json);
            runOnUiThread(() -> {
                notifyJs(true);
                Toast.makeText(MainActivity.this, "VPN connected", Toast.LENGTH_SHORT).show();
            });
            return true;
        }

        @JavascriptInterface
        public boolean disconnect() {
            startServiceDisconnect();
            runOnUiThread(() ->
                    Toast.makeText(MainActivity.this, "VPN disconnected", Toast.LENGTH_SHORT).show());
            return true;
        }

        @JavascriptInterface
        public String status() {
            return XrVpnService.isRunning() ? "connected" : "idle";
        }

        @JavascriptInterface
        public boolean isVpnActive() {
            return XrVpnService.isRunning();
        }
    }
}
