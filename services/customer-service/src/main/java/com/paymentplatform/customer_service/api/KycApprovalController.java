package com.paymentplatform.customer_service.api;

import com.paymentplatform.customer_service.kyc.KycApprovalRepository;
import com.paymentplatform.customer_service.security.CustomerActor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

@RestController
public class KycApprovalController {
    private final KycApprovalRepository approvals;
    private final Clock clock;
    public KycApprovalController(KycApprovalRepository approvals, Clock clock) { this.approvals = approvals; this.clock = clock; }
    @GetMapping("/internal/v1/customers/{customerId}/kyc-approval")
    ResponseEntity<Approval> get(@AuthenticationPrincipal Jwt principal, @PathVariable UUID customerId) {
        if (!"service".equals(principal.getClaims().get("actor_type"))) throw new CustomerOperationException(
                HttpStatus.FORBIDDEN, "SERVICE_REQUIRED", "An authenticated service identity is required.");
        CustomerActor.requireSubject(principal);
        var snapshot = approvals.find(principal.getClaimAsString("integrator_id"), customerId)
                .orElseThrow(() -> new CustomerOperationException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Customer not found for this integrator."));
        Instant checkedAt = clock.instant();
        LocalDate today = LocalDate.ofInstant(checkedAt, ZoneOffset.UTC);
        boolean current = "APPROVED".equals(snapshot.status()) && "APPROVED".equals(snapshot.decisionStatus())
                && snapshot.decisionId() != null && snapshot.submittedVersion() != null
                && snapshot.version() - snapshot.submittedVersion() == 2
                && !snapshot.documents().isEmpty()
                && snapshot.documents().stream().noneMatch(document -> document.expiresOn() != null && document.expiresOn().isBefore(today));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Approval(customerId, snapshot.status(),
                snapshot.version(), snapshot.decisionId(), snapshot.reviewedAt(), current, checkedAt));
    }
    public record Approval(UUID customerId, String kycStatus, long profileVersion, UUID decisionId,
                           Instant reviewedAt, boolean approvalCurrent, Instant checkedAt) { }
}
