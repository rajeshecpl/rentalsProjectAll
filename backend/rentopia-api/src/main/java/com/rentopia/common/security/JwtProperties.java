package com.rentopia.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

@ConfigurationProperties(prefix = "rentopia.security.jwt")
public record JwtProperties(String secret, String issuer, Duration accessTokenTtl, Duration refreshTokenTtl) {

    public JwtProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "rentopia.security.jwt.secret (env JWT_SECRET) must be set and at least 32 bytes long");
        }
        if (issuer == null || issuer.isBlank()) {
            issuer = "rentopia-api";
        }
        if (accessTokenTtl == null) {
            accessTokenTtl = Duration.ofMinutes(15);
        }
        if (refreshTokenTtl == null) {
            refreshTokenTtl = Duration.ofDays(30);
        }
    }
}
