package tz.go.tirdo.cbrms.reports;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.go.tirdo.cbrms.audit.AuditService;
import tz.go.tirdo.cbrms.common.ApiException;
import tz.go.tirdo.cbrms.identity.Actor;
import tz.go.tirdo.cbrms.identity.Actors;

/** Finance reports as JSON for the console and as CSV (UTF-8 with BOM, so Excel opens it directly). */
@RestController
@RequestMapping("/internal/v1/admin/reports")
public class ReportsController {

    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final ReportService reports;
    private final Actors actors;
    private final AuditService audit;

    public ReportsController(ReportService reports, Actors actors, AuditService audit) {
        this.reports = reports;
        this.actors = actors;
        this.audit = audit;
    }

    @GetMapping("/collections")
    public ReportService.Collections collections(@RequestParam LocalDate from, @RequestParam LocalDate to,
                                                 @RequestParam(defaultValue = "platform") String groupBy) {
        finance();
        return reports.collections(from, to, groupBy(groupBy));
    }

    @GetMapping("/collections.csv")
    public ResponseEntity<String> collectionsCsv(@RequestParam LocalDate from, @RequestParam LocalDate to,
                                                 @RequestParam(defaultValue = "platform") String groupBy) {
        Actor a = finance();
        ReportService.Collections c = reports.collections(from, to, groupBy(groupBy));
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("CBRMS collections " + from + " to " + to + " by " + c.groupBy()));
        rows.add(List.of(c.groupBy().equals("gfs") ? "GFS code" : c.groupBy().equals("service") ? "Service code" : "Platform",
                "Name", "Payments", "Amount applied to bills (TZS)"));
        for (ReportService.CollectionRow r : c.rows()) {
            rows.add(List.of(r.key(), r.name(), r.payments(), r.amount()));
        }
        rows.add(List.of());
        rows.add(List.of("Applied to bills", "", "", c.applied()));
        rows.add(List.of("Held as client credit", "", "", c.credit()));
        rows.add(List.of("In suspense (no matching bill)", "", "", c.suspense()));
        rows.add(List.of("Received from GePG", "", c.payments(), c.received()));
        if (c.apportioned()) {
            rows.add(List.of("Payments on bills with several lines are shared across the lines in proportion to line totals."));
        }
        audit.record(a.subject(), "REPORT_EXPORTED", "report:collections", from + ".." + to + " " + c.groupBy(), null);
        return csv("cbrms-collections-" + c.groupBy() + "-" + from + "-to-" + to + ".csv", rows);
    }

    @GetMapping("/outstanding")
    public ReportService.Outstanding outstanding() {
        finance();
        return reports.outstanding();
    }

    @GetMapping("/outstanding.csv")
    public ResponseEntity<String> outstandingCsv() {
        Actor a = finance();
        ReportService.Outstanding o = reports.outstanding();
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("CBRMS outstanding balances as of " + o.asOf()));
        rows.add(List.of("Platform", "Reference", "Client", "Control number", "Due date", "Bill total (TZS)", "Paid (TZS)",
                "Balance (TZS)", "Days overdue", "Age"));
        for (ReportService.OutstandingBill b : o.bills()) {
            rows.add(List.of(b.platform().toUpperCase(Locale.ROOT), b.sourceReference(), b.payerName(),
                    b.controlNumber() == null ? "" : b.controlNumber(), b.dueDate(), b.total(), b.paid(), b.balance(), b.daysOverdue(),
                    label(b.bucket())));
        }
        rows.add(List.of());
        rows.add(List.of("Platform", "Bills", "Not yet due", "1-30 days", "31-60 days", "61-90 days", "Over 90 days", "Total"));
        List<ReportService.AgedRow> summary = new ArrayList<>(o.rows());
        summary.add(o.totals());
        for (ReportService.AgedRow r : summary) {
            rows.add(List.of(r.platformName(), r.bills(), r.notYetDue(), r.days1to30(), r.days31to60(), r.days61to90(), r.over90(), r.total()));
        }
        audit.record(a.subject(), "REPORT_EXPORTED", "report:outstanding", o.asOf().toString(), null);
        return csv("cbrms-outstanding-" + o.asOf() + ".csv", rows);
    }

    static String label(String bucket) {
        return switch (bucket) {
            case "NOT_YET_DUE" -> "Not yet due";
            case "1_30" -> "1-30 days";
            case "31_60" -> "31-60 days";
            case "61_90" -> "61-90 days";
            default -> "Over 90 days";
        };
    }

    /** RFC 4180 CSV. Text starting with = + - @ is prefixed with ' so spreadsheets never run it as a formula. */
    static String toCsv(List<List<Object>> rows) {
        StringBuilder out = new StringBuilder("﻿");
        for (List<Object> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(cell(row.get(i)));
            }
            out.append("\r\n");
        }
        return out.toString();
    }

    private static String cell(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof BigDecimal d) {
            return d.toPlainString();
        }
        if (v instanceof Number n) {
            return n.toString();
        }
        String s = v.toString();
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static ResponseEntity<String> csv(String filename, List<List<Object>> rows) {
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .header("Cache-Control", "no-store")
                .contentType(CSV)
                .body(toCsv(rows));
    }

    private static ReportService.GroupBy groupBy(String value) {
        try {
            return ReportService.GroupBy.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("groupBy", "use platform, service or gfs");
        }
    }

    private Actor finance() {
        Actor a = actors.current();
        if (!a.finance()) {
            throw ApiException.forbidden("CBR-AUT-006", "Reports require a Finance role");
        }
        return a;
    }
}
