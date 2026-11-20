package com.paymentplatform.identity_service.api;

import com.paymentplatform.identity_service.pin.PinVerificationService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class PinVerificationController {
    private final PinVerificationService service;

    public PinVerificationController(PinVerificationService service) { this.service = service; }

    @PostMapping("/v1/customers/{customerId}/pin/verify")
    ResponseEntity<Void> verify(@AuthenticationPrincipal Jwt principal, @PathVariable UUID customerId,
                               @Valid @RequestBody VerifyPinRequest request) {
        service.verify(principal.getClaimAsString("integrator_id"), customerId, request.value(), principal.getTokenValue());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
