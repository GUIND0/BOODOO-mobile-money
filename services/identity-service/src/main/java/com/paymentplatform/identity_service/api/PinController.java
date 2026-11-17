package com.paymentplatform.identity_service.api;

import com.paymentplatform.identity_service.pin.PinCreationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class PinController {
    private final PinCreationService service;

    public PinController(PinCreationService service) { this.service = service; }

    @PostMapping("/v1/customers/{customerId}/pin")
    ResponseEntity<PinResponse> create(@AuthenticationPrincipal Jwt principal, @PathVariable UUID customerId,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") String key,
            @Valid @RequestBody CreatePinRequest request) {
        var creation = service.create(principal.getClaimAsString("integrator_id"), customerId, key,
                request.value(), principal.getTokenValue());
        return ResponseEntity.status(HttpStatus.CREATED).header("Idempotency-Replayed", Boolean.toString(creation.replayed()))
                .body(creation.response());
    }
}
