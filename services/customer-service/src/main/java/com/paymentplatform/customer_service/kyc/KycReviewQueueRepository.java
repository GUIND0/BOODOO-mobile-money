package com.paymentplatform.customer_service.kyc;

import com.paymentplatform.customer_service.api.KycReviewQueueController.Entry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class KycReviewQueueRepository {
    private final JdbcTemplate jdbc;
    public KycReviewQueueRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Entry> findPage(String tenant, String reviewer, UUID after, int limit) {
        return jdbc.query("""
                SELECT s.id, s.customer_id, s.profile_version, s.submitted_at
                FROM kyc_submissions s
                JOIN customers c ON c.id = s.customer_id AND c.integrator_id = s.integrator_id
                  AND c.active_kyc_submission_id = s.id
                WHERE s.integrator_id = ? AND c.kyc_status = 'UNDER_REVIEW'
                  AND c.profile_version = s.profile_version + 1
                  AND s.submitted_by <> ? AND (?::uuid IS NULL OR s.id > ?::uuid)
                  AND NOT EXISTS (SELECT 1 FROM kyc_decisions d
                      WHERE d.integrator_id = s.integrator_id AND d.submission_id = s.id)
                ORDER BY s.id LIMIT ?
                """, (row, index) -> new Entry(row.getObject("id", UUID.class), row.getObject("customer_id", UUID.class),
                row.getLong("profile_version"), row.getObject("submitted_at", OffsetDateTime.class).toInstant()),
                tenant, reviewer, after, after, limit + 1);
    }
}
