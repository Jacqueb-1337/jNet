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
    private Button wifi, vpn, usb, stop, copyEndpoint;
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
        add(content, label("Install a SOCKS5 proxy client on the device you want to connect.", 16, MUTED), 12);
        var clientHelp = button("Recommended proxy clients");
        add(content, clientHelp, 8);
        clientHelp.setOnClickListener(view -> new android.app.AlertDialog.Builder(this)
            .setTitle("Connect another device")
            .setMessage("iOS · Potatso\nAndroid · NekoBox for Android (VPN mode)\n"
                + "Windows · Proxifier (paid)\nLinux · ProxyChains-NG (run apps through it)\n\n"
                + "Add a SOCKS5 proxy in the client and enter the address and port shown in jNet. "
                + "Use remote DNS if your client offers it.\n\n"
                + "Wi-Fi Direct supports TCP and UDP. USB and Tailscale are supported for TCP.")
            .setPositiveButton("Done", null)
            .show());

        add(content, label("Nearby devices", 20, INK), 32);
        add(content, label("Join this phone’s Wi-Fi network, then enter the SOCKS5 address in your client.", 16, MUTED), 6);
        wifi = button("Start Wi-Fi Direct");
        add(content, wifi, 10);
        wifi.setOnClickListener(view -> {
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED) start(HostService.WIFI);
            else requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}, 10);
        });

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

        add(content, label("USB", 20, INK), 28);
        add(content, label("Connect the cable and run adb forward tcp:1080 tcp:1080 on your computer.", 16, MUTED), 8);
        usb = button("Start USB proxy");
        add(content, usb, 12);
        usb.setOnClickListener(view -> start(HostService.USB));

        stop = button("Stop sharing");
        add(content, stop, 20);
        stop.setOnClickListener(view -> stopService(new Intent(this, HostService.class)));
    }

    private void start(String action) {
        String ip = address.getText().toString().trim();
        startForegroundService(new Intent(this, HostService.class).setAction(action).putExtra("address", ip));
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
            boolean running = HostService.status.equals("Sharing") || HostService.status.equals("Starting…");
            wifi.setEnabled(!running);
            vpn.setEnabled(!running);
            usb.setEnabled(!running);
            stop.setVisibility(running ? View.VISIBLE : View.GONE);
            handler.postDelayed(this, 300);
        }
    };
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
