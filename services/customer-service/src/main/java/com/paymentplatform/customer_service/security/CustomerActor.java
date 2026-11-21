package com.paymentplatform.customer_service.security;
import com.paymentplatform.customer_service.api.CustomerOperationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
public final class CustomerActor {
    private CustomerActor() { }
    public static String requireSubject(Jwt jwt) {
        String subject=jwt.getSubject();
        if(subject==null || subject.isBlank() || subject.length()>128 || !subject.equals(subject.strip())
                || subject.chars().anyMatch(Character::isISOControl))
            throw new CustomerOperationException(HttpStatus.FORBIDDEN,"ACTOR_REQUIRED","A stable authenticated actor is required.");
        return subject;
    }
}
