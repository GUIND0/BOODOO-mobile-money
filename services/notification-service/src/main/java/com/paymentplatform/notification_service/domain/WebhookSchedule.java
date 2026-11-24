package com.paymentplatform.notification_service.domain;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;import org.springframework.context.annotation.Configuration;import org.springframework.scheduling.annotation.*;
@Configuration @EnableScheduling @ConditionalOnProperty(name="boodoo.webhooks.enabled",havingValue="true") public class WebhookSchedule {
 private final WebhookDispatcher dispatcher;public WebhookSchedule(WebhookDispatcher dispatcher){this.dispatcher=dispatcher;}
 @Scheduled(fixedDelayString="${boodoo.webhooks.delay-ms:5000}") public void deliver(){for(int i=0;i<25&&!Thread.currentThread().isInterrupted()&&dispatcher.once();i++){} }
}
