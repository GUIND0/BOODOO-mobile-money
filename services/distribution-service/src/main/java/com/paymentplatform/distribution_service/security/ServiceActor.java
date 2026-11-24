package com.paymentplatform.distribution_service.security;
import com.paymentplatform.distribution_service.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
public final class ServiceActor {
    private ServiceActor() { }
    public static String requireService(Jwt jwt) {
        if (!"service".equals(jwt.getClaims().get("actor_type"))) throw new ApiException(HttpStatus.FORBIDDEN,
                "SERVICE_REQUIRED", "An authenticated service identity is required.");
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank() || subject.length() > 128 || !subject.equals(subject.strip())
                || subject.chars().anyMatch(Character::isISOControl)) throw new ApiException(HttpStatus.FORBIDDEN,
                "ACTOR_REQUIRED", "A stable authenticated service subject is required.");
        return subject;
    }
}
