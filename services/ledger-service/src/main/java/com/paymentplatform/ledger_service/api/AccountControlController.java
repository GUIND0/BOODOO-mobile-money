package com.paymentplatform.ledger_service.api;
import com.paymentplatform.ledger_service.journal.AccountControlService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController public class AccountControlController {
 private final AccountControlService service;public AccountControlController(AccountControlService service){this.service=service;}
 @GetMapping("/internal/v1/accounts/{id}/control") ResponseEntity<?> get(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(jwt.getClaimAsString("integrator_id"),id));}
 @PostMapping("/internal/v1/accounts/{id}/control") ResponseEntity<?> change(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@Valid @RequestBody AccountControlService.Request request){String actor=jwt.getSubject();if(!"operator".equals(jwt.getClaims().get("actor_type"))||actor==null||actor.isBlank()||actor.length()>128||!actor.equals(actor.strip())||actor.chars().anyMatch(Character::isISOControl))throw new AccountApiException(HttpStatus.FORBIDDEN,"OPERATOR_REQUIRED","A stable signed operator is required.");return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.change(jwt.getClaimAsString("integrator_id"),actor,id,request));}
}
