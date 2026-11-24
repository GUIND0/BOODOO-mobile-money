package com.paymentplatform.risk_compliance_service.api;
import com.paymentplatform.risk_compliance_service.domain.RiskService;import com.paymentplatform.risk_compliance_service.security.ServiceActor;import jakarta.validation.Valid;import org.springframework.http.*;import org.springframework.security.core.annotation.AuthenticationPrincipal;import org.springframework.security.oauth2.jwt.Jwt;import org.springframework.web.bind.annotation.*;import java.util.UUID;
@RestController public class RiskController {
 private final RiskService service;public RiskController(RiskService service){this.service=service;}
 @PostMapping("/internal/v1/evaluations") ResponseEntity<?> evaluate(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody RiskService.Request request){var result=service.evaluate(jwt.getClaimAsString("integrator_id"),ServiceActor.requireService(jwt),request);return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).header("Idempotency-Replayed",Boolean.toString(result.replayed())).body(result.response());}
 @GetMapping("/internal/v1/evaluations/{id}") ResponseEntity<?> get(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){ServiceActor.requireService(jwt);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(jwt.getClaimAsString("integrator_id"),id));}
}
