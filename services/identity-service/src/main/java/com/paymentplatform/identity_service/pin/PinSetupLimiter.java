package com.paymentplatform.identity_service.pin;

import com.paymentplatform.identity_service.api.PinApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class PinSetupLimiter {
    private final JdbcTemplate jdbc;

    public PinSetupLimiter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // The attempt must remain committed if the later creation conflicts or rolls back.
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public void consume(String integrator, UUID customer) {
        var accepted = jdbc.query("""
                INSERT INTO pin_setup_attempts (integrator_id, customer_id, window_started_at, attempts)
                VALUES (?, ?, CURRENT_TIMESTAMP, 1)
                ON CONFLICT (integrator_id, customer_id) DO UPDATE SET
                  window_started_at = CASE WHEN pin_setup_attempts.window_started_at <= CURRENT_TIMESTAMP - INTERVAL '15 minutes'
                    THEN CURRENT_TIMESTAMP ELSE pin_setup_attempts.window_started_at END,
                  attempts = CASE WHEN pin_setup_attempts.window_started_at <= CURRENT_TIMESTAMP - INTERVAL '15 minutes'
                    THEN 1 ELSE pin_setup_attempts.attempts + 1 END
                WHERE pin_setup_attempts.window_started_at <= CURRENT_TIMESTAMP - INTERVAL '15 minutes'
                  OR pin_setup_attempts.attempts < 10
                RETURNING attempts
                """, (row, index) -> row.getInt(1), integrator, customer);
        if (accepted.isEmpty()) throw new PinApiException(HttpStatus.TOO_MANY_REQUESTS, "PIN_SETUP_RATE_LIMITED",
                "PIN setup attempts are temporarily limited. Retry after 15 minutes.");
    }
}
