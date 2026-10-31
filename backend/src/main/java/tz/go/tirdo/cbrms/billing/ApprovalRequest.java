package tz.go.tirdo.cbrms.billing;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ApprovalRequest(
        @NotBlank @Pattern(regexp = "APPROVED|REJECTED") String decision,
        @Size(max = 500) String reason) {}
