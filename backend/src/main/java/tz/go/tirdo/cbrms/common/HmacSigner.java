package tz.go.tirdo.cbrms.common;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 signatures for events delivered to source platforms (FR36).
 * Signed text is "{epochSeconds}.{body}". Receivers reject signatures older than the allowed age.
 */
public final class HmacSigner {
    private HmacSigner() {}

    public static String sign(byte[] key, long epochSeconds, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] sig = mac.doFinal((epochSeconds + "." + body).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    public static boolean verify(byte[] key, long epochSeconds, String body, String signature, Instant now, Duration maxAge) {
        long age = now.getEpochSecond() - epochSeconds;
        if (age > maxAge.getSeconds() || age < -maxAge.getSeconds()) {
            return false;
        }
        byte[] expected = sign(key, epochSeconds, body).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = signature == null ? new byte[0] : signature.getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }
}
