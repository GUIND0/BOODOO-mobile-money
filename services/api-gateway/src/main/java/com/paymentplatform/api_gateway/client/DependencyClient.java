package com.paymentplatform.api_gateway.client;
import com.paymentplatform.api_gateway.api.ApiException;
import org.springframework.http.HttpStatus;import org.springframework.stereotype.Component;import tools.jackson.databind.ObjectMapper;import tools.jackson.databind.JsonNode;
import java.net.http.*;import java.nio.ByteBuffer;import java.io.ByteArrayOutputStream;import java.time.Duration;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.Flow;
@Component public class DependencyClient implements AutoCloseable {
 private final DependencyProperties properties;private final ObjectMapper json;
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
 public DependencyClient(DependencyProperties properties,ObjectMapper json){this.properties=properties;this.json=json;}
 public record Reply(int status,JsonNode body,Map<String,List<String>> headers){}
 public Reply get(String target,String path,String bearer){return send(target,"GET",path,null,null,bearer);}
 public Reply post(String target,String path,Object body,String key,String bearer){return send(target,"POST",path,body,key,bearer);}
 public Reply send(String target,String method,String path,Object body,String key,String bearer){return sendWithHeaders(target,method,path,body,key,bearer,Map.of());}
 public Reply sendWithHeaders(String target,String method,String path,Object body,String key,String bearer,Map<String,String> headers){
  var origin=properties.origins().get(target);if(origin==null||!path.startsWith("/")||path.startsWith("//")||path.contains("#"))throw unavailable();
  var builder=HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(4)).header("Authorization","Bearer "+bearer).header("Accept","application/json");
  if(key!=null)builder.header("Idempotency-Key",key);
  for(var header:headers.entrySet()){
   if(!Set.of("If-Match","If-None-Match").contains(header.getKey())||header.getValue().length()>256||header.getValue().chars().anyMatch(Character::isISOControl))throw unavailable();
   builder.header(header.getKey(),header.getValue());
  }
  builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
  if(body!=null)builder.header("Content-Type","application/json");
  var future=http.sendAsync(builder.build(),info->new LimitedBody());
  try{
   var response=future.get(5,TimeUnit.SECONDS);byte[] bytes=response.body();JsonNode node=null;
   if(bytes.length>0){String type=response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);if(!(type.startsWith("application/json")||type.startsWith("application/problem+json")))throw unavailable();node=json.readTree(bytes);}
   return new Reply(response.statusCode(),node,response.headers().map());
  }catch(InterruptedException e){future.cancel(true);Thread.currentThread().interrupt();throw unavailable();}catch(Exception e){future.cancel(true);throw unavailable();}
 }
 public static ApiException unavailable(){return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"DEPENDENCY_UNAVAILABLE","A required service could not provide a valid response. Retry the same operation identifier.");}
 public static String text(JsonNode body,String key){try{var node=body.get(key);if(node==null||!node.isString())throw unavailable();return node.asString();}catch(Exception e){throw unavailable();}}
 public static long integer(JsonNode body,String key){try{var node=body.get(key);if(node==null||!node.isIntegralNumber())throw unavailable();return Long.parseLong(node.toString());}catch(Exception e){throw unavailable();}}
 public static boolean bool(JsonNode body,String key){try{var node=body.get(key);if(node==null||!node.isBoolean())throw unavailable();return node.asBoolean();}catch(Exception e){throw unavailable();}}
 public static UUID uuid(JsonNode body,String key){try{return UUID.fromString(text(body,key));}catch(Exception e){throw unavailable();}}
 @jakarta.annotation.PreDestroy public void close(){http.close();}
 private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
  private final CompletableFuture<byte[]> result=new CompletableFuture<>();private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();private Flow.Subscription subscription;
  public CompletionStage<byte[]> getBody(){return result;}
  public void onSubscribe(Flow.Subscription subscription){this.subscription=subscription;subscription.request(Long.MAX_VALUE);}
  public void onNext(List<ByteBuffer> items){for(var item:items){if(bytes.size()+item.remaining()>65536){subscription.cancel();result.completeExceptionally(new IllegalStateException("Dependency response exceeds limit"));return;}byte[] data=new byte[item.remaining()];item.get(data);bytes.writeBytes(data);}}
  public void onError(Throwable error){result.completeExceptionally(error);}public void onComplete(){result.complete(bytes.toByteArray());}
 }
}
