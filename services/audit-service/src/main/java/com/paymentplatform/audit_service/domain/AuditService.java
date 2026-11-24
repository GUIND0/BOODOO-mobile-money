package com.paymentplatform.audit_service.domain;
import com.paymentplatform.audit_service.api.ApiException;import jakarta.validation.constraints.*;import org.springframework.http.HttpStatus;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;import tools.jackson.databind.ObjectMapper;import java.time.*;import java.util.*;
@Service public class AuditService {
 private final JdbcTemplate jdbc;private final ObjectMapper json;public AuditService(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
 public enum Type {WALLET_ACTIVATED,TRANSFER_POSTED,TRANSFER_REJECTED,LIMIT_RESERVED,KYC_APPROVED,POLICY_CHANGED}
 public record Request(@NotNull UUID id,@NotNull UUID resourceId,@NotNull Type type,@NotBlank @Pattern(regexp="[A-Z][A-Z0-9_]{0,63}") String reasonCode){}
 public record Event(UUID id,UUID resourceId,Type type,String reasonCode,String producer,Instant recordedAt){}
 public record Result(Event event,boolean replayed){}
 public record Page(List<Event> items,UUID nextAfter){}
 @Transactional(timeout=10) public Result append(String tenant,String actor,Request request){
  String hash=RequestKeys.hash(json.writeValueAsString(request),actor);Instant now=jdbc.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class).toInstant();
  var event=new Event(request.id(),request.resourceId(),request.type(),request.reasonCode(),actor,now);
  int inserted=jdbc.update("INSERT INTO audit_events VALUES (?,?,?,?,?,?,?, ?,?::jsonb) ON CONFLICT DO NOTHING",tenant,event.id(),event.resourceId(),event.type().name(),event.reasonCode(),actor,hash,OffsetDateTime.ofInstant(now,ZoneOffset.UTC),json.writeValueAsString(event));
  if(inserted==1)return new Result(event,false);
  var saved=jdbc.queryForObject("SELECT fingerprint,snapshot::text FROM audit_events WHERE integrator_id=? AND id=?",(row,n)->List.of(row.getString(1),row.getString(2)),tenant,event.id());
  if(!saved.getFirst().equals(hash))throw new ApiException(HttpStatus.CONFLICT,"EVENT_CONFLICT","This event ID was used with different details or a different producer.");
  return new Result(json.readValue(saved.get(1),Event.class),true);
 }
 public Page page(String tenant,UUID resource,UUID after,int limit){
  var rows=jdbc.query("SELECT snapshot::text FROM audit_events WHERE integrator_id=? AND resource_id=? AND (?::uuid IS NULL OR id>?::uuid) ORDER BY id LIMIT ?",(row,n)->json.readValue(row.getString(1),Event.class),tenant,resource,after,after,limit+1);
  var items=List.copyOf(rows.subList(0,Math.min(rows.size(),limit)));return new Page(items,rows.size()>limit?items.getLast().id():null);
 }
}
