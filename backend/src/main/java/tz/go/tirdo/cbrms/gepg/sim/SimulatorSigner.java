package tz.go.tirdo.cbrms.gepg.sim;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tz.go.tirdo.cbrms.common.HmacSigner;
import tz.go.tirdo.cbrms.config.CbrmsProperties;
import tz.go.tirdo.cbrms.gepg.MessageVerifier;

/**
 * Simulator message protection: "{epochSeconds}.{hmac}" over the raw payload.
 * Stands in for the GePG signature scheme until the assigned contract and certificates are available.
 */
@Component
@ConditionalOnProperty(name = "cbrms.gepg.mode", havingValue = "simulator")
public class SimulatorSigner implements MessageVerifier {
    private final byte[] key;

    public SimulatorSigner(CbrmsProperties props) {
        String secret = props.gepg().simulatorSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("cbrms.gepg.simulator-secret must be set in simulator mode");
        }
        this.key = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String sign(String raw) {
        long ts = Instant.now().getEpochSecond();
        return ts + "." + HmacSigner.sign(key, ts, raw);
    }

    @Override
    public boolean verify(String raw, String signature) {
        if (signature == null) {
            return false;
        }
        int dot = signature.indexOf('.');
        if (dot <= 0) {
            return false;
        }
        try {
            long ts = Long.parseLong(signature.substring(0, dot));
            return HmacSigner.verify(key, ts, raw, signature.substring(dot + 1), Instant.now(), Duration.ofMinutes(5));
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
