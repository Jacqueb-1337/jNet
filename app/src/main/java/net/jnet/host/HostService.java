package net.jnet.host;

import android.app.*;
import android.content.*;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.wifi.p2p.*;
import android.os.*;
import java.net.*;
import java.util.*;

public final class HostService extends Service {
    static final String WIFI = "wifi", LAN = "lan", VPN = "vpn", STOP = "stop";
    static String status = "Idle";
    static String details = "";
    static String endpoint = "";
    static boolean active;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private SocksProxy proxy;
    private SocksProxy localProxy;
    private String mode;
    private boolean ownsGroup;
    private int attempts;
    private int wifiBand;
    static int bandIndex(int band) { return band == 1 || band == 2 ? band : 0; }
    private String vpnOverride;
    private static final long RETRY_MS = 2000;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        String action = intent == null ? STOP : intent.getAction();
        if (STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (mode != null) return START_NOT_STICKY;
        mode = action;
        active = true;
        var notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("host", "Sharing status", NotificationManager.IMPORTANCE_LOW));
        var stop = PendingIntent.getService(this, 0, new Intent(this, HostService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        var open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        startForeground(1, new Notification.Builder(this, "host")
            .setContentTitle("jNet is sharing").setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(open).addAction(new Notification.Action.Builder(null, "Stop", stop).build()).build());

        status = "Starting…";
        details = "";
        vpnOverride = intent.getStringExtra("address");
        wifiBand = bandIndex(intent.getIntExtra("wifiBand", 0));
        reconnect();
        return START_NOT_STICKY;
    }

    private void reconnect() {
        if (mode == null) return;
        try {
            if (WIFI.equals(mode)) startWifi();
            else if (LAN.equals(mode)) shareCurrentWifi();
            else startVpn(vpnOverride);
        } catch (SecurityException e) {
            fail("Permission required to share the connection.");
        } catch (Exception e) {
            retry("Could not start the proxy. Retrying…");
        }
    }

    private void shareCurrentWifi() {
        var connectivity = getSystemService(ConnectivityManager.class);
        InetAddress address = null;
        for (var network : connectivity.getAllNetworks()) {
            var caps = connectivity.getNetworkCapabilities(network);
            var links = connectivity.getLinkProperties(network);
            if (caps == null || links == null ||
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            for (var link : links.getLinkAddresses()) {
                var ip = link.getAddress();
                if (!(ip instanceof Inet4Address) || ip.isLoopbackAddress() ||
                    ip.isLinkLocalAddress() || ip.isAnyLocalAddress()) continue;
                if (address == null || endpoint.equals(ip.getHostAddress() + ":" + SocksProxy.PORT)) {
                    address = ip;
                }
            }
        }
        if (address == null) {
            retry("Waiting for a Wi-Fi network with an IPv4 address…");
            return;
        }
        if (!endpoint.equals(address.getHostAddress() + ":" + SocksProxy.PORT)) {
            closeProxies();
            if (!listen(address, "Current Wi-Fi")) return;
        }
        handler.postDelayed(this::reconnect, RETRY_MS);
    }

    private void retry(String message) {
        closeProxies();
        status = message;
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::reconnect, RETRY_MS);
    }

    private void startWifi() {
        manager = getSystemService(WifiP2pManager.class);
        if (manager == null) { fail("Wi-Fi Direct is unavailable on this device."); return; }
        if (channel == null) {
            channel = manager.initialize(this, getMainLooper(), () -> {
                channel = null;
                ownsGroup = false;
                if (WIFI.equals(mode)) retry("Wi-Fi Direct disconnected. Reconnecting…");
            });
        }
        if (channel == null) { retry("Waiting for Wi-Fi Direct…"); return; }
        if (ownsGroup) { readWifi(); return; }
        var preferences = getSharedPreferences("host", MODE_PRIVATE);
        String password = preferences.getString("password", null);
        if (password == null) {
            password = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            preferences.edit().putString("password", password).apply();
        }
        int band = switch (wifiBand) {
            case 1 -> WifiP2pConfig.GROUP_OWNER_BAND_2GHZ;
            case 2 -> WifiP2pConfig.GROUP_OWNER_BAND_5GHZ;
            default -> WifiP2pConfig.GROUP_OWNER_BAND_AUTO;
        };
        var config = new WifiP2pConfig.Builder()
            .setNetworkName("DIRECT-JN-jNet").setPassphrase(password)
            .setGroupOperatingBand(band).build();
        var creatingChannel = channel;
        String expectedPassword = password;
        manager.createGroup(creatingChannel, config, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                if (mode == null) { manager.removeGroup(creatingChannel, null); return; }
                if (channel != creatingChannel) return;
                ownsGroup = true;
                attempts = 0;
                readWifi();
            }
            @Override public void onFailure(int reason) {
                if (!WIFI.equals(mode) || channel != creatingChannel) return;
                manager.requestGroupInfo(creatingChannel, group -> {
                    if (!WIFI.equals(mode) || channel != creatingChannel) return;
                    if (group != null && group.isGroupOwner() &&
                        "DIRECT-JN-jNet".equals(group.getNetworkName()) &&
                        expectedPassword.equals(group.getPassphrase())) {
                        ownsGroup = true;
                        attempts = 0;
                        readWifi();
                    } else {
                        retry(wifiBand == 0 ? "Waiting for Wi-Fi Direct…" :
                            "Waiting for " + (wifiBand == 1 ? "2.4 GHz" : "5 GHz") +
                            " Wi-Fi Direct. Stop sharing to try another band.");
                    }
                });
            }
        });
    }

    private void readWifi() {
        var readingChannel = channel;
        manager.requestGroupInfo(readingChannel, group -> {
            if (!WIFI.equals(mode) || channel != readingChannel) return;
            if (group == null || !group.isGroupOwner() || !"DIRECT-JN-jNet".equals(group.getNetworkName())) {
                closeProxies();
                status = "Wi-Fi Direct disconnected. Reconnecting…";
                if (++attempts < 8) handler.postDelayed(this::readWifi, 500);
                else {
                    ownsGroup = false;
                    retry(status);
                }
                return;
            }
            int frequency = group.getFrequency();
            boolean on24 = frequency >= 2400 && frequency < 2500;
            boolean on5 = frequency >= 4900 && frequency < 5925;
            if (frequency > 0 && ((wifiBand == 1 && !on24) || (wifiBand == 2 && !on5))) {
                fail("Android started Wi-Fi Direct on a different band. Try Auto or the other band.");
                return;
            }
            String frequencyLine = frequency > 0 ? "\nFrequency   " + frequency + " MHz" : "";
            manager.requestConnectionInfo(readingChannel, connection -> {
                if (!WIFI.equals(mode) || channel != readingChannel) return;
                if (connection == null || !connection.groupFormed || connection.groupOwnerAddress == null) {
                    retry("Waiting for a Wi-Fi Direct address…");
                    return;
                }
                if (!endpoint.equals(connection.groupOwnerAddress.getHostAddress() + ":" + SocksProxy.PORT)) {
                    closeProxies();
                    if (!listen(connection.groupOwnerAddress,
                        "Network   " + group.getNetworkName() + "\nPassword   " + group.getPassphrase()
                            + frequencyLine)) return;
                }
                attempts = 0;
                handler.postDelayed(this::readWifi, RETRY_MS);
            });
        });
    }

    private void startVpn(String address) throws Exception {
        if (address == null || address.isBlank()) {
            var networks = getSystemService(android.net.ConnectivityManager.class);
            var candidates = new HashSet<String>();
            for (var network : networks.getAllNetworks()) {
                var caps = networks.getNetworkCapabilities(network);
                var links = networks.getLinkProperties(network);
                if (caps == null || links == null ||
                    !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) continue;
                for (var link : links.getLinkAddresses()) {
                    byte[] bytes = link.getAddress().getAddress();
                    if (bytes.length == 4 && bytes[0] == 100 &&
                        Byte.toUnsignedInt(bytes[1]) >= 64 && Byte.toUnsignedInt(bytes[1]) <= 127) {
                        candidates.add(link.getAddress().getHostAddress());
                    }
                }
            }
            if (candidates.size() != 1) {
                retry("Waiting for Tailscale. Use the address override if auto-detection is unavailable.");
                return;
            }
            address = candidates.iterator().next();
        }
        if (address == null || !address.matches("100\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}")) {
            fail("Enter the phone’s Tailscale IPv4 address.");
            return;
        }
        var ip = InetAddress.getByName(address);
        byte[] bytes = ip.getAddress();
        int second = Byte.toUnsignedInt(bytes[1]);
        if (second < 64 || second > 127) {
            fail("Enter the phone’s Tailscale IPv4 address.");
            return;
        }
        if (NetworkInterface.getByInetAddress(ip) == null) {
            retry("Waiting for the selected Tailscale address…");
            return;
        }
        localProxy = new SocksProxy(InetAddress.getByName("127.0.0.1"), false);
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
        retry("Tailscale disconnected. Reconnecting…");
    }

    private boolean listen(InetAddress ip, String connectionDetails) {
        try {
            proxy = new SocksProxy(ip, WIFI.equals(mode) || LAN.equals(mode));
            endpoint = ip.getHostAddress() + ":" + SocksProxy.PORT;
            details = connectionDetails + "\nSOCKS5   " + endpoint;
            status = "Sharing";
            return true;
        } catch (Exception e) {
            retry("Could not bind the proxy. Retrying…");
            return false;
        }
    }

    private void closeProxies() {
        if (proxy != null) proxy.close();
        if (localProxy != null) localProxy.close();
        proxy = null;
        localProxy = null;
        endpoint = "";
        details = "";
    }

    private void fail(String message) {
        active = false;
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
        boolean running = active;
        active = false;
        mode = null;
        handler.removeCallbacksAndMessages(null);
        closeProxies();
        removeGroup();
        details = "";
        if (running) status = "Idle";
        super.onDestroy();
    }
}
