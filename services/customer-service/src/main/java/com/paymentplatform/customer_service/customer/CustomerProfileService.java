package com.paymentplatform.customer_service.customer;
import com.paymentplatform.customer_service.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
@Service
public class CustomerProfileService {
    private final CustomerRepository customers;
    public CustomerProfileService(CustomerRepository customers){this.customers=customers;}
    @Transactional(timeout=10)
    public CustomerProfileResponse update(String integrator,UUID id,String ifMatch,PatchCustomerRequest request,String actor){
        if(ifMatch==null)throw error(HttpStatus.PRECONDITION_REQUIRED,"PRECONDITION_REQUIRED","Supply the current profile ETag in If-Match.");
        long expected;
        try {
            if(!ifMatch.matches("\"[0-9]{1,19}\""))throw new IllegalArgumentException();
            expected=Long.parseLong(ifMatch.substring(1,ifMatch.length()-1));
        }catch(IllegalArgumentException e){throw error(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","If-Match must be a quoted profile revision.");}
        var old=customers.lockProfile(integrator,id).orElseThrow(()->error(HttpStatus.NOT_FOUND,"CUSTOMER_NOT_FOUND","Customer not found for this integrator."));
        if(old.version()!=expected)throw error(HttpStatus.PRECONDITION_FAILED,"PROFILE_VERSION_CONFLICT","The profile changed; read it again before updating.");
        if(!old.kycStatus().equals("PENDING") && !old.kycStatus().equals("REJECTED"))
            throw error(HttpStatus.CONFLICT,"PROFILE_LOCKED_FOR_KYC","This profile cannot be changed in its current KYC state.");
        var next=new CustomerProfileResponse(old.id(),old.externalReference(),pick(request.firstName(),old.firstName()),
                pick(request.lastName(),old.lastName()),old.phoneNumber(),old.kycStatus(),old.createdAt(),old.version(),old.updatedAt(),
                pick(request.dateOfBirth(),old.dateOfBirth()),pick(request.nationality(),old.nationality()),
                pick(request.residenceCountry(),old.residenceCountry()),pick(request.addressLine(),old.addressLine()),pick(request.city(),old.city()));
        var fields=new ArrayList<String>();
        if(!Objects.equals(next.firstName(),old.firstName()))fields.add("firstName");
        if(!Objects.equals(next.lastName(),old.lastName()))fields.add("lastName");
        if(!Objects.equals(next.dateOfBirth(),old.dateOfBirth()))fields.add("dateOfBirth");
        if(!Objects.equals(next.nationality(),old.nationality()))fields.add("nationality");
        if(!Objects.equals(next.residenceCountry(),old.residenceCountry()))fields.add("residenceCountry");
        if(!Objects.equals(next.addressLine(),old.addressLine()))fields.add("addressLine");
        if(!Objects.equals(next.city(),old.city()))fields.add("city");
        return fields.isEmpty()?old:customers.updateProfile(integrator,next,actor,fields);
    }
    private <T>T pick(T value,T old){return value==null?old:value;}
    private CustomerOperationException error(HttpStatus status,String code,String detail){return new CustomerOperationException(status,code,detail);}
}
