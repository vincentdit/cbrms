package tz.go.tirdo.cbrms.billing;

import java.util.List;

/** API representation of a bill. Amounts are decimal strings; timestamps are ISO 8601 with offsets. */
public record BillView(
        String billId, String sourceSystem, String sourceReference, int liabilityVersion,
        String currency, String total, String paymentMode, String description,
        String preparationState, String externalRegistrationStatus, String collectionState, String releaseState,
        String controlNumber, String dueDate, String validUntil, String issuedAt, List<LineView> lines) {

    public record LineView(int lineNo, String serviceCode, int tariffVersion, String description,
                           String quantity, String unitPrice, String lineTotal) {}
}
