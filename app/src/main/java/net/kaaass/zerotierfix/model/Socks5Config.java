package net.kaaass.zerotierfix.model;

/**
 * Validated local SOCKS5 settings. This class does not start or configure networking.
 */
public final class Socks5Config {
    public static final int DEFAULT_PORT = 9050;

    private final boolean enabled;
    private final String host;
    private final int port;

    private Socks5Config(boolean enabled, String host, int port) {
        this.enabled = enabled;
        this.host = host;
        this.port = port;
    }

    public static Socks5Config defaults() {
        return new Socks5Config(false, "", DEFAULT_PORT);
    }

    public static Socks5Config create(boolean enabled, String host, String portValue) {
        int port = parsePort(portValue);
        String normalizedHost = host == null ? "" : host.trim();
        if (enabled && normalizedHost.isEmpty()) {
            throw new IllegalArgumentException("SOCKS5 host is required when enabled");
        }
        return new Socks5Config(enabled, normalizedHost, port);
    }

    public static int parsePort(String portValue) {
        if (portValue == null || portValue.trim().isEmpty()) {
            throw new IllegalArgumentException("SOCKS5 port must be an integer");
        }
        final int port;
        try {
            port = Integer.parseInt(portValue.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("SOCKS5 port must be an integer", e);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("SOCKS5 port must be between 1 and 65535");
        }
        return port;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }
}
