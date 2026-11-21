package net.jnet.host;

import android.net.Network;
import fi.iki.elonen.NanoHTTPD;
import java.io.FilterInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

final class SetupPortal extends NanoHTTPD {
    private record Asset(String label, String file, String release) {}
    private final Map<String, Asset> assets = new LinkedHashMap<>();
    private final Semaphore downloads = new Semaphore(3);
    private final Network upstream;
    private final String address;
    private final boolean login;

    SetupPortal(String address, Network upstream, boolean login) {
        super(address, 0);
        this.address = address;
        this.upstream = upstream;
        this.login = login;
        String desktop = "2dust/v2rayN/releases/download/7.25.6/";
        String android = "MatsuriDayo/NekoBoxForAndroid/releases/download/1.4.2/";
        assets.put("windows", new Asset("Windows x64", "v2rayN-windows-64.zip", desktop));
        assets.put("windows-arm", new Asset("Windows ARM64", "v2rayN-windows-arm64.zip", desktop));
        assets.put("android", new Asset("Android ARM64", "NekoBox-1.4.2-arm64-v8a.apk", android));
        assets.put("android-arm", new Asset("Android 32-bit ARM", "NekoBox-1.4.2-armeabi-v7a.apk", android));
        assets.put("linux", new Asset("Linux x64", "v2rayN-linux-64.zip", desktop));
        assets.put("linux-arm", new Asset("Linux ARM64", "v2rayN-linux-arm64.zip", desktop));
    }

    String url() { return "http://" + address + ":" + getListeningPort() + "/"; }

    @Override public Response serve(IHTTPSession request) {
        if (request.getMethod() != Method.GET && request.getMethod() != Method.HEAD)
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Use GET.");
        String path = request.getUri();
        if (path.equals("/") || path.equals("/generate_204") || path.equals("/hotspot-detect.html") ||
                path.equals("/connecttest.txt")) {
            var html = new StringBuilder("<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>jNet setup</title><style>body{font:1rem/1.5 system-ui;max-width:36rem;margin:auto;padding:1.25rem}li{margin:.65rem 0}</style><h1>Connect through jNet</h1><p>Download a proxy client using the host phone’s internet connection.</p><ul>");
            assets.forEach((key, asset) -> html.append("<li><a href='/download/").append(key)
                .append("'>").append(asset.label()).append("</a> · ").append(asset.file()).append("</li>"));
            html.append("</ul><p>On Windows or Linux, extract v2rayN. On Android, install NekoBox. On iOS, use Potatso from the App Store.</p><p>Add a SOCKS5 server at <strong>")
                .append(address).append("</strong>, port <strong>").append(SocksProxy.PORT).append("</strong>. ")
                .append(login ? "Use username jnet and ask the host for the proxy password. " : "")
                .append("Select the server and enable TUN or VPN mode.</p>");
            return newFixedLengthResponse(Response.Status.OK, MIME_HTML, html.toString());
        }
        Asset asset = path.startsWith("/download/") ? assets.get(path.substring(10)) : null;
        if (asset == null) return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Page not found.");
        if (!downloads.tryAcquire()) return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT, "Downloads are busy. Try again shortly.");
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
                    var reply = size >= 0 ? newFixedLengthResponse(Response.Status.OK, "application/octet-stream", stream, size)
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
            // Show a local error even when the phone cannot reach the release host.
        } finally {
            if (!handedOff) {
                if (connection != null) connection.disconnect();
                downloads.release();
            }
        }
        return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT,
            "The phone could not retrieve the official release. Check its internet connection and try again.");
    }
}
