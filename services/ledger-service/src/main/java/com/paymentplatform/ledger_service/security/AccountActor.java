package com.paymentplatform.ledger_service.security;
import com.paymentplatform.ledger_service.api.AccountApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
public final class AccountActor {
    private AccountActor() { }
    public static String requireService(Jwt jwt) {
        if (!"service".equals(jwt.getClaims().get("actor_type"))) throw new AccountApiException(HttpStatus.FORBIDDEN,
                "SERVICE_REQUIRED", "An authenticated service identity is required.");
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank() || subject.length() > 128 || !subject.equals(subject.strip())
                || subject.chars().anyMatch(Character::isISOControl)) throw new AccountApiException(HttpStatus.FORBIDDEN,
                "ACTOR_REQUIRED", "A stable authenticated service subject is required.");
        return subject;
    }
}
