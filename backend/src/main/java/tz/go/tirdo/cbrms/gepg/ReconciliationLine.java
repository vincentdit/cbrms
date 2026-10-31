package tz.go.tirdo.cbrms.gepg;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** One payment as GePG reports it in the daily reconciliation. */
public record ReconciliationLine(String transactionId, String controlNumber, BigDecimal amount, String currency, OffsetDateTime paidAt) {}
