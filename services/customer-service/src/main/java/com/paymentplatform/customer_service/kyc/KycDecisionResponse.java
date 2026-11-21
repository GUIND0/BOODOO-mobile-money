package com.paymentplatform.customer_service.kyc;
import java.time.Instant;
import java.util.UUID;
public record KycDecisionResponse(UUID id,UUID customerId,UUID submissionId,String status,String reasonCode,String reviewedBy,Instant reviewedAt) { }
