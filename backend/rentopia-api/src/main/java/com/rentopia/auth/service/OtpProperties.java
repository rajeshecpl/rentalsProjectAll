package com.rentopia.auth.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "rentopia.otp")
public record OtpProperties(int length, Duration ttl, int maxAttempts, int maxSendsPerWindow, Duration sendWindow) {

    public OtpProperties {
        if (length < 4 || length > 8) length = 6;
        if (ttl == null) ttl = Duration.ofMinutes(5);
        if (maxAttempts <= 0) maxAttempts = 5;
        if (maxSendsPerWindow <= 0) maxSendsPerWindow = 5;
        if (sendWindow == null) sendWindow = Duration.ofMinutes(15);
    }
}
