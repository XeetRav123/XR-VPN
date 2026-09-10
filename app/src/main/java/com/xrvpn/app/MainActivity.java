package com.xeetr.xrvpn;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {

    // Код запроса разрешения на VPN
    private static final int VPN_REQUEST_CODE = 1;

    // Конфиг, ожидающий разрешения пользователя
    private String pendingConfig = null;

    private WebView webView;

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        // Настройка WebView
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        // JS-мост: в index.html доступны XRVpn.connect(...) и XRVpn.disconnect()
        webView.addJavascriptInterface(new XRVpnBridge(), "XRVpn");

        webView.setWebViewClient(new WebViewClient());

        // Загружаем UI из assets/index.html
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webView != null) webView.destroy();
    }

    // ── Обработка результата запроса разрешения VPN ────────────────────────

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                // Пользователь разрешил — запускаем сервис
                startVpn(pendingConfig);
            } else {
                // Отказал — уведомляем WebView
                runOnUiThread(() ->
                    webView.evaluateJavascript("if(window.onVpnDenied) onVpnDenied();", null)
                );
            }
            pendingConfig = null;
        }
    }

    // ── Запуск / остановка сервиса ─────────────────────────────────────────

    private void requestAndStart(String configJson) {
        // Проверяем, нужно ли запрашивать разрешение
        Intent intent = VpnService.prepare(this);
        if (intent != null) {
            // Нужно разрешение — запрашиваем и сохраняем конфиг
            pendingConfig = configJson;
            startActivityForResult(intent, VPN_REQUEST_CODE);
        } else {
            // Разрешение уже есть — сразу запускаем
            startVpn(configJson);
        }
    }

    private void startVpn(String configJson) {
        Intent i = new Intent(this, XrVpnService.class);
        i.setAction(XrVpnService.ACTION_CONNECT);
        i.putExtra(XrVpnService.EXTRA_CONFIG, configJson);
        startService(i);

        // Уведомляем WebView что VPN запущен
        runOnUiThread(() ->
            webView.evaluateJavascript("if(window.onVpnStarted) onVpnStarted();", null)
        );
    }

    private void stopVpn() {
        Intent i = new Intent(this, XrVpnService.class);
        i.setAction(XrVpnService.ACTION_DISCONNECT);
        startService(i);

        // Уведомляем WebView что VPN остановлен
        runOnUiThread(() ->
            webView.evaluateJavascript("if(window.onVpnStopped) onVpnStopped();", null)
        );
    }

    // ── JS-мост ────────────────────────────────────────────────────────────

    /**
     * Используй из index.html так:
     *
     *   XRVpn.connect('{"routing":{"mode":"full"}}');
     *   XRVpn.disconnect();
     */
    private class XRVpnBridge {

        /**
         * Запустить VPN.
         * @param configJson  JSON-строка с полем routing.mode и опционально routing.list
         */
        @JavascriptInterface
        public void connect(String configJson) {
            runOnUiThread(() -> requestAndStart(configJson));
        }

        /**
         * Остановить VPN.
         */
        @JavascriptInterface
        public void disconnect() {
            runOnUiThread(() -> stopVpn());
        }

        /**
         * Вернуть текущий статус (можно вызвать из JS).
         * @return "connected" или "disconnected"
         */
        @JavascriptInterface
        public String getStatus() {
            // Простая проверка — можно расширить через статическое поле в XrVpnService
            return XrVpnService.isRunning() ? "connected" : "disconnected";
        }
    }
}
