package com.paymentplatform.customer_service.kyc;
import com.paymentplatform.customer_service.api.CustomerProfileResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public record KycDossierResponse(UUID id,UUID customerId,String status,long profileVersion,CustomerProfileResponse profile,
        List<KycDocument> documents,String submittedBy,Instant submittedAt,KycDecisionResponse decision) {
    public static KycDossierResponse of(KycSubmissionResponse s,KycDecisionResponse d){return new KycDossierResponse(
            s.id(),s.customerId(),s.status(),s.profileVersion(),s.profile(),s.documents(),s.submittedBy(),s.submittedAt(),d);}
    @Override public String toString(){return "KycDossierResponse[REDACTED]";}
}
