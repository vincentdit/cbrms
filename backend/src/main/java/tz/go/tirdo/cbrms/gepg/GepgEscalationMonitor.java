package tz.go.tirdo.cbrms.gepg;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.go.tirdo.cbrms.cases.CaseService;
import tz.go.tirdo.cbrms.config.CbrmsProperties;

/** Opens cases for stuck submissions (SOP-02 and the escalation matrix in document 05). */
@Component
public class GepgEscalationMonitor {
    private final JdbcClient jdbc;
    private final CaseService cases;
    private final CbrmsProperties props;

    public GepgEscalationMonitor(JdbcClient jdbc, CaseService cases, CbrmsProperties props) {
        this.jdbc = jdbc;
        this.cases = cases;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${cbrms.gepg.escalation-interval:PT1M}")
    public void runOnce() {
        List<UUID> ackUnknown = jdbc.sql("""
                SELECT b.id FROM bill b JOIN external_bill e ON e.bill_id = b.id
                 WHERE b.registration_state = 'AckUnknown'
                   AND (SELECT max(sent_at) FROM submission_attempt a WHERE a.external_bill_id = e.id) < now() - CAST(:after AS interval)
                """)
            .param("after", props.gepg().ackUnknownEscalation().toSeconds() + " seconds")
            .query(UUID.class)
            .list();
        ackUnknown.forEach(id -> cases.openOnceForBill("ACK_UNKNOWN", id, "Submission outcome unknown; resolve by enquiry before any resubmission"));

        List<UUID> delayed = jdbc.sql("""
                SELECT b.id FROM bill b JOIN external_bill e ON e.bill_id = b.id
                 WHERE b.registration_state = 'AwaitingControlNumber'
                   AND e.acknowledged_at < now() - CAST(:after AS interval)
                """)
            .param("after", props.gepg().controlNumberEscalation().toSeconds() + " seconds")
            .query(UUID.class)
            .list();
        delayed.forEach(id -> cases.openOnceForBill("CONTROL_NUMBER_DELAY", id, "Acknowledged by GePG but no control number received"));
    }
}
