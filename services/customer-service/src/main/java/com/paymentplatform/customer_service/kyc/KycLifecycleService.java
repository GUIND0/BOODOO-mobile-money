package com.paymentplatform.customer_service.kyc;
import com.paymentplatform.customer_service.api.CustomerOperationException;
import com.paymentplatform.customer_service.customer.CustomerRepository;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
@Service public class KycLifecycleService {
 private final JdbcTemplate jdbc;private final CustomerRepository customers;private final ObjectMapper json;
 public KycLifecycleService(JdbcTemplate jdbc,CustomerRepository customers,ObjectMapper json){this.jdbc=jdbc;this.customers=customers;this.json=json;}
 public enum Action {RENEW,REVOKE}
 public record Request(@NotNull UUID id,@NotNull Action action,@Min(0) long expectedVersion,@NotBlank @Pattern(regexp="[A-Z][A-Z0-9_]{0,63}") String reasonCode){}
 public record View(UUID id,UUID customerId,Action action,String kycStatus,long profileVersion){}
 @Transactional(timeout=10) public View change(String tenant,String actor,UUID customer,Request request){
  customers.lockCreation(tenant,"kyc-lifecycle:"+request.id());String fingerprint=json.writeValueAsString(List.of(customer,actor,request));
  var prior=jdbc.query("SELECT fingerprint,snapshot::text FROM kyc_lifecycle_events WHERE integrator_id=? AND id=?",(r,n)->List.of(r.getString(1),r.getString(2)),tenant,request.id());
  if(!prior.isEmpty()){if(!prior.getFirst().getFirst().equals(fingerprint))throw error(HttpStatus.CONFLICT,"LIFECYCLE_ID_CONFLICT");return json.readValue(prior.getFirst().get(1),View.class);}
  var old=customers.lockProfile(tenant,customer).orElseThrow(()->error(HttpStatus.NOT_FOUND,"CUSTOMER_NOT_FOUND"));
  if(old.version()!=request.expectedVersion())throw error(HttpStatus.PRECONDITION_FAILED,"PROFILE_VERSION_CONFLICT");
  if(!Set.of("APPROVED","UNDER_REVIEW").contains(old.kycStatus()))throw error(HttpStatus.CONFLICT,"KYC_STATE_CONFLICT");
  // Both actions invalidate the active frozen dossier. Old evidence and decisions remain immutable.
  jdbc.update("UPDATE customers SET kyc_status='PENDING',active_kyc_submission_id=NULL,profile_version=profile_version+1,updated_at=clock_timestamp() WHERE integrator_id=? AND id=?",tenant,customer);
  var view=new View(request.id(),customer,request.action(),"PENDING",old.version()+1);
  jdbc.update("INSERT INTO kyc_lifecycle_events(integrator_id,id,customer_id,actor_id,fingerprint,snapshot) VALUES (?,?,?,?,?,?::jsonb)",tenant,request.id(),customer,actor,fingerprint,json.writeValueAsString(view));return view;
 }
 private CustomerOperationException error(HttpStatus s,String c){return new CustomerOperationException(s,c,"The KYC lifecycle change cannot be accepted.");}
}
