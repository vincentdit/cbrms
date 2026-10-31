package tz.go.tirdo.cbrms.reports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class CsvExportTest {

    @Test
    void startsWithBomSoExcelReadsUtf8() {
        assertTrue(ReportsController.toCsv(List.of(List.of("Uchambuzi wa kibaiolojia"))).startsWith("﻿"));
    }

    @Test
    void quotesCommasAndQuotes() {
        String csv = ReportsController.toCsv(List.of(List.of("Tanga Cement, PLC", "He said \"pay\"", new BigDecimal("45000.00"), 3)));
        assertEquals("﻿\"Tanga Cement, PLC\",\"He said \"\"pay\"\"\",45000.00,3\r\n", csv);
    }

    @Test
    void neutralisesSpreadsheetFormulas() {
        String csv = ReportsController.toCsv(List.of(List.of("=HYPERLINK(\"http://x\")", "+255700", "@SUM(A1)")));
        assertTrue(csv.contains("\"'=HYPERLINK(\"\"http://x\"\")\""));
        assertTrue(csv.contains("'+255700"));
        assertTrue(csv.contains("'@SUM(A1)"));
    }

    @Test
    void numbersAreNotTreatedAsFormulas() {
        assertEquals("﻿-500.00\r\n", ReportsController.toCsv(List.of(List.of(new BigDecimal("-500.00")))));
    }

    @Test
    void agesByDaysPastDue() {
        LocalDate today = LocalDate.of(2026, 10, 31);
        assertEquals("NOT_YET_DUE", ReportService.bucket(today, today));
        assertEquals("1_30", ReportService.bucket(today.minusDays(1), today));
        assertEquals("1_30", ReportService.bucket(today.minusDays(30), today));
        assertEquals("31_60", ReportService.bucket(today.minusDays(31), today));
        assertEquals("61_90", ReportService.bucket(today.minusDays(90), today));
        assertEquals("OVER_90", ReportService.bucket(today.minusDays(91), today));
    }
}
