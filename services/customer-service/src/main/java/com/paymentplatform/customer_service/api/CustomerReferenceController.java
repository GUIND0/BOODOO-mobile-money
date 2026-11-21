package com.paymentplatform.customer_service.api;

import com.paymentplatform.customer_service.customer.CustomerRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
public class CustomerReferenceController {
    private final CustomerRepository customers;
    public CustomerReferenceController(CustomerRepository customers) { this.customers = customers; }

    @GetMapping("/v1/customers/by-reference")
    ResponseEntity<CustomerProfileResponse> find(@AuthenticationPrincipal Jwt principal,
            @RequestParam @NotBlank @Size(max = 128) String externalReference) {
        var profile = customers.findProfileByReference(principal.getClaimAsString("integrator_id"), externalReference.strip())
                .orElseThrow(() -> new CustomerOperationException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Customer not found for this integrator."));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag("\"" + profile.version() + "\"").body(profile);
    }
}
