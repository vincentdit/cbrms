package tz.go.tirdo.cbrms.gepg;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A cancellation or expiry change for a bill GePG already holds. newExpiry is set only for EXPIRY_CHANGE. */
public record BillChangeSubmission(UUID changeRequestId, String kind, String gepgBillId, String controlNumber,
                                   String spCode, String reason, OffsetDateTime newExpiry) {}
