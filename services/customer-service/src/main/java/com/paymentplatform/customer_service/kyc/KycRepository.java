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
    private static final String DOSSIER_SQL="""
            SELECT s.*,d.id AS decision_id,d.status AS decision_status,d.reason_code,d.reviewed_by,d.reviewed_at
            FROM kyc_submissions s LEFT JOIN kyc_decisions d ON d.integrator_id=s.integrator_id AND d.submission_id=s.id
            WHERE s.integrator_id=? AND s.customer_id=? AND s.id=?
            """;
    private KycSubmissionResponse readSubmission(java.sql.ResultSet row)throws java.sql.SQLException {
        String status=row.getString("decision_status");
        return new KycSubmissionResponse(row.getObject("id",UUID.class),row.getObject("customer_id",UUID.class),status==null?"UNDER_REVIEW":status,
                row.getLong("profile_version"),json.readValue(row.getString("profile_snapshot"),CustomerProfileResponse.class),
                java.util.List.of(json.readValue(row.getString("documents"),KycDocument[].class)),row.getString("submitted_by"),
                row.getObject("submitted_at",OffsetDateTime.class).toInstant());
    }
    public Optional<KycSubmissionResponse> findSubmission(String integrator,UUID customer,UUID submission){
        return jdbc.query(DOSSIER_SQL,(row,index)->readSubmission(row),integrator,customer,submission).stream().findFirst();
    }
    public Optional<KycDossierResponse> findDossier(String integrator,UUID customer,UUID submission){
        return jdbc.query(DOSSIER_SQL,(row,index)->{
            var base=readSubmission(row);UUID decision=row.getObject("decision_id",UUID.class);
            var result=decision==null?null:new KycDecisionResponse(decision,customer,submission,row.getString("decision_status"),
                    row.getString("reason_code"),row.getString("reviewed_by"),row.getObject("reviewed_at",OffsetDateTime.class).toInstant());
            return KycDossierResponse.of(base,result);
        },integrator,customer,submission).stream().findFirst();
    }
    public void activate(String integrator,UUID customer,UUID submission){
        jdbc.update("UPDATE customers SET active_kyc_submission_id=?,kyc_status='UNDER_REVIEW',profile_version=profile_version+1,updated_at=clock_timestamp() WHERE integrator_id=? AND id=?",submission,integrator,customer);
    }
    public void saveRequest(String integrator,String key,String fingerprint,KycSubmissionResponse response){
        jdbc.update("INSERT INTO kyc_submission_requests VALUES (?,?,?,?,?,?::jsonb)",integrator,key,response.customerId(),response.id(),fingerprint,json.writeValueAsString(response));
    }
    public record SavedRequest(String fingerprint,String response) { }
}
