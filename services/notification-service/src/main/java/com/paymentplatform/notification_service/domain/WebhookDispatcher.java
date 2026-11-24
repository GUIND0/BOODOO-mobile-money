package com.paymentplatform.notification_service.domain;
import org.springframework.stereotype.Component;import java.net.http.*;import java.nio.ByteBuffer;import java.io.ByteArrayOutputStream;import java.util.concurrent.*;import java.util.concurrent.Flow;import java.util.*;import java.nio.charset.StandardCharsets;import java.time.*;import javax.crypto.Mac;import javax.crypto.spec.SecretKeySpec;
@Component public class WebhookDispatcher implements AutoCloseable {
 private final WebhookOutbox outbox;private final WebhookDestinations destinations;private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
 public WebhookDispatcher(WebhookOutbox outbox,WebhookDestinations destinations){this.outbox=outbox;this.destinations=destinations;}
 public boolean once(){var candidate=outbox.claim();if(candidate.isEmpty())return false;var work=candidate.get();var config=destinations.get(work.tenant());if(config.isEmpty()||!config.get().version().equals(work.version())){outbox.finish(work,false,"DESTINATION_VERSION_CHANGED",true);return true;}
  String timestamp=Long.toString(Instant.now().getEpochSecond());try{
   var c=config.get();String signature=signature(c.secret(),timestamp,work.id(),work.payload());
   var request=HttpRequest.newBuilder(c.endpoint()).timeout(Duration.ofSeconds(4)).header("Content-Type","application/json").header("X-Boodoo-Delivery-Id",work.id().toString()).header("X-Boodoo-Timestamp",timestamp).header("X-Boodoo-Key-Id",c.keyId()).header("X-Boodoo-Signature","v1="+signature).POST(HttpRequest.BodyPublishers.ofString(work.payload(),StandardCharsets.UTF_8)).build();
   var pending=http.sendAsync(request,info->new LimitedBody());HttpResponse<byte[]> response;try{response=pending.get(5,TimeUnit.SECONDS);}catch(Exception error){pending.cancel(true);if(error instanceof InterruptedException)Thread.currentThread().interrupt();throw error;}
   boolean ok=response.statusCode()>=200&&response.statusCode()<300;outbox.finish(work,ok,ok?"HTTP_ACCEPTED":"HTTP_REJECTED",false);
  }catch(Exception error){outbox.finish(work,false,"DELIVERY_UNCERTAIN",false);}return true;
 }
 public static String signature(byte[] key,String timestamp,UUID id,String payload){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal((timestamp+"."+id+"."+payload).getBytes(StandardCharsets.UTF_8)));}catch(Exception error){throw new IllegalStateException("Webhook signing unavailable");}}
 @jakarta.annotation.PreDestroy public void close(){http.close();}
 private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
  private final CompletableFuture<byte[]> result=new CompletableFuture<>();private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();private Flow.Subscription subscription;
  public CompletionStage<byte[]> getBody(){return result;}
  public void onSubscribe(Flow.Subscription subscription){this.subscription=subscription;subscription.request(Long.MAX_VALUE);}
  public void onNext(List<ByteBuffer> items){for(var item:items){if(bytes.size()+item.remaining()>65536){subscription.cancel();result.completeExceptionally(new IllegalStateException("Dependency response exceeds limit"));return;}byte[] data=new byte[item.remaining()];item.get(data);bytes.writeBytes(data);}}
  public void onError(Throwable error){result.completeExceptionally(error);}public void onComplete(){result.complete(bytes.toByteArray());}
 }
}
