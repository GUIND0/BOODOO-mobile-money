package com.paymentplatform.identity_service.api;

import com.paymentplatform.identity_service.customer.CustomerOwnershipClient;
import com.paymentplatform.identity_service.pin.PinRepository;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
public class PinStatusController {
    private final CustomerOwnershipClient customers;
    private final PinRepository pins;
    public PinStatusController(CustomerOwnershipClient customers, PinRepository pins) { this.customers = customers; this.pins = pins; }
    @GetMapping("/v1/customers/{customerId}/pin")
    ResponseEntity<Status> get(@AuthenticationPrincipal Jwt principal, @PathVariable UUID customerId) {
        customers.requireOwnership(customerId, principal.getTokenValue());
        boolean configured = pins.exists(principal.getClaimAsString("integrator_id"), customerId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Status(customerId, configured));
    }
    public record Status(UUID customerId, boolean configured) { }
}
