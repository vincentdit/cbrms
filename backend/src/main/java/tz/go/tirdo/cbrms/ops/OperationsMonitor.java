package tz.go.tirdo.cbrms.ops;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.go.tirdo.cbrms.billing.BillService;

/**
 * Operational health (NFR09, FR27e). Reads the database every 30 seconds, publishes the figures as Prometheus gauges
 * (cbrms_*) and keeps a snapshot for the console's Operations page. The alert thresholds below are the NFR09 values;
 * ops/prometheus/alerts.yml uses the same numbers.
 */
@Component
public class OperationsMonitor {

    public static final long PENDING_EVENT_LIMIT_S = 300;          // oldest undelivered platform event: 5 minutes
    public static final long CONTROL_NUMBER_LIMIT_S = 900;         // waiting for a control number: 15 minutes
    public static final long WAL_ARCHIVE_LIMIT_S = 300;            // WAL archive lag: 5 minutes
    public static final long BACKUP_LIMIT_S = 86_400;              // backup older than 24 hours
    public static final int[] CERT_WARN_DAYS = {60, 30, 7};

    public record Certificate(String name, String notAfter, Long daysLeft, String error) {}

    public record Alert(String severity, String title, String detail, String owner, String runbook) {}

    public record Snapshot(String takenAt, long oldestPendingEventSeconds, long failedDeliveries,
                           long oldestAwaitingControlNumberSeconds, long ackUnknown, long unallocatedPayments,
                           String unallocatedAmount, long failedNotices, long openCases, long overdueCases,
                           long invariantViolations, String lastReconciledDay, long reconciliationDaysBehind,
                           boolean archiveExpected, Long walArchiveAgeSeconds, long walArchiveFailures,
                           boolean backupExpected, Long backupAgeSeconds, Boolean lastBackupOk,
                           List<Certificate> certificates, List<Alert> alerts) {}

    private final JdbcClient jdbc;
    private final OpsProperties props;
    private volatile Snapshot snapshot;

    public OperationsMonitor(JdbcClient jdbc, OpsProperties props, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.props = props;
        gauge(registry, "cbrms_outbox_oldest_pending_seconds", "Age of the oldest platform event not yet delivered", s -> s.oldestPendingEventSeconds());
        gauge(registry, "cbrms_event_deliveries_failed", "Platform event deliveries that gave up", s -> s.failedDeliveries());
        gauge(registry, "cbrms_awaiting_control_number_oldest_seconds", "Age of the oldest bill waiting for a control number", s -> s.oldestAwaitingControlNumberSeconds());
        gauge(registry, "cbrms_bills_ack_unknown", "Bills whose GePG submission outcome is unknown", s -> s.ackUnknown());
        gauge(registry, "cbrms_unallocated_payments", "Payments with money not applied to any bill", s -> s.unallocatedPayments());
        gauge(registry, "cbrms_notices_failed", "Client SMS and email notices that gave up", s -> s.failedNotices());
        gauge(registry, "cbrms_cases_open", "Open exception cases", s -> s.openCases());
        gauge(registry, "cbrms_cases_overdue", "Open exception cases past their due date", s -> s.overdueCases());
        gauge(registry, "cbrms_invariant_violations", "Rows breaking a CBRMS financial invariant", s -> s.invariantViolations());
        gauge(registry, "cbrms_reconciliation_days_behind", "Business days without a GePG reconciliation run", s -> s.reconciliationDaysBehind());
        gauge(registry, "cbrms_wal_archive_age_seconds", "Seconds since PostgreSQL last archived a WAL file (-1: never)", s -> nz(s.walArchiveAgeSeconds()));
        gauge(registry, "cbrms_wal_archive_failures", "WAL archive failures since statistics reset", s -> s.walArchiveFailures());
        gauge(registry, "cbrms_wal_archive_expected", "1 where WAL archiving must be running", s -> s.archiveExpected() ? 1 : 0);
        gauge(registry, "cbrms_backup_age_seconds", "Seconds since the last successful backup (-1: none recorded)", s -> nz(s.backupAgeSeconds()));
        gauge(registry, "cbrms_backup_expected", "1 where backups must be running", s -> s.backupExpected() ? 1 : 0);
        for (OpsProperties.Certificate c : props.certs()) {
            Gauge.builder("cbrms_certificate_days_left", this, m -> m.certDays(c.name()))
                    .description("Days until the certificate expires").tag("certificate", c.name()).register(registry);
        }
    }

