package tz.go.tirdo.cbrms.catalogue;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
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
import tz.go.tirdo.cbrms.common.ApiException;
import tz.go.tirdo.cbrms.identity.Actor;
import tz.go.tirdo.cbrms.identity.Actors;

/** Catalogue: Finance maintains it through approved proposals; platforms read their own services. */
@RestController
@RequestMapping("/internal/v1")
public class CatalogueController {

    public record ProposalBody(@NotBlank @Pattern(regexp = "NEW_SERVICE|NEW_TARIFF|NEW_REVENUE_MAPPING|SERVICE_UPDATE") String kind,
                               @NotBlank @Size(max = 50) String serviceCode,
                               @NotBlank @Size(max = 500) String reason,
                               @Size(max = 100) String approvalRef,
                               @Size(max = 200) String name, @Size(max = 200) String nameSw,
                               @Size(max = 20) String directorateCode, @Size(max = 30) String unit, Integer quantityScale,
                               Boolean requiresApproval, @Pattern(regexp = "TARIFF|QUOTED") String pricingBasis,
                               @Pattern(regexp = "ACTIVE|INACTIVE") String serviceStatus, List<@Size(max = 30) String> platforms,
                               BigDecimal unitPrice, String paymentMode, String roundingRule, Integer maxValidityDays,
                               @Size(max = 30) String gfsCode, @Size(max = 30) String accountingCode,
                               LocalDate effectiveFrom, LocalDate effectiveTo) {}

    public record Decision(@NotBlank @Pattern(regexp = "APPROVED|REJECTED") String decision, @Size(max = 500) String reason) {}

    private final CatalogueService catalogue;
    private final Actors actors;

    public CatalogueController(CatalogueService catalogue, Actors actors) {
        this.catalogue = catalogue;
        this.actors = actors;
    }

    /** For platforms: the services they may bill, the tariff version to send today and any scheduled price change. */
    @GetMapping("/catalogue")
    public List<CatalogueService.PlatformService> mine() {
        Actor a = actors.current();
        if (!a.isPlatform()) {
            throw ApiException.validation("caller", "Finance staff use /internal/v1/admin/catalogue");
        }
        return catalogue.forPlatform(a.sourceSystemId());
    }

    @GetMapping("/admin/catalogue")
    public List<CatalogueService.ServiceRow> services() {
        finance();
        return catalogue.services();
    }

    @GetMapping("/admin/catalogue/services/{code}")
    public CatalogueService.ServiceDetail service(@PathVariable String code) {
        finance();
        return catalogue.detail(code);
    }

    @GetMapping("/admin/catalogue/changes")
    public List<CatalogueService.ChangeView> changes(@RequestParam(defaultValue = "") String status) {
        finance();
        if (!status.isEmpty() && !List.of("Requested", "Applied", "Rejected").contains(status)) {
            throw ApiException.validation("status", "must be Requested, Applied or Rejected");
        }
        return catalogue.changes(status);
    }

    @GetMapping("/admin/catalogue/changes/{id}")
    public CatalogueService.ChangeView change(@PathVariable UUID id) {
        finance();
        return catalogue.change(id);
    }

    @GetMapping("/admin/directorates")
    public List<CatalogueService.Directorate> directorates() {
        finance();
        return catalogue.directorates();
    }

    @PostMapping("/admin/catalogue/changes")
    public ResponseEntity<CatalogueService.ChangeView> propose(@Valid @RequestBody ProposalBody b) {
        Actor a = finance();
        CatalogueService.ChangeView v = catalogue.propose(a, new CatalogueService.Proposal(
                CatalogueService.Kind.valueOf(b.kind()), b.serviceCode(), b.reason(), b.approvalRef(), b.name(), b.nameSw(),
                b.directorateCode(), b.unit(), b.quantityScale(), b.requiresApproval(), b.pricingBasis(), b.serviceStatus(),
                b.platforms(), b.unitPrice(), b.paymentMode(), b.roundingRule(), b.maxValidityDays(), b.gfsCode(),
                b.accountingCode(), b.effectiveFrom(), b.effectiveTo()));
        return ResponseEntity.status(HttpStatus.CREATED).body(v);
    }

    @PostMapping("/catalogue-changes/{id}/decision")
    public CatalogueService.ChangeView decide(@PathVariable UUID id, @Valid @RequestBody Decision body) {
        return catalogue.decide(actors.current(), id, body.decision(), body.reason());
    }

    private Actor finance() {
        Actor a = actors.current();
        if (!a.finance()) {
            throw ApiException.forbidden("CBR-AUT-006", "Finance console access requires a Finance role");
        }
        return a;
    }
}
