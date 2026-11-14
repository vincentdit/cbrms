package tz.go.tirdo.cbrms.lookup;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** FR32 rate limits: X-Real-IP counts only when it comes from the console's proxy. */
class ClientAddressTest {

    private static List<InetAddress> ip(String a) {
        try {
            return List.of(InetAddress.getByName(a));
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void headerFromUntrustedCallerIsIgnored() {
        ClientAddress c = new ClientAddress("10.0.0.0/8", h -> List.of());
        assertEquals("203.0.113.9", c.of("203.0.113.9", "198.51.100.1"));
    }

    @Test
    void headerFromTrustedRangeIsUsed() {
        ClientAddress c = new ClientAddress("10.0.0.0/8, 172.16.0.0/12", h -> List.of());
        assertEquals("198.51.100.1", c.of("172.18.0.5", "198.51.100.1"));
        assertEquals("198.51.100.1", c.of("10.1.2.3", " 198.51.100.1 "));
        assertEquals("172.32.0.1", c.of("172.32.0.1", "198.51.100.1"));   // just outside /12
    }

    @Test
    void hostNameIsResolvedAndCached() {
        AtomicInteger lookups = new AtomicInteger();
        ClientAddress c = new ClientAddress("cbrms-console", h -> {
            lookups.incrementAndGet();
            return "cbrms-console".equals(h) ? ip("172.18.0.4") : List.of();
        });
        assertEquals("198.51.100.1", c.of("172.18.0.4", "198.51.100.1"));
        assertEquals("172.18.0.9", c.of("172.18.0.9", "198.51.100.1"));
        assertEquals(1, lookups.get());
    }

    @Test
    void garbageHeaderFallsBackToConnectingAddress() {
        ClientAddress c = new ClientAddress("127.0.0.1", h -> List.of());
        assertEquals("127.0.0.1", c.of("127.0.0.1", "not-an-ip.example"));
        assertEquals("127.0.0.1", c.of("127.0.0.1", "999.1.1.1"));
        assertEquals("127.0.0.1", c.of("127.0.0.1", ""));
        assertEquals("2001:db8::1", c.of("127.0.0.1", "2001:db8::1"));
    }

    @Test
    void ipv6RangesWork() {
        ClientAddress c = new ClientAddress("fd00::/8", h -> List.of());
        assertEquals("198.51.100.1", c.of("fd12:3456::1", "198.51.100.1"));
        assertEquals("2001:db8::5", c.of("2001:db8::5", "198.51.100.1"));
    }

    @Test
    void nothingTrustedByDefault() {
        ClientAddress c = new ClientAddress("", h -> List.of());
        assertEquals("10.0.0.1", c.of("10.0.0.1", "198.51.100.1"));
    }
}
