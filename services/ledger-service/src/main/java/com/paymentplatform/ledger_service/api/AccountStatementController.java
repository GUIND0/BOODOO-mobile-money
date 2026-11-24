package com.paymentplatform.ledger_service.api;
import com.paymentplatform.ledger_service.journal.AccountStatementService;import com.paymentplatform.ledger_service.security.AccountActor;import org.springframework.http.*;import org.springframework.security.core.annotation.AuthenticationPrincipal;import org.springframework.security.oauth2.jwt.Jwt;import org.springframework.web.bind.annotation.*;import java.time.Instant;import java.util.UUID;
@RestController public class AccountStatementController {
 private final AccountStatementService service;public AccountStatementController(AccountStatementService service){this.service=service;}
 @GetMapping("/internal/v1/accounts/{id}/statement") ResponseEntity<?> get(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestParam Instant from,@RequestParam Instant to){AccountActor.requireService(jwt);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.snapshot(jwt.getClaimAsString("integrator_id"),id,from,to));}
}
