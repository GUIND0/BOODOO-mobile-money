package com.paymentplatform.risk_compliance_service.domain;
import com.paymentplatform.risk_compliance_service.api.ApiException;import jakarta.validation.constraints.*;import org.springframework.boot.context.properties.EnableConfigurationProperties;import org.springframework.http.HttpStatus;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;import tools.jackson.databind.ObjectMapper;import java.time.*;import java.util.*;
@Service @EnableConfigurationProperties(RiskPolicy.class) public class RiskService {
 private final JdbcTemplate jdbc;private final ObjectMapper json;private final RiskPolicy policy;
 public RiskService(JdbcTemplate jdbc,ObjectMapper json,RiskPolicy policy){this.jdbc=jdbc;this.json=json;this.policy=policy;}
 public record Request(@NotNull UUID id,@NotNull UUID reference,@NotNull UUID customerId,@NotNull @Positive Long amount,@NotBlank @Pattern(regexp="XOF") String currency){}
 public record Evaluation(UUID id,UUID reference,UUID customerId,long amount,String currency,String outcome,String reasonCode,String policyVersion,String rulesFingerprint,Instant checkedAt,Instant expiresAt){}
 public record Result(Evaluation response,boolean replayed){}
 @Transactional(timeout=10) public Result evaluate(String tenant,String actor,Request request){
  String hash=RequestKeys.hash(json.writeValueAsString(request),actor);
  jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",(org.springframework.jdbc.core.RowCallbackHandler)row->{},tenant.length()+":"+tenant+request.id());
  var old=jdbc.query("SELECT fingerprint,snapshot::text FROM risk_evaluations WHERE integrator_id=? AND id=?",(row,n)->List.of(row.getString(1),row.getString(2)),tenant,request.id());
  if(!old.isEmpty()){if(!old.getFirst().getFirst().equals(hash))throw new ApiException(HttpStatus.CONFLICT,"EVALUATION_CONFLICT","Evaluation ID conflicts with request or producer.");return new Result(json.readValue(old.getFirst().get(1),Evaluation.class),true);}
  boolean blocked=policy.blockedCustomers().contains(request.customerId()),review=request.amount()>policy.automaticAmount();
  String outcome=blocked?"DENY":review?"REVIEW":"ALLOW",reason=blocked?"CONFIGURED_BLOCK":review?"AMOUNT_REQUIRES_REVIEW":"LOCAL_RULES_PASSED";
  String rules=RequestKeys.hash(json.writeValueAsString(List.of(policy.version(),policy.automaticAmount(),policy.ttlSeconds(),policy.blockedCustomers().stream().map(UUID::toString).sorted().toList())),"risk-policy");
  Instant now=jdbc.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class).toInstant();
  var result=new Evaluation(request.id(),request.reference(),request.customerId(),request.amount(),"XOF",outcome,reason,policy.version(),rules,now,now.plusSeconds(policy.ttlSeconds()));
  jdbc.update("INSERT INTO risk_evaluations VALUES (?,?,?,?,?,?,?::jsonb)",tenant,request.id(),hash,request.reference(),request.customerId(),policy.version(),json.writeValueAsString(result));return new Result(result,false);
 }
 public Evaluation get(String tenant,UUID id){return jdbc.query("SELECT snapshot::text FROM risk_evaluations WHERE integrator_id=? AND id=?",(row,n)->json.readValue(row.getString(1),Evaluation.class),tenant,id).stream().findFirst().orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"EVALUATION_NOT_FOUND","Evaluation not found for this integrator."));}
}
