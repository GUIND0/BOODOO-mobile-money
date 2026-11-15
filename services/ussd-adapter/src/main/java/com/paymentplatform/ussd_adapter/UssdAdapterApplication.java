package com.paymentplatform.ussd_adapter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class UssdAdapterApplication {

    public static void main(String[] args) {
        SpringApplication.run(UssdAdapterApplication.class, args);
    }
}
