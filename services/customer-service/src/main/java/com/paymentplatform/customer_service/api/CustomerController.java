package com.paymentplatform.customer_service.api;

import com.paymentplatform.customer_service.customer.CustomerCreationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CustomerController {
    private final CustomerCreationService service;

    public CustomerController(CustomerCreationService service) {
        this.service = service;
    }

    @PostMapping("/v1/customers")
    ResponseEntity<CustomerResponse> create(@AuthenticationPrincipal Jwt principal,
            @RequestHeader("Idempotency-Key")
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") String key,
            @Valid @RequestBody CreateCustomerRequest request) {
        var creation = service.create(principal.getClaimAsString("integrator_id"), key, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Idempotency-Replayed", Boolean.toString(creation.replayed()))
                .body(creation.customer());
    }
}
