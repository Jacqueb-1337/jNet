package net.jnet.host;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class UpdateReleaseTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private static JSONObject release(String version, String digest, String url, boolean draft) {
        JSONObject asset = new JSONObject();
        asset.put("name", "jNet-" + version + ".apk");
        asset.put("browser_download_url", url);
        asset.put("digest", digest);
        asset.put("size", 12345);
        JSONObject release = new JSONObject();
        release.put("tag_name", "v" + version);
        release.put("draft", draft);
        release.put("assets", new JSONArray().put(asset));
        return release;
    }

    private static String validUrl(String version) {
        return "https://github.com/Jacqueb-1337/jNet/releases/download/v" +
            version + "/jNet-" + version + ".apk";
    }

    @Test public void choosesHighestNewerSignedRelease() {
        String digest = "sha256:" + "a".repeat(64);
        JSONArray releases = new JSONArray()
            .put(release("0.1.25", digest, validUrl("0.1.25"), false))
            .put(release("0.1.30", digest, validUrl("0.1.30"), false))
            .put(release("0.1.29", digest, validUrl("0.1.29"), false));
        UpdateRelease match = UpdateRelease.newest(releases, "0.1.26");
        assertNotNull(match);
        assertEquals("0.1.30", match.version);
        assertEquals(validUrl("0.1.30"), match.url);
        assertEquals("a".repeat(64), match.sha256);
        assertEquals(12345, match.size);
    }

    @Test public void ignoresDraftsMalformedVersionsAndTamperedAssets() {
        String digest = "sha256:" + "b".repeat(64);
        JSONArray releases = new JSONArray()
            .put(release("0.2.0", digest, validUrl("0.2.0"), true))
            .put(release("0.1.99", digest, "https://example.org/evil.apk", false))
            .put(release("0.1.99", "", validUrl("0.1.99"), false))
            .put(release("0.1.98", digest, validUrl("0.1.98"), false));
        UpdateRelease match = UpdateRelease.newest(releases, "0.1.26");
        assertNotNull(match);
        assertEquals("0.1.98", match.version);
        assertNull(UpdateRelease.newest(releases, "0.1.98"));
    }

    @Test public void comparesNumericVersions() {
        assertTrue(UpdateRelease.compare("0.1.27", "0.1.26") > 0);
        assertTrue(UpdateRelease.compare("1.0.0", "0.99.999") > 0);
        assertEquals(0, UpdateRelease.compare("1.2.3", "1.2.3"));
        assertThrows(IllegalArgumentException.class, () -> UpdateRelease.compare("bad", "1.2.3"));
    }

    @Test public void verifiesActualDownloadedFile() throws Exception {
        File file = temp.newFile("jnet.apk");
        byte[] content = "signed-jnet-update".getBytes(StandardCharsets.UTF_8);
        Files.write(file.toPath(), content);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
        String valid = HexFormat.of().formatHex(digest);
        assertTrue(UpdateRelease.matchesSha256(file, valid));
        assertFalse(UpdateRelease.matchesSha256(file, "0".repeat(64)));
        assertFalse(UpdateRelease.matchesSha256(file, "invalid"));
    }
}
