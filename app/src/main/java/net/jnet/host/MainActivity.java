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
    private InAppUpdater updater;
    private TextView status, details;
    private String pendingShare;
    private View wifi, lan;
    private Button stop, copyEndpoint;
    private LinearLayout connection;
    private TextView stateLabel;
    private Button setupQr, portalQr;
    private EditText proxyPassword;
    private CheckBox mobileOnly;
    private TextView activitySummary;
    private TrafficHistory traffic;
    private LinearLayout settingsPanel;
    private static final int INK = 0xfff1f4f5, MUTED = 0xffa8b3b7, ACCENT = 0xff79dcc6;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xff101315);
        getWindow().setNavigationBarColor(0xff101315);
        var scroll = new ScrollView(this);
        var content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(0xff101315);
        content.setPadding(0, 0, 0, dp(24));
        scroll.setFillViewport(true);
        scroll.addView(content);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            var bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });
        setContentView(scroll);

        var header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(10), dp(16), dp(10));
        var title = label("jNet", 26, INK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        stateLabel = label("", 14, MUTED);
        header.addView(stateLabel);
        add(content, header, 0);
        connection = panel();
        add(content, connection, 0);
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
        var units = new RadioGroup(this);
        units.setOrientation(LinearLayout.HORIZONTAL);
        var preferences = getPreferences(MODE_PRIVATE);
        traffic.megabits = preferences.getBoolean("trafficMegabits", false);
        for (String unit : new String[]{"MB/s", "Mbps"}) {
            var choice = new RadioButton(this);
            choice.setId(View.generateViewId());
            choice.setText(unit);
            choice.setMinHeight(dp(48));
            units.addView(choice);
            boolean bits = unit.equals("Mbps");
            choice.setChecked(bits == traffic.megabits);
            choice.setOnClickListener(v -> {
                traffic.megabits = bits;
                preferences.edit().putBoolean("trafficMegabits", bits).apply();
            });
        }
        add(activityPanel, units, 0);
        var chart = new LinearLayout.LayoutParams(-1, dp(68));
        chart.topMargin = dp(8);
        activityPanel.addView(traffic, chart);
        activitySummary = label("", 15, INK);
        add(activityPanel, activitySummary, 8);
        var diagnostics = button("Share diagnostics");
        add(activityPanel, diagnostics, 8);
        diagnostics.setOnClickListener(view -> shareDiagnostics());
        setupQr = button("Proxy QR");
        setupQr.setVisibility(View.GONE);
        connectionActions.addView(setupQr, new LinearLayout.LayoutParams(0, -2, 1));
        setupQr.setOnClickListener(view -> showSetupQr());
        portalQr = button("Setup page QR");
        add(connection, portalQr, 0);
        portalQr.setOnClickListener(v -> {
            String url = HostService.portalUrl;
            if (!url.isEmpty()) showQr(url, "Open setup page", "Join the shared Wi-Fi, then scan with the camera or open " + url, false);
        });
        stop = button("Stop sharing");
        add(connection, stop, 4);
        stop.setOnClickListener(view -> stopService(new Intent(this, HostService.class)));
        settingsPanel = panel();
        var clientHelp = button("Help connecting another device");
        add(settingsPanel, clientHelp, 12);
        clientHelp.setOnClickListener(view -> new android.app.AlertDialog.Builder(this)
            .setTitle("Connect another device")
            .setMessage("1. Join the jNet Wi-Fi network on the other device.\n"
                + "2. Scan Setup page QR to open the setup page.\n"
                + "3. Install FlClash and tap Import into FlClash.\n"
                + "4. In FlClash, enable VPN on Android or TUN on a computer.\n\n"
                + "On Android, tap Dashboard > Edit and add VPN if it's missing.\n\n"
                + "On iPhone or iPad, use Potatso.")
            .setPositiveButton("Done", null)
            .show());
        proxyPassword = new EditText(this);
        proxyPassword.setSingleLine(true);
        proxyPassword.setTextColor(INK);
        proxyPassword.setHintTextColor(MUTED);
        proxyPassword.setHint("Proxy password (optional)");
        proxyPassword.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        try {
            proxyPassword.setText(ProxySecretStore.read(this));
        } catch (Exception error) {
            android.util.Log.e("jNet", "Could not restore saved proxy password", error);
            Toast.makeText(this, "Could not restore the saved proxy password.", Toast.LENGTH_LONG).show();
        }
        add(settingsPanel, proxyPassword, 0);
        add(settingsPanel, label("SOCKS5 username: jnet", 14, MUTED), 4);
        section(content, "Sharing");
        wifi = modeButton(content, "Wi-Fi Direct", "Create a network for nearby devices");
        wifi.setOnClickListener(view -> {
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED) start(HostService.WIFI);
            else requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}, 10);
        });

        lan = modeButton(content, "Current Wi-Fi", "Share on the router you’re already connected to");
        lan.setOnClickListener(view -> start(HostService.LAN));

        // This setting only affects Current Wi-Fi sharing, not Wi-Fi Direct.
        var currentWifiOptions = panel();
        add(content, currentWifiOptions, 4);
        mobileOnly = new CheckBox(this);
        mobileOnly.setText("Use mobile data for Current Wi-Fi");
        mobileOnly.setTextColor(INK);
        mobileOnly.setMinHeight(dp(48));
        mobileOnly.setChecked(getPreferences(MODE_PRIVATE).getBoolean("mobileOnly", false));
        mobileOnly.setOnCheckedChangeListener((button, checked) ->
                getPreferences(MODE_PRIVATE).edit().putBoolean("mobileOnly", checked).apply());
        add(currentWifiOptions, mobileOnly, 0);
        add(currentWifiOptions,
            label("Off: phone’s default internet (usually Wi-Fi). On: mobile data only.", 13, MUTED), 2);

        section(content, "Settings");
        var hotspot = listRow(content, "Wi-Fi Direct settings", "Network name, password and band", "›");
        hotspot.setOnClickListener(v -> hotspotDialog());
        expandable(content, "Connection settings", settingsPanel);
        expandable(content, "Activity & diagnostics", activityPanel);
        updater = new InAppUpdater(this);
        updater.onCreate(state == null);
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
        try {
            ProxySecretStore.write(this, password);
        } catch (Exception error) {
            android.util.Log.e("jNet", "Couldn't save SOCKS5 password", error);
            Toast.makeText(this, "Could not save proxy password.", Toast.LENGTH_LONG).show();
            return;
        }
        getPreferences(MODE_PRIVATE).edit()
                .putString("selectedMode", action)
                .putBoolean("mobileOnly", mobileOnly.isChecked()).apply();
        int band = getPreferences(MODE_PRIVATE).getInt("wifiBand", 0);
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                !getPreferences(MODE_PRIVATE).getBoolean("askedForNotifications", false)) {
            pendingShare = action;
            getPreferences(MODE_PRIVATE).edit().putBoolean("askedForNotifications", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 11);
            return;
        }
        startForegroundService(new Intent(this, HostService.class).setAction(action)
            .putExtra("wifiBand", band)

            .putExtra("proxyPassword", password).putExtra("mobileOnly", mobileOnly.isChecked()));

    }

    private LinearLayout panel() {
        var panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xff181d20);
        panel.setPadding(dp(12), dp(12), dp(12), dp(12));
        return panel;
    }

    private void section(LinearLayout parent, String text) {
        var heading = label(text, 13, ACCENT);
        heading.setBackgroundColor(0xff20272a);
        heading.setPadding(dp(16), dp(5), dp(16), dp(5));
        add(parent, heading, 0);
    }

    private View listRow(LinearLayout parent, String title, String subtitle, String indicator) {
        var row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(16), dp(10));
        row.setMinimumHeight(dp(50));
        var background = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, background, true);
        row.setBackgroundResource(background.resourceId);
        row.setFocusable(true);
        var labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        add(labels, label(title, 18, INK), 0);
        if (!subtitle.isEmpty()) add(labels, label(subtitle, 14, MUTED), 2);
        var mark = label(indicator, 22, MUTED);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(mark);
        add(parent, row, 0);
        var divider = new View(this);
        divider.setBackgroundColor(0xff2a3033);
        parent.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        return row;
    }

    private View modeButton(LinearLayout parent, String title, String description) {
        return listRow(parent, title, description, "○");
    }

    private void modeState(View view, String title, boolean running) {
        var row = (LinearLayout) view;
        boolean selected = running && title.equals(HostService.diagnosticMode);
        var mark = (TextView) row.getChildAt(1);
        mark.setText(selected ? "●" : "○");
        mark.setTextColor(selected ? ACCENT : MUTED);
        row.setEnabled(!running);
        row.setStateDescription(selected ? "Sharing" : "Off");
    }

    private void expandable(LinearLayout parent, String title, LinearLayout panel) {
        panel.setVisibility(View.GONE);
        var control = (LinearLayout) listRow(parent, title, "", "›");
        add(parent, panel, 0);
        control.setOnClickListener(view -> {
            boolean expanded = panel.getVisibility() != View.VISIBLE;
            panel.setVisibility(expanded ? View.VISIBLE : View.GONE);
            ((TextView) control.getChildAt(1)).setText(expanded ? "⌄" : "›");
            control.setStateDescription(expanded ? "Expanded" : "Collapsed");
        });
        control.setStateDescription("Collapsed");
    }

    private void hotspotDialog() {
        var body = panel();
        var prefs = getSharedPreferences("host", MODE_PRIVATE);
        add(body, label("Network name", 14, MUTED), 0);
        add(body, label("DIRECT-JN-jNet", 18, INK), 2);
        add(body, label("Password", 14, MUTED), 12);
        var password = new EditText(this);
        password.setSingleLine(true);
        password.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setText(HostService.wifiPassword(this));
        var field = new LinearLayout(this);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.addView(password, new LinearLayout.LayoutParams(0, -2, 1));
        var eye = new ImageButton(this, null, android.R.attr.borderlessButtonStyle);
        eye.setImageResource(R.drawable.show_password);
        eye.setContentDescription("Show password");
        field.addView(eye, new LinearLayout.LayoutParams(dp(48), dp(48)));
        eye.setOnClickListener(v -> {
            boolean reveal = password.getTransformationMethod() != null;
            int start = password.getSelectionStart(), end = password.getSelectionEnd();
            password.setTransformationMethod(reveal ? null :
                android.text.method.PasswordTransformationMethod.getInstance());
            if (start >= 0 && end >= 0) password.setSelection(start, end);
            eye.setImageResource(reveal ? R.drawable.hide_password : R.drawable.show_password);
            eye.setContentDescription(reveal ? "Hide password" : "Show password");
        });
        add(body, field, 0);
        add(body, label("Band", 14, MUTED), 10);
        var band = new Spinner(this);
        var adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[]{"Auto", "2.4 GHz", "5 GHz"});
        band.setAdapter(adapter);
        band.setSelection(HostService.bandIndex(getPreferences(MODE_PRIVATE).getInt("wifiBand", 0)));
        add(body, band, 4);
        if (HostService.active) add(body, label("Changes apply the next time you start Wi-Fi Direct.", 14, MUTED), 8);
        var dialog = new android.app.AlertDialog.Builder(this).setTitle("Wi-Fi Direct settings")
            .setView(body).setNegativeButton("Cancel", null).setPositiveButton("Save", null).show();
        dialog.getButton(-1).setOnClickListener(v -> {
            String value = password.getText().toString();
            if (!HostService.validWifiPassword(value)) {
                password.setError("Use 8–63 printable ASCII characters");
                return;
            }
            prefs.edit().putString("password", value).apply();
            getPreferences(MODE_PRIVATE).edit().putInt("wifiBand", band.getSelectedItemPosition()).apply();
            dialog.dismiss();
        });
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == 11 && pendingShare != null) {
            String action = pendingShare;
            pendingShare = null;
            start(action);
            return;
        }
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
            portalQr.setVisibility(HostService.portalUrl.isEmpty() ? View.GONE : View.VISIBLE);
            setupQr.setVisibility(hasScannableEndpoint() ? View.VISIBLE : View.GONE);
            boolean running = HostService.active;
            proxyPassword.setEnabled(!running);
            mobileOnly.setEnabled(!running);
            stateLabel.setText(running ? "●  Sharing" : "○  Offline");
            stateLabel.setTextColor(running ? ACCENT : MUTED);
            connection.setVisibility(running || !("Idle".equals(state) || "Stopped".equals(state)) ? View.VISIBLE : View.GONE);
            modeState(wifi, "Wi-Fi Direct", running);
            modeState(lan, "Current Wi-Fi", running);
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
        private boolean megabits;
        private SessionStats previous;
        private long at, tx, rx;
        private float txRate, rxRate, txPeak, rxPeak;

        TrafficHistory(android.content.Context context) {
            super(context);
            setContentDescription("Recent traffic graph. Sent rises above zero in red-orange. Received falls below zero in green.");
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
            return "↑ Sent " + rate(txRate) + " · peak " + rate(txPeak)
                + "\n↓ Received " + rate(rxRate) + " · peak " + rate(rxPeak);
        }

        private String rate(float bytesPerSecond) {
            double value = bytesPerSecond * (megabits ? 8.0 : 1.0) / 1_000_000;
            String unit = megabits ? " Mbps" : " MB/s";
            if (value > 0 && value < 0.01) return "<0.01" + unit;
            return String.format(java.util.Locale.getDefault(), "%.2f", value) + unit;
        }

        private void append(float[] series, float value) {
            System.arraycopy(series, 1, series, 0, series.length - 1);
            series[series.length - 1] = value;
        }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(0xff181d20);
            float max = 1;
            for (int i = 0; i < sent.length; i++) max = Math.max(max, Math.max(sent[i], received[i]));
            paint.setStrokeWidth(3);
            float zero = getHeight() / 2f;
            paint.setColor(0xff626d70);
            canvas.drawLine(4, zero, getWidth() - 4, zero, paint);
            float scale = (zero - 4) / max;
            drawSeries(canvas, sent, zero, -scale, 0xffff805f);
            drawSeries(canvas, received, zero, scale, 0xff66d991);
        }

        private void drawSeries(android.graphics.Canvas canvas, float[] series, float zero, float scale, int color) {
            paint.setColor(color);
            float dx = (getWidth() - 8f) / (series.length - 1);
            for (int i = 1; i < series.length; i++) {
                if (series[i - 1] == 0 && series[i] == 0) continue;
                canvas.drawLine(4 + (i - 1) * dx, zero + scale * series[i - 1],
                    4 + i * dx, zero + scale * series[i], paint);
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
        showQr(uri, "Scan to add jNet", "On the other device, open Potatso or NekoBox and use its QR scanner.", !password.isEmpty());
    }

    private void showQr(String uri, String title, String instructions, boolean includesPassword) {
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
            qr.setContentDescription(title);
            var panel = new LinearLayout(this);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(dp(20), dp(8), dp(20), 0);
            add(panel, label(instructions, 16, INK), 0);
            if (includesPassword) {
                add(panel, label("This QR includes your proxy password.", 14, MUTED), 6);
            }
            add(panel, qr, 12);
            new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setView(panel)
                .setPositiveButton("Done", null)
                .show();
        } catch (com.google.zxing.WriterException exception) {
            Toast.makeText(this, "Could not create the setup QR.", Toast.LENGTH_SHORT).show();
        }
    }
    @Override protected void onResume() {
        super.onResume();
        if (updater != null) updater.onResume();
    }
    @Override protected void onPause() {
        if (proxyPassword != null && proxyPassword.getText().toString()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 255) {
            try {
                ProxySecretStore.write(this, proxyPassword.getText().toString());
            } catch (Exception error) {
                android.util.Log.e("jNet", "Couldn't save proxy password", error);
                Toast.makeText(this, "Could not save proxy password.", Toast.LENGTH_LONG).show();
            }
        }
        if (updater != null) updater.onPause();
        super.onPause();
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
