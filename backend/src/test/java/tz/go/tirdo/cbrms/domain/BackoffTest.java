package tz.go.tirdo.cbrms.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.Test;

class BackoffTest {
    @Test
    void growsExponentiallyAndCaps() {
        Backoff b = new Backoff(Duration.ofSeconds(5), Duration.ofMinutes(15), 0.0);
        Random r = new Random(1);
        assertEquals(Duration.ofSeconds(5), b.delay(1, r));
        assertEquals(Duration.ofSeconds(10), b.delay(2, r));
        assertEquals(Duration.ofSeconds(40), b.delay(4, r));
        assertEquals(Duration.ofMinutes(15), b.delay(20, r));
    }

    @Test
    void jitterStaysInBounds() {
        Backoff b = new Backoff(Duration.ofSeconds(10), Duration.ofMinutes(15), 0.2);
        Random r = new Random(7);
        for (int i = 0; i < 100; i++) {
            long ms = b.delay(1, r).toMillis();
            assertTrue(ms >= 8000 && ms <= 12000, "delay " + ms);
        }
    }
}
