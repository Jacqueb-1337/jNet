package net.jnet.host;

import android.content.Context;
import android.net.Network;
import fi.iki.elonen.NanoHTTPD;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

final class SetupPortal extends NanoHTTPD {
    private record Asset(String label, String file, String release) {}
    private record Grant(String password, long expiryMs) {}

    private static final long GRANT_LIFETIME_MS = 10 * 60 * 1000L;
    private final Map<String, Asset> assets = new LinkedHashMap<>();
    private final Map<String, Grant> grants = new ConcurrentHashMap<>();
    private final Semaphore downloads = new Semaphore(3);
    private final Context context;
    private final Network upstream;
    private final String address;
    private final String proxyPassword;

    SetupPortal(Context context, String address, Network upstream, String proxyPassword) {
        super(address, 0);
        this.context = context.getApplicationContext();
        this.address = address;
        this.upstream = upstream;
        this.proxyPassword = proxyPassword == null ? "" : proxyPassword;
        String desktop = "2dust/v2rayN/releases/download/7.25.6/";
        String android = "MatsuriDayo/NekoBoxForAndroid/releases/download/1.4.2/";
        String flclash = "chen08209/FlClash/releases/download/v0.9.4/";

        // Preserve the original third-party installers and their existing URLs.
        assets.put("windows", new Asset("Windows x64", "v2rayN-windows-64.zip", desktop));
        assets.put("windows-arm", new Asset("Windows ARM64", "v2rayN-windows-arm64.zip", desktop));
        assets.put("android", new Asset("Android ARM64", "NekoBox-1.4.2-arm64-v8a.apk", android));
        assets.put("android-arm", new Asset("Android 32-bit ARM", "NekoBox-1.4.2-armeabi-v7a.apk", android));
        assets.put("linux", new Asset("Linux x64", "v2rayN-linux-64.zip", desktop));
        assets.put("linux-arm", new Asset("Linux ARM64", "v2rayN-linux-arm64.zip", desktop));

        // Official FlClash 0.9.4 release assets, streamed by the phone for offline clients.
        assets.put("flclash-android-arm64", new Asset("Android ARM64", "FlClash-0.9.4-android-arm64-v8a.apk", flclash));
        assets.put("flclash-android-arm", new Asset("Android 32-bit ARM", "FlClash-0.9.4-android-armeabi-v7a.apk", flclash));
        assets.put("flclash-android-x64", new Asset("Android x86_64", "FlClash-0.9.4-android-x86_64.apk", flclash));
        assets.put("flclash-windows", new Asset("Windows x64 installer", "FlClash-0.9.4-windows-amd64-setup.exe", flclash));
        assets.put("flclash-windows-arm", new Asset("Windows ARM64 installer", "FlClash-0.9.4-windows-arm64-setup.exe", flclash));
        assets.put("flclash-linux", new Asset("Linux x64 DEB", "FlClash-0.9.4-linux-amd64.deb", flclash));
        assets.put("flclash-linux-arm", new Asset("Linux ARM64 DEB", "FlClash-0.9.4-linux-arm64.deb", flclash));
        assets.put("flclash-linux-appimage", new Asset("Linux x64 AppImage", "FlClash-0.9.4-linux-amd64.AppImage", flclash));
        assets.put("flclash-linux-arm-appimage", new Asset("Linux ARM64 AppImage", "FlClash-0.9.4-linux-arm64.AppImage", flclash));
        assets.put("flclash-macos", new Asset("macOS Apple Silicon", "FlClash-0.9.4-macos-arm64.dmg", flclash));
        assets.put("flclash-macos-intel", new Asset("macOS Intel", "FlClash-0.9.4-macos-amd64.dmg", flclash));
    }

    String url() { return "http://" + address + ":" + getListeningPort() + "/"; }

    @Override public Response serve(IHTTPSession request) {
        String path = request.getUri();
        Method method = request.getMethod();
        if (method == Method.POST && path.equals("/flclash/claim")) return claim(request);
        if (method != Method.GET && method != Method.HEAD)
            return plain(Response.Status.METHOD_NOT_ALLOWED, "Use GET or HEAD.");

        if (path.equals("/") || path.equals("/generate_204") || path.equals("/hotspot-detect.html") ||
                path.equals("/connecttest.txt")) return page();

        if (path.equals("/flclash.yaml")) {
            if (!proxyPassword.isEmpty())
                return plain(Response.Status.FORBIDDEN, "This jNet connection uses a proxy password. Open the setup page and enter the password to create a short-lived import link.");
            return yaml("");
        }
        if (path.startsWith("/flclash/token/") && path.endsWith(".yaml")) {
            String token = path.substring("/flclash/token/".length(), path.length() - ".yaml".length());
            if (!token.matches("[0-9a-f]{32}"))
                return plain(Response.Status.NOT_FOUND, "Profile link not found.");
            Grant grant = grants.get(token);
            if (grant == null || System.currentTimeMillis() >= grant.expiryMs()) {
                grants.remove(token);
                return plain(Response.Status.GONE, "This import link expired. Open the jNet setup page for a fresh link.");
            }
            return yaml(grant.password());
        }
        Asset asset = path.startsWith("/download/") ? assets.get(path.substring(10)) : null;
        if (asset == null) return plain(Response.Status.NOT_FOUND, "Page not found.");
        return download(asset);
    }

