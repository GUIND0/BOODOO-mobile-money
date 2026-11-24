package com.paymentplatform.reconciliation_service.client;
import org.springframework.context.annotation.Configuration;import org.springframework.boot.context.properties.EnableConfigurationProperties;
@Configuration(proxyBeanMethods=false) @EnableConfigurationProperties(DependencyProperties.class) public class DependenciesConfiguration{}
