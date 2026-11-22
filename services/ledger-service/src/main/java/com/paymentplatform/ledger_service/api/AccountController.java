package com.paymentplatform.ledger_service.api;
import com.paymentplatform.ledger_service.account.AccountService;
import com.paymentplatform.ledger_service.security.AccountActor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;
@RestController
public class AccountController {
    private final AccountService service;
    public AccountController(AccountService service) { this.service = service; }
    @PostMapping("/internal/v1/accounts")
    ResponseEntity<AccountResponse> create(@AuthenticationPrincipal Jwt principal,
            @RequestHeader("Idempotency-Key") @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") String key,
            @Valid @RequestBody CreateAccountRequest request) {
        String actor=AccountActor.requireService(principal);
        var result=service.create(principal.getClaimAsString("integrator_id"),key,request,actor);
        return ResponseEntity.created(URI.create("/internal/v1/accounts/"+result.response().id())).cacheControl(CacheControl.noStore())
                .header("Idempotency-Replayed",Boolean.toString(result.replayed())).body(result.response());
    }
    @GetMapping("/internal/v1/accounts/{accountId}")
    ResponseEntity<AccountResponse> get(@AuthenticationPrincipal Jwt principal, @PathVariable UUID accountId) {
        AccountActor.requireService(principal);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(principal.getClaimAsString("integrator_id"),accountId));
    }
}