    private Response page() {
        try (InputStream stream = context.getAssets().open("portal.html")) {
            String html = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                .replace("{{JNET_HOST}}", address)
                .replace("{{JNET_PORT}}", Integer.toString(SocksProxy.PORT))
                .replace("{{JNET_AUTH}}", Boolean.toString(!proxyPassword.isEmpty()));
            return noCache(newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html));
        } catch (IOException e) {
            return plain(Response.Status.INTERNAL_ERROR, "The setup page is unavailable.");
        }
    }

    private Response yaml(String password) {
        String profile = FlClashProfile.yaml(address, SocksProxy.PORT, password);
        Response result = newFixedLengthResponse(Response.Status.OK, "application/yaml; charset=utf-8", profile);
        result.addHeader("Content-Disposition", "attachment; filename=\"jnet-flclash.yaml\"");
        return noCache(result);
    }

    // The host never places the configured SOCKS password in the public captive
    // page or /flclash.yaml. The user supplies it explicitly on a trusted local
    // network and receives a time-limited unguessable URL for FlClash to fetch.
    private Response claim(IHTTPSession request) {
        if (proxyPassword.isEmpty()) return plain(Response.Status.BAD_REQUEST, "No password is configured.");
        String length = request.getHeaders().get("content-length");
        try {
            if (length == null || Long.parseLong(length) > 1024 || Long.parseLong(length) < 0)
                return plain(Response.Status.BAD_REQUEST, "Invalid request length.");
        } catch (NumberFormatException e) {
            return plain(Response.Status.BAD_REQUEST, "Invalid request length.");
        }
        try {
            request.parseBody(new java.util.HashMap<>());
        } catch (IOException | ResponseException e) {
            return plain(Response.Status.BAD_REQUEST, "Could not read the submitted password.");
        }
        String submitted = request.getParms().getOrDefault("password", "");
        byte[] expected = proxyPassword.getBytes(StandardCharsets.UTF_8);
        byte[] received = submitted.getBytes(StandardCharsets.UTF_8);
        if (received.length > 255 || !MessageDigest.isEqual(expected, received))
            return plain(Response.Status.FORBIDDEN, "Incorrect proxy password.");

        long now = System.currentTimeMillis();
        grants.entrySet().removeIf(entry -> entry.getValue().expiryMs() <= now);
        if (grants.size() >= 24) grants.clear();
        String token = UUID.randomUUID().toString().replace("-", "");
        grants.put(token, new Grant(proxyPassword, now + GRANT_LIFETIME_MS));
        return plain(Response.Status.OK, "/flclash/token/" + token + ".yaml");
    }

    private Response plain(Response.Status status, String message) {
        return noCache(newFixedLengthResponse(status, "text/plain; charset=utf-8", message));
    }

    private static Response noCache(Response response) {
        response.addHeader("Cache-Control", "no-store");
        response.addHeader("X-Content-Type-Options", "nosniff");
        return response;
    }

    private Response download(Asset asset) {
        if (!downloads.tryAcquire())
            return plain(Response.Status.SERVICE_UNAVAILABLE, "Downloads are busy. Try again shortly.");
        HttpURLConnection connection = null;
        boolean handedOff = false;
        try {
            URL url = new URL("https://github.com/" + asset.release() + asset.file());
            for (int hop = 0; hop < 5; hop++) {
                connection = (HttpURLConnection)(upstream == null ? url.openConnection() : upstream.openConnection(url));
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(25000);
                int code = connection.getResponseCode();
                if (code == 200) {
                    var source = connection;
                    var stream = new FilterInputStream(source.getInputStream()) {
                        private boolean closed;
                        @Override public synchronized void close() throws IOException {
                            if (closed) return;
                            closed = true;
                            try { super.close(); } finally { source.disconnect(); downloads.release(); }
                        }
                    };
                    long size = source.getContentLengthLong();
                    var reply = size >= 0
                        ? newFixedLengthResponse(Response.Status.OK, "application/octet-stream", stream, size)
                        : newChunkedResponse(Response.Status.OK, "application/octet-stream", stream);
                    reply.addHeader("Content-Disposition", "attachment; filename=\"" + asset.file() + "\"");
                    handedOff = true;
                    return reply;
                }
                String next = connection.getHeaderField("Location");
                if (code < 300 || code >= 400 || next == null) break;
                url = new URL(url, next);
                if (!url.getProtocol().equals("https") || !(url.getHost().equals("github.com") ||
                        url.getHost().endsWith(".githubusercontent.com"))) break;
                connection.disconnect();
            }
        } catch (IOException ignored) {
            // Display a local error when the phone cannot fetch an official release.
        } finally {
            if (!handedOff) {
                if (connection != null) connection.disconnect();
                downloads.release();
            }
        }
        return plain(Response.Status.SERVICE_UNAVAILABLE,
            "The phone could not retrieve the official release. Check its internet connection and try again.");
    }
}
