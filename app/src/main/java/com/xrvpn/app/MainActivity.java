package com.xeetr.xrvpn;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Environment;
import android.util.TypedValue;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;

public class MainActivity extends Activity {

    private static final int VPN_REQUEST_CODE = 1;
    private String pendingConfig = null;
    private WebView webView;

    // ── Ловушка краша — ставим ДО всего остального ───────────────────────────
    private void installCrashHandler() {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {

            // Собираем стектрейс в строку
            StringWriter sw = new StringWriter();
            throwable.printStackTrace(new PrintWriter(sw));
            String trace = sw.toString();

            // Пишем в файл на SD-карту (читай через MT Manager)
            try {
                File f = new File(Environment.getExternalStorageDirectory(), "xrvpn_crash.txt");
                FileWriter fw = new FileWriter(f, false);
                fw.write(trace);
                fw.close();
            } catch (Exception ignored) {}

            // Показываем прямо на экране
            runOnUiThread(() -> {
                TextView tv = new TextView(this);
                tv.setText("CRASH:\n\n" + trace);
                tv.setTextColor(Color.RED);
                tv.setBackgroundColor(Color.BLACK);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                tv.setPadding(16, 16, 16, 16);

                ScrollView sv = new ScrollView(this);
                sv.addView(tv);
                setContentView(sv);
            });
        });
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        installCrashHandler(); // ← первым делом
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.addJavascriptInterface(new XRVpnBridge(), "XRVpn");
        webView.setWebViewClient(new WebViewClient());
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webView != null) webView.destroy();
    }

    // ── VPN permission result ─────────────────────────────────────────────────

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

    // ── VPN start / stop ──────────────────────────────────────────────────────

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
        startService(i);
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

    // ── JS bridge ─────────────────────────────────────────────────────────────

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

