package tz.go.tirdo.cbrms.billing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * POST /internal/v1/bills body. Platforms send what was requested; CBRMS prices it.
 * There is deliberately no amount or total field (Blueprint section 8).
 */
public record CreateBillRequest(
        @NotBlank @Size(max = 100) String sourceReference,
        @NotNull @Valid Payer payer,
        @NotBlank @Pattern(regexp = "TZS", message = "only TZS is supported in Phase 1") String currency,
        @NotBlank @Size(max = 200) String description,
        @Min(1) @Max(365) Integer validityDays,
        @NotEmpty @Size(max = 50) List<@NotNull @Valid Line> lines,
        /** Set when this bill replaces a cancelled bill for the same liability (FR11). */
        java.util.UUID replacesBillId,
        /** Payer's language for notices and receipts: "sw" (default) or "en". */
        @Pattern(regexp = "sw|en") String language) {

    public record Payer(
            @NotBlank @Size(max = 100) String reference,
            @NotBlank @Size(max = 200) String name,
            @Pattern(regexp = "\\+?[0-9]{9,15}", message = "phone must be 9 to 15 digits") String phone,
            @Email @Size(max = 200) String email) {}

    /**
     * A catalogue line (serviceCode, tariffVersion, quantity), or for a QUOTED service a milestone of an approved
     * quotation (quotationReference and milestone, quantity 1). The price always comes from CBRMS records.
     */
    public record Line(
            @NotBlank @Size(max = 50) String serviceCode,
            @NotNull @Min(1) Integer tariffVersion,
            @NotNull @Positive BigDecimal quantity,
            @Size(max = 100) String quotationReference,
            @Min(1) Integer milestone) {

        public Line(String serviceCode, Integer tariffVersion, BigDecimal quantity) {
            this(serviceCode, tariffVersion, quantity, null, null);
        }
    }
}
