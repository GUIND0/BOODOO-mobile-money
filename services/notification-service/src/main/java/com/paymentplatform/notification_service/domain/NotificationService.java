package com.paymentplatform.notification_service.domain;
import com.paymentplatform.notification_service.api.ApiException;import jakarta.validation.constraints.*;import org.springframework.http.HttpStatus;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;import tools.jackson.databind.ObjectMapper;import java.time.*;import java.util.*;
@Service public class NotificationService {
 private final WebhookOutbox outbox;private final JdbcTemplate jdbc;private final ObjectMapper json;public NotificationService(JdbcTemplate jdbc,ObjectMapper json,WebhookOutbox outbox){this.outbox=outbox;this.jdbc=jdbc;this.json=json;}
 public enum Template {WALLET_ACTIVATED,TRANSFER_POSTED,TRANSFER_REJECTED}
 public record Request(@NotNull UUID id,@NotNull UUID customerId,@NotNull UUID reference,@NotNull Template template){}
 public record Notification(UUID id,UUID customerId,UUID reference,Template template,String status,Instant createdAt,Instant acknowledgedAt){Notification acknowledge(Instant at){return new Notification(id,customerId,reference,template,at==null?"UNREAD":"ACKNOWLEDGED",createdAt,at);}}
 public record Result(Notification response,boolean replayed){}
 public record Page(List<Notification> items,UUID nextAfter){}
 @Transactional(timeout=10) public Result create(String tenant,String actor,Request request){
  String hash=RequestKeys.hash(json.writeValueAsString(request),actor);Instant now=jdbc.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class).toInstant();var response=new Notification(request.id(),request.customerId(),request.reference(),request.template(),"UNREAD",now,null);
  int inserted=jdbc.update("INSERT INTO notifications(integrator_id,id,customer_id,fingerprint,producer,snapshot) VALUES (?,?,?,?,?,?::jsonb) ON CONFLICT DO NOTHING",tenant,request.id(),request.customerId(),hash,actor,json.writeValueAsString(response));
  if(inserted==1){outbox.enqueue(tenant,response.id(),json.writeValueAsString(response));return new Result(response,false);}
  var old=jdbc.queryForObject("SELECT fingerprint,snapshot::text FROM notifications WHERE integrator_id=? AND id=?",(row,n)->List.of(row.getString(1),row.getString(2)),tenant,request.id());
  if(!old.getFirst().equals(hash))throw new ApiException(HttpStatus.CONFLICT,"NOTIFICATION_CONFLICT","This notification ID belongs to different details or a different producer.");
  return new Result(json.readValue(old.get(1),Notification.class),true);
 }
 private Notification read(java.sql.ResultSet row)throws java.sql.SQLException{var at=row.getObject("acknowledged_at",OffsetDateTime.class);return json.readValue(row.getString("snapshot"),Notification.class).acknowledge(at==null?null:at.toInstant());}
 public Page list(String tenant,UUID customer,UUID after,int limit){var rows=jdbc.query("SELECT snapshot::text,acknowledged_at FROM notifications WHERE integrator_id=? AND customer_id=? AND (?::uuid IS NULL OR id>?::uuid) ORDER BY id LIMIT ?",(row,n)->read(row),tenant,customer,after,after,limit+1);var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new Page(items,rows.size()>limit?items.getLast().id():null);}
 @Transactional(timeout=10) public Notification acknowledge(String tenant,String actor,UUID customer,UUID id){
  jdbc.update("UPDATE notifications SET acknowledged_at=clock_timestamp(),acknowledged_by=? WHERE integrator_id=? AND customer_id=? AND id=? AND acknowledged_at IS NULL",actor,tenant,customer,id);
  return jdbc.query("SELECT snapshot::text,acknowledged_at FROM notifications WHERE integrator_id=? AND customer_id=? AND id=?",(row,n)->read(row),tenant,customer,id).stream().findFirst().orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"NOTIFICATION_NOT_FOUND","Notification not found for this integrator and customer."));
 }
}
