CREATE TABLE pin_verification_state (
    integrator_id VARCHAR(64) NOT NULL,
    customer_id UUID NOT NULL,
    rate_window_started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 30),
    failed_attempts INTEGER NOT NULL DEFAULT 0 CHECK (failed_attempts BETWEEN 0 AND 5),
    locked_until TIMESTAMPTZ,
    PRIMARY KEY (integrator_id, customer_id),
    FOREIGN KEY (integrator_id, customer_id) REFERENCES customer_pins (integrator_id, customer_id),
    CHECK ((failed_attempts = 5) = (locked_until IS NOT NULL))
);
