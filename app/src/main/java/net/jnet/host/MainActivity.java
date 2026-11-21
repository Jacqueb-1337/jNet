package net.jnet.host;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.*;
import android.view.*;
import android.widget.*;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status, details;
    private EditText address;
    private Button wifi, lan, vpn, stop, copyEndpoint;
    private Button setupQr;
    private Spinner wifiBand;
    private static final int INK = 0xfff1f0df, MUTED = 0xffb5b8ab, ACCENT = 0xffd4ec7d;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xff1b201c);
        getWindow().setNavigationBarColor(0xff1b201c);
        var scroll = new ScrollView(this);
        var content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(0xff1b201c);
        int horizontal = dp(24);
        content.setPadding(horizontal, dp(32), horizontal, dp(28));
        scroll.setFillViewport(true);
        scroll.addView(content);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            var bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });
        setContentView(scroll);

        var title = label("jNet", 38, INK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        add(content, title, 0);
        status = label("", 16, ACCENT);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(content, status, 10);
        details = label("", 16, INK);
        details.setTypeface(Typeface.MONOSPACE);
        details.setTextIsSelectable(true);
        details.setLineSpacing(dp(6), 1);
        add(content, details, 12);
        copyEndpoint = button("Copy proxy address");
        add(content, copyEndpoint, 8);
        copyEndpoint.setOnClickListener(view -> {
            if (HostService.endpoint.isEmpty()) return;
            getSystemService(android.content.ClipboardManager.class).setPrimaryClip(
                android.content.ClipData.newPlainText("jNet proxy address", HostService.endpoint));
        });
        setupQr = button("Show setup QR");
        setupQr.setVisibility(View.GONE);
        add(content, setupQr, 8);
        setupQr.setOnClickListener(view -> showSetupQr());
        add(content, label("Install a SOCKS5 proxy client on the device you want to connect.", 16, MUTED), 12);
        var clientHelp = button("Recommended proxy clients");
        add(content, clientHelp, 8);
        clientHelp.setOnClickListener(view -> new android.app.AlertDialog.Builder(this)
            .setTitle("Connect another device")
            .setMessage("iOS · Potatso\nAndroid · NekoBox for Android (VPN mode)\n"
                + "Windows · Proxifier (paid)\nLinux · ProxyChains-NG (run apps through it)\n\n"
                + "Add a SOCKS5 proxy in the client and enter the address and port shown in jNet. "
                + "Use remote DNS if your client offers it.\n\n"
                + "Current Wi-Fi and Wi-Fi Direct support TCP and UDP. Tailscale supports TCP.")
            .setPositiveButton("Done", null)
            .show());

        add(content, label("Nearby devices", 20, INK), 32);
        add(content, label("Join this phone’s Wi-Fi network, then enter the SOCKS5 address in your client.", 16, MUTED), 6);
        wifiBand = new Spinner(this);
        wifiBand.setContentDescription("Wi-Fi Direct band");
        var bands = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
            new String[]{"Auto", "2.4 GHz", "5 GHz"});
        bands.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        wifiBand.setAdapter(bands);
        wifiBand.setSelection(HostService.bandIndex(getPreferences(MODE_PRIVATE).getInt("wifiBand", 0)));
        wifiBand.setMinimumHeight(dp(48));
        add(content, label("Wi-Fi Direct band", 16, MUTED), 8);
        add(content, wifiBand, 4);
        wifi = button("Start Wi-Fi Direct");
        add(content, wifi, 10);
        wifi.setOnClickListener(view -> {
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED) start(HostService.WIFI);
            else requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}, 10);
        });

        add(content, label("Current Wi-Fi", 20, INK), 26);
        add(content, label("Join the same trusted router on both devices. The phone needs its own internet connection.", 16, MUTED), 6);
        lan = button("Share on current Wi-Fi");
        add(content, lan, 10);
        lan.setOnClickListener(view -> start(HostService.LAN));

        add(content, label("Tailscale", 20, INK), 26);
        add(content, label("Connect Tailscale here and on the client. jNet can find this phone’s VPN address automatically.", 16, MUTED), 6);
        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextColor(INK);
        address.setHintTextColor(MUTED);
        address.setHint("Override IPv4 address (optional)");
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        add(content, address, 8);
        vpn = button("Start tailnet proxy");
        add(content, vpn, 10);
        vpn.setOnClickListener(view -> start(HostService.VPN));

        stop = button("Stop sharing");
        add(content, stop, 20);
        stop.setOnClickListener(view -> stopService(new Intent(this, HostService.class)));
    }

    private void start(String action) {
        String ip = address.getText().toString().trim();
        int band = wifiBand.getSelectedItemPosition();
        if (HostService.WIFI.equals(action)) {
            getPreferences(MODE_PRIVATE).edit().putInt("wifiBand", band).apply();
        }
        startForegroundService(new Intent(this, HostService.class).setAction(action)
            .putExtra("address", ip).putExtra("wifiBand", band));
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == 10 && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) start(HostService.WIFI);
        else if (request == 10) HostService.status = "Allow Nearby devices to start Wi-Fi Direct.";
    }

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!HostService.status.contentEquals(status.getText())) status.setText(HostService.status);
            if (!HostService.details.contentEquals(details.getText())) details.setText(HostService.details);
            details.setVisibility(HostService.details.isEmpty() ? View.GONE : View.VISIBLE);
            copyEndpoint.setVisibility(HostService.endpoint.isEmpty() ? View.GONE : View.VISIBLE);
            setupQr.setVisibility(hasScannableEndpoint() ? View.VISIBLE : View.GONE);
            boolean running = HostService.active;
            wifi.setEnabled(!running);
            wifiBand.setEnabled(!running);
            lan.setEnabled(!running);
            vpn.setEnabled(!running);
            stop.setVisibility(running ? View.VISIBLE : View.GONE);
            handler.postDelayed(this, 300);
        }
    };

    private boolean hasScannableEndpoint() {
        return !HostService.endpoint.isEmpty() && !HostService.endpoint.startsWith("127.0.0.1:");
    }

    private void showSetupQr() {
        if (!hasScannableEndpoint()) return;
        String uri = "socks5://" + HostService.endpoint + "#jNet";
        try {
            var matrix = new com.google.zxing.qrcode.QRCodeWriter()
                .encode(uri, com.google.zxing.BarcodeFormat.QR_CODE, 768, 768);
            int width = matrix.getWidth();
            int height = matrix.getHeight();
            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    pixels[y * width + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            var qr = new ImageView(this);
            qr.setImageBitmap(android.graphics.Bitmap.createBitmap(
                pixels, width, height, android.graphics.Bitmap.Config.ARGB_8888));
            qr.setAdjustViewBounds(true);
            qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
            qr.setContentDescription("SOCKS5 setup code for " + HostService.endpoint);
            var panel = new LinearLayout(this);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(dp(20), dp(8), dp(20), 0);
            add(panel, label("On the other device, open Potatso or NekoBox and use its QR scanner.", 16, INK), 0);
            add(panel, qr, 12);
            new android.app.AlertDialog.Builder(this)
                .setTitle("Scan to add jNet")
                .setView(panel)
                .setPositiveButton("Done", null)
                .show();
        } catch (com.google.zxing.WriterException exception) {
            Toast.makeText(this, "Could not create the setup QR.", Toast.LENGTH_SHORT).show();
        }
    }
    @Override protected void onStart() { super.onStart(); handler.post(refresh); }
    @Override protected void onStop() { handler.removeCallbacks(refresh); super.onStop(); }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView label(String copy, int size, int color) {
        var label = new TextView(this);
        label.setText(copy);
        label.setTextSize(size);
        label.setTextColor(color);
        return label;
    }
    private Button button(String copy) {
        var button = new Button(this);
        button.setAllCaps(false);
        button.setText(copy);
        button.setTextSize(16);
        button.setTextColor(Color.WHITE);
        button.setMinHeight(dp(48));
        return button;
    }
    private void add(LinearLayout column, View child, int gap) {
        var params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(gap);
        column.addView(child, params);
    }
}
