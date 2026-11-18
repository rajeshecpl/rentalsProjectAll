package com.rentopia.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class TestBeans {

    @Bean
    @Primary
    CapturingOtpSender capturingOtpSender() {
        return new CapturingOtpSender();
    }
}
