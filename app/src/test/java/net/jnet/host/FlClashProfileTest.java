package net.jnet.host;

import org.junit.Test;
import org.yaml.snakeyaml.Yaml;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class FlClashProfileTest {
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parsed(String profile) {
        return new Yaml().load(profile);
    }

    @SuppressWarnings("unchecked")
    @Test public void noPasswordProfileUsesJnetAsOnlyProxyAndDirectLan() {
        Map<String, Object> config = parsed(FlClashProfile.yaml("192.168.49.1", 1080, ""));
        assertEquals("rule", config.get("mode"));
        List<Map<String, Object>> proxies = (List<Map<String, Object>>) config.get("proxies");
        assertEquals(1, proxies.size());
        Map<String, Object> node = proxies.get(0);
        assertEquals("socks5", node.get("type"));
        assertEquals("192.168.49.1", node.get("server"));
        assertEquals(1080, node.get("port"));
        assertEquals(true, node.get("udp"));
        assertFalse(node.containsKey("username"));
        assertFalse(node.containsKey("password"));

        List<Map<String, Object>> groups = (List<Map<String, Object>>) config.get("proxy-groups");
        assertEquals("jNet", groups.get(0).get("name"));
        assertEquals(List.of("jNet SOCKS5"), groups.get(0).get("proxies"));
        List<String> rules = (List<String>) config.get("rules");
        assertEquals("MATCH,jNet", rules.get(rules.size() - 1));
        assertTrue(((List<String>) config.get("rules")).contains("IP-CIDR,192.168.49.1/32,DIRECT,no-resolve"));
        // Enabling VPN/TUN belongs to FlClash/OS, never assumed from a YAML import.
        assertFalse(config.containsKey("tun"));
        @SuppressWarnings("unchecked") Map<String, Object> dns = (Map<String, Object>) config.get("dns");
        assertEquals(List.of("https://1.1.1.1/dns-query#jNet", "https://8.8.8.8/dns-query#jNet"), dns.get("nameserver"));
    }

    @SuppressWarnings("unchecked")
    @Test public void passwordIsEscapedAndRoundTripsAsYamlString() {
        String password = "secret:\"#\n\\ \u0001";
        Map<String, Object> config = parsed(FlClashProfile.yaml("10.0.0.1", 1080, password));
        Map<String, Object> node = ((List<Map<String, Object>>) config.get("proxies")).get(0);
        assertEquals("jnet", node.get("username"));
        assertEquals(password, node.get("password"));
    }

    @Test public void rejectsInvalidEndpointAndPort() {
        assertThrows(IllegalArgumentException.class, () -> FlClashProfile.yaml("bad.host", 1080, ""));
        assertThrows(IllegalArgumentException.class, () -> FlClashProfile.yaml("192.168.49.1", 0, ""));
        assertThrows(IllegalArgumentException.class, () -> FlClashProfile.yaml("192.168.49.1", 65536, ""));
    }
}
