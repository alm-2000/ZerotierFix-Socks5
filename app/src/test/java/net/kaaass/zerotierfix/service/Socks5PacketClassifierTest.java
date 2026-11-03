package net.kaaass.zerotierfix.service;

import org.junit.Test;

import java.net.InetAddress;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class Socks5PacketClassifierTest {
    private static final InetAddress SOCKS = address("10.10.0.9");

    @Test
    public void socksEndpointIsZeroTierDirectBeforeProxyClassification() {
        assertEquals(Socks5PacketClassifier.Decision.ZERO_TIER,
                Socks5PacketClassifier.classify(packet(6, "10.10.0.9"), SOCKS,
                        Collections.emptyList(), true));
    }

    @Test
    public void overlayPrefixIsDirectEvenWhenDefaultRouteExists() {
        Route overlay = new Route(address("10.10.0.0"), 24);
        Route defaultRoute = new Route(address("0.0.0.0"), 0);
        assertEquals(Socks5PacketClassifier.Decision.ZERO_TIER,
                Socks5PacketClassifier.classify(packet(6, "10.10.0.12"), SOCKS,
                        java.util.Arrays.asList(defaultRoute, overlay), true));
    }

    @Test
    public void internetIpv4TcpUsesHevOnlyWhenAvailable() {
        assertEquals(Socks5PacketClassifier.Decision.HEV,
                Socks5PacketClassifier.classify(packet(6, "1.1.1.1"), SOCKS,
                        Collections.singletonList(new Route(address("0.0.0.0"), 0)), true));
        assertEquals(Socks5PacketClassifier.Decision.BLOCK,
                Socks5PacketClassifier.classify(packet(6, "1.1.1.1"), SOCKS,
                        Collections.emptyList(), false));
    }

    @Test
    public void unsupportedProtocolsAndMalformedPacketsAreBlocked() {
        assertEquals(Socks5PacketClassifier.Decision.BLOCK,
                Socks5PacketClassifier.classify(packet(17, "1.1.1.1"), SOCKS,
                        Collections.emptyList(), true));
        assertEquals(Socks5PacketClassifier.Decision.BLOCK,
                Socks5PacketClassifier.classify(new byte[7], SOCKS,
                        Collections.emptyList(), true));
    }

    private static byte[] packet(int protocol, String destination) {
        byte[] bytes = new byte[40];
        bytes[0] = 0x45;
        bytes[2] = 0;
        bytes[3] = 40;
        bytes[9] = (byte) protocol;
        System.arraycopy(address("198.18.0.2").getAddress(), 0, bytes, 12, 4);
        System.arraycopy(address(destination).getAddress(), 0, bytes, 16, 4);
        return bytes;
    }

    private static InetAddress address(String value) {
        try { return InetAddress.getByName(value); }
        catch (Exception e) { throw new AssertionError(e); }
    }
}
