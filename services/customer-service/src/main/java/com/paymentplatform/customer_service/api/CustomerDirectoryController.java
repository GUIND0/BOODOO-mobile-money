package com.paymentplatform.customer_service.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import com.paymentplatform.customer_service.customer.CustomerDirectoryRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
public class CustomerDirectoryController {
    private final CustomerDirectoryRepository directory;

    public CustomerDirectoryController(CustomerDirectoryRepository directory) { this.directory = directory; }

    @GetMapping("/v1/customers")
    ResponseEntity<Page> list(@AuthenticationPrincipal Jwt principal,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) UUID after,
            @RequestParam(required = false) KycStatus kycStatus) {
        String tenant = principal.getClaimAsString("integrator_id");
        var rows = directory.findPage(tenant, after, kycStatus, limit);
        boolean more = rows.size() > limit;
        var items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Page(items, more ? items.getLast().id() : null));
    }

    public enum KycStatus { PENDING, UNDER_REVIEW, APPROVED, REJECTED }
    public record Entry(UUID id, String externalReference, String firstName, String lastName,
                        String kycStatus, Instant createdAt) {
        @Override public String toString() { return "CustomerDirectoryEntry[REDACTED]"; }
    }
    public record Page(List<Entry> items, UUID nextAfter) { }
}
