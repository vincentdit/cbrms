package tz.go.tirdo.cbrms.billing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tz.go.tirdo.cbrms.events.EventSubject;

@Repository
public class BillQueries {
    private final JdbcClient jdbc;

    public BillQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UUID> findActive(UUID sourceSystemId, String sourceReference) {
        return jdbc.sql("""
                SELECT id FROM bill
                 WHERE source_system_id = :sourceSystemId AND source_reference = :ref
                   AND preparation_state NOT IN ('Rejected','Superseded')
                   AND registration_state NOT IN ('Rejected','Cancelled')
                """)
            .param("sourceSystemId", sourceSystemId)
            .param("ref", sourceReference)
            .query(UUID.class)
            .optional();
    }

    /** Locks the bill row for the rest of the transaction and returns what events need. */
    public Optional<EventSubject> lockSubject(UUID billId) {
        return jdbc.sql("""
                SELECT b.id, b.source_system_id, s.code, b.source_reference
                  FROM bill b JOIN source_system s ON s.id = b.source_system_id
                 WHERE b.id = :id
                   FOR UPDATE OF b
                """)
            .param("id", billId)
            .query((rs, n) -> new EventSubject(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4)))
            .optional();
    }

    public Optional<BillView> view(UUID billId) {
        Optional<BillView> head = jdbc.sql("""
                SELECT b.id, s.code, b.source_reference, b.liability_version, b.currency, b.total, b.payment_mode, b.description,
                       b.preparation_state, b.registration_state, b.collection_state, b.release_state,
                       e.control_number, b.due_date, b.valid_until, b.issued_at
                  FROM bill b
                  JOIN source_system s ON s.id = b.source_system_id
                  LEFT JOIN external_bill e ON e.bill_id = b.id
                 WHERE b.id = :id
                """)
            .param("id", billId)
            .query((rs, n) -> new BillView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5),
                    rs.getBigDecimal(6).toPlainString(), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                    rs.getString(11), rs.getString(12), rs.getString(13), String.valueOf(rs.getObject(14)),
                    String.valueOf(rs.getObject(15, java.time.OffsetDateTime.class)),
                    rs.getObject(16) == null ? null : String.valueOf(rs.getObject(16, java.time.OffsetDateTime.class)),
                    List.of()))
            .optional();
        return head.map(h -> {
            List<BillView.LineView> lines = jdbc.sql("""
                    SELECT l.line_no, s.code, t.version_no, l.description, l.quantity, l.unit_price, l.line_total
                      FROM bill_line l
                      JOIN service s ON s.id = l.service_id
                      JOIN tariff_version t ON t.id = l.tariff_version_id
                     WHERE l.bill_id = :id ORDER BY l.line_no
                    """)
                .param("id", billId)
                .query((rs, n) -> new BillView.LineView(rs.getInt(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                        rs.getBigDecimal(5).stripTrailingZeros().toPlainString(), rs.getBigDecimal(6).toPlainString(),
                        rs.getBigDecimal(7).toPlainString()))
                .list();
            return new BillView(h.billId(), h.sourceSystem(), h.sourceReference(), h.liabilityVersion(), h.currency(), h.total(),
                    h.paymentMode(), h.description(), h.preparationState(), h.externalRegistrationStatus(), h.collectionState(),
                    h.releaseState(), h.controlNumber(), h.dueDate(), h.validUntil(), h.issuedAt(), lines);
        });
    }

    public Optional<UUID> ownerSourceSystem(UUID billId) {
        return jdbc.sql("SELECT source_system_id FROM bill WHERE id = :id").param("id", billId).query(UUID.class).optional();
    }
}
