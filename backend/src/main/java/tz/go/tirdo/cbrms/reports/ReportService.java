package tz.go.tirdo.cbrms.reports;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tz.go.tirdo.cbrms.billing.BillService;
import tz.go.tirdo.cbrms.common.ApiException;

/**
 * Finance reports, all computed in PostgreSQL from the append-only payment records.
 * Dates are business days in Africa/Dar_es_Salaam time.
 */
@Service
public class ReportService {

    static final int MAX_RANGE_DAYS = 366;

    public enum GroupBy { PLATFORM, SERVICE, GFS }

    public record CollectionRow(String key, String name, long payments, BigDecimal amount) {}

    /**
     * Cash received in the period, by when GePG's payment reached CBRMS.
     * received = applied to bills + held as client credit + in suspense, so the totals always reconcile.
     */
    public record Collections(LocalDate from, LocalDate to, String groupBy, List<CollectionRow> rows,
                              BigDecimal applied, BigDecimal credit, BigDecimal suspense, BigDecimal received, long payments,
                              boolean apportioned) {}

    public record AgedRow(String platform, String platformName, long bills, BigDecimal notYetDue, BigDecimal days1to30,
                          BigDecimal days31to60, BigDecimal days61to90, BigDecimal over90, BigDecimal total) {}

    public record OutstandingBill(String billId, String platform, String sourceReference, String payerName, String controlNumber,
                                  String dueDate, BigDecimal total, BigDecimal paid, BigDecimal balance, int daysOverdue, String bucket) {}

    public record Outstanding(LocalDate asOf, List<AgedRow> rows, AgedRow totals, List<OutstandingBill> bills) {}

    private static final String EAT = "'Africa/Dar_es_Salaam'";

    private final JdbcClient jdbc;

    public ReportService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Collections collections(LocalDate from, LocalDate to, GroupBy groupBy) {
        checkRange(from, to);
        String key = switch (groupBy) {
            case PLATFORM -> "s.code";
            case SERVICE -> "v.code";
            case GFS -> "f.gfs_code";
        };
        String name = switch (groupBy) {
            case PLATFORM -> "s.name";
            case SERVICE -> "v.name";
            case GFS -> "string_agg(DISTINCT v.name, '; ' ORDER BY v.name)";
        };
        String groupCols = groupBy == GroupBy.GFS ? "f.gfs_code" : key + ", " + name;
        // Each allocation is shared across the bill's lines in proportion to line totals; the rounding
        // remainder goes to the last line, so the shares always add up to the allocation exactly.
        String sql = """
                WITH pay AS (
                  SELECT p.id FROM payment p
                   WHERE (p.received_at AT TIME ZONE %1$s)::date BETWEEN :from AND :to
                ), alloc AS (
                  SELECT a.id, a.payment_id, a.bill_id, a.amount FROM payment_allocation a JOIN pay ON pay.id = a.payment_id
                ), shares AS (
                  SELECT al.id AS alloc_id, al.payment_id, al.amount AS alloc, b.source_system_id, bl.service_id, bl.gfs_code,
                         CASE WHEN b.total = 0 THEN 0 ELSE round(al.amount * bl.line_total / b.total, 2) END AS share,
                         row_number() OVER (PARTITION BY al.id ORDER BY bl.line_no DESC) AS rn
                    FROM alloc al JOIN bill b ON b.id = al.bill_id JOIN bill_line bl ON bl.bill_id = b.id
                ), f AS (
                  SELECT payment_id, source_system_id, service_id, gfs_code,
                         share + CASE WHEN rn = 1 THEN alloc - sum(share) OVER (PARTITION BY alloc_id) ELSE 0 END AS amount
                    FROM shares
                )
                SELECT %2$s, %3$s, count(DISTINCT f.payment_id), sum(f.amount)
                  FROM f JOIN source_system s ON s.id = f.source_system_id JOIN service v ON v.id = f.service_id
                 GROUP BY %4$s
                 ORDER BY sum(f.amount) DESC, 1
                """.formatted(EAT, key, name, groupCols);
        List<CollectionRow> rows = jdbc.sql(sql).param("from", from).param("to", to)
                .query((rs, n) -> new CollectionRow(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getBigDecimal(4)))
                .list();

        record Totals(BigDecimal received, long payments, BigDecimal applied, BigDecimal credit) {}
        Totals t = jdbc.sql("""
                WITH pay AS (
                  SELECT p.id, p.amount FROM payment p
                   WHERE (p.received_at AT TIME ZONE %s)::date BETWEEN :from AND :to
                )
                SELECT coalesce(sum(pay.amount), 0), count(*),
                       coalesce((SELECT sum(a.amount) FROM payment_allocation a JOIN pay q ON q.id = a.payment_id), 0),
                       coalesce((SELECT sum(c.amount) FROM credit_balance c JOIN pay q ON q.id = c.payment_id), 0)
                  FROM pay
                """.formatted(EAT))
            .param("from", from).param("to", to)
            .query((rs, n) -> new Totals(rs.getBigDecimal(1), rs.getLong(2), rs.getBigDecimal(3), rs.getBigDecimal(4)))
            .single();
        BigDecimal suspense = t.received().subtract(t.applied()).subtract(t.credit());
        return new Collections(from, to, groupBy.name().toLowerCase(), rows, t.applied(), t.credit(), suspense, t.received(),
                t.payments(), groupBy != GroupBy.PLATFORM);
    }

