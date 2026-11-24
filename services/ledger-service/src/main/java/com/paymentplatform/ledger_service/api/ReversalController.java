package com.paymentplatform.ledger_service.api;
import com.paymentplatform.ledger_service.journal.ReversalService;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
@RestController
public class ReversalController {
 private final ReversalService service;public ReversalController(ReversalService service){this.service=service;}
 public record Request(@NotBlank @Pattern(regexp="[A-Z][A-Z0-9_]{0,63}") String reasonCode){}
 private String operator(Jwt jwt){String sub=jwt.getSubject();if(!"operator".equals(jwt.getClaims().get("actor_type"))||sub==null||sub.isBlank()||sub.length()>128||!sub.equals(sub.strip())||sub.chars().anyMatch(Character::isISOControl))throw new AccountApiException(HttpStatus.FORBIDDEN,"OPERATOR_REQUIRED","A stable operator identity is required.");return sub;}
 @PostMapping("/internal/v1/journals/{original}/reversal") ResponseEntity<?> request(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID original,@Valid @RequestBody Request body){return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(service.request(jwt.getClaimAsString("integrator_id"),operator(jwt),original,body.reasonCode()));}
 @PostMapping("/internal/v1/reversals/{id}/approval") ResponseEntity<?> approve(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.approve(jwt.getClaimAsString("integrator_id"),operator(jwt),id));}
 @GetMapping("/internal/v1/reversals/{id}") ResponseEntity<?> get(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){operator(jwt);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(jwt.getClaimAsString("integrator_id"),id));}
}
