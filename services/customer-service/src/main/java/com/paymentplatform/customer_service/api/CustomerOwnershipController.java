package com.paymentplatform.customer_service.api;

import com.paymentplatform.customer_service.customer.CustomerRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class CustomerOwnershipController {
    private final CustomerRepository customers;

    public CustomerOwnershipController(CustomerRepository customers) {
        this.customers = customers;
    }

    @GetMapping("/internal/v1/customers/{customerId}/ownership")
    ResponseEntity<Void> ownership(@AuthenticationPrincipal Jwt principal, @PathVariable UUID customerId) {
        return customers.belongsTo(principal.getClaimAsString("integrator_id"), customerId)
                ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
