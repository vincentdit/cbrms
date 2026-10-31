package tz.go.tirdo.cbrms.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class BusinessDaysTest {

    // 2026-10-30 is a Friday, 2026-11-02 a Monday
    @Test
    void upToDateWhenYesterdayIsReconciled() {
        assertEquals(0, OperationsMonitor.businessDaysBehind(LocalDate.of(2026, 10, 29), LocalDate.of(2026, 10, 30)));
    }

    @Test
    void mondayOnlyNeedsFriday() {
        assertEquals(0, OperationsMonitor.businessDaysBehind(LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 2)));
        assertEquals(1, OperationsMonitor.businessDaysBehind(LocalDate.of(2026, 10, 29), LocalDate.of(2026, 11, 2)));
    }

    @Test
    void weekendsAreNotCounted() {
        // last run Wednesday 28th; on Tuesday 3 Nov, Thursday, Friday and Monday are missing
        assertEquals(3, OperationsMonitor.businessDaysBehind(LocalDate.of(2026, 10, 28), LocalDate.of(2026, 11, 3)));
    }
}
