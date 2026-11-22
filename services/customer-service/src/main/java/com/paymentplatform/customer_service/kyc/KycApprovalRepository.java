package com.paymentplatform.customer_service.kyc;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class KycApprovalRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public KycApprovalRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }
    public Optional<Snapshot> find(String tenant, UUID customer) {
        return jdbc.query("""
                SELECT c.kyc_status, c.profile_version, s.profile_version AS submitted_version, s.documents,
                       d.id AS decision_id, d.status AS decision_status, d.reviewed_at
                FROM customers c
                LEFT JOIN kyc_submissions s ON s.id = c.active_kyc_submission_id
                  AND s.integrator_id = c.integrator_id AND s.customer_id = c.id
                LEFT JOIN kyc_decisions d ON d.submission_id = s.id
                  AND d.integrator_id = c.integrator_id AND d.customer_id = c.id
                WHERE c.integrator_id = ? AND c.id = ?
                """, (row, index) -> {
                    String documents = row.getString("documents");
                    OffsetDateTime reviewed = row.getObject("reviewed_at", OffsetDateTime.class);
                    return new Snapshot(row.getString("kyc_status"), row.getLong("profile_version"),
                            row.getObject("submitted_version", Long.class), row.getObject("decision_id", UUID.class),
                            row.getString("decision_status"), reviewed == null ? null : reviewed.toInstant(),
                            documents == null ? List.of() : List.of(json.readValue(documents, KycDocument[].class)));
                }, tenant, customer).stream().findFirst();
    }
    public record Snapshot(String status, long version, Long submittedVersion, UUID decisionId,
                           String decisionStatus, Instant reviewedAt, List<KycDocument> documents) {
        @Override public String toString() { return "KycApprovalSnapshot[REDACTED]"; }
    }
}
