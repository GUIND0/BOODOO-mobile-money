package com.paymentplatform.notification_service.domain;
import com.paymentplatform.notification_service.api.ApiException;import jakarta.validation.constraints.*;import org.springframework.http.HttpStatus;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;import tools.jackson.databind.ObjectMapper;import java.util.*;import java.time.*;
@Service public class WebhookOutbox {
 private final JdbcTemplate jdbc;private final WebhookDestinations destinations;private final ObjectMapper json;
 public WebhookOutbox(JdbcTemplate jdbc,WebhookDestinations destinations,ObjectMapper json){this.jdbc=jdbc;this.destinations=destinations;this.json=json;}
 public void enqueue(String tenant,UUID id,String payload){var destination=destinations.get(tenant);jdbc.update("INSERT INTO webhook_outbox(integrator_id,id,payload,destination_version,state) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",tenant,id,payload,destination.map(WebhookDestinations.Destination::version).orElse(null),destination.isPresent()?"READY":"UNCONFIGURED");}
 public record Work(String tenant,UUID id,String payload,String version,UUID lease,int attempt){}
 @Transactional(timeout=10) public Optional<Work> claim(){
  jdbc.update("UPDATE webhook_outbox SET state='DEAD',last_code='RETRY_BUDGET_EXHAUSTED',lease_token=NULL,lease_until=NULL WHERE state IN ('READY','INFLIGHT') AND attempts>=8 AND (lease_until IS NULL OR lease_until<=clock_timestamp())");
  return jdbc.query("""
   WITH candidate AS(SELECT integrator_id,id FROM webhook_outbox WHERE attempts<8 AND ((state='READY' AND next_attempt_at<=clock_timestamp()) OR (state='INFLIGHT' AND lease_until<=clock_timestamp())) ORDER BY next_attempt_at,id FOR UPDATE SKIP LOCKED LIMIT 1)
   UPDATE webhook_outbox w SET state='INFLIGHT',attempts=attempts+1,lease_token=?,lease_until=clock_timestamp()+INTERVAL '30 seconds' FROM candidate c WHERE w.integrator_id=c.integrator_id AND w.id=c.id RETURNING w.*
   """,(r,n)->new Work(r.getString("integrator_id"),r.getObject("id",UUID.class),r.getString("payload"),r.getString("destination_version"),r.getObject("lease_token",UUID.class),r.getInt("attempts")),UUID.randomUUID()).stream().findFirst();
 }
 public void finish(Work work,boolean delivered,String code,boolean permanent){long delay=Math.min(3600,30L<<Math.min(work.attempt()-1,7));String state=delivered?"DELIVERED":permanent||work.attempt()>=8?"DEAD":"READY";jdbc.update("UPDATE webhook_outbox SET state=?,last_code=?,next_attempt_at=clock_timestamp()+(? * INTERVAL '1 second'),delivered_at=CASE WHEN ? THEN clock_timestamp() ELSE delivered_at END,lease_token=NULL,lease_until=NULL WHERE integrator_id=? AND id=? AND lease_token=? AND lease_until>clock_timestamp()",state,code,delay,delivered,work.tenant(),work.id(),work.lease());}
 public record View(UUID id,String state,int attempts,Instant nextAttemptAt,Instant deliveredAt,String lastCode){}
 public View get(String tenant,UUID id){return jdbc.query("SELECT * FROM webhook_outbox WHERE integrator_id=? AND id=?",(r,n)->new View(id,r.getString("state"),r.getInt("attempts"),r.getObject("next_attempt_at",OffsetDateTime.class).toInstant(),r.getObject("delivered_at",OffsetDateTime.class)==null?null:r.getObject("delivered_at",OffsetDateTime.class).toInstant(),r.getString("last_code")),tenant,id).stream().findFirst().orElseThrow(()->error(HttpStatus.NOT_FOUND,"DELIVERY_NOT_FOUND"));}
 public record Retry(@NotNull UUID id,@NotBlank @Pattern(regexp="[A-Z][A-Z0-9_]{0,63}") String reasonCode){}
 @Transactional(timeout=10) public View retry(String tenant,String actor,UUID id,Retry request){
  jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",(org.springframework.jdbc.core.RowCallbackHandler)r->{},"webhook-retry:"+tenant+":"+request.id());
  String hash=RequestKeys.hash(json.writeValueAsString(List.of(id,request)),actor);var previous=jdbc.query("SELECT fingerprint,snapshot::text FROM webhook_redrives WHERE integrator_id=? AND id=?",(r,n)->List.of(r.getString(1),r.getString(2)),tenant,request.id());if(!previous.isEmpty()){if(!hash.equals(previous.getFirst().getFirst()))throw error(HttpStatus.CONFLICT,"REDRIVE_ID_CONFLICT");return json.readValue(previous.getFirst().get(1),View.class);}
  jdbc.query("SELECT id FROM webhook_outbox WHERE integrator_id=? AND id=? FOR UPDATE",(r,n)->r.getObject(1,UUID.class),tenant,id);var old=get(tenant,id);if(!Set.of("DEAD","UNCONFIGURED").contains(old.state()))throw error(HttpStatus.CONFLICT,"DEAD_DELIVERY_REQUIRED");var config=destinations.get(tenant).orElseThrow(()->error(HttpStatus.CONFLICT,"DESTINATION_REQUIRED"));
  jdbc.update("UPDATE webhook_outbox SET state='READY',attempts=0,destination_version=?,next_attempt_at=clock_timestamp(),last_code=NULL,lease_token=NULL,lease_until=NULL WHERE integrator_id=? AND id=?",config.version(),tenant,id);var view=get(tenant,id);jdbc.update("INSERT INTO webhook_redrives VALUES (?,?,?,?::jsonb)",tenant,request.id(),hash,json.writeValueAsString(view));return view;
 }
 private ApiException error(HttpStatus s,String c){return new ApiException(s,c,"The webhook delivery operation cannot be accepted.");}
}
