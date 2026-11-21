package com.paymentplatform.customer_service.kyc;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Comparator;
import java.util.List;
public record KycSubmissionRequest(@NotEmpty @Size(max=3) List<@NotNull @Valid KycDocument> documents) {
    public KycSubmissionRequest {if(documents!=null)documents=List.copyOf(documents);}
    @AssertTrue public boolean isDistinct(){return documents==null || documents.stream().map(KycDocument::reference).distinct().count()==documents.size();}
    public List<KycDocument> canonical(){return documents.stream().sorted(Comparator.comparing(KycDocument::reference)).toList();}
    @Override public String toString(){return "KycSubmissionRequest[REDACTED]";}
}
