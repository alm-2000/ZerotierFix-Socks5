package net.kaaass.zerotierfix.service;

import java.net.InetAddress;
import java.util.Collection;

/** Destination-only policy for Phase 3's IPv4 TCP SOCKS route. */
final class Socks5PacketClassifier {
    enum Decision { ZERO_TIER, HEV, BLOCK }

    private Socks5PacketClassifier() { }

    static Decision classify(byte[] packet, InetAddress socksEndpoint,
                             Collection<Route> routes, boolean hevAvailable) {
        if (!isValidIpv4(packet)) return Decision.BLOCK;
        InetAddress destination = ipv4At(packet, 16);
        if ((socksEndpoint != null && socksEndpoint.equals(destination))
                || belongsToSpecificRoute(destination, routes)) {
            return Decision.ZERO_TIER;
        }
        if ((packet[9] & 0xff) == 6 && hevAvailable) return Decision.HEV;
        return Decision.BLOCK;
    }

    static boolean isValidIpv4(byte[] packet) {
        if (packet == null || packet.length < 20 || (packet[0] & 0xf0) != 0x40) return false;
        int headerLength = (packet[0] & 0x0f) * 4;
        if (headerLength < 20 || packet.length < headerLength) return false;
        int totalLength = ((packet[2] & 0xff) << 8) | (packet[3] & 0xff);
        return totalLength >= headerLength && totalLength <= packet.length;
    }

    private static boolean belongsToSpecificRoute(InetAddress destination, Collection<Route> routes) {
        for (Route route : routes) {
            if (route.getPrefix() > 0 && route.belongsToRoute(destination)) return true;
        }
        return false;
    }

    private static InetAddress ipv4At(byte[] packet, int offset) {
        try {
            byte[] address = new byte[4];
            System.arraycopy(packet, offset, address, 0, address.length);
            return InetAddress.getByAddress(address);
        } catch (Exception impossible) {
            throw new IllegalArgumentException(impossible);
        }
    }
}
