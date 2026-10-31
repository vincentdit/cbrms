package tz.go.tirdo.cbrms.lookup;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.go.tirdo.cbrms.audit.AuditService;
import tz.go.tirdo.cbrms.common.ApiException;
import tz.go.tirdo.cbrms.common.Digests;
import tz.go.tirdo.cbrms.notifications.NotificationService;

/**
 * Public bill status lookup for clients (FR32, AT19): control number plus a one-time code sent by SMS
 * to the phone on the bill. Responses never reveal whether a control number exists, codes are stored only
 * as hashes, a code locks after 5 wrong tries, and each address is rate limited.
 */
@RestController
@RequestMapping("/public/v1/bill-lookup")
public class PublicLookupController {
    private static final String GENERIC = "If this control number is registered, a code has been sent to the phone number on the bill.";
    private static final int MAX_TRIES = 5;
    private static final int CODES_PER_15_MIN = 3;
    private static final int REQUESTS_PER_10_MIN = 20;

    public record CodeRequest(@NotBlank @Pattern(regexp = "[0-9 ]{8,20}") String controlNumber) {}

    public record VerifyRequest(@NotBlank @Pattern(regexp = "[0-9 ]{8,20}") String controlNumber,
                                @NotBlank @Pattern(regexp = "[0-9]{6}") String code) {}

    public record PublicBill(String controlNumber, String description, String payer, String amount, String paid,
                             String outstanding, String currency, String status, String dueDate) {}

    private final JdbcClient jdbc;
    private final NotificationService notices;
    private final AuditService audit;
    private final String pepper;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Deque<Long>> recent = new ConcurrentHashMap<>();

    public PublicLookupController(JdbcClient jdbc, NotificationService notices, AuditService audit,
                                  @Value("${cbrms.lookup.pepper:local-development-pepper}") String pepper) {
        this.jdbc = jdbc;
        this.notices = notices;
        this.audit = audit;
        this.pepper = pepper;
    }

    @PostMapping("/code")
    @Transactional
    public ResponseEntity<Map<String, String>> sendCode(@Valid @RequestBody CodeRequest body, HttpServletRequest request) {
        limit(request);
        String cn = body.controlNumber().replace(" ", "");
        Optional<UUID> billId = jdbc.sql("SELECT bill_id FROM external_bill WHERE control_number = :cn").param("cn", cn).query(UUID.class).optional();
        long issued = jdbc.sql("SELECT count(*) FROM lookup_otp WHERE control_number = :cn AND created_at > now() - interval '15 minutes'")
                .param("cn", cn).query(Long.class).single();
        if (billId.isPresent() && issued < CODES_PER_15_MIN) {
            String code = String.format("%06d", random.nextInt(1_000_000));
            jdbc.sql("INSERT INTO lookup_otp (control_number, code_hash, expires_at) VALUES (:cn, :hash, now() + interval '5 minutes')")
                .param("cn", cn).param("hash", hash(cn, code)).update();
            notices.enqueueSmsForBill(billId.get(), "LOOKUP_CODE", Map.of("code", code));
        }
        return ResponseEntity.accepted().body(Map.of("message", GENERIC));
    }

    @PostMapping("/verify")
    @Transactional
    public PublicBill verify(@Valid @RequestBody VerifyRequest body, HttpServletRequest request) {
        limit(request);
        String cn = body.controlNumber().replace(" ", "");
        record Otp(UUID id, String hash, int attempts) {}
        Optional<Otp> otp = jdbc.sql("""
                SELECT id, code_hash, attempts FROM lookup_otp
                 WHERE control_number = :cn AND used_at IS NULL AND expires_at > now()
                 ORDER BY created_at DESC LIMIT 1 FOR UPDATE
                """)
            .param("cn", cn)
            .query((rs, n) -> new Otp(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3)))
            .optional();
        ApiException invalid = new ApiException(400, "CBR-OTP-001", "The code is not correct or has expired. Request a new code.");
        if (otp.isEmpty()) {
            throw invalid;
        }
        boolean ok = MessageDigest.isEqual(otp.get().hash().getBytes(StandardCharsets.US_ASCII), hash(cn, body.code()).getBytes(StandardCharsets.US_ASCII));
        if (!ok) {
            int attempts = otp.get().attempts() + 1;
            String sql = attempts >= MAX_TRIES
                    ? "UPDATE lookup_otp SET attempts = :a, used_at = now() WHERE id = :id"
                    : "UPDATE lookup_otp SET attempts = :a WHERE id = :id";
            jdbc.sql(sql).param("a", attempts).param("id", otp.get().id()).update();
            throw invalid;
        }
        jdbc.sql("UPDATE lookup_otp SET used_at = now() WHERE id = :id").param("id", otp.get().id()).update();
        PublicBill bill = jdbc.sql("""
                SELECT e.control_number, b.description, p.display_name, b.total,
                       coalesce((SELECT sum(a.amount) FROM payment_allocation a WHERE a.bill_id = b.id), 0),
                       b.currency, b.collection_state, b.registration_state, b.due_date
                  FROM external_bill e JOIN bill b ON b.id = e.bill_id JOIN payer p ON p.id = b.payer_id
                 WHERE e.control_number = :cn
                """)
            .param("cn", cn)
            .query((rs, n) -> {
                BigDecimal total = rs.getBigDecimal(4);
                BigDecimal paid = rs.getBigDecimal(5);
                String status = "Cancelled".equals(rs.getString(8)) ? "Cancelled" : rs.getString(7);
                return new PublicBill(rs.getString(1), rs.getString(2), maskName(rs.getString(3)), total.toPlainString(), paid.toPlainString(),
                        total.subtract(paid).max(BigDecimal.ZERO).toPlainString(), rs.getString(6), status, String.valueOf(rs.getObject(9)));
            })
            .single();
        audit.record("public", "BILL_LOOKUP", "control-number:" + cn, null, null);
        return bill;
    }

    private String hash(String cn, String code) {
        return Digests.sha256(pepper + "|" + cn + "|" + code);
    }

    /** "Kilimanjaro Breweries Ltd" -> "Ki*********** B******** L**" */
    static String maskName(String name) {
        StringBuilder out = new StringBuilder();
        for (String word : name.split(" ")) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            int keep = Math.min(word.length(), out.isEmpty() ? 2 : 1);
            out.append(word, 0, keep).append("*".repeat(word.length() - keep));
        }
        return out.toString();
    }

    private void limit(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Real-IP");
        String ip = forwarded != null && !forwarded.isBlank() ? forwarded : request.getRemoteAddr();
        long now = System.currentTimeMillis();
        Deque<Long> times = recent.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > 600_000) {
                times.pollFirst();
            }
            if (times.size() >= REQUESTS_PER_10_MIN) {
                throw new ApiException(429, "CBR-RAT-001", "Too many requests. Wait a few minutes and try again.");
            }
            times.addLast(now);
        }
    }
}
