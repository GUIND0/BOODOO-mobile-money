package com.paymentplatform.customer_service.api;

import com.paymentplatform.customer_service.customer.CustomerRepository;
import com.paymentplatform.customer_service.customer.CustomerProfileService;
import com.paymentplatform.customer_service.security.CustomerActor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
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
    private final CustomerProfileService service;
    public CustomerProfileController(CustomerRepository customers, CustomerProfileService service) { this.customers = customers; this.service = service; }
    @GetMapping("/v1/customers/{customerId}")
    ResponseEntity<CustomerProfileResponse> get(@AuthenticationPrincipal Jwt principal, @PathVariable UUID customerId) {
        var profile = customers.findProfile(principal.getClaimAsString("integrator_id"), customerId)
                .orElseThrow(() -> new CustomerOperationException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Customer not found for this integrator."));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag("\""+profile.version()+"\"").body(profile);
    }
    @PatchMapping("/v1/customers/{customerId}")
    ResponseEntity<CustomerProfileResponse> patch(@AuthenticationPrincipal Jwt principal,@PathVariable UUID customerId,
            @RequestHeader(value="If-Match",required=false) String ifMatch,@Valid @RequestBody PatchCustomerRequest request){
        var profile=service.update(principal.getClaimAsString("integrator_id"),customerId,ifMatch,request,CustomerActor.requireSubject(principal));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag("\""+profile.version()+"\"").body(profile);
    }
}
