package tz.go.tirdo.cbrms.payments;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.go.tirdo.cbrms.cases.CaseWorkService;
import tz.go.tirdo.cbrms.common.ApiException;
import tz.go.tirdo.cbrms.identity.Actor;
import tz.go.tirdo.cbrms.identity.Actors;

/** Corrections and case work for Finance (FR18, FR21). */
@RestController
@RequestMapping("/internal/v1")
public class CorrectionController {

    public record CorrectionBody(@NotBlank @Pattern(regexp = "ALLOCATE|REVERSE") String kind, @NotNull UUID paymentId,
                                 @NotNull UUID billId, @NotBlank @Size(max = 500) String reason, UUID caseId) {}

    public record Decision(@NotBlank @Pattern(regexp = "APPROVED|REJECTED") String decision, @Size(max = 500) String reason) {}

    public record CloseBody(@NotBlank @Size(max = 500) String resolution, @Size(max = 100) String evidenceRef) {}

    private final CorrectionService corrections;
    private final CaseWorkService caseWork;
    private final Actors actors;

    public CorrectionController(CorrectionService corrections, CaseWorkService caseWork, Actors actors) {
        this.corrections = corrections;
        this.caseWork = caseWork;
        this.actors = actors;
    }

    /** Payments with money not applied to any bill: suspense, and money freed by reversals. */
    @GetMapping("/admin/payments/unallocated")
    public List<CorrectionService.UnallocatedPayment> unallocated() {
        finance();
        return corrections.unallocated();
    }

    @GetMapping("/admin/corrections")
    public List<CorrectionService.CorrectionView> list(@RequestParam(defaultValue = "") String status) {
        finance();
        if (!status.isEmpty() && !List.of("Requested", "Applied", "Rejected").contains(status)) {
            throw ApiException.validation("status", "must be Requested, Applied or Rejected");
        }
        return corrections.list(status);
    }

    @PostMapping("/admin/corrections")
    public ResponseEntity<CorrectionService.CorrectionView> request(@Valid @RequestBody CorrectionBody b) {
        return ResponseEntity.status(HttpStatus.CREATED).body(corrections.request(finance(), CorrectionService.Kind.valueOf(b.kind()),
                b.paymentId(), b.billId(), b.reason(), b.caseId()));
    }

    @PostMapping("/corrections/{id}/decision")
    public CorrectionService.CorrectionView decide(@PathVariable UUID id, @Valid @RequestBody Decision body) {
        return corrections.decide(actors.current(), id, body.decision(), body.reason());
    }

    @PostMapping("/admin/cases/{id}/take")
    public void take(@PathVariable UUID id) {
        caseWork.take(finance(), id);
    }

    @PostMapping("/admin/cases/{id}/close")
    public void close(@PathVariable UUID id, @Valid @RequestBody CloseBody body) {
        caseWork.close(finance(), id, body.resolution(), body.evidenceRef());
    }

    private Actor finance() {
        Actor a = actors.current();
        if (!a.finance()) {
            throw ApiException.forbidden("CBR-AUT-006", "Finance console access requires a Finance role");
        }
        return a;
    }
}
