package com.paymentplatform.wallet_service.customer;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

@ConfigurationProperties("boodoo.customers")
public record CustomerClientProperties(URI baseUrl) {
    public CustomerClientProperties {
        if (baseUrl == null || !("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()))
                || baseUrl.getHost() == null || baseUrl.getUserInfo() != null || baseUrl.getQuery() != null
                || baseUrl.getFragment() != null || !(baseUrl.getPath().isEmpty() || "/".equals(baseUrl.getPath()))) {
            throw new IllegalArgumentException("Customer Service base URL must be an HTTP(S) origin without credentials or query");
        }
    }
}
