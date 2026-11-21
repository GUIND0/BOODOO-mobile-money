package com.paymentplatform.customer_service.kyc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
@Repository
public class KycDecisionRepository {
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    public KycDecisionRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    public Optional<KycRepository.SavedRequest> findRequest(String integrator,String key){return jdbc.query(
            "SELECT request_fingerprint,response_snapshot::text FROM kyc_decision_requests WHERE integrator_id=? AND idempotency_key=?",
            (row,index)->new KycRepository.SavedRequest(row.getString(1),row.getString(2)),integrator,key).stream().findFirst();}
    public boolean decided(String integrator,UUID submission){return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM kyc_decisions WHERE integrator_id=? AND submission_id=?)",Boolean.class,integrator,submission));}
    public UUID active(String integrator,UUID customer){return jdbc.queryForObject(
            "SELECT active_kyc_submission_id FROM customers WHERE integrator_id=? AND id=?",UUID.class,integrator,customer);}
    public KycDecisionResponse insert(String integrator,UUID customer,UUID submission,KycDecisionRequest request,String actor){
        UUID id=UUID.randomUUID();var at=jdbc.queryForObject("""
                INSERT INTO kyc_decisions (id,integrator_id,customer_id,submission_id,status,reason_code,reviewed_by)
                VALUES (?,?,?,?,?,?,?) RETURNING reviewed_at
                """,OffsetDateTime.class,id,integrator,customer,submission,request.decision().name(),request.reasonCode(),actor);
        return new KycDecisionResponse(id,customer,submission,request.decision().name(),request.reasonCode(),actor,at.toInstant());
    }
    public void updateCustomer(String integrator,UUID customer,String status){jdbc.update(
            "UPDATE customers SET kyc_status=?,profile_version=profile_version+1,updated_at=clock_timestamp() WHERE integrator_id=? AND id=?",status,integrator,customer);}
    public void saveRequest(String integrator,String key,String hash,KycDecisionResponse response){jdbc.update(
            "INSERT INTO kyc_decision_requests VALUES (?,?,?,?,?,?::jsonb)",integrator,key,response.customerId(),response.id(),hash,json.writeValueAsString(response));}
}
