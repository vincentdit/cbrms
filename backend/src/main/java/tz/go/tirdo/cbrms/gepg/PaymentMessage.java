package tz.go.tirdo.cbrms.gepg;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Normalised payment notification. transactionId is GePG's unique payment identity (FR13). */
public record PaymentMessage(String messageId, String transactionId, String controlNumber, BigDecimal amount,
                             String currency, String channel, OffsetDateTime paidAt) {}
