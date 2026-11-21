package com.paymentplatform.customer_service.api;
import org.springframework.http.HttpStatus;
public final class ProfileRevision {
    private ProfileRevision() { }
    public static long require(String value) {
        if(value==null)throw new CustomerOperationException(HttpStatus.PRECONDITION_REQUIRED,"PRECONDITION_REQUIRED","Supply the current profile ETag in If-Match.");
        try {
            if(!value.matches("\"[0-9]{1,19}\""))throw new IllegalArgumentException();
            return Long.parseLong(value.substring(1,value.length()-1));
        }catch(IllegalArgumentException error){throw new CustomerOperationException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","If-Match must be a quoted profile revision.");}
    }
}
