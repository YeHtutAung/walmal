package com.walmal.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code walmal.jwt.*} properties from application.yml.
 * Enabled via @EnableConfigurationProperties(JwtProperties.class) in AuthSecurityConfig.
 */
@ConfigurationProperties(prefix = "walmal.jwt")
public record JwtProperties(String secret, long accessTokenExpireMinutes) {

    public JwtProperties {
        if (accessTokenExpireMinutes <= 0) {
            accessTokenExpireMinutes = 15;
        }
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException(
                "walmal.jwt.secret must not be blank. Set the WALMAL_JWT_SECRET "
                + "environment variable to a value of at least 32 bytes (256 bits); "
                + "there is deliberately no default so the app cannot boot on a "
                + "committed key.");
        }
        if (secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException(
                "walmal.jwt.secret is too short: HS256 requires at least 32 bytes (256 bits). " +
                "Current secret is " + secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + " bytes. " +
                "Set a longer value via the WALMAL_JWT_SECRET environment variable."
            );
        }
    }
}
