package com.paymentplatform.customer_service.kyc;

import com.paymentplatform.customer_service.api.KycSubmissionHistoryController.Entry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class KycSubmissionHistoryRepository {
    private final JdbcTemplate jdbc;
    public KycSubmissionHistoryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public List<Entry> findPage(String tenant, UUID customer, UUID after, int limit) {
        return jdbc.query("""
                SELECT s.id, s.profile_version, s.submitted_at, COALESCE(d.status, 'UNDER_REVIEW') AS status
                FROM kyc_submissions s LEFT JOIN kyc_decisions d
                  ON d.integrator_id = s.integrator_id AND d.submission_id = s.id
                WHERE s.integrator_id = ? AND s.customer_id = ? AND (?::uuid IS NULL OR s.id > ?::uuid)
                ORDER BY s.id LIMIT ?
                """, (row, index) -> new Entry(row.getObject("id", UUID.class), row.getLong("profile_version"),
                row.getString("status"), row.getObject("submitted_at", OffsetDateTime.class).toInstant()),
                tenant, customer, after, after, limit + 1);
    }
}
