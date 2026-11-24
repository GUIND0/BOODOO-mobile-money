package com.paymentplatform.customer_service.api;
import com.paymentplatform.customer_service.kyc.KycLifecycleService;
import com.paymentplatform.customer_service.security.CustomerActor;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController public class KycLifecycleController {
 private final KycLifecycleService service;public KycLifecycleController(KycLifecycleService service){this.service=service;}
 @PostMapping("/internal/v1/customers/{id}/kyc-lifecycle") ResponseEntity<?> change(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@Valid @RequestBody KycLifecycleService.Request body){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.change(jwt.getClaimAsString("integrator_id"),CustomerActor.requireOperator(jwt),id,body));}
}
