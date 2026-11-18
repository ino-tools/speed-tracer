package com.ino.speedtracer;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * SpeedTracer の Android アプリ。
 * 中身は GitHub Pages のアプリ(speed_tracer_pc.html)をそのまま表示し、ブラウザでは使えない部分
 * (Bluetooth・ファイル保存・SpeedTracer の WiFi への通信)をアプリ側で補う。
 * ページは公開版を読み込むので、アプリ自体を更新しなくても中身は最新になる。
 */
public class MainActivity extends Activity {

    static final String APP_URL = "https://ino-tools.github.io/speed-tracer/speed_tracer_pc.html";
    static final String[] ALLOWED_HOSTS = {"ino-tools.github.io", "192.168.4.1"};

    static final int REQ_FILE = 11;
    static final int REQ_PERM_LOCATION = 21;

    WebView web;
    BleBridge ble;
    String bridgeJs;
    ValueCallback<Uri[]> fileCallback;
    GeolocationPermissions.Callback geoCallback;
    String geoOrigin;
    ConnectivityManager.NetworkCallback wifiCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); // 走行中に画面が消えないように

        bridgeJs = readAsset("bridge.js");
        web = new WebView(this);
        web.setBackgroundColor(0xFF0A0C0F);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW); // https のページから ws://192.168.4.1 へつなぐため
        s.setUserAgentString(s.getUserAgentString() + " SpeedTracerApp/" + BuildConfigCompat.versionName(this));
        WebView.setWebContentsDebuggingEnabled(true);

        ble = new BleBridge(this, web);
        web.addJavascriptInterface(new JsApi(), "STAndroid");
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, bridgeJs, Collections.singleton("*"));
        }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                // 古い WebView 向けの予備(ページより先に入れられない場合)
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    view.evaluateJavascript(bridgeJs, null);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String host = u.getHost() == null ? "" : u.getHost();
                for (String h : ALLOWED_HOSTS) if (host.equals(h)) return false;
                // それ以外(GitHub など)はブラウザで開く
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (ActivityNotFoundException ignored) { }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) {
                    String html = "<html><body style='background:#0A0C0F;color:#E7E9EA;font-family:sans-serif;padding:24px;line-height:1.7'>"
                            + "<h3>アプリを読み込めませんでした</h3>"
                            + "<p>初めて開くときはインターネット(モバイル通信など)が必要です。"
                            + "一度開けば、次からは通信なしでも使えます。</p>"
                            + "<p><a style='color:#4FD1C5' href='" + APP_URL + "'>もう一度読み込む</a></p></body></html>";
                    view.loadDataWithBaseURL(APP_URL, html, "text/html", "utf-8", APP_URL);
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (hasLocationPermission()) { callback.invoke(origin, true, false); return; }
                geoOrigin = origin; geoCallback = callback;
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_PERM_LOCATION);
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) { request.deny(); }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*"); // CSV や JSON は端末によって種類の判定がまちまちなので全部を出す
                try {
                    startActivityForResult(Intent.createChooser(i, "ファイルを選ぶ"), REQ_FILE);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null; return false;
                }
                return true;
            }
        });

        watchSpeedTracerWifi();

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(APP_URL);
    }

    // SpeedTracer の WiFi(192.168.4.x、インターネットなし)につながっている間は、通信をその WiFi に向ける。
    // こうしないと Android が通信をモバイル回線に流してしまい、192.168.4.1 に届かないことがある。
    void watchSpeedTracerWifi() {
        final ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) return;
        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        wifiCallback = new ConnectivityManager.NetworkCallback() {
            Network bound;
            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties lp) {
                boolean st = false;
                for (LinkAddress a : lp.getLinkAddresses()) {
                    if (a.getAddress() instanceof Inet4Address && a.getAddress().getHostAddress().startsWith("192.168.4.")) st = true;
                }
                if (st && !network.equals(bound)) { cm.bindProcessToNetwork(network); bound = network; }
                else if (!st && network.equals(bound)) { cm.bindProcessToNetwork(null); bound = null; }
            }
            @Override
            public void onLost(Network network) {
                if (network.equals(bound)) { cm.bindProcessToNetwork(null); bound = null; }
            }
        };
        try { cm.registerNetworkCallback(req, wifiCallback); } catch (Exception ignored) { }
    }

    boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM_LOCATION && geoCallback != null) {
            geoCallback.invoke(geoOrigin, hasLocationPermission(), false);
            geoCallback = null;
        }
        ble.onPermissionResult(requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && fileCallback != null) {
            Uri[] r = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) r = new Uri[]{data.getData()};
            fileCallback.onReceiveValue(r);
            fileCallback = null;
        }
        ble.onActivityResult(requestCode, resultCode);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        ble.close();
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            if (cm != null && wifiCallback != null) { cm.unregisterNetworkCallback(wifiCallback); cm.bindProcessToNetwork(null); }
        } catch (Exception ignored) { }
        web.destroy();
        super.onDestroy();
    }

    String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) { return ""; }
    }

    void toast(String t) { runOnUiThread(() -> Toast.makeText(this, t, Toast.LENGTH_LONG).show()); }

    /** ページの JavaScript から呼ばれる窓口(window.STAndroid) */
    class JsApi {
        @JavascriptInterface
        public void ble(String method, String argsJson, int cbId) { ble.call(method, argsJson, cbId); }

        @JavascriptInterface
        public boolean bleAvailable() { return ble.available(); }

        @JavascriptInterface
        public void toast(String t) { MainActivity.this.toast(t); }

        @JavascriptInterface
        public String appVersion() { return BuildConfigCompat.versionName(MainActivity.this); }

        /** ページで作った CSV / JSON を「ダウンロード」フォルダに保存する */
        @JavascriptInterface
        public void saveFile(String name, String mime, String b64) {
            try {
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, safe);
                    v.put(MediaStore.Downloads.MIME_TYPE, mime);
                    v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SpeedTracer");
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) throw new IllegalStateException("保存先を作れません");
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) { os.write(data); }
                    MainActivity.this.toast("ダウンロード/SpeedTracer に保存しました: " + safe);
                } else {
                    File dir = new File(getExternalFilesDir(null), "SpeedTracer");
                    dir.mkdirs();
                    File f = new File(dir, safe);
                    try (FileOutputStream os = new FileOutputStream(f)) { os.write(data); }
                    MainActivity.this.toast("保存しました: " + f.getAbsolutePath());
                }
            } catch (Exception e) {
                MainActivity.this.toast("保存できませんでした: " + e.getMessage());
            }
        }
    }
}
