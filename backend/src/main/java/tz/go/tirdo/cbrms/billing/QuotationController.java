package tz.go.tirdo.cbrms.billing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.go.tirdo.cbrms.common.ApiException;
import tz.go.tirdo.cbrms.identity.Actor;
import tz.go.tirdo.cbrms.identity.Actors;

/** Quotations for QUOTED services. Platforms submit and read their own; Finance lists and decides. */
@RestController
@RequestMapping("/internal/v1")
public class QuotationController {

    public record Milestone(@NotBlank @Size(max = 200) String description, @NotNull @Positive BigDecimal amount) {}

    public record SubmitBody(@NotBlank @Size(max = 100) String quoteReference,
                             @NotNull @Valid CreateBillRequest.Payer payer,
                             @NotBlank @Size(max = 50) String serviceCode,
                             @NotBlank @Size(max = 200) String description,
                             @NotNull LocalDate validUntil,
                             @Pattern(regexp = "sw|en") String language,
                             @NotEmpty @Size(max = 24) List<@NotNull @Valid Milestone> milestones) {}

    public record Decision(@NotBlank @Pattern(regexp = "APPROVED|REJECTED") String decision, @Size(max = 500) String reason) {}

    private final QuotationService quotations;
    private final Actors actors;

    public QuotationController(QuotationService quotations, Actors actors) {
        this.quotations = quotations;
        this.actors = actors;
    }

    @PostMapping("/quotations")
    public ResponseEntity<QuotationService.QuotationView> submit(@Valid @RequestBody SubmitBody body) {
        QuotationService.QuotationView v = quotations.submit(actors.current(), new QuotationService.SubmitRequest(
                body.quoteReference(), body.payer(), body.serviceCode(), body.description(), body.validUntil(), body.language(),
                body.milestones().stream().map(m -> new QuotationService.MilestoneRequest(m.description(), m.amount())).toList()));
        return ResponseEntity.created(URI.create("/internal/v1/quotations/" + v.quotationId())).body(v);
    }

    /** A platform looks up its own quotation by its reference, for example to see whether Finance has approved it. */
    @GetMapping("/quotations")
    public QuotationService.QuotationView byReference(@RequestParam String reference) {
        Actor a = actors.current();
        if (!a.isPlatform()) {
            throw ApiException.validation("reference", "Finance staff use /internal/v1/admin/quotations");
        }
        UUID id = quotations.findByReference(a.sourceSystemId(), reference).orElseThrow(() -> ApiException.notFound("Quotation not found"));
        return quotations.view(id);
    }

    @GetMapping("/quotations/{id}")
    public QuotationService.QuotationView get(@PathVariable UUID id) {
        Actor a = actors.current();
        UUID owner = quotations.owner(id).orElseThrow(() -> ApiException.notFound("Quotation not found"));
        if (!a.finance() && !owner.equals(a.sourceSystemId())) {
            throw ApiException.notFound("Quotation not found");
        }
        return quotations.view(id);
    }

    @PostMapping("/quotations/{id}/decision")
    public QuotationService.QuotationView decide(@PathVariable UUID id, @Valid @RequestBody Decision body) {
        return quotations.decide(actors.current(), id, body.decision(), body.reason());
    }

    @GetMapping("/admin/quotations")
    public List<QuotationService.QuotationView> list(@RequestParam(defaultValue = "") String status) {
        if (!actors.current().finance()) {
            throw ApiException.forbidden("CBR-AUT-006", "Finance console access requires a Finance role");
        }
        if (!status.isEmpty() && !List.of("Requested", "Approved", "Rejected", "Withdrawn").contains(status)) {
            throw ApiException.validation("status", "must be Requested, Approved, Rejected or Withdrawn");
        }
        return quotations.list(status);
    }
}
