package com.paymentplatform.api_gateway.client;
import org.springframework.context.annotation.Configuration;import org.springframework.boot.context.properties.EnableConfigurationProperties;
@Configuration(proxyBeanMethods=false) @EnableConfigurationProperties(DependencyProperties.class) public class DependenciesConfiguration{}
