package net.jnet.host;

import android.app.*;
import android.content.*;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.p2p.*;
import android.os.*;
import java.net.*;
import java.util.*;

public final class HostService extends Service {
    static final String WIFI = "wifi", LAN = "lan", STOP = "stop";
    static String status = "Idle";
    static String details = "";
    static String endpoint = "";
    static boolean active;
    static long startedAt;
    static String proxyPassword = "";
    static String upstreamStatus = "";
    static SessionStats stats = new SessionStats();
    static String diagnosticMode = "None";
    static String diagnosticEndpoint = "";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private SocksProxy proxy;
    private String mode;
    private boolean ownsGroup;
    private int attempts;
    private int wifiBand;
    static int bandIndex(int band) { return band == 1 || band == 2 ? band : 0; }
    static String wifiPassword(Context context) {
        var preferences = context.getSharedPreferences("host", MODE_PRIVATE);
        String password = preferences.getString("password", null);
        if (password == null) {
            password = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            preferences.edit().putString("password", password).apply();
        }
        return password;
    }

    static boolean validWifiPassword(String password) { return password.matches("[ -~]{8,63}"); }
    private boolean mobileOnly;
    private Network mobileNetwork;
    static String portalUrl = "";
    private SetupPortal portal;
    private Network boundNetwork;
    private Network checkedNetwork;
    private ConnectivityManager.NetworkCallback mobileCallback;
    private boolean checkingInternet;
    private long nextCheck;
    private static final long RETRY_MS = 2000;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        String action = intent == null ? STOP : intent.getAction();
        if (STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (mode != null) return START_NOT_STICKY;
        if (!WIFI.equals(action) && !LAN.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        mode = action;
        stats = new SessionStats();
        diagnosticMode = WIFI.equals(mode) ? "Wi-Fi Direct" : "Current Wi-Fi";
        diagnosticEndpoint = "";
        stats.note("Sharing started");
        active = true;
        startedAt = System.currentTimeMillis();
        var notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("host", "Sharing status", NotificationManager.IMPORTANCE_LOW));
        var stop = PendingIntent.getService(this, 0, new Intent(this, HostService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        var open = PendingIntent.getActivity(this, 0, new Intent(this, PrismActivity.class), PendingIntent.FLAG_IMMUTABLE);
        startForeground(1, new Notification.Builder(this, "host")
            .setContentTitle("jNet is sharing").setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true).setOnlyAlertOnce(true).setContentText(diagnosticMode + " · SOCKS5 and setup page")
            .setContentIntent(open).addAction(new Notification.Action.Builder(null, "Stop sharing", stop).build()).build());

        status = "Starting…";
        details = "";
        wifiBand = bandIndex(intent.getIntExtra("wifiBand", 0));
        String newWifiPassword = intent.getStringExtra("wifiPassword");
        if (WIFI.equals(mode) && newWifiPassword != null && !newWifiPassword.isEmpty()) {
            if (!validWifiPassword(newWifiPassword)) {
                fail("Wi-Fi Direct password must use 8–63 printable ASCII characters.");
                return START_NOT_STICKY;
            }
            getSharedPreferences("host", MODE_PRIVATE).edit().putString("password", newWifiPassword).apply();
        }
        proxyPassword = Objects.toString(intent.getStringExtra("proxyPassword"), "");
        mobileOnly = LAN.equals(mode) && intent.getBooleanExtra("mobileOnly", false);
        if (mobileOnly) {
            try {
                requestMobile();
            } catch (RuntimeException exception) {
                fail("Android could not request mobile data.");
                return START_NOT_STICKY;
            }
        }
        reconnect();
        return START_NOT_STICKY;
    }

    private void requestMobile() {
        var connectivity = getSystemService(ConnectivityManager.class);
        mobileCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { mobileNetwork = network; }
            @Override public void onLost(Network network) {
                if (network.equals(mobileNetwork)) mobileNetwork = null;
            }
        };
        connectivity.requestNetwork(new NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), mobileCallback, handler);
    }

    private void reconnect() {
        if (mode == null) return;
        try {
            if (WIFI.equals(mode)) startWifi();
            else shareCurrentWifi();
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
        if (mobileOnly && mobileNetwork == null) {
            retry("Waiting for mobile data. Turn on mobile data to continue.");
            return;
        }
        Network requested = mobileOnly ? mobileNetwork : null;
        if (!endpoint.equals(address.getHostAddress() + ":" + SocksProxy.PORT) ||
            !Objects.equals(boundNetwork, requested)) {
            closeProxies();
            boundNetwork = requested;
            if (!listen(address, "Current Wi-Fi")) return;
        }
        checkInternet(mobileOnly ? mobileNetwork : connectivity.getActiveNetwork(), connectivity);
        handler.postDelayed(this::reconnect, RETRY_MS);
    }

    private void checkInternet(Network network, ConnectivityManager connectivity) {
        if (network == null) { upstreamStatus = "Internet: no outbound network"; return; }
        var caps = connectivity.getNetworkCapabilities(network);
        String transport = caps == null ? "selected network" :
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ? "VPN" :
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "mobile data" :
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi" : "selected network";
        if (checkingInternet || (network.equals(checkedNetwork) && SystemClock.elapsedRealtime() < nextCheck)) return;
        checkingInternet = true;
        checkedNetwork = network;
        upstreamStatus = "Internet via " + transport + ": checking…";
        new Thread(() -> {
            boolean reachable = false;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) network.openConnection(
                    new URL("https://connectivitycheck.gstatic.com/generate_204"));
                connection.setConnectTimeout(4000);
                connection.setReadTimeout(4000);
                connection.setInstanceFollowRedirects(false);
                reachable = connection.getResponseCode() == 204;
            } catch (java.io.IOException ignored) {
            } finally {
                if (connection != null) connection.disconnect();
            }
            boolean result = reachable;
            handler.post(() -> {
                checkingInternet = false;
                if (!LAN.equals(mode) || proxy == null) return;
                var current = mobileOnly ? mobileNetwork : connectivity.getActiveNetwork();
                if (!network.equals(current)) { nextCheck = 0; return; }
                nextCheck = SystemClock.elapsedRealtime() + 30000;
                upstreamStatus = "Internet via " + transport + (result ? ": reachable" : ": check failed");
            });
        }, "jNet internet check").start();
    }

    private void retry(String message) {
        closeProxies();
        if (!status.equals(message)) stats.note(message);
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
        String password = wifiPassword(this);
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

    private boolean listen(InetAddress ip, String connectionDetails) {
        try {
            proxy = new SocksProxy(ip, true, boundNetwork, proxyPassword, stats);
            endpoint = ip.getHostAddress() + ":" + SocksProxy.PORT;
            diagnosticEndpoint = endpoint;
            stats.note("SOCKS5 listener ready");
            details = connectionDetails + "\nSOCKS5   " + endpoint;
            try {
                portal = new SetupPortal(this, ip.getHostAddress(), boundNetwork, proxyPassword);
                portal.start();
                portalUrl = portal.url();
                details += "\nSetup page   " + portalUrl;
            } catch (java.io.IOException e) {
                if (portal != null) portal.stop();
                portal = null;
                details += "\nSetup page unavailable";
            }
            status = "Sharing";
            return true;
        } catch (Exception e) {
            retry("Could not bind the proxy. Retrying…");
            return false;
        }
    }

    private void closeProxies() {
        portalUrl = "";
        if (portal != null) portal.stop();
        portal = null;
        if (proxy != null) proxy.close();
        proxy = null;
        endpoint = "";
        details = "";
        upstreamStatus = "";
        nextCheck = 0;
    }

    private void fail(String message) {
        stats.note(message);
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
        if (running) stats.note("Sharing stopped");
        active = false;
        mode = null;
        proxyPassword = "";
        if (mobileCallback != null) {
            try {
                getSystemService(ConnectivityManager.class).unregisterNetworkCallback(mobileCallback);
            } catch (IllegalArgumentException ignored) {}
        }
        handler.removeCallbacksAndMessages(null);
        closeProxies();
        removeGroup();
        details = "";
        if (running) status = "Idle";
        super.onDestroy();
    }
}
