package io.nekohasekai.sagernet.group;

import org.junit.Test;
import org.yaml.snakeyaml.error.YAMLException;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class RawUpdaterYamlSecurityTest {
    @Test public void acceptsOrdinaryClashSubscriptionYaml() {
        Map<?, ?> parsed = ClashSubscriptionYaml.load(
                "proxies:\n  - name: !!str 123\n    type: socks5\n    server: example.com\n    port: 1080\n");
        List<?> proxies = (List<?>) parsed.get("proxies");
        Map<?, ?> proxy = (Map<?, ?>) proxies.get(0);
        assertEquals("123", proxy.get("name"));
        assertEquals("socks5", proxy.get("type"));
        assertEquals("example.com", proxy.get("server"));
        assertEquals(1080, proxy.get("port"));
    }

    @Test public void rejectsMalformedSubscriptionStructure() {
        assertThrows(YAMLException.class, () -> ClashSubscriptionYaml.load("[]"));
        assertThrows(YAMLException.class, () -> ClashSubscriptionYaml.load("proxies: {}"));
        assertThrows(YAMLException.class, () -> ClashSubscriptionYaml.load("proxies: [not-a-node]"));
    }

    @Test public void rejectsUnsupportedCustomTags() {
        assertThrows(YAMLException.class,
                () -> ClashSubscriptionYaml.load("proxies:\n  - !unsupported {name: fixture}\n"));
    }
}
