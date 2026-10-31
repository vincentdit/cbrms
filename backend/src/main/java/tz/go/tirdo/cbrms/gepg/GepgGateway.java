package tz.go.tirdo.cbrms.gepg;

import java.time.LocalDate;
import java.util.List;

/**
 * Outbound port to GePG. The production implementation is written against the GePG specification
 * assigned to TIRDO (SRS section 7); the simulator implements the same port for development and tests.
 * A thrown GepgUnavailableException always means "outcome unknown", never "failed".
 */
public interface GepgGateway {

    /** Registers a bill. The control number arrives later as a ControlNumberMessage. */
    SubmitResult submit(BillSubmission submission) throws GepgUnavailableException;

    /** Asks GePG to cancel or change the expiry of a registered bill. The outcome arrives later as a ChangeResultMessage. */
    SubmitResult requestChange(BillChangeSubmission change) throws GepgUnavailableException;

    /** Returns GePG's record of payments for one business day (Africa/Dar_es_Salaam). */
    List<ReconciliationLine> reconciliation(LocalDate businessDate) throws GepgUnavailableException;
}
