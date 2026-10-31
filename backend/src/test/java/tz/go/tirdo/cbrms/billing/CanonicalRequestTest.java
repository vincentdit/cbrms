package tz.go.tirdo.cbrms.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The idempotency fingerprint ignores formatting differences but not content differences. */
class CanonicalRequestTest {

    private static CreateBillRequest req(String qty, String phone) {
        return new CreateBillRequest("LAB-1", new CreateBillRequest.Payer("C1", "Client", phone, null), "TZS", "Lab", 30,
                List.of(new CreateBillRequest.Line("LIMS-CHEM-001", 1, new BigDecimal(qty))), null, null);
    }

    @Test
    void quantityFormattingDoesNotChangeFingerprint() {
        assertEquals(BillService.canonical(req("1", null)), BillService.canonical(req("1.000", null)));
    }

    @Test
    void contentChangesFingerprint() {
        assertNotEquals(BillService.canonical(req("1", null)), BillService.canonical(req("2", null)));
        assertNotEquals(BillService.canonical(req("1", null)), BillService.canonical(req("1", "255700000000")));
    }
}
