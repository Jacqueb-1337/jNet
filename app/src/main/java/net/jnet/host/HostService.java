package net.jnet.host;

import android.app.*;
import android.content.*;
import android.net.wifi.p2p.*;
import android.os.*;
import java.net.*;
import java.util.*;

public final class HostService extends Service {
    static final String WIFI = "wifi", VPN = "vpn", STOP = "stop";
    static String status = "Idle";
    static String details = "";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private SocksProxy proxy;
    private String mode;
    private boolean ownsGroup;
    private int attempts;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        String action = intent == null ? STOP : intent.getAction();
        if (STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (mode != null) return START_NOT_STICKY;
        mode = action;
        var notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("host", "Sharing status", NotificationManager.IMPORTANCE_LOW));
        var stop = PendingIntent.getService(this, 0, new Intent(this, HostService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        var open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        startForeground(1, new Notification.Builder(this, "host")
            .setContentTitle("jNet is sharing").setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(open).addAction(new Notification.Action.Builder(null, "Stop", stop).build()).build());

        status = "Starting…";
        details = "";
        try {
            if (WIFI.equals(mode)) startWifi();
            else startVpn(intent.getStringExtra("address"));
        } catch (Exception e) {
            fail("Could not start: " + e.getMessage());
        }
        return START_NOT_STICKY;
    }

    private void startWifi() {
        manager = getSystemService(WifiP2pManager.class);
        if (manager == null) { fail("Wi-Fi Direct is unavailable on this device."); return; }
        channel = manager.initialize(this, getMainLooper(), () -> fail("Wi-Fi Direct disconnected."));
        var preferences = getSharedPreferences("host", MODE_PRIVATE);
        String password = preferences.getString("password", null);
        if (password == null) {
            password = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            preferences.edit().putString("password", password).apply();
        }
        var config = new WifiP2pConfig.Builder()
            .setNetworkName("DIRECT-JN-jNet").setPassphrase(password).build();
        manager.createGroup(channel, config, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                ownsGroup = true;
                if (mode == null) { removeGroup(); return; }
                readWifi();
            }
            @Override public void onFailure(int reason) { fail("Wi-Fi Direct could not start (" + reason + ")."); }
        });
    }

    private void readWifi() {
        manager.requestGroupInfo(channel, group -> {
            if (mode == null) return;
            if (group == null || !group.isGroupOwner()) { retryWifi(); return; }
            manager.requestConnectionInfo(channel, connection -> {
                if (mode == null) return;
                if (connection == null || connection.groupOwnerAddress == null) { retryWifi(); return; }
                listen(connection.groupOwnerAddress,
                    "Network   " + group.getNetworkName() + "\nPassword   " + group.getPassphrase());
            });
        });
    }

    private void retryWifi() {
        if (++attempts < 20) handler.postDelayed(this::readWifi, 300);
        else fail("Wi-Fi Direct did not provide an address.");
    }

    private void startVpn(String address) throws Exception {
        if (address == null || !address.matches("100\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}")) {
            fail("Enter the phone’s Tailscale IPv4 address.");
            return;
        }
        var ip = InetAddress.getByName(address);
        byte[] bytes = ip.getAddress();
        int second = Byte.toUnsignedInt(bytes[1]);
        if (second < 64 || second > 127 || NetworkInterface.getByInetAddress(ip) == null) {
            fail("Connect Tailscale and enter the IPv4 address assigned to this phone.");
            return;
        }
        listen(ip, "Tailnet only");
        if (proxy != null) handler.postDelayed(() -> checkVpn(ip), 2000);
    }

    private void checkVpn(InetAddress ip) {
        try {
            if (NetworkInterface.getByInetAddress(ip) != null) {
                handler.postDelayed(() -> checkVpn(ip), 2000);
                return;
            }
        } catch (SocketException ignored) {}
        fail("The Tailscale address disconnected.");
    }

    private void listen(InetAddress ip, String connectionDetails) {
        try {
            proxy = new SocksProxy(ip);
            details = connectionDetails + "\nSOCKS5   " + ip.getHostAddress() + ":" + SocksProxy.PORT;
            status = "Sharing";
        } catch (Exception e) { fail("Could not bind the proxy: " + e.getMessage()); }
    }

    private void fail(String message) {
        status = message;
        details = "";
        stopSelf();
    }

    private void removeGroup() {
        if (!ownsGroup) return;
        ownsGroup = false;
        manager.removeGroup(channel, null);
    }

    @Override public void onDestroy() {
        mode = null;
        handler.removeCallbacksAndMessages(null);
        if (proxy != null) proxy.close();
        removeGroup();
        details = "";
        if (status.equals("Sharing") || status.equals("Starting…")) status = "Idle";
        super.onDestroy();
    }
}