    /** The latest figures, read now if nothing has been read yet. */
    public Snapshot current() {
        Snapshot s = snapshot;
        return s != null ? s : refresh();
    }

    @Scheduled(fixedDelayString = "${cbrms.ops.refresh:PT30S}", initialDelayString = "PT10S")
    public void scheduled() {
        refresh();
    }

    public synchronized Snapshot refresh() {
        Map<String, Object> m = jdbc.sql("""
                SELECT
                  coalesce((SELECT extract(epoch FROM now() - min(e.created_at))::bigint
                              FROM event_delivery d JOIN outbox_event e ON e.id = d.event_id WHERE d.status = 'PENDING'), 0) AS pending_age,
                  (SELECT count(*) FROM event_delivery WHERE status = 'FAILED') AS failed_deliveries,
                  coalesce((SELECT extract(epoch FROM now() - min(s.sent_at))::bigint
                              FROM bill b JOIN external_bill x ON x.bill_id = b.id
                              JOIN submission_attempt s ON s.external_bill_id = x.id AND s.kind = 'SUBMIT'
                             WHERE b.registration_state IN ('Submitted','AwaitingControlNumber')), 0) AS cn_age,
                  (SELECT count(*) FROM bill WHERE registration_state = 'AckUnknown') AS ack_unknown,
                  (SELECT count(*) FROM v_payment_unallocated WHERE unallocated > 0) AS unallocated_count,
                  coalesce((SELECT sum(unallocated) FROM v_payment_unallocated WHERE unallocated > 0), 0) AS unallocated_amount,
                  (SELECT count(*) FROM client_notification WHERE status = 'FAILED') AS failed_notices,
                  (SELECT count(*) FROM exception_case WHERE status <> 'CLOSED') AS open_cases,
                  (SELECT count(*) FROM exception_case WHERE status <> 'CLOSED' AND due_date < (now() AT TIME ZONE 'Africa/Dar_es_Salaam')::date) AS overdue_cases,
                  (SELECT count(*) FROM v_invariant_violations) AS violations,
                  (SELECT max(business_date) FROM reconciliation_batch WHERE source = 'GEPG') AS last_reconciled,
                  (SELECT extract(epoch FROM now() - last_archived_time)::bigint FROM pg_stat_archiver) AS wal_age,
                  (SELECT failed_count FROM pg_stat_archiver) AS wal_failures,
                  (SELECT extract(epoch FROM now() - max(occurred_at))::bigint FROM ops_heartbeat WHERE name LIKE 'backup.%' AND ok) AS backup_age,
                  (SELECT ok FROM ops_heartbeat WHERE name LIKE 'backup.%' ORDER BY occurred_at DESC LIMIT 1) AS backup_ok
                """)
            .query().singleRow();

        LocalDate today = LocalDate.now(BillService.BUSINESS_ZONE);
        LocalDate lastReconciled = m.get("last_reconciled") == null ? null : ((java.sql.Date) m.get("last_reconciled")).toLocalDate();
        long behind = businessDaysBehind(lastReconciled, today);
        List<Certificate> certs = certificates(today);

        Snapshot s = new Snapshot(Instant.now().toString(), num(m, "pending_age"), num(m, "failed_deliveries"), num(m, "cn_age"),
                num(m, "ack_unknown"), num(m, "unallocated_count"), ((BigDecimal) m.get("unallocated_amount")).toPlainString(),
                num(m, "failed_notices"), num(m, "open_cases"), num(m, "overdue_cases"), num(m, "violations"),
                lastReconciled == null ? null : lastReconciled.toString(), behind,
                props.archiving(), opt(m, "wal_age"), num(m, "wal_failures"),
                props.backups(), opt(m, "backup_age"), (Boolean) m.get("backup_ok"), certs, List.of());
        s = withAlerts(s);
        snapshot = s;
        return s;
    }

