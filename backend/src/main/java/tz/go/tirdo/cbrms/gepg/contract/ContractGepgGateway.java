package tz.go.tirdo.cbrms.gepg.contract;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tz.go.tirdo.cbrms.gepg.BillSubmission;
import tz.go.tirdo.cbrms.gepg.GepgGateway;
import tz.go.tirdo.cbrms.gepg.MessageVerifier;
import tz.go.tirdo.cbrms.gepg.SubmitResult;

/**
 * Placeholder for the adapter built to the GePG specification assigned to TIRDO.
 * It refuses to start so that no environment can run against GePG with an unimplemented adapter.
 *
 * To implement (SRS section 7 items): endpoints and transport from configuration; request mapping from BillSubmission
 * (SP code, sub-SP code, system ID, GFS items, payment mode, expiry); signing with the TIRDO private key from the
 * secret store; verification of GePG responses with the GePG certificate; mapping of acknowledgement codes;
 * an HTTP callback controller for control-number and payment messages that calls GepgInboundService.
 */
@Component
@ConditionalOnProperty(name = "cbrms.gepg.mode", havingValue = "contract")
public class ContractGepgGateway implements GepgGateway, MessageVerifier {

    public ContractGepgGateway() {
        throw new IllegalStateException("cbrms.gepg.mode=contract: implement ContractGepgGateway against the GePG "
                + "specification assigned to TIRDO before enabling this mode (see document 04, section 7).");
    }

    @Override
    public SubmitResult submit(BillSubmission submission) {
        throw new UnsupportedOperationException();
    }

    @Override
    public SubmitResult requestChange(tz.go.tirdo.cbrms.gepg.BillChangeSubmission change) {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.util.List<tz.go.tirdo.cbrms.gepg.ReconciliationLine> reconciliation(java.time.LocalDate businessDate) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean verify(String rawPayload, String signature) {
        return false;
    }
}
