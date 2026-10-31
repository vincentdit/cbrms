package tz.go.tirdo.cbrms.ops;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.go.tirdo.cbrms.common.ApiException;
import tz.go.tirdo.cbrms.identity.Actor;
import tz.go.tirdo.cbrms.identity.Actors;

/** The console's Operations page (FR27e). */
@RestController
@RequestMapping("/internal/v1/admin/operations")
public class OperationsController {

    private final OperationsMonitor monitor;
    private final InvariantCheck invariants;
    private final Actors actors;

    public OperationsController(OperationsMonitor monitor, InvariantCheck invariants, Actors actors) {
        this.monitor = monitor;
        this.invariants = invariants;
        this.actors = actors;
    }

    @GetMapping
    public OperationsMonitor.Snapshot current() {
        finance();
        return monitor.current();
    }

    /** Re-reads the figures now instead of waiting up to 30 seconds. */
    @PostMapping("/refresh")
    public OperationsMonitor.Snapshot refresh() {
        finance();
        return monitor.refresh();
    }

    @PostMapping("/invariant-check")
    public InvariantCheck.Result checkNow() {
        Actor a = finance();
        InvariantCheck.Result r = invariants.run(a.subject());
        monitor.refresh();
        return r;
    }

    private Actor finance() {
        Actor a = actors.current();
        if (!a.finance()) {
            throw ApiException.forbidden("CBR-AUT-006", "Finance console access requires a Finance role");
        }
        return a;
    }
}
