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
public class KycSubmissionController {
    private final KycSubmissionService service;private final KycRepository kyc;
    public KycSubmissionController(KycSubmissionService service,KycRepository kyc){this.service=service;this.kyc=kyc;}
    @PostMapping("/v1/customers/{customerId}/kyc-submissions")
    ResponseEntity<KycSubmissionResponse> submit(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID customerId,
            @RequestHeader("Idempotency-Key") @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") String key,
            @RequestHeader(value="If-Match",required=false) String ifMatch,@Valid @RequestBody KycSubmissionRequest request){
        var submission=service.submit(jwt.getClaimAsString("integrator_id"),customerId,key,ifMatch,request,CustomerActor.requireSubject(jwt));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .header("Idempotency-Replayed",Boolean.toString(submission.replayed())).body(submission.response());
    }
    @GetMapping("/v1/customers/{customerId}/kyc-submissions/{submissionId}")
    ResponseEntity<KycDossierResponse> get(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID customerId,@PathVariable UUID submissionId){
        var result=kyc.findDossier(jwt.getClaimAsString("integrator_id"),customerId,submissionId)
                .orElseThrow(()->new CustomerOperationException(HttpStatus.NOT_FOUND,"KYC_SUBMISSION_NOT_FOUND","KYC submission not found for this integrator."));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
}