    /** Issued, payable bills with money still owed, aged by days past the due date. */
    public Outstanding outstanding() {
        LocalDate today = LocalDate.now(BillService.BUSINESS_ZONE);
        List<OutstandingBill> bills = jdbc.sql("""
                SELECT b.id, s.code, b.source_reference, p.display_name, e.control_number, b.due_date, b.total, x.paid,
                       b.total - x.paid AS balance
                  FROM bill b
                  JOIN source_system s ON s.id = b.source_system_id
                  JOIN payer p ON p.id = b.payer_id
                  LEFT JOIN external_bill e ON e.bill_id = b.id
                  CROSS JOIN LATERAL (SELECT coalesce(sum(a.amount), 0) AS paid FROM payment_allocation a WHERE a.bill_id = b.id) x
                 WHERE b.registration_state = 'Accepted'
                   AND b.preparation_state NOT IN ('Rejected', 'Superseded')
                   AND b.total - x.paid > 0
                 ORDER BY b.due_date, s.code, b.source_reference
                """)
            .query((rs, n) -> {
                LocalDate due = rs.getObject(6, LocalDate.class);
                int overdue = (int) Math.max(0, ChronoUnit.DAYS.between(due, today));
                return new OutstandingBill(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        due.toString(), rs.getBigDecimal(7), rs.getBigDecimal(8), rs.getBigDecimal(9), overdue, bucket(due, today));
            })
            .list();

        record Platform(String code, String name) {}
        List<Platform> platforms = jdbc.sql("SELECT code, name FROM source_system ORDER BY code")
                .query((rs, n) -> new Platform(rs.getString(1), rs.getString(2))).list();
        List<AgedRow> rows = platforms.stream()
                .map(pl -> aged(pl.code(), pl.name(), bills.stream().filter(b -> b.platform().equals(pl.code())).toList()))
                .filter(r -> r.bills() > 0)
                .toList();
        return new Outstanding(today, rows, aged("ALL", "All platforms", bills), bills);
    }

    static String bucket(LocalDate due, LocalDate today) {
        long days = ChronoUnit.DAYS.between(due, today);
        if (days <= 0) {
            return "NOT_YET_DUE";
        }
        if (days <= 30) {
            return "1_30";
        }
        if (days <= 60) {
            return "31_60";
        }
        if (days <= 90) {
            return "61_90";
        }
        return "OVER_90";
    }

    private static AgedRow aged(String code, String name, List<OutstandingBill> bills) {
        return new AgedRow(code, name, bills.size(), sum(bills, "NOT_YET_DUE"), sum(bills, "1_30"), sum(bills, "31_60"),
                sum(bills, "61_90"), sum(bills, "OVER_90"),
                bills.stream().map(OutstandingBill::balance).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static BigDecimal sum(List<OutstandingBill> bills, String bucket) {
        return bills.stream().filter(b -> b.bucket().equals(bucket)).map(OutstandingBill::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static void checkRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw ApiException.validation("from", "give both from and to dates");
        }
        if (to.isBefore(from)) {
            throw ApiException.validation("to", "must be on or after the from date");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
            throw ApiException.validation("to", "a report can cover at most " + MAX_RANGE_DAYS + " days");
        }
    }
}