    /** Last business day is yesterday, or Friday on a Monday or weekend. Public holidays are not known to CBRMS. */
    static long businessDaysBehind(LocalDate lastReconciled, LocalDate today) {
        LocalDate due = today.minusDays(1);
        while (due.getDayOfWeek() == DayOfWeek.SATURDAY || due.getDayOfWeek() == DayOfWeek.SUNDAY) {
            due = due.minusDays(1);
        }
        LocalDate from = lastReconciled == null ? due.minusDays(30) : lastReconciled;
        long n = 0;
        for (LocalDate d = from.plusDays(1); !d.isAfter(due); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                n++;
            }
        }
        return n;
    }

    private Snapshot withAlerts(Snapshot s) {
        List<Alert> a = new ArrayList<>();
        if (s.invariantViolations() > 0) {
            a.add(new Alert("critical", "Financial invariant broken", s.invariantViolations() + " row(s) in v_invariant_violations",
                    "Director, ICTT and Head of Finance", "RB-01 Invariant violation"));
        }
        if (s.oldestPendingEventSeconds() > PENDING_EVENT_LIMIT_S) {
            a.add(new Alert("warning", "Platform updates delayed", "Oldest undelivered event is " + minutes(s.oldestPendingEventSeconds()),
                    "ICTT integration custodian", "RB-02 Event delivery delayed"));
        }
        if (s.failedDeliveries() > 0) {
            a.add(new Alert("warning", "Platform updates failed", s.failedDeliveries() + " delivery(ies) gave up",
                    "ICTT integration custodian", "RB-02 Event delivery delayed"));
        }
        if (s.oldestAwaitingControlNumberSeconds() > CONTROL_NUMBER_LIMIT_S) {
            a.add(new Alert("warning", "Control numbers delayed", "Oldest bill has waited " + minutes(s.oldestAwaitingControlNumberSeconds()),
                    "ICTT integration custodian", "RB-03 GePG delays (SOP-02)"));
        }
        if (s.ackUnknown() > 0) {
            a.add(new Alert("warning", "GePG outcome unknown", s.ackUnknown() + " bill(s); resolve by enquiry, never resubmit",
                    "ICTT integration custodian", "RB-03 GePG delays (SOP-02)"));
        }
        if (s.reconciliationDaysBehind() > 0) {
            a.add(new Alert("warning", "Reconciliation behind", s.reconciliationDaysBehind() + " business day(s) not reconciled",
                    "Head of Finance", "RB-04 Reconciliation (SOP-08)"));
        }
        if (s.archiveExpected() && (s.walArchiveAgeSeconds() == null || s.walArchiveAgeSeconds() > WAL_ARCHIVE_LIMIT_S)) {
            a.add(new Alert("critical", "WAL archiving behind", s.walArchiveAgeSeconds() == null ? "No WAL file archived yet"
                    : "Last archived " + minutes(s.walArchiveAgeSeconds()) + " ago", "ICTT infrastructure", "RB-05 Backup and archive"));
        }
        if (s.backupExpected() && (s.backupAgeSeconds() == null || s.backupAgeSeconds() > BACKUP_LIMIT_S || Boolean.FALSE.equals(s.lastBackupOk()))) {
            a.add(new Alert("critical", "Backup missing or failed", s.backupAgeSeconds() == null ? "No successful backup recorded"
                    : "Last good backup " + (s.backupAgeSeconds() / 3600) + " hours ago", "ICTT infrastructure", "RB-05 Backup and archive"));
        }
        for (Certificate c : s.certificates()) {
            if (c.error() != null) {
                a.add(new Alert("warning", "Certificate unreadable: " + c.name(), c.error(), "ICTT integration custodian", "RB-06 Certificates (SOP-14)"));
            } else if (c.daysLeft() != null && c.daysLeft() <= CERT_WARN_DAYS[0]) {
                a.add(new Alert(c.daysLeft() <= CERT_WARN_DAYS[2] ? "critical" : "warning", "Certificate expiring: " + c.name(),
                        c.daysLeft() + " day(s) left (" + c.notAfter() + ")", "ICTT integration custodian", "RB-06 Certificates (SOP-14)"));
            }
        }
        if (s.failedNotices() > 0) {
            a.add(new Alert("info", "Client messages failed", s.failedNotices() + " SMS or email gave up", "ICTT and customer service",
                    "RB-07 Client notices"));
        }
        if (s.overdueCases() > 0) {
            a.add(new Alert("info", "Exceptions overdue", s.overdueCases() + " open case(s) past their due date", "Head of Finance",
                    "RB-04 Reconciliation (SOP-08)"));
        }
        return new Snapshot(s.takenAt(), s.oldestPendingEventSeconds(), s.failedDeliveries(), s.oldestAwaitingControlNumberSeconds(),
                s.ackUnknown(), s.unallocatedPayments(), s.unallocatedAmount(), s.failedNotices(), s.openCases(), s.overdueCases(),
                s.invariantViolations(), s.lastReconciledDay(), s.reconciliationDaysBehind(), s.archiveExpected(), s.walArchiveAgeSeconds(),
                s.walArchiveFailures(), s.backupExpected(), s.backupAgeSeconds(), s.lastBackupOk(), s.certificates(), List.copyOf(a));
    }

    private List<Certificate> certificates(LocalDate today) {
        List<Certificate> out = new ArrayList<>();
        for (OpsProperties.Certificate c : props.certs()) {
            try {
                LocalDate notAfter = c.notAfter() == null || c.notAfter().isBlank() ? null : LocalDate.parse(c.notAfter().trim());
                if (c.path() != null && !c.path().isBlank()) {
                    try (InputStream in = Files.newInputStream(Path.of(c.path()))) {
                        X509Certificate x = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
                        notAfter = x.getNotAfter().toInstant().atZone(ZoneId.of("UTC")).toLocalDate();
                    }
                }
                if (notAfter == null) {
                    out.add(new Certificate(c.name(), null, null, "give a path or a notAfter date"));
                } else {
                    out.add(new Certificate(c.name(), notAfter.toString(), ChronoUnit.DAYS.between(today, notAfter), null));
                }
            } catch (Exception e) {
                out.add(new Certificate(c.name(), null, null, e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }
        return out;
    }

    private double certDays(String name) {
        return current().certificates().stream().filter(c -> c.name().equals(name) && c.daysLeft() != null)
                .mapToDouble(Certificate::daysLeft).findFirst().orElse(Double.NaN);
    }

    private void gauge(MeterRegistry registry, String name, String help, ToDoubleFunction<Snapshot> value) {
        Gauge.builder(name, this, m -> {
            try {
                return value.applyAsDouble(m.current());
            } catch (RuntimeException e) {
                return Double.NaN;   // database unreachable: no value rather than a wrong one
            }
        }).description(help).register(registry);
    }

    private static long num(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? 0 : ((Number) v).longValue();
    }

    private static Long opt(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : ((Number) v).longValue();
    }

    private static double nz(Long v) {
        return v == null ? -1 : v;
    }

    private static String minutes(long seconds) {
        return seconds < 120 ? seconds + " s" : (seconds / 60) + " min";
    }
}
