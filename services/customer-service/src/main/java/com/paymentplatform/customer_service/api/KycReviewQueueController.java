package com.paymentplatform.customer_service.api;

import com.paymentplatform.customer_service.kyc.KycReviewQueueRepository;
import com.paymentplatform.customer_service.security.CustomerActor;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
public class KycReviewQueueController {
    private final KycReviewQueueRepository queue;
    public KycReviewQueueController(KycReviewQueueRepository queue) { this.queue = queue; }

    @GetMapping("/v1/kyc-review-queue")
    ResponseEntity<Page> list(@AuthenticationPrincipal Jwt principal,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) UUID after) {
        String reviewer = CustomerActor.requireOperator(principal);
        var rows = queue.findPage(principal.getClaimAsString("integrator_id"), reviewer, after, limit);
        boolean more = rows.size() > limit;
        var items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Page(items, more ? items.getLast().submissionId() : null));
    }
    public record Entry(UUID submissionId, UUID customerId, long profileVersion, Instant submittedAt) { }
    public record Page(List<Entry> items, UUID nextAfter) { }
}
