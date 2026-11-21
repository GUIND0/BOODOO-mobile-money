package com.paymentplatform.customer_service.api;

import com.paymentplatform.customer_service.customer.CustomerRepository;
import com.paymentplatform.customer_service.kyc.KycSubmissionHistoryRepository;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
public class KycSubmissionHistoryController {
    private final CustomerRepository customers;
    private final KycSubmissionHistoryRepository history;
    public KycSubmissionHistoryController(CustomerRepository customers, KycSubmissionHistoryRepository history) {
        this.customers = customers; this.history = history;
    }
    @GetMapping("/v1/customers/{customerId}/kyc-submissions")
    ResponseEntity<Page> list(@AuthenticationPrincipal Jwt principal, @PathVariable UUID customerId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) UUID after) {
        String tenant = principal.getClaimAsString("integrator_id");
        if (!customers.belongsTo(tenant, customerId)) throw new CustomerOperationException(HttpStatus.NOT_FOUND,
                "CUSTOMER_NOT_FOUND", "Customer not found for this integrator.");
        var rows = history.findPage(tenant, customerId, after, limit);
        var items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Page(items, rows.size() > limit ? items.getLast().id() : null));
    }
    public record Entry(UUID id, long profileVersion, String status, Instant submittedAt) { }
    public record Page(List<Entry> items, UUID nextAfter) { }
}
