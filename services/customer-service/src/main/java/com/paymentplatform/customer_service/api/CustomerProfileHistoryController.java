package com.paymentplatform.customer_service.api;

import com.paymentplatform.customer_service.customer.CustomerProfileHistoryRepository;
import com.paymentplatform.customer_service.customer.CustomerRepository;
import com.paymentplatform.customer_service.security.CustomerActor;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
public class CustomerProfileHistoryController {
    private final CustomerRepository customers;
    private final CustomerProfileHistoryRepository history;
    public CustomerProfileHistoryController(CustomerRepository customers, CustomerProfileHistoryRepository history) {
        this.customers = customers; this.history = history;
    }
    @GetMapping("/v1/customers/{customerId}/profile-history")
    ResponseEntity<Page> list(@AuthenticationPrincipal Jwt principal, @PathVariable UUID customerId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) long afterVersion) {
        CustomerActor.requireOperator(principal);
        String tenant = principal.getClaimAsString("integrator_id");
        if (!customers.belongsTo(tenant, customerId)) throw new CustomerOperationException(HttpStatus.NOT_FOUND,
                "CUSTOMER_NOT_FOUND", "Customer not found for this integrator.");
        var rows = history.findPage(tenant, customerId, afterVersion, limit);
        var items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Page(items, rows.size() > limit ? items.getLast().version() : null));
    }
    public record Entry(long version, String actorId, List<String> changedFields, Instant changedAt) {
        @Override public String toString() { return "ProfileHistoryEntry[REDACTED]"; }
    }
    public record Page(List<Entry> items, Long nextAfterVersion) { }
}
