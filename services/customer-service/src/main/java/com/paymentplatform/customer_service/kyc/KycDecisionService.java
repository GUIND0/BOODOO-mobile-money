package com.paymentplatform.customer_service.kyc;
import com.paymentplatform.customer_service.api.CustomerOperationException;
import com.paymentplatform.customer_service.customer.CustomerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
@Service
public class KycDecisionService {
    private final CustomerRepository customers;private final KycRepository kyc;private final KycDecisionRepository decisions;
    private final ObjectMapper json;private final Clock clock;
    public KycDecisionService(CustomerRepository customers,KycRepository kyc,KycDecisionRepository decisions,ObjectMapper json,Clock clock){
        this.customers=customers;this.kyc=kyc;this.decisions=decisions;this.json=json;this.clock=clock;}
    @Transactional(timeout=10)
    public Review review(String integrator,UUID customer,UUID submission,String key,KycDecisionRequest request,String actor){
        String hash=fingerprint(customer,submission,request,actor);customers.lockCreation(integrator,"kyc-review:"+key);
        var saved=decisions.findRequest(integrator,key);
        if(saved.isPresent()){
            if(!saved.get().fingerprint().equals(hash))throw error(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","This key belongs to another KYC decision request.");
            return new Review(json.readValue(saved.get().response(),KycDecisionResponse.class),true);
        }
        var profile=customers.lockProfile(integrator,customer).orElseThrow(()->error(HttpStatus.NOT_FOUND,"CUSTOMER_NOT_FOUND","Customer not found for this integrator."));
        var dossier=kyc.findSubmission(integrator,customer,submission).orElseThrow(()->error(HttpStatus.NOT_FOUND,"KYC_SUBMISSION_NOT_FOUND","KYC submission not found for this integrator."));
        if(decisions.decided(integrator,submission))throw error(HttpStatus.CONFLICT,"KYC_DECISION_EXISTS","This submission already has a final decision.");
        if(!profile.kycStatus().equals("UNDER_REVIEW") || !submission.equals(decisions.active(integrator,customer))
                || profile.version()!=dossier.profileVersion()+1)
            throw error(HttpStatus.CONFLICT,"STALE_KYC_SUBMISSION","The dossier is no longer the current frozen profile.");
        if(actor.equals(dossier.submittedBy()))throw error(HttpStatus.FORBIDDEN,"SELF_REVIEW_FORBIDDEN","The submitter cannot review their own dossier.");
        if(request.decision()==KycDecisionRequest.Decision.APPROVED && dossier.documents().stream()
                .anyMatch(document->document.expiresOn()!=null && document.expiresOn().isBefore(LocalDate.now(clock))))
            throw error(HttpStatus.CONFLICT,"KYC_DOCUMENT_EXPIRED","Expired evidence cannot be approved.");
        var response=decisions.insert(integrator,customer,submission,request,actor);
        decisions.updateCustomer(integrator,customer,response.status());decisions.saveRequest(integrator,key,hash,response);
        return new Review(response,false);
    }
    private String fingerprint(UUID customer,UUID submission,KycDecisionRequest request,String actor){
        try{
            var digest=MessageDigest.getInstance("SHA-256");
            for(String value:List.of(customer.toString(),submission.toString(),request.decision().name(),request.reasonCode(),actor)){
                byte[] bytes=value.getBytes(StandardCharsets.UTF_8);digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes);}
            return HexFormat.of().formatHex(digest.digest());
        }catch(NoSuchAlgorithmException e){throw new IllegalStateException("Required digest unavailable",e);}
    }
    private CustomerOperationException error(HttpStatus status,String code,String message){return new CustomerOperationException(status,code,message);}
    public record Review(KycDecisionResponse response,boolean replayed) { }
}
