package tz.go.tirdo.cbrms.gepg;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Everything the adapter needs to register one bill with GePG. */
public record BillSubmission(
        UUID billId, String gepgBillId, String spCode, String subSpCode, String systemId,
        BigDecimal amount, String currency, String paymentMode, OffsetDateTime expiresAt,
        String payerReference, String payerName, String payerPhone, String payerEmail,
        String description, List<Item> items) {

    public record Item(String gfsCode, BigDecimal amount) {}
}
