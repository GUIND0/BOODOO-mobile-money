package com.paymentplatform.customer_service.kyc;
import com.paymentplatform.customer_service.api.*;
import com.paymentplatform.customer_service.customer.CustomerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
@Service
public class KycSubmissionService {
    private final CustomerRepository customers;private final KycRepository kyc;private final ObjectMapper json;private final java.time.Clock clock;
    public KycSubmissionService(CustomerRepository customers,KycRepository kyc,ObjectMapper json,java.time.Clock clock){this.customers=customers;this.kyc=kyc;this.json=json;this.clock=clock;}
    @Transactional(timeout=10)
    public Submission submit(String integrator,UUID id,String key,String ifMatch,KycSubmissionRequest request,String actor){
        long version=ProfileRevision.require(ifMatch);String fingerprint=fingerprint(id,version,request,actor);
        customers.lockCreation(integrator,"kyc-submit:"+key);
        var saved=kyc.findRequest(integrator,key);
        if(saved.isPresent()){
            if(!saved.get().fingerprint().equals(fingerprint))throw error(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","This key belongs to another KYC request.");
            return new Submission(json.readValue(saved.get().response(),KycSubmissionResponse.class),true);
        }
        var profile=customers.lockProfile(integrator,id).orElseThrow(()->error(HttpStatus.NOT_FOUND,"CUSTOMER_NOT_FOUND","Customer not found for this integrator."));
        if(profile.version()!=version)throw error(HttpStatus.PRECONDITION_FAILED,"PROFILE_VERSION_CONFLICT","Read the current profile before submitting KYC.");
        if(!profile.kycStatus().equals("PENDING") && !profile.kycStatus().equals("REJECTED"))
            throw error(HttpStatus.CONFLICT,"KYC_SUBMISSION_CONFLICT","This customer already has an active or approved KYC dossier.");
        if(profile.dateOfBirth()==null || profile.nationality()==null || profile.residenceCountry()==null || profile.addressLine()==null || profile.city()==null)
            throw error(HttpStatus.CONFLICT,"PROFILE_INCOMPLETE","Complete the profile before submitting KYC.");
        if(request.documents().stream().anyMatch(document->document.expiresOn()!=null && document.expiresOn().isBefore(java.time.LocalDate.now(clock))))
            throw error(HttpStatus.BAD_REQUEST,"KYC_DOCUMENT_EXPIRED","A document is expired at submission time.");
        var response=kyc.insert(integrator,profile,request.canonical(),actor);kyc.activate(integrator,id,response.id());
        kyc.saveRequest(integrator,key,fingerprint,response);return new Submission(response,false);
    }
    private String fingerprint(UUID id,long version,KycSubmissionRequest request,String actor){
        try{
            MessageDigest hash=MessageDigest.getInstance("SHA-256");
            add(hash,id.toString());add(hash,Long.toString(version));add(hash,actor);
            for(var document:request.canonical()){
                add(hash,document.type().name());add(hash,document.reference());add(hash,document.issuingCountry());
                add(hash,document.expiresOn()==null?"":document.expiresOn().toString());
            }
            return HexFormat.of().formatHex(hash.digest());
        }catch(NoSuchAlgorithmException e){throw new IllegalStateException("Required digest unavailable",e);}
    }
    private void add(MessageDigest hash,String value){byte[] bytes=value.getBytes(StandardCharsets.UTF_8);hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array());hash.update(bytes);}
    private CustomerOperationException error(HttpStatus status,String code,String detail){return new CustomerOperationException(status,code,detail);}
    public record Submission(KycSubmissionResponse response,boolean replayed) { }
}
