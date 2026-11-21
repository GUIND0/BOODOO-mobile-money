package com.paymentplatform.identity_service.pin;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

@Repository
public class PinVerificationRepository {
    private final JdbcTemplate jdbc;

    public PinVerificationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Instant now() {
        return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
    }

    public State load(String integrator, UUID customer) {
        jdbc.update("""
                INSERT INTO pin_verification_state (integrator_id, customer_id, rate_window_started_at)
                VALUES (?, ?, clock_timestamp()) ON CONFLICT DO NOTHING
                """, integrator, customer);
        return jdbc.queryForObject("""
                SELECT rate_window_started_at, attempts, failed_attempts, locked_until
                FROM pin_verification_state WHERE integrator_id = ? AND customer_id = ? FOR UPDATE
                """, (row, index) -> {
            var locked = row.getObject("locked_until", OffsetDateTime.class);
            return new State(row.getObject("rate_window_started_at", OffsetDateTime.class).toInstant(),
                    row.getInt("attempts"), row.getInt("failed_attempts"), locked == null ? null : locked.toInstant());
        }, integrator, customer);
    }

    public void save(String integrator, UUID customer, State state) {
        int changed = jdbc.update("""
                UPDATE pin_verification_state SET rate_window_started_at = ?, attempts = ?, failed_attempts = ?, locked_until = ?
                WHERE integrator_id = ? AND customer_id = ?
                """, OffsetDateTime.ofInstant(state.windowStarted(), java.time.ZoneOffset.UTC), state.attempts(), state.failures(),
                state.lockedUntil() == null ? null : OffsetDateTime.ofInstant(state.lockedUntil(), java.time.ZoneOffset.UTC), integrator, customer);
        if (changed != 1) throw new DataIntegrityViolationException("PIN verification state was not persisted");
    }

    public record State(Instant windowStarted, int attempts, int failures, Instant lockedUntil) { }
}
