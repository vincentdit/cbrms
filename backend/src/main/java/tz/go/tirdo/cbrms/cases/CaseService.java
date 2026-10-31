package tz.go.tirdo.cbrms.cases;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Opens exception cases (unmatched payments, mismatches, delays, failed deliveries). */
@Service
public class CaseService {
    private final JdbcClient jdbc;

    public CaseService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID open(String type, UUID billId, String reference, BigDecimal value, String currency, String details, int dueInDays) {
        return jdbc.sql("""
                INSERT INTO exception_case (type, bill_id, reference, value, currency, details, due_date)
                VALUES (:type, :billId, :reference, :value, :currency, :details, :dueDate)
                RETURNING id
                """)
            .param("type", type)
            .param("billId", billId)
            .param("reference", reference)
            .param("value", value)
            .param("currency", currency)
            .param("details", details)
            .param("dueDate", LocalDate.now().plusDays(dueInDays))
            .query(UUID.class)
            .single();
    }

    /** Opens a case of this type for a bill unless one is already open. Returns true if a case was opened. */
    public boolean openOnceForBill(String type, UUID billId, String details) {
        int rows = jdbc.sql("""
                INSERT INTO exception_case (type, bill_id, details, due_date)
                SELECT :type, :billId, :details, current_date + 1
                 WHERE NOT EXISTS (SELECT 1 FROM exception_case
                                    WHERE type = :type AND bill_id = :billId AND status <> 'CLOSED')
                """)
            .param("type", type)
            .param("billId", billId)
            .param("details", details)
            .update();
        return rows > 0;
    }
}
