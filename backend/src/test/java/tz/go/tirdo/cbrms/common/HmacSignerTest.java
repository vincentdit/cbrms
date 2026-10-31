package tz.go.tirdo.cbrms.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** AT21: tampered and stale deliveries are rejected. */
class HmacSignerTest {
    private static final byte[] KEY = "test-key-material".getBytes(StandardCharsets.UTF_8);

    @Test
    void validSignatureVerifies() {
        Instant now = Instant.parse("2026-11-02T08:00:00Z");
        String sig = HmacSigner.sign(KEY, now.getEpochSecond(), "{\"a\":1}");
        assertTrue(HmacSigner.verify(KEY, now.getEpochSecond(), "{\"a\":1}", sig, now, Duration.ofMinutes(5)));
    }

    @Test
    void tamperedBodyFails() {
        Instant now = Instant.parse("2026-11-02T08:00:00Z");
        String sig = HmacSigner.sign(KEY, now.getEpochSecond(), "{\"a\":1}");
        assertFalse(HmacSigner.verify(KEY, now.getEpochSecond(), "{\"a\":2}", sig, now, Duration.ofMinutes(5)));
    }

    @Test
    void staleSignatureFails() {
        Instant signed = Instant.parse("2026-11-02T08:00:00Z");
        String sig = HmacSigner.sign(KEY, signed.getEpochSecond(), "x");
        assertFalse(HmacSigner.verify(KEY, signed.getEpochSecond(), "x", sig, signed.plusSeconds(301), Duration.ofMinutes(5)));
    }

    @Test
    void wrongKeyFails() {
        Instant now = Instant.parse("2026-11-02T08:00:00Z");
        String sig = HmacSigner.sign(KEY, now.getEpochSecond(), "x");
        assertFalse(HmacSigner.verify("other".getBytes(StandardCharsets.UTF_8), now.getEpochSecond(), "x", sig, now, Duration.ofMinutes(5)));
    }
}
