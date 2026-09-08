package com.xrvpn.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {
    public static final int REQ_VPN = 1001;
    private WebView web;
    private String pendingJson;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        hideSystemBars();

        web = new WebView(this);
        web.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "XRVpn");
        web.loadUrl("file:///android_asset/index.html");
    }

    private void hideSystemBars() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            final View decor = getWindow().getDecorView();
            decor.setOnApplyWindowInsetsListener((v, insets) -> {
                v.setBackgroundColor(0xFF050505);
                return insets;
            });
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
        getWindow().setStatusBarColor(0x00000000);
        getWindow().setNavigationBarColor(0xFF050505);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    private void notifyJs(boolean ok) {
        final String js = "window.__xrVpnNativeResult && window.__xrVpnNativeResult(" + ok + ")";
        web.post(() -> web.evaluateJavascript(js, null));
    }

    private void startServiceConnect(String json) {
        Intent i = new Intent(this, XrVpnService.class);
        i.setAction(XrVpnService.ACTION_CONNECT);
        i.putExtra(XrVpnService.EXTRA_CONFIG, json);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
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
                runOnUiThread(() -> startActivityForResult(prepare, REQ_VPN));
                return true;
            }
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

        /** Список установленных приложений для split-tunnel (bypass apps) */
        @JavascriptInterface
        public String listApps() {
            JSONArray arr = new JSONArray();
            try {
                PackageManager pm = getPackageManager();
                List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
                Collections.sort(apps, (a, b) -> {
                    String la = pm.getApplicationLabel(a).toString();
                    String lb = pm.getApplicationLabel(b).toString();
                    return la.compareToIgnoreCase(lb);
                });
                String self = getPackageName();
                for (ApplicationInfo info : apps) {
                    // user apps + launchable; skip self
                    if (self.equals(info.packageName)) continue;
                    boolean isSystem = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                    // include non-system always; system only if launchable
                    Intent launch = pm.getLaunchIntentForPackage(info.packageName);
                    if (isSystem && launch == null) continue;
                    JSONObject o = new JSONObject();
                    o.put("package", info.packageName);
                    o.put("name", pm.getApplicationLabel(info).toString());
                    o.put("system", isSystem);
                    arr.put(o);
                }
            } catch (Exception e) {
                // return empty
            }
            return arr.toString();
        }
    }
}
