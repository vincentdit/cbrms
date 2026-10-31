package tz.go.tirdo.cbrms.billing;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.go.tirdo.cbrms.common.ApiException;
import tz.go.tirdo.cbrms.identity.Actor;
import tz.go.tirdo.cbrms.identity.Actors;

@RestController
@RequestMapping("/internal/v1/bills")
public class BillController {
    private final BillService billing;
    private final ApprovalService approvals;
    private final BillQueries queries;
    private final Actors actors;

    public BillController(BillService billing, ApprovalService approvals, BillQueries queries, Actors actors) {
        this.billing = billing;
        this.approvals = approvals;
        this.queries = queries;
        this.actors = actors;
    }

    /** 201 for a new bill; 200 with the original bill for an identical retry or an existing active liability. */
    @PostMapping
    public ResponseEntity<BillView> create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                           @Valid @RequestBody CreateBillRequest request) {
        Actor actor = actors.current();
        BillService.CreateOutcome outcome = billing.create(actor, idempotencyKey, request);
        BillView view = queries.view(outcome.billId()).orElseThrow();
        if (outcome.created()) {
            return ResponseEntity.created(URI.create("/internal/v1/bills/" + outcome.billId())).body(view);
        }
        return ResponseEntity.ok(view);
    }

    @GetMapping("/{billId}")
    public BillView get(@PathVariable UUID billId) {
        Actor actor = actors.current();
        UUID owner = queries.ownerSourceSystem(billId).orElseThrow(() -> ApiException.notFound("Bill not found"));
        if (!actor.finance() && !owner.equals(actor.sourceSystemId())) {
            // Same response as a missing bill, so IDs of other platforms' bills cannot be probed (FR26)
            throw ApiException.notFound("Bill not found");
        }
        return queries.view(billId).orElseThrow(() -> ApiException.notFound("Bill not found"));
    }

    @PostMapping("/{billId}/approval")
    public BillView decide(@PathVariable UUID billId, @Valid @RequestBody ApprovalRequest request) {
        approvals.decide(actors.current(), billId, request);
        return queries.view(billId).orElseThrow();
    }
}
