package tz.go.tirdo.cbrms.gepg.sim;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.go.tirdo.cbrms.common.Json;
import tz.go.tirdo.cbrms.gepg.GepgInboundService;
import tz.go.tirdo.cbrms.gepg.InboundResult;
import tz.go.tirdo.cbrms.gepg.PaymentMessage;

/**
 * Lets developers and testers act as GePG: pay a control number, replay a message, or issue control numbers now.
 * Exists only when cbrms.gepg.mode=simulator.
 */
@RestController
@RequestMapping("/sim/v1")
@ConditionalOnProperty(name = "cbrms.gepg.mode", havingValue = "simulator")
public class SimulatorController {

    /** transactionId and messageId are optional; reuse them to test duplicate handling (AT08, AT13). */
    public record SimulatedPayment(@NotBlank String controlNumber, @NotNull @Positive BigDecimal amount,
                                   String currency, String channel, String transactionId, String messageId, OffsetDateTime paidAt,
                                   Boolean tamperSignature) {}

    private final GepgInboundService inbound;
    private final SimulatorSigner signer;
    private final SimulatorGepgGateway gateway;

    public SimulatorController(GepgInboundService inbound, SimulatorSigner signer, SimulatorGepgGateway gateway) {
        this.inbound = inbound;
        this.signer = signer;
        this.gateway = gateway;
    }

    @PostMapping("/payments")
    public Map<String, Object> pay(@Valid @RequestBody SimulatedPayment p) {
        PaymentMessage m = new PaymentMessage(
                p.messageId() == null ? UUID.randomUUID().toString() : p.messageId(),
                p.transactionId() == null ? "SIMTX" + System.nanoTime() : p.transactionId(),
                p.controlNumber(), p.amount(), p.currency() == null ? "TZS" : p.currency(),
                p.channel() == null ? "SIM-BANK" : p.channel(),
                p.paidAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : p.paidAt());
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("messageId", m.messageId());
        raw.put("transactionId", m.transactionId());
        raw.put("controlNumber", m.controlNumber());
        raw.put("amount", m.amount());
        raw.put("currency", m.currency());
        raw.put("channel", m.channel());
        raw.put("paidAt", m.paidAt());
        String body = Json.write(raw);
        String signature = Boolean.TRUE.equals(p.tamperSignature()) ? signer.sign(body + "tampered") : signer.sign(body);
        InboundResult result = inbound.receivePayment(body, signature, m);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("result", result.name());
        response.put("messageId", m.messageId());
        response.put("transactionId", m.transactionId());
        return response;
    }

    @PostMapping("/control-numbers/issue")
    public Map<String, Object> issueNow() {
        return Map.of("issued", gateway.issuePendingControlNumbers(), "changesAnswered", gateway.answerPendingChanges());
    }

    /** Adds a payment that GePG reports but CBRMS never received, to test reconciliation differences. */
    public record ExtraLine(@NotNull java.time.LocalDate businessDate, @NotBlank String transactionId, String controlNumber,
                            @NotNull @Positive BigDecimal amount) {}

    @PostMapping("/reconciliation-extras")
    public Map<String, Object> extra(@Valid @RequestBody ExtraLine line) {
        gateway.addExtraReconciliationLine(line.businessDate(), line.transactionId(), line.controlNumber(), line.amount());
        return Map.of("added", line.transactionId());
    }
}
