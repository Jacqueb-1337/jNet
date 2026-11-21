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
    private EditText proxyPassword;
    private CheckBox mobileOnly;
    private TextView activitySummary;
    private TrafficHistory traffic;
    private LinearLayout settingsPanel;
    private static final int INK = 0xfff1f0df, MUTED = 0xffb5b8ab, ACCENT = 0xffd4ec7d;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xff1b201c);
        getWindow().setNavigationBarColor(0xff1b201c);
        var scroll = new ScrollView(this);
        var content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(0xff1b201c);
        int horizontal = dp(20);
        content.setPadding(horizontal, dp(20), horizontal, dp(24));
        scroll.setFillViewport(true);
        scroll.addView(content);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            var bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });
        setContentView(scroll);

        var title = label("jNet", 32, INK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        add(content, title, 0);
        var connection = panel();
        connection.setBackgroundColor(0xff26362d);
        add(content, connection, 16);
        status = label("", 18, ACCENT);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(connection, status, 0);
        details = label("", 15, INK);
        details.setTypeface(Typeface.MONOSPACE);
        details.setTextIsSelectable(true);
        details.setLineSpacing(dp(4), 1);
        add(connection, details, 8);
        var connectionActions = new LinearLayout(this);
        add(connection, connectionActions, 8);
        copyEndpoint = button("Copy address");
        connectionActions.addView(copyEndpoint, new LinearLayout.LayoutParams(0, -2, 1));
        copyEndpoint.setOnClickListener(view -> {
            if (HostService.endpoint.isEmpty()) return;
            getSystemService(android.content.ClipboardManager.class).setPrimaryClip(
                android.content.ClipData.newPlainText("jNet proxy address", HostService.endpoint));
        });
        var activityPanel = panel();
        add(activityPanel, label("Session traffic", 18, INK), 0);
        traffic = new TrafficHistory(this);
        var chart = new LinearLayout.LayoutParams(-1, dp(68));
        chart.topMargin = dp(8);
        activityPanel.addView(traffic, chart);
        activitySummary = label("", 15, INK);
        add(activityPanel, activitySummary, 8);
        var diagnostics = button("Share diagnostics");
        add(activityPanel, diagnostics, 8);
        diagnostics.setOnClickListener(view -> shareDiagnostics());
        setupQr = button("Setup QR");
        setupQr.setVisibility(View.GONE);
        connectionActions.addView(setupQr, new LinearLayout.LayoutParams(0, -2, 1));
        setupQr.setOnClickListener(view -> showSetupQr());
        stop = button("Stop sharing");
        add(connection, stop, 4);
        stop.setOnClickListener(view -> stopService(new Intent(this, HostService.class)));
        settingsPanel = panel();
        var clientHelp = button("Recommended proxy clients");
        add(settingsPanel, clientHelp, 12);
        clientHelp.setOnClickListener(view -> new android.app.AlertDialog.Builder(this)
            .setTitle("Connect another device")
            .setMessage("iOS · Potatso\nAndroid · NekoBox for Android (VPN mode)\n"
                + "Windows · Proxifier (paid)\nLinux · ProxyChains-NG (run apps through it)\n\n"
                + "Add a SOCKS5 proxy in the client and enter the address and port shown in jNet. "
                + "Use remote DNS if your client offers it.\n\n"
                + "Current Wi-Fi and Wi-Fi Direct support TCP and UDP. Tailscale supports TCP.")
            .setPositiveButton("Done", null)
            .show());
        proxyPassword = new EditText(this);
        proxyPassword.setSingleLine(true);
        proxyPassword.setTextColor(INK);
        proxyPassword.setHintTextColor(MUTED);
        proxyPassword.setHint("Proxy password (optional)");
        proxyPassword.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        add(settingsPanel, proxyPassword, 0);
        add(settingsPanel, label("SOCKS5 username: jnet", 14, MUTED), 4);
        wifiBand = new Spinner(this);
        wifiBand.setContentDescription("Wi-Fi Direct band");
        var bands = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
            new String[]{"Auto", "2.4 GHz", "5 GHz"});
        bands.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        wifiBand.setAdapter(bands);
        wifiBand.setSelection(HostService.bandIndex(getPreferences(MODE_PRIVATE).getInt("wifiBand", 0)));
        wifiBand.setMinimumHeight(dp(48));
        add(settingsPanel, label("Wi-Fi Direct band", 15, MUTED), 12);
        add(settingsPanel, wifiBand, 0);
        add(content, label("Share a connection", 18, INK), 20);
        wifi = modeButton(content, "Wi-Fi Direct", "Create a network for nearby devices");
        wifi.setOnClickListener(view -> {
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED) start(HostService.WIFI);
            else requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}, 10);
        });

        mobileOnly = new CheckBox(this);
        mobileOnly.setText("Use mobile data for proxy traffic");
        mobileOnly.setTextColor(INK);
        add(settingsPanel, mobileOnly, 4);
        lan = modeButton(content, "Current Wi-Fi", "Share on the router you’re already connected to");
        lan.setOnClickListener(view -> start(HostService.LAN));

        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextColor(INK);
        address.setHintTextColor(MUTED);
        address.setHint("Tailscale IPv4 override (optional)");
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        add(settingsPanel, address, 4);
        vpn = modeButton(content, "Tailscale", "Share with devices on your tailnet");
        vpn.setOnClickListener(view -> start(HostService.VPN));

        expandable(content, "Connection settings", settingsPanel);
        expandable(content, "Activity & diagnostics", activityPanel);
        refresh.run();
        handler.removeCallbacks(refresh);
    }

    private void start(String action) {
        String password = proxyPassword.getText().toString();
        if (password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 255) {
            settingsPanel.setVisibility(View.VISIBLE);
            proxyPassword.setError("Maximum 255 UTF-8 bytes");
            return;
        }
        String ip = address.getText().toString().trim();
        int band = wifiBand.getSelectedItemPosition();
        if (HostService.WIFI.equals(action)) {
            getPreferences(MODE_PRIVATE).edit().putInt("wifiBand", band).apply();
        }
        startForegroundService(new Intent(this, HostService.class).setAction(action)
            .putExtra("address", ip).putExtra("wifiBand", band)
            .putExtra("proxyPassword", password).putExtra("mobileOnly", mobileOnly.isChecked()));
    }

    private LinearLayout panel() {
        var panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xff242b25);
        panel.setPadding(dp(12), dp(12), dp(12), dp(12));
        return panel;
    }

    private Button modeButton(LinearLayout parent, String title, String description) {
        var row = panel();
        var action = button(title);
        action.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        action.setContentDescription("Start " + title);
        add(row, action, 0);
        add(row, label(description, 14, MUTED), 4);
        add(parent, row, 8);
        return action;
    }

    private void expandable(LinearLayout parent, String title, LinearLayout panel) {
        panel.setVisibility(View.GONE);
        var control = button(title);
        control.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        add(parent, control, 12);
        add(parent, panel, 0);
        control.setOnClickListener(view -> {
            boolean expanded = panel.getVisibility() != View.VISIBLE;
            panel.setVisibility(expanded ? View.VISIBLE : View.GONE);
            control.setStateDescription(expanded ? "Expanded" : "Collapsed");
        });
        control.setStateDescription("Collapsed");
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == 10 && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) start(HostService.WIFI);
        else if (request == 10) HostService.status = "Allow Nearby devices to start Wi-Fi Direct.";
    }

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            String state = HostService.active ? HostService.diagnosticMode + " · " + HostService.status : HostService.status;
            if (!state.contentEquals(status.getText())) status.setText(state);
            String summary = HostService.details;
            if (!HostService.upstreamStatus.isEmpty()) summary += "\n" + HostService.upstreamStatus;
            if (!HostService.endpoint.isEmpty() && !HostService.proxyPassword.isEmpty()) {
                summary += "\nProxy login   jnet";
            }
            if (!summary.contentEquals(details.getText())) details.setText(summary);
            var stats = HostService.stats;
            traffic.sample(stats);
            String activity = traffic.rates() + "\n" + activitySummary(stats);
            if (!activity.contentEquals(activitySummary.getText())) activitySummary.setText(activity);
            details.setVisibility(HostService.details.isEmpty() ? View.GONE : View.VISIBLE);
            copyEndpoint.setVisibility(HostService.endpoint.isEmpty() ? View.GONE : View.VISIBLE);
            setupQr.setVisibility(hasScannableEndpoint() ? View.VISIBLE : View.GONE);
            boolean running = HostService.active;
            proxyPassword.setEnabled(!running);
            mobileOnly.setEnabled(!running);
            wifi.setEnabled(!running);
            wifiBand.setEnabled(!running);
            lan.setEnabled(!running);
            vpn.setEnabled(!running);
            stop.setVisibility(running ? View.VISIBLE : View.GONE);
            handler.postDelayed(this, 300);
        }
    };

    private String activitySummary(SessionStats stats) {
        long last = stats.lastUdp.get();
        String udp = last == 0 ? "none this session" :
            java.time.Instant.ofEpochMilli(last).atZone(java.time.ZoneId.systemDefault())
                .toLocalTime().withNano(0).toString();
        return android.text.format.Formatter.formatShortFileSize(this, stats.sent.get()) + " sent · "
            + android.text.format.Formatter.formatShortFileSize(this, stats.received.get()) + " received"
            + "\n" + stats.active.get() + " active · " + stats.accepted.get() + " total · "
            + stats.devices() + " client addresses\nLast UDP relay: " + udp;
    }

    private void shareDiagnostics() {
        var stats = HostService.stats;
        String report = "jNet diagnostics\nMode: " + HostService.diagnosticMode
            + "\nStatus: " + HostService.status + "\nProxy: "
            + (HostService.diagnosticEndpoint.isEmpty() ? "none" : HostService.diagnosticEndpoint)
            + "\nUpstream: " + (HostService.upstreamStatus.isEmpty() ? "unknown" : HostService.upstreamStatus)
            + "\n" + activitySummary(stats) + "\n\nRecent events\n" + stats.log();
        var share = new Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, report);
        new android.app.AlertDialog.Builder(this)
            .setTitle("Review diagnostics")
            .setMessage(report)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Share", (dialog, which) ->
                startActivity(Intent.createChooser(share, "Share jNet diagnostics")))
            .show();
    }

    private static final class TrafficHistory extends View {
        private final float[] sent = new float[48], received = new float[48];
        private final android.graphics.Paint paint = new android.graphics.Paint(3);
        private SessionStats previous;
        private long at, tx, rx;
        private float txRate, rxRate, txPeak, rxPeak;

        TrafficHistory(android.content.Context context) {
            super(context);
            setContentDescription("Recent traffic graph. Lime is sent, blue is received.");
        }

        void sample(SessionStats current) {
            long now = SystemClock.elapsedRealtime();
            long s = current.sent.get(), r = current.received.get();
            if (previous != current) {
                java.util.Arrays.fill(sent, 0);
                java.util.Arrays.fill(received, 0);
                previous = current;
                txRate = rxRate = txPeak = rxPeak = 0;
            } else {
                if (now - at < 1000) return;
                float seconds = (now - at) / 1000f;
                txRate = (s - tx) / seconds;
                rxRate = (r - rx) / seconds;
                txPeak = Math.max(txPeak, txRate);
                rxPeak = Math.max(rxPeak, rxRate);
                append(sent, txRate);
                append(received, rxRate);
            }
            at = now;
            tx = s;
            rx = r;
            invalidate();
        }

        String rates() {
            return "Sent " + rate(txRate) + " · peak " + rate(txPeak)
                + "\nReceived " + rate(rxRate) + " · peak " + rate(rxPeak);
        }

        private String rate(float bytesPerSecond) {
            return android.text.format.Formatter.formatShortFileSize(getContext(), (long) bytesPerSecond) + "/s";
        }

        private void append(float[] series, float value) {
            System.arraycopy(series, 1, series, 0, series.length - 1);
            series[series.length - 1] = value;
        }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(0xff242b25);
            float max = 1;
            for (int i = 0; i < sent.length; i++) max = Math.max(max, Math.max(sent[i], received[i]));
            paint.setStrokeWidth(3);
            drawSeries(canvas, sent, max, ACCENT);
            drawSeries(canvas, received, max, 0xff8ad0f0);
        }

        private void drawSeries(android.graphics.Canvas canvas, float[] series, float max, int color) {
            paint.setColor(color);
            float dx = (getWidth() - 8f) / (series.length - 1);
            float height = getHeight() - 8f;
            for (int i = 1; i < series.length; i++) {
                canvas.drawLine(4 + (i - 1) * dx, 4 + height * (1 - series[i - 1] / max),
                    4 + i * dx, 4 + height * (1 - series[i] / max), paint);
            }
        }
    }

    private boolean hasScannableEndpoint() {
        return !HostService.endpoint.isEmpty() && !HostService.endpoint.startsWith("127.0.0.1:");
    }

    private void showSetupQr() {
        if (!hasScannableEndpoint()) return;
        String password = HostService.proxyPassword;
        String credentials = password.isEmpty() ? "" : "jnet:" + android.net.Uri.encode(password) + "@";
        String uri = "socks5://" + credentials + HostService.endpoint + "#jNet";
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
            if (!password.isEmpty()) {
                add(panel, label("This QR includes your proxy password.", 14, MUTED), 6);
            }
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
