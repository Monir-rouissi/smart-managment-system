package com.smartmgmt.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;

/**
 * HS256 signing configuration for access/refresh tokens, bound from
 * {@code app.jwt.*} (see application.yml). {@code secret} must come from the
 * {@code JWT_SECRET} env var outside local dev.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {

    private String secret;
    private Duration accessTokenTtl = Duration.ofMinutes(15);
    private Duration refreshTokenTtl = Duration.ofDays(7);
    private String issuer = "smart-management-system";

    @PostConstruct
    void validate() {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "app.jwt.secret must be set and at least 32 bytes (256 bits) long for HS256");
        }
    }
}
