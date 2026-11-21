package com.paymentplatform.customer_service.kyc;
import com.paymentplatform.customer_service.api.CustomerProfileResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public record KycSubmissionResponse(UUID id,UUID customerId,String status,long profileVersion,
        CustomerProfileResponse profile,List<KycDocument> documents,String submittedBy,Instant submittedAt) {
    @Override public String toString(){return "KycSubmissionResponse[REDACTED]";}
}
