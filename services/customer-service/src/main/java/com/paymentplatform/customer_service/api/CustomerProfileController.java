package com.paymentplatform.customer_service.api;

import com.paymentplatform.customer_service.customer.CustomerRepository;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

@RestController
public class CustomerProfileController {
    private final CustomerRepository customers;
    public CustomerProfileController(CustomerRepository customers) { this.customers = customers; }
    @GetMapping("/v1/customers/{customerId}")
    ResponseEntity<CustomerResponse> get(@AuthenticationPrincipal Jwt principal, @PathVariable UUID customerId) {
        var profile = customers.findProfile(principal.getClaimAsString("integrator_id"), customerId)
                .orElseThrow(() -> new CustomerOperationException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Customer not found for this integrator."));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profile);
    }
}
