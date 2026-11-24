package com.paymentplatform.limits_service.domain;
import com.paymentplatform.limits_service.api.ApiException;import jakarta.validation.constraints.*;import org.springframework.boot.context.properties.EnableConfigurationProperties;import org.springframework.http.HttpStatus;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;import tools.jackson.databind.ObjectMapper;import java.time.*;import java.util.*;import java.math.BigDecimal;
@Service @EnableConfigurationProperties(LimitPolicy.class) public class ReservationService {
 private final JdbcTemplate jdbc;private final ObjectMapper json;private final LimitPolicy policy;
 public ReservationService(JdbcTemplate jdbc,ObjectMapper json,LimitPolicy policy){this.jdbc=jdbc;this.json=json;this.policy=policy;}
 public record Request(@NotNull UUID reference,@NotNull UUID customerId,@NotNull @Positive Long amount,@NotBlank @Pattern(regexp="XOF") String currency){}
 public record Reservation(UUID id,UUID reference,UUID customerId,long amount,String currency,String status,String policyVersion,long perTransaction,long daily,long monthly,LocalDate dayBucket,LocalDate monthBucket,Instant expiresAt){public Reservation withStatus(String status){return new Reservation(id,reference,customerId,amount,currency,status,policyVersion,perTransaction,daily,monthly,dayBucket,monthBucket,expiresAt);}}
 public record Result(Reservation response,boolean replayed){}
 private void lock(String tenant,UUID customer){jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",(org.springframework.jdbc.core.RowCallbackHandler)row->{},tenant.length()+":"+tenant+customer);}
 @Transactional(timeout=10) public Result reserve(String tenant,String actor,String key,Request request){
  lock(tenant,request.customerId());String hash=RequestKeys.hash(json.writeValueAsString(request),actor);
  var old=jdbc.query("SELECT fingerprint,snapshot::text FROM limit_reservations WHERE integrator_id=? AND idempotency_key=?",(row,n)->List.of(row.getString(1),row.getString(2)),tenant,key);
  if(!old.isEmpty()){if(!old.getFirst().getFirst().equals(hash))throw conflict("IDEMPOTENCY_CONFLICT");return new Result(json.readValue(old.getFirst().get(1),Reservation.class),true);}
  Instant now=jdbc.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class).toInstant();LocalDate day=LocalDate.ofInstant(now,ZoneOffset.UTC),month=day.withDayOfMonth(1);
  BigDecimal daily=usage(tenant,request.customerId(),day,true),monthly=usage(tenant,request.customerId(),month,false),amount=BigDecimal.valueOf(request.amount());
  if(request.amount()>policy.perTransaction()||daily.add(amount).compareTo(BigDecimal.valueOf(policy.daily()))>0||monthly.add(amount).compareTo(BigDecimal.valueOf(policy.monthly()))>0)throw conflict("LIMIT_EXCEEDED");
  Instant expiry=now.plusSeconds(policy.ttlSeconds()),midnight=day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();if(expiry.isAfter(midnight))expiry=midnight;
  var response=new Reservation(UUID.randomUUID(),request.reference(),request.customerId(),request.amount(),"XOF","RESERVED",policy.version(),policy.perTransaction(),policy.daily(),policy.monthly(),day,month,expiry);
  int rows=jdbc.update("INSERT INTO limit_reservations VALUES (?,?,?,?,?,?,?,?,?,?,?,?::jsonb) ON CONFLICT DO NOTHING",response.id(),tenant,key,hash,actor,request.reference(),request.customerId(),request.amount(),day,month,"RESERVED",json.writeValueAsString(response));
  if(rows!=1)throw conflict("RESERVATION_REFERENCE_OR_KEY_EXISTS");return new Result(response,false);
 }
 private BigDecimal usage(String tenant,UUID customer,LocalDate bucket,boolean day){return jdbc.queryForObject("SELECT COALESCE(sum(amount::numeric),0) FROM limit_reservations WHERE integrator_id=? AND customer_id=? AND "+(day?"day_bucket":"month_bucket")+"=? AND status<>'RELEASED'",BigDecimal.class,tenant,customer,bucket);}
 public Reservation get(String tenant,UUID id){return jdbc.query("SELECT snapshot::text,status FROM limit_reservations WHERE integrator_id=? AND id=?",(row,n)->json.readValue(row.getString(1),Reservation.class).withStatus(row.getString(2)),tenant,id).stream().findFirst().orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"RESERVATION_NOT_FOUND","Reservation not found for this integrator."));}
 @Transactional(timeout=10) public Reservation settle(String tenant,String actor,UUID id,String desired){
  var current=get(tenant,id);lock(tenant,current.customerId());current=get(tenant,id);
  String creator=jdbc.queryForObject("SELECT actor_id FROM limit_reservations WHERE integrator_id=? AND id=?",String.class,tenant,id);
  if(!actor.equals(creator))throw new ApiException(HttpStatus.FORBIDDEN,"RESERVATION_ACTOR_MISMATCH","Only the reserving service can settle this allowance.");
  if(current.status().equals(desired))return current;if(!current.status().equals("RESERVED"))throw conflict("RESERVATION_ALREADY_SETTLED");
  jdbc.update("UPDATE limit_reservations SET status=? WHERE integrator_id=? AND id=?",desired,tenant,id);return current.withStatus(desired);
 }
 private ApiException conflict(String code){return new ApiException(HttpStatus.CONFLICT,code,"The allowance request cannot be accepted.");}
}
