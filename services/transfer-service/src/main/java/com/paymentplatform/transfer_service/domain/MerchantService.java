package com.paymentplatform.transfer_service.domain;
import com.paymentplatform.transfer_service.api.ApiException;import com.paymentplatform.transfer_service.client.DependencyClient;import jakarta.validation.constraints.*;import org.springframework.http.HttpStatus;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;import tools.jackson.databind.ObjectMapper;import java.time.*;import java.util.*;
@Service public class MerchantService {
 private final JdbcTemplate jdbc;private final DependencyClient client;private final ServiceCredentials credentials;private final ObjectMapper json;
 public MerchantService(JdbcTemplate jdbc,DependencyClient client,ServiceCredentials credentials,ObjectMapper json){this.jdbc=jdbc;this.client=client;this.credentials=credentials;this.json=json;}
 public record Request(@NotNull UUID id,@NotNull UUID walletId,@NotBlank @Pattern(regexp="[A-Z][A-Z0-9_-]{0,63}") String code){}
 public record StateRequest(@NotNull UUID id,@NotBlank @Pattern(regexp="ACTIVE|SUSPENDED") String state,@Min(1) long expectedVersion,@NotBlank @Pattern(regexp="[A-Z][A-Z0-9_]{0,63}") String reasonCode){}
 public record Merchant(UUID id,UUID walletId,String code,String state,long version){}
 public Merchant get(String tenant,UUID id){return jdbc.query("SELECT * FROM merchants WHERE integrator_id=? AND id=?",(r,n)->new Merchant(id,r.getObject("wallet_id",UUID.class),r.getString("code"),r.getString("state"),r.getLong("version")),tenant,id).stream().findFirst().orElseThrow(()->error(HttpStatus.NOT_FOUND,"MERCHANT_NOT_FOUND"));}
 public Merchant create(String tenant,String actor,Request request){
  var current=client.get("wallet","/internal/v1/wallets/"+request.walletId()+"/operational-state",credentials.bearer(tenant));if(current.status()!=200||!request.walletId().equals(DependencyClient.uuid(current.body(),"walletId"))||!"XOF".equals(DependencyClient.text(current.body(),"currency"))||!"ACTIVE".equals(DependencyClient.text(current.body(),"status"))||!DependencyClient.bool(current.body(),"eligible"))throw error(HttpStatus.CONFLICT,"ELIGIBLE_WALLET_REQUIRED");
  Instant checked=Instant.parse(DependencyClient.text(current.body(),"checkedAt"));if(checked.isBefore(Instant.now().minusSeconds(30))||checked.isAfter(Instant.now().plusSeconds(30)))throw DependencyClient.unavailable();
  String hash=RequestKeys.hash(json.writeValueAsString(request),actor);
  int inserted=jdbc.update("INSERT INTO merchants(integrator_id,id,wallet_id,code,state,version,fingerprint) VALUES (?,?,?,?,'ACTIVE',1,?) ON CONFLICT DO NOTHING",tenant,request.id(),request.walletId(),request.code(),hash);
  if(inserted==0){var old=jdbc.query("SELECT fingerprint FROM merchants WHERE integrator_id=? AND id=?",(r,n)->r.getString(1),tenant,request.id());if(old.isEmpty()||!old.getFirst().equals(hash))throw error(HttpStatus.CONFLICT,"MERCHANT_IDENTITY_CONFLICT");}
  return get(tenant,request.id());
 }
 @Transactional(timeout=10) public Merchant state(String tenant,String actor,UUID id,StateRequest request){
  jdbc.query("SELECT id FROM merchants WHERE integrator_id=? AND id=? FOR UPDATE",(r,n)->r.getObject(1,UUID.class),tenant,id);String hash=RequestKeys.hash(json.writeValueAsString(List.of(id,request)),actor);
  var prior=jdbc.query("SELECT fingerprint,snapshot::text FROM merchant_events WHERE integrator_id=? AND id=?",(r,n)->List.of(r.getString(1),r.getString(2)),tenant,request.id());
  if(!prior.isEmpty()){if(!prior.getFirst().getFirst().equals(hash))throw error(HttpStatus.CONFLICT,"MERCHANT_EVENT_CONFLICT");return json.readValue(prior.getFirst().get(1),Merchant.class);}
  var old=get(tenant,id);if(old.version()!=request.expectedVersion())throw error(HttpStatus.PRECONDITION_FAILED,"MERCHANT_VERSION_CONFLICT");if(old.state().equals(request.state()))throw error(HttpStatus.CONFLICT,"MERCHANT_STATE_CONFLICT");
  jdbc.update("UPDATE merchants SET state=?,version=version+1 WHERE integrator_id=? AND id=?",request.state(),tenant,id);var result=get(tenant,id);jdbc.update("INSERT INTO merchant_events VALUES (?,?,?, ?,?::jsonb)",tenant,request.id(),id,hash,json.writeValueAsString(result));return result;
 }
 public UUID payableWallet(String tenant,UUID id){var merchant=get(tenant,id);if(!merchant.state().equals("ACTIVE"))throw error(HttpStatus.CONFLICT,"MERCHANT_SUSPENDED");return merchant.walletId();}
 private ApiException error(HttpStatus status,String code){return new ApiException(status,code,"The merchant operation cannot be accepted.");}
}
