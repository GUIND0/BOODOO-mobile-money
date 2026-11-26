package com.paymentplatform.ledger_service.api;
import com.paymentplatform.ledger_service.journal.*;
import com.paymentplatform.ledger_service.security.AccountActor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController
public class JournalController {
 private final JournalService service;private final PostingPolicy policy;public JournalController(JournalService service,PostingPolicy policy){this.service=service;this.policy=policy;}
 @PostMapping("/internal/v1/journals") ResponseEntity<?> post(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") String key,@Valid @RequestBody JournalRequest body){AccountActor.requireService(jwt);policy.require(jwt,body);var result=service.post(jwt.getClaimAsString("integrator_id"),AccountActor.requireService(jwt),key,body);return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).header("Idempotency-Replayed",Boolean.toString(result.replayed())).body(result.receipt());}
 @GetMapping("/internal/v1/journals/{id}") ResponseEntity<?> get(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){AccountActor.requireService(jwt);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(jwt.getClaimAsString("integrator_id"),id));}
 @GetMapping("/internal/v1/journals/by-reference") ResponseEntity<?> reference(@AuthenticationPrincipal Jwt jwt,@RequestParam UUID reference){AccountActor.requireService(jwt);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.byReference(jwt.getClaimAsString("integrator_id"),reference));}
 @GetMapping("/internal/v1/accounts/{id}/balance") ResponseEntity<?> balance(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){AccountActor.requireService(jwt);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.balance(jwt.getClaimAsString("integrator_id"),id));}
 @GetMapping("/internal/v1/reconciliation/snapshot") ResponseEntity<?> reconciliation(@AuthenticationPrincipal Jwt jwt){AccountActor.requireService(jwt);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.reconcile(jwt.getClaimAsString("integrator_id")));}
}
