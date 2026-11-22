package net.jnet.host;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.view.WindowManager;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/**
 * Prism jNet shell. UI and gesture physics come from the user's Prism JS v3 study.
 * The existing HostService remains the only proxy and Wi-Fi implementation.
 * MainActivity is retained as an advanced/classic fallback.
 */
public final class PrismActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView web;
    private InAppUpdater updater;
    private boolean pageReady;
    private String pendingShare;
    private String proxyPassword = "";
    private boolean mobileOnly;
    private long lastBytesOut, lastBytesIn, lastSampleAt;
    private float sentRate, receivedRate;
    private SessionStats lastStats;
    private static final int REQUEST_NEARBY = 10, REQUEST_NOTIFICATIONS = 11;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            pushStatus();
            handler.postDelayed(this, 500);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xff121214);
        getWindow().setNavigationBarColor(0xff121214);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        mobileOnly = oldPrefs().getBoolean("mobileOnly", false);
        web = new WebView(this);
        web.setBackgroundColor(0xff121214);
        web.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        web.setLayerType(WebView.LAYER_TYPE_HARDWARE, null);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportZoom(false);
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "Native");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !request.getUrl().toString().startsWith("file:///android_asset/");
            }
            @Override public void onPageFinished(WebView view, String url) {
                pageReady = url.equals("file:///android_asset/jnet-prism.html");
                if (pageReady) {
                    pushConfig();
                    pushStatus();
                }
            }
        });
        setContentView(web);
        web.loadUrl("file:///android_asset/jnet-prism.html");
        updater = new InAppUpdater(this);
        updater.onCreate(state == null);
    }

    private android.content.SharedPreferences oldPrefs() {
        // Activity.getPreferences in the classic MainActivity uses this name.
        return getSharedPreferences("MainActivity", MODE_PRIVATE);
    }

    @Override protected void onResume() {
        super.onResume();
        if (updater != null) updater.onResume();
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        if (updater != null) updater.onPause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(refresh);
        if (web != null) {
            web.removeJavascriptInterface("Native");
            web.destroy();
        }
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (web == null || !pageReady) {
            super.onBackPressed();
            return;
        }
        web.evaluateJavascript("window.jNet ? window.jNet.handleBack() : false", value -> {
            if ("false".equals(value)) PrismActivity.super.onBackPressed();
        });
    }

    private void send(String js) {
        if (pageReady && web != null && !isDestroyed()) web.evaluateJavascript(js, null);
    }

    private void pushConfig() {
        try {
            JSONObject config = new JSONObject();
            config.put("mobileOnly", mobileOnly);
            config.put("proxyPassword", proxyPassword);
            config.put("wifiPassword", HostService.wifiPassword(this));
            config.put("wifiBand", HostService.bandIndex(oldPrefs().getInt("wifiBand", 0)));
            config.put("units", oldPrefs().getBoolean("trafficMegabits", false) ? "Mbps" : "MB/s");
            config.put("version", getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
            send("window.jNet && window.jNet.config(" + config + ")");
        } catch (Exception exception) {
            android.util.Log.e("jNet", "Could not show sharing settings", exception);
        }
    }

    private void pushStatus() {
        if (!pageReady) return;
        try {
            SessionStats stats = HostService.stats;
            long now = android.os.SystemClock.elapsedRealtime();
            long out = stats.sent.get();
            long in = stats.received.get();
            if (lastStats != stats) {
                lastStats = stats;
                lastBytesOut = out;
                lastBytesIn = in;
                lastSampleAt = now;
                sentRate = receivedRate = 0;
            } else if (now > lastSampleAt + 350) {
                float seconds = (now - lastSampleAt) / 1000f;
                sentRate = Math.max(0, (out - lastBytesOut) / seconds);
                receivedRate = Math.max(0, (in - lastBytesIn) / seconds);
                lastBytesOut = out;
                lastBytesIn = in;
                lastSampleAt = now;
            }
            JSONObject payload = new JSONObject();
            payload.put("active", HostService.active);
            payload.put("status", HostService.status);
            payload.put("mode", HostService.diagnosticMode);
            payload.put("details", HostService.details);
            payload.put("upstream", HostService.upstreamStatus);
            payload.put("endpoint", HostService.endpoint);
            payload.put("portalUrl", HostService.portalUrl);
            payload.put("sent", out);
            payload.put("received", in);
            payload.put("sentRate", Math.round(sentRate));
            payload.put("receivedRate", Math.round(receivedRate));
            payload.put("connections", stats.active.get());
            payload.put("accepted", stats.accepted.get());
            payload.put("devices", stats.devices());
            payload.put("log", stats.log());
            payload.put("lastUdp", stats.lastUdp.get());
            payload.put("startedAt", HostService.startedAt);
            send("window.jNet && window.jNet.receive(" + payload + ")");
        } catch (Exception error) {
            android.util.Log.w("jNet", "Could not refresh dashboard", error);
        }
    }

    private void start(String mode) {
        if (HostService.active) {
            toast("Stop the current connection before switching modes.");
            return;
        }
        if (mode.equals(HostService.WIFI) &&
                checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}, REQUEST_NEARBY);
            return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                !oldPrefs().getBoolean("askedForNotifications", false)) {
            pendingShare = mode;
            oldPrefs().edit().putBoolean("askedForNotifications", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            return;
        }
        startForegroundService(new Intent(this, HostService.class).setAction(mode)
                .putExtra("wifiBand", oldPrefs().getInt("wifiBand", 0))
                .putExtra("proxyPassword", proxyPassword)
                .putExtra("mobileOnly", mobileOnly));
        send("window.jNet && window.jNet.connectionRequested()");
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_NOTIFICATIONS && pendingShare != null) {
            String mode = pendingShare;
            pendingShare = null;
            start(mode);
        } else if (requestCode == REQUEST_NEARBY) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)
                start(HostService.WIFI);
            else toast("Nearby devices permission is needed for Wi-Fi Direct.");
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void copy(String type) {
        String value = switch (type) {
            case "endpoint" -> HostService.endpoint;
            case "portal" -> HostService.portalUrl;
            default -> "";
        };
        if (value.isBlank()) { toast("Start sharing first."); return; }
        getSystemService(ClipboardManager.class).setPrimaryClip(
            ClipData.newPlainText(type.equals("portal") ? "jNet setup page" : "jNet proxy", value));
        toast("Copied");
    }

    private void showQr(String value, String title, boolean includesPassword) {
        try {
            var matrix = new com.google.zxing.qrcode.QRCodeWriter()
                    .encode(value, com.google.zxing.BarcodeFormat.QR_CODE, 640, 640);
            int[] pixels = new int[matrix.getWidth() * matrix.getHeight()];
            for (int y = 0; y < matrix.getHeight(); y++)
                for (int x = 0; x < matrix.getWidth(); x++)
                    pixels[y * matrix.getWidth() + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            ImageView image = new ImageView(this);
            image.setImageBitmap(android.graphics.Bitmap.createBitmap(
                    pixels, matrix.getWidth(), matrix.getHeight(), android.graphics.Bitmap.Config.ARGB_8888));
            image.setAdjustViewBounds(true);
            LinearLayout wrapper = new LinearLayout(this);
            wrapper.setOrientation(LinearLayout.VERTICAL);
            int padding = Math.round(18 * getResources().getDisplayMetrics().density);
            wrapper.setPadding(padding, padding, padding, padding);
            if (includesPassword) {
                android.widget.TextView caption = new android.widget.TextView(this);
                caption.setText("This QR includes the proxy password.");
                wrapper.addView(caption);
            }
            wrapper.addView(image);
            new AlertDialog.Builder(this).setTitle(title).setView(wrapper)
                    .setPositiveButton("Done", null).show();
        } catch (Exception e) {
            toast("Could not create QR code.");
        }
    }

    private void shareDiagnostics() {
        SessionStats stats = HostService.stats;
        String report = "jNet diagnostics\nMode: " + HostService.diagnosticMode +
            "\nStatus: " + HostService.status +
            "\nProxy: " + HostService.diagnosticEndpoint +
            "\nUpstream: " + HostService.upstreamStatus +
            "\nSent: " + stats.sent.get() + " bytes" +
            "\nReceived: " + stats.received.get() + " bytes" +
            "\nConnections: " + stats.active.get() + " active, " + stats.accepted.get() + " total" +
            "\nDevices: " + stats.devices() + "\n\nRecent events\n" + stats.log();
        new AlertDialog.Builder(this).setTitle("Review diagnostics")
            .setMessage(report)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Share", (dialog, which) -> {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, report);
                startActivity(Intent.createChooser(send, "Share jNet diagnostics"));
            }).show();
    }

    private void saveSettings(String json) {
        if (HostService.active) { toast("Stop sharing before changing settings."); return; }
        try {
            JSONObject data = new JSONObject(json);
            String socks = data.optString("proxyPassword", "");
            String wifiPass = data.optString("wifiPassword", "");
            int band = data.optInt("wifiBand", 0);
            if (socks.getBytes(StandardCharsets.UTF_8).length > 255) {
                toast("Proxy password is too long."); return;
            }
            if (!HostService.validWifiPassword(wifiPass)) {
                toast("Wi-Fi Direct password must be 8 to 63 printable characters."); return;
            }
            if (band < 0 || band > 2) { toast("Select a Wi-Fi band."); return; }
            mobileOnly = data.optBoolean("mobileOnly", false);
            proxyPassword = socks;
            oldPrefs().edit().putBoolean("mobileOnly", mobileOnly).putInt("wifiBand", band).apply();
            getSharedPreferences("host", MODE_PRIVATE).edit().putString("password", wifiPass).apply();
            pushConfig();
            send("window.jNet && window.jNet.saved()");
        } catch (Exception exception) {
            toast("Couldn't save settings.");
        }
    }

    private final class Bridge {
        @JavascriptInterface public void start(String mode) {
            if (!HostService.WIFI.equals(mode) && !HostService.LAN.equals(mode)) return;
            runOnUiThread(() -> PrismActivity.this.start(mode));
        }
        @JavascriptInterface public void stop() {
            runOnUiThread(() -> stopService(new Intent(PrismActivity.this, HostService.class)));
        }
        @JavascriptInterface public void copy(String type) {
            runOnUiThread(() -> PrismActivity.this.copy(type));
        }
        @JavascriptInterface public void qr(String type) {
            runOnUiThread(() -> {
                if ("portal".equals(type)) {
                    if (HostService.portalUrl.isBlank()) { toast("Start sharing first."); return; }
                    showQr(HostService.portalUrl, "Connect another device", false);
                } else if ("proxy".equals(type)) {
                    if (HostService.endpoint.isBlank()) { toast("Start sharing first."); return; }
                    String password = HostService.proxyPassword;
                    String credentials = password.isEmpty() ? "" : "jnet:" + Uri.encode(password) + "@";
                    showQr("socks5://" + credentials + HostService.endpoint + "#jNet", "Scan to add jNet", !password.isEmpty());
                }
            });
        }
        @JavascriptInterface public void openPortal() {
            runOnUiThread(() -> {
                String url = HostService.portalUrl;
                if (url.startsWith("http://")) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
                    catch (Exception e) { toast("No browser available."); }
                } else toast("Start sharing first.");
            });
        }
        @JavascriptInterface public void save(String json) {
            runOnUiThread(() -> saveSettings(json));
        }
        @JavascriptInterface public void units(String unit) {
            if (!"Mbps".equals(unit) && !"MB/s".equals(unit)) return;
            runOnUiThread(() -> {
                oldPrefs().edit().putBoolean("trafficMegabits", "Mbps".equals(unit)).apply();
                pushConfig();
            });
        }
        @JavascriptInterface public void diagnostics() {
            runOnUiThread(() -> shareDiagnostics());
        }
        @JavascriptInterface public void advanced() {
            runOnUiThread(() -> startActivity(new Intent(PrismActivity.this, MainActivity.class)));
        }
    }
}

