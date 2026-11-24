package com.paymentplatform.wallet_service.api;
import com.paymentplatform.wallet_service.activation.ActivationWorkflow;
import jakarta.validation.Valid;import jakarta.validation.constraints.*;import org.springframework.http.*;import org.springframework.security.core.annotation.AuthenticationPrincipal;import org.springframework.security.oauth2.jwt.Jwt;import org.springframework.web.bind.annotation.*;import java.util.*;
@RestController public class WalletControlController {
 private final ActivationWorkflow workflow;public WalletControlController(ActivationWorkflow workflow){this.workflow=workflow;}
 public record Request(@NotNull UUID id,@NotBlank @Pattern(regexp="OPEN|FROZEN|CLOSED") String state,@Min(0) long expectedVersion,@NotBlank @Pattern(regexp="[A-Z][A-Z0-9_]{0,63}") String reasonCode){}
 @PostMapping("/internal/v1/wallets/{id}/control") ResponseEntity<?> change(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@Valid @RequestBody Request body){if(!"operator".equals(jwt.getClaims().get("actor_type")))throw new WalletApiException(HttpStatus.FORBIDDEN,"OPERATOR_REQUIRED","A signed operator is required.");return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(workflow.control(jwt.getClaimAsString("integrator_id"),id,Map.of("id",body.id(),"state",body.state(),"expectedVersion",body.expectedVersion(),"reasonCode",body.reasonCode()),jwt.getTokenValue()));}
}
