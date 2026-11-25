package moe.matsuri.nb4a;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RoutePredicateTest {
    @Test public void keepsRulesWithOnlyNetworkProtocolOrSourcePort() {
        SingBoxOptions.Rule_DefaultOptions rule = new SingBoxOptions.Rule_DefaultOptions();
        assertTrue(SingBoxOptionsUtilKt.checkEmpty(rule));

        rule.network = Collections.singletonList("udp");
        assertFalse(SingBoxOptionsUtilKt.checkEmpty(rule));
        rule.network = null;
        rule.protocol = Collections.singletonList("quic");
        assertFalse(SingBoxOptionsUtilKt.checkEmpty(rule));
        rule.protocol = null;
        rule.source_port = Collections.singletonList(1234);
        assertFalse(SingBoxOptionsUtilKt.checkEmpty(rule));
        rule.source_port = null;
        rule.source_port_range = Collections.singletonList("1200:1300");
        assertFalse(SingBoxOptionsUtilKt.checkEmpty(rule));
        rule.source_port_range = null;
        rule.ip_is_private = true;
        assertFalse(SingBoxOptionsUtilKt.checkEmpty(rule));
    }
}
