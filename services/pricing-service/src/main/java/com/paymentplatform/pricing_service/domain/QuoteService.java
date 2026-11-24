package com.paymentplatform.pricing_service.domain;
import com.paymentplatform.pricing_service.api.ApiException;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.math.BigInteger;
import java.util.*;
@Service @EnableConfigurationProperties(PricingPolicy.class)
public class QuoteService {
 private final JdbcTemplate jdbc;private final ObjectMapper json;private final PricingPolicy policy;
 public QuoteService(JdbcTemplate jdbc,ObjectMapper json,PricingPolicy policy){this.jdbc=jdbc;this.json=json;this.policy=policy;}
 public record Request(@NotNull UUID reference,@NotNull @Positive Long amount,@NotBlank @Pattern(regexp="XOF") String currency){}
 public record Quote(UUID id,UUID reference,long amount,long fee,long totalDebit,String currency,String policyVersion,int basisPoints,long flatFee,Instant createdAt,Instant expiresAt){}
 public record Result(Quote response,boolean replayed){}
 @Transactional(timeout=10) public Result create(String tenant,String actor,String key,Request request){
  String hash=RequestKeys.hash(json.writeValueAsString(request),actor);
  jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",(org.springframework.jdbc.core.RowCallbackHandler)row->{},tenant.length()+":"+tenant+key);
  var saved=jdbc.query("SELECT fingerprint,snapshot::text FROM price_quotes WHERE integrator_id=? AND idempotency_key=?",(row,n)->List.of(row.getString(1),row.getString(2)),tenant,key);
  if(!saved.isEmpty()){if(!saved.getFirst().getFirst().equals(hash))throw error("IDEMPOTENCY_CONFLICT");return new Result(json.readValue(saved.getFirst().get(1),Quote.class),true);}
  long fee,total;
  try{fee=BigInteger.valueOf(request.amount()).multiply(BigInteger.valueOf(policy.basisPoints())).add(BigInteger.valueOf(9999)).divide(BigInteger.valueOf(10000)).add(BigInteger.valueOf(policy.flatFee())).longValueExact();total=Math.addExact(request.amount(),fee);}catch(ArithmeticException e){throw new ApiException(HttpStatus.BAD_REQUEST,"AMOUNT_OVERFLOW","Amount and fee exceed the supported range.");}
  Instant now=jdbc.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class).toInstant();
  var quote=new Quote(UUID.randomUUID(),request.reference(),request.amount(),fee,total,"XOF",policy.version(),policy.basisPoints(),policy.flatFee(),now,now.plusSeconds(policy.ttlSeconds()));
  int inserted=jdbc.update("INSERT INTO price_quotes VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb) ON CONFLICT DO NOTHING",quote.id(),tenant,key,hash,actor,request.reference(),request.amount(),fee,policy.version(),OffsetDateTime.ofInstant(quote.expiresAt(),ZoneOffset.UTC),json.writeValueAsString(quote));
  if(inserted!=1)throw error("QUOTE_REFERENCE_EXISTS");return new Result(quote,false);
 }
 public Quote get(String tenant,UUID id){return jdbc.query("SELECT snapshot::text FROM price_quotes WHERE integrator_id=? AND id=?",(row,n)->json.readValue(row.getString(1),Quote.class),tenant,id).stream().findFirst().orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"QUOTE_NOT_FOUND","Quote not found for this integrator."));}
 private ApiException error(String code){return new ApiException(HttpStatus.CONFLICT,code,"The quote request conflicts with an existing request.");}
}
