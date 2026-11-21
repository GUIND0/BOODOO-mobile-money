package com.paymentplatform.customer_service.kyc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import com.paymentplatform.customer_service.api.CustomerProfileResponse;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
@Repository
public class KycRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public KycRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    public Optional<SavedRequest> findRequest(String integrator,String key){
        return jdbc.query("SELECT request_fingerprint,response_snapshot::text FROM kyc_submission_requests WHERE integrator_id=? AND idempotency_key=?",
                (row,index)->new SavedRequest(row.getString(1),row.getString(2)),integrator,key).stream().findFirst();
    }
    public KycSubmissionResponse insert(String integrator,CustomerProfileResponse profile,java.util.List<KycDocument> documents,String actor){
        UUID id=UUID.randomUUID();
        var at=jdbc.queryForObject("""
                INSERT INTO kyc_submissions (id,integrator_id,customer_id,profile_version,profile_snapshot,documents,submitted_by)
                VALUES (?,?,?,?,?::jsonb,?::jsonb,?) RETURNING submitted_at
                """,OffsetDateTime.class,id,integrator,profile.id(),profile.version(),json.writeValueAsString(profile),json.writeValueAsString(documents),actor);
        return new KycSubmissionResponse(id,profile.id(),"UNDER_REVIEW",profile.version(),profile,documents,actor,at.toInstant());
    }
    public Optional<KycSubmissionResponse> findSubmission(String integrator,UUID customer,UUID submission){
        return jdbc.query("SELECT * FROM kyc_submissions WHERE integrator_id=? AND customer_id=? AND id=?",
                (row,index)->new KycSubmissionResponse(row.getObject("id",UUID.class),row.getObject("customer_id",UUID.class),"UNDER_REVIEW",
                        row.getLong("profile_version"),json.readValue(row.getString("profile_snapshot"),CustomerProfileResponse.class),
                        java.util.List.of(json.readValue(row.getString("documents"),KycDocument[].class)),row.getString("submitted_by"),
                        row.getObject("submitted_at",OffsetDateTime.class).toInstant()),integrator,customer,submission).stream().findFirst();
    }
    public void activate(String integrator,UUID customer,UUID submission){
        jdbc.update("UPDATE customers SET active_kyc_submission_id=?,kyc_status='UNDER_REVIEW',profile_version=profile_version+1,updated_at=clock_timestamp() WHERE integrator_id=? AND id=?",submission,integrator,customer);
    }
    public void saveRequest(String integrator,String key,String fingerprint,KycSubmissionResponse response){
        jdbc.update("INSERT INTO kyc_submission_requests VALUES (?,?,?,?,?,?::jsonb)",integrator,key,response.customerId(),response.id(),fingerprint,json.writeValueAsString(response));
    }
    public record SavedRequest(String fingerprint,String response) { }
}
