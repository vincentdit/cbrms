package tz.go.tirdo.cbrms.config;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** All CBRMS settings live under the "cbrms" prefix (see application.yml). */
@ConfigurationProperties("cbrms")
public record CbrmsProperties(Gepg gepg, Events events, Billing billing, Map<String, String> webhookKeys) {

    /**
     * @param mode                     "simulator" (local and test) or "contract" (assigned GePG specification)
     * @param providerCode             gepg_provider.code used for this deployment
     * @param simulatorSecret          HMAC key the simulator signs messages with (simulator mode only)
     * @param ackUnknownEscalation     open an ACK_UNKNOWN case after this long (SOP-02: 30 minutes)
     * @param controlNumberEscalation  open a CONTROL_NUMBER_DELAY case after this long (SOP-02: 60 minutes)
     */
    public record Gepg(String mode, String providerCode, String simulatorSecret,
                       Duration ackUnknownEscalation, Duration controlNumberEscalation) {}

    public record Events(Duration baseBackoff, Duration maxBackoff, int maxAttempts, Duration lease,
                         int batchSize, Duration cursorSafetyWindow, Duration httpTimeout) {}

    public record Billing(int defaultValidityDays, int maxLines, Duration idempotencyRetention) {}
}
