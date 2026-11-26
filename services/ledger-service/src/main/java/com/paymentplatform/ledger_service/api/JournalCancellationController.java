package com.paymentplatform.ledger_service.api;
import com.paymentplatform.ledger_service.journal.JournalService;import com.paymentplatform.ledger_service.security.AccountActor;import org.springframework.http.*;import org.springframework.security.core.annotation.AuthenticationPrincipal;import org.springframework.security.oauth2.jwt.Jwt;import org.springframework.web.bind.annotation.*;import java.util.UUID;
@RestController public class JournalCancellationController {
 private final JournalService service;public JournalCancellationController(JournalService service){this.service=service;}
 @PostMapping("/internal/v1/journal-references/{reference}/cancel") ResponseEntity<?> cancel(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID reference){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.cancel(jwt.getClaimAsString("integrator_id"),AccountActor.requireService(jwt),reference));}
}
