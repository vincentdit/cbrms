package tz.go.tirdo.cbrms.billing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.go.tirdo.cbrms.identity.Actors;

/** Cancellation and expiry-change requests (Blueprint section 8). Requests return 202: GePG confirms later. */
@RestController
@RequestMapping("/internal/v1")
public class BillChangeController {

    public record CancellationRequest(@NotBlank @Size(max = 500) String reason) {}

    public record ExpiryChangeRequest(@NotNull LocalDate newDueDate, @NotBlank @Size(max = 500) String reason) {}

    public record Decision(@NotBlank @Pattern(regexp = "APPROVED|REJECTED") String decision, @Size(max = 500) String reason) {}

    private final BillChangeService changes;
    private final Actors actors;

    public BillChangeController(BillChangeService changes, Actors actors) {
        this.changes = changes;
        this.actors = actors;
    }

    @PostMapping("/bills/{billId}/cancellation-requests")
    public ResponseEntity<BillChangeService.ChangeView> cancel(@PathVariable UUID billId, @Valid @RequestBody CancellationRequest body) {
        return ResponseEntity.accepted().body(changes.requestCancellation(actors.current(), billId, body.reason()));
    }

    @PostMapping("/bills/{billId}/expiry-change-requests")
    public ResponseEntity<BillChangeService.ChangeView> extend(@PathVariable UUID billId, @Valid @RequestBody ExpiryChangeRequest body) {
        return ResponseEntity.accepted().body(changes.requestExpiryChange(actors.current(), billId, body.newDueDate(), body.reason()));
    }

    @PostMapping("/change-requests/{requestId}/decision")
    public BillChangeService.ChangeView decide(@PathVariable UUID requestId, @Valid @RequestBody Decision body) {
        return changes.decide(actors.current(), requestId, body.decision(), body.reason());
    }
}
