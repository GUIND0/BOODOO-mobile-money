package com.paymentplatform.pricing_service.api;
import com.paymentplatform.pricing_service.domain.QuoteService;import com.paymentplatform.pricing_service.security.ServiceActor;
import jakarta.validation.Valid;import jakarta.validation.constraints.Pattern;import org.springframework.http.*;import org.springframework.security.core.annotation.AuthenticationPrincipal;import org.springframework.security.oauth2.jwt.Jwt;import org.springframework.web.bind.annotation.*;import java.util.UUID;
@RestController public class QuoteController {
 private final QuoteService service;public QuoteController(QuoteService service){this.service=service;}
 @PostMapping("/internal/v1/quotes") ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") String key,@Valid @RequestBody QuoteService.Request body){var result=service.create(jwt.getClaimAsString("integrator_id"),ServiceActor.requireService(jwt),key,body);return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).header("Idempotency-Replayed",Boolean.toString(result.replayed())).body(result.response());}
 @GetMapping("/internal/v1/quotes/{id}") ResponseEntity<?> get(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){ServiceActor.requireService(jwt);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(jwt.getClaimAsString("integrator_id"),id));}
}
