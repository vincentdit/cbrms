package tz.go.tirdo.cbrms.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/** NFR09 and FR27e: the monitor reads the live database and flags only what is wrong. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "CBRMS_TEST_DB_URL", matches = ".+")
class OperationsIntegrationTest {

    @Autowired OperationsMonitor monitor;
    @Autowired InvariantCheck invariants;
    @Autowired JdbcClient jdbc;

    @Test
    void snapshotReadsTheDatabase() {
        OperationsMonitor.Snapshot s = monitor.refresh();
        assertNotNull(s.takenAt());
        assertFalse(s.backupExpected(), "backups are not required in the test profile");
        assertTrue(s.alerts().stream().noneMatch(a -> a.title().startsWith("Backup")), "no backup alert where backups are not expected");
    }

    @Test
    void backupHeartbeatIsPickedUp() {
        jdbc.sql("INSERT INTO ops_heartbeat (name, ok, detail) VALUES ('backup.full', true, 'integration test')").update();
        OperationsMonitor.Snapshot s = monitor.refresh();
        assertNotNull(s.backupAgeSeconds());
        assertTrue(s.backupAgeSeconds() < 60);
        assertEquals(Boolean.TRUE, s.lastBackupOk());
    }

    @Test
    void invariantCheckMatchesTheView() {
        long violations = jdbc.sql("SELECT count(*) FROM v_invariant_violations").query(Long.class).single();
        InvariantCheck.Result r = invariants.run("test");
        assertEquals(violations, r.violations());
        assertEquals(violations, monitor.refresh().invariantViolations());
    }
}
