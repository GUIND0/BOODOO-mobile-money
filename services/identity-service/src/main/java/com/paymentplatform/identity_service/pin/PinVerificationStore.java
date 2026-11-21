package com.paymentplatform.identity_service.pin;

import com.paymentplatform.identity_service.api.PinApiException;
import com.paymentplatform.identity_service.security.PinProtector;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.UUID;

@Service
public class PinVerificationStore {
    private final PinRepository pins;
    private final PinVerificationRepository states;
    private final PinProtector protector;

    public PinVerificationStore(PinRepository pins, PinVerificationRepository states, PinProtector protector) {
        this.pins = pins;
        this.states = states;
        this.protector = protector;
    }

    // Business failures commit the comparison counters. Infrastructure failures roll them back.
    @Transactional(noRollbackFor = PinApiException.class, timeout = 10)
    public void verify(String integrator, UUID customer, String pin) {
        compare(integrator, customer, pin, null);
    }

    // Joins creation replay transactions; both boundaries commit business-failure counters.
    @Transactional(noRollbackFor = PinApiException.class, timeout = 10)
    public void verifyCreationReplay(String integrator, UUID customer, String pin, String requestHmac) {
        compare(integrator, customer, pin, requestHmac);
    }

    private void compare(String integrator, UUID customer, String pin, String requestHmac) {
        pins.lock("pin-verification", integrator, customer.toString());
        var credential = pins.findCredential(integrator, customer).orElseThrow(() ->
                new PinApiException(HttpStatus.NOT_FOUND, "PIN_NOT_CONFIGURED", "No PIN is configured for this customer."));
        if (!credential.version().equals(protector.version())) throw new PinApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "PIN_KEY_UNAVAILABLE", "The configured PIN protection key version is unavailable for this credential.");
        var previous = states.load(integrator, customer);
        Instant now = states.now();
        if (previous.lockedUntil() != null && previous.lockedUntil().isAfter(now)) throw locked();
        int failures = previous.lockedUntil() == null ? previous.failures() : 0;
        boolean expired = !previous.windowStarted().plusSeconds(900).isAfter(now);
        Instant window = expired ? now : previous.windowStarted();
        int attempts = expired ? 0 : previous.attempts();
        if (attempts >= 30) {
            states.save(integrator, customer, new PinVerificationRepository.State(window, attempts, failures, null));
            throw new PinApiException(HttpStatus.TOO_MANY_REQUESTS, "PIN_VERIFICATION_RATE_LIMITED",
                    "PIN comparisons are temporarily limited. Retry after 15 minutes.");
        }
        boolean valid = requestHmac == null ? protector.matches(integrator, customer, pin, credential.hash())
                : MessageDigest.isEqual(requestHmac.getBytes(StandardCharsets.US_ASCII),
                        protector.fingerprint(integrator, customer, pin).getBytes(StandardCharsets.US_ASCII));
        int nextFailures = valid ? 0 : failures + 1;
        Instant lockUntil = nextFailures == 5 ? now.plusSeconds(900) : null;
        states.save(integrator, customer, new PinVerificationRepository.State(window, attempts + 1, nextFailures, lockUntil));
        if (lockUntil != null) throw locked();
        if (!valid) {
            if (requestHmac != null) throw new PinApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "This key was used with a different PIN setup request.");
            throw new PinApiException(HttpStatus.UNPROCESSABLE_CONTENT, "PIN_INVALID", "PIN verification failed.");
        }
    }

    private PinApiException locked() {
        return new PinApiException(HttpStatus.TOO_MANY_REQUESTS, "PIN_VERIFICATION_LOCKED",
                "PIN comparisons are temporarily blocked after repeated failures. Retry after 15 minutes.");
    }
}
