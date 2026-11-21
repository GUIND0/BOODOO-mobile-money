package com.paymentplatform.wallet_service.api;

import com.paymentplatform.wallet_service.wallet.WalletService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;

@RestController
public class WalletController {
    private final WalletService service;
    public WalletController(WalletService service) { this.service = service; }
    @PostMapping("/v1/wallets")
    ResponseEntity<WalletResponse> create(@AuthenticationPrincipal Jwt principal,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") String key,
            @Valid @RequestBody CreateWalletRequest request) {
        var result = service.create(principal.getClaimAsString("integrator_id"), key, request, principal.getTokenValue());
        return ResponseEntity.created(URI.create("/v1/wallets/" + result.response().id()))
                .cacheControl(CacheControl.noStore()).header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                .body(result.response());
    }
    @GetMapping("/v1/wallets/{walletId}")
    ResponseEntity<WalletResponse> get(@AuthenticationPrincipal Jwt principal, @PathVariable UUID walletId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.get(principal.getClaimAsString("integrator_id"), walletId, principal.getTokenValue()));
    }
}
