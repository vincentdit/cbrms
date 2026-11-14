package tz.go.tirdo.cbrms.lookup;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * Works out which client address to rate limit. The X-Real-IP header is honoured only when the request comes
 * straight from a trusted proxy (the console's nginx); anyone else could put any address in it and so never be
 * limited. Entries are IP addresses, CIDR ranges (10.0.0.0/8, fd00::/8) or host names (cbrms-console), which are
 * looked up again at most once a minute so a recreated container with a new address is still recognised.
 */
public final class ClientAddress {
    private static final long RESOLVE_EVERY_MS = 60_000;

    private record Range(byte[] network, int prefix) {}

    private final List<Range> ranges = new ArrayList<>();
    private final List<String> hostNames = new ArrayList<>();
    private final Function<String, List<InetAddress>> resolver;
    private volatile List<InetAddress> resolved = List.of();
    private volatile long resolvedAt = Long.MIN_VALUE / 2;

    public ClientAddress(String trustedProxies) {
        this(trustedProxies, ClientAddress::lookUp);
    }

    ClientAddress(String trustedProxies, Function<String, List<InetAddress>> resolver) {
        this.resolver = resolver;
        if (trustedProxies == null) {
            return;
        }
        for (String raw : trustedProxies.split(",")) {
            String entry = raw.trim();
            if (entry.isEmpty()) {
                continue;
            }
            Range range = parseRange(entry);
            if (range != null) {
                ranges.add(range);
            } else {
                hostNames.add(entry);
            }
        }
    }

    /** The address to rate limit: the forwarded one from a trusted proxy, otherwise the connecting address. */
    public String of(String remoteAddr, String realIpHeader) {
        if (realIpHeader == null || realIpHeader.isBlank() || !isTrusted(remoteAddr)) {
            return remoteAddr;
        }
        String forwarded = realIpHeader.trim();
        return literal(forwarded) != null ? forwarded : remoteAddr;
    }

    boolean isTrusted(String remoteAddr) {
        byte[] addr = literal(remoteAddr);
        if (addr == null) {
            return false;
        }
        for (Range r : ranges) {
            if (inRange(addr, r)) {
                return true;
            }
        }
        if (hostNames.isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - resolvedAt > RESOLVE_EVERY_MS) {
            List<InetAddress> fresh = new ArrayList<>();
            for (String host : hostNames) {
                fresh.addAll(resolver.apply(host));
            }
            resolved = List.copyOf(fresh);
            resolvedAt = now;
        }
        for (InetAddress a : resolved) {
            if (Arrays.equals(a.getAddress(), addr)) {
                return true;
            }
        }
        return false;
    }

    private static List<InetAddress> lookUp(String host) {
        try {
            return List.of(InetAddress.getAllByName(host));
        } catch (UnknownHostException e) {
            return List.of();   // e.g. running outside Docker: the name simply matches nothing
        }
    }

    private static Range parseRange(String entry) {
        String[] parts = entry.split("/", 2);
        byte[] net = literal(parts[0]);
        if (net == null) {
            return null;
        }
        int prefix = net.length * 8;
        if (parts.length == 2) {
            try {
                prefix = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Bad trusted proxy range: " + entry);
            }
            if (prefix < 0 || prefix > net.length * 8) {
                throw new IllegalArgumentException("Bad trusted proxy range: " + entry);
            }
        }
        return new Range(net, prefix);
    }

    private static boolean inRange(byte[] addr, Range r) {
        if (addr.length != r.network().length) {
            return false;
        }
        int bits = addr.length * 8;
        if (r.prefix() == 0) {
            return true;
        }
        BigInteger mask = BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE)
                .xor(BigInteger.ONE.shiftLeft(bits - r.prefix()).subtract(BigInteger.ONE));
        return new BigInteger(1, addr).and(mask).equals(new BigInteger(1, r.network()).and(mask));
    }

    /** Parses an IPv4 or IPv6 literal without any DNS lookup; null if it is not one. */
    static byte[] literal(String s) {
        if (s == null || s.isEmpty() || s.length() > 45) {
            return null;
        }
        String t = s.startsWith("[") && s.endsWith("]") ? s.substring(1, s.length() - 1) : s;
        boolean v4 = t.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        boolean v6 = t.contains(":") && t.matches("[0-9A-Fa-f:.%a-zA-Z0-9]+");
        if (v4) {
            for (String p : t.split("\\.")) {
                if (Integer.parseInt(p) > 255) {
                    return null;
                }
            }
        }
        if (!v4 && !v6) {
            return null;
        }
        try {
            return InetAddress.getByName(t).getAddress();   // literal: no lookup happens
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
