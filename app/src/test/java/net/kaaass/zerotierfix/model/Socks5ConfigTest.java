package net.kaaass.zerotierfix.model;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class Socks5ConfigTest {
    @Test
    public void defaultsAreDisabledWithDefaultPort() {
        Socks5Config config = Socks5Config.defaults();
        assertFalse(config.isEnabled());
        assertEquals("", config.getHost());
        assertEquals(9050, config.getPort());
    }

    @Test
    public void enabledAndDisabledConfigurationsAreAccepted() {
        Socks5Config enabled = Socks5Config.create(true, "  proxy.example  ", "9050");
        Socks5Config disabled = Socks5Config.create(false, "", "9050");
        assertTrue(enabled.isEnabled());
        assertEquals("proxy.example", enabled.getHost());
        assertEquals(9050, enabled.getPort());
        assertFalse(disabled.isEnabled());
    }

    @Test
    public void acceptsValidPortBoundaries() {
        assertEquals(1, Socks5Config.parsePort("1"));
        assertEquals(65535, Socks5Config.parsePort("65535"));
    }

    @Test
    public void rejectsNonIntegerAndOutOfRangePorts() {
        assertInvalidPort("abc");
        assertInvalidPort("");
        assertInvalidPort("0");
        assertInvalidPort("65536");
    }

    @Test
    public void rejectsEmptyHostWhenEnabled() {
        try {
            Socks5Config.create(true, "   ", "9050");
            fail("Expected empty enabled host to be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected validation failure.
        }
    }

    private static void assertInvalidPort(String value) {
        try {
            Socks5Config.parsePort(value);
            fail("Expected invalid port to be rejected: " + value);
        } catch (IllegalArgumentException expected) {
            // Expected validation failure.
        }
    }
}
