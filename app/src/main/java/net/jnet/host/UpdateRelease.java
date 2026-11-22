package net.jnet.host;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class UpdateRelease {
    private static final long MAX_APK_BYTES = 200L * 1024 * 1024;

    final String version;
    final String url;
    final String sha256;
    final long size;

    UpdateRelease(String version, String url, String sha256, long size) {
        this.version = version;
        this.url = url;
        this.sha256 = sha256;
        this.size = size;
    }

    static UpdateRelease newest(JSONArray releases, String installedVersion) {
        if (!isVersion(installedVersion)) return null;
        UpdateRelease newest = null;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.optJSONObject(i);
            if (release == null || release.optBoolean("draft")) continue;
            String tag = release.optString("tag_name");
            if (!tag.startsWith("v") || !isVersion(tag.substring(1))) continue;
            String version = tag.substring(1);
            if (compare(version, installedVersion) <= 0 ||
                    (newest != null && compare(version, newest.version) <= 0)) continue;

            String expectedName = "jNet-" + version + ".apk";
            String expectedUrl = "https://github.com/Jacqueb-1337/jNet/releases/download/v" +
                version + "/" + expectedName;
            JSONArray assets = release.optJSONArray("assets");
            if (assets == null) continue;

            for (int j = 0; j < assets.length(); j++) {
                JSONObject asset = assets.optJSONObject(j);
                if (asset == null || !expectedName.equals(asset.optString("name")) ||
                        !expectedUrl.equals(asset.optString("browser_download_url"))) continue;
                String digest = asset.optString("digest", "");
                long size = asset.optLong("size");
                if (!digest.matches("sha256:[A-Fa-f0-9]{64}") ||
                        size <= 0 || size > MAX_APK_BYTES) continue;
                newest = new UpdateRelease(version, expectedUrl,
                    digest.substring(7).toLowerCase(Locale.ROOT), size);
                break;
            }
        }
        return newest;
    }

    static boolean matchesSha256(File file, String expected) throws IOException {
        if (expected == null || !expected.matches("[0-9a-f]{64}")) return false;
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        byte[] buffer = new byte[64 * 1024];
        try (FileInputStream input = new FileInputStream(file)) {
            int bytes;
            while ((bytes = input.read(buffer)) != -1) digest.update(buffer, 0, bytes);
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte item : digest.digest())
            hex.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return expected.equals(hex.toString());
    }

    private static boolean isVersion(String version) {
        return version != null && version.matches("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}");
    }

    static int compare(String first, String second) {
        if (!isVersion(first) || !isVersion(second))
            throw new IllegalArgumentException("Invalid app version.");
        String[] a = first.split("\\."), b = second.split("\\.");
        for (int i = 0; i < 3; i++) {
            int order = Integer.compare(Integer.parseInt(a[i]), Integer.parseInt(b[i]));
            if (order != 0) return order;
        }
        return 0;
    }
}
