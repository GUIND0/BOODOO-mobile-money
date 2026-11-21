package com.paymentplatform.customer_service.api;
import com.paymentplatform.customer_service.kyc.*;
import com.paymentplatform.customer_service.security.CustomerActor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController
public class KycDecisionController {
    private final KycDecisionService service;
    public KycDecisionController(KycDecisionService service){this.service=service;}
    @PostMapping("/v1/customers/{customerId}/kyc-submissions/{submissionId}/decisions")
    ResponseEntity<KycDecisionResponse> review(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID customerId,@PathVariable UUID submissionId,
            @RequestHeader("Idempotency-Key") @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") String key,
            @Valid @RequestBody KycDecisionRequest request){
        var review=service.review(jwt.getClaimAsString("integrator_id"),customerId,submissionId,key,request,CustomerActor.requireOperator(jwt));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .header("Idempotency-Replayed",Boolean.toString(review.replayed())).body(review.response());
    }
}
