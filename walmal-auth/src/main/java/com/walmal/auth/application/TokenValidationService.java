package com.walmal.auth.application;

import java.util.UUID;

/**
 * Cross-module interface for validating JWT access tokens.
 * All downstream modules depend on this interface only; no walmal-auth internals are exposed.
 *
 * <p>Token validation is purely in-memory (HMAC signature + expiry check).
 * No Redis or database I/O is performed on this path.</p>
 */
public interface TokenValidationService {

    /**
     * Returns {@code true} if the token is a well-formed, unexpired JWT signed with
     * the platform secret. Returns {@code false} for any invalid, expired, or
     * tampered token — never throws.
     */
    boolean isValid(String token);

    /**
     * Extracts the subject (userId as UUID) from the token.
     *
     * @throws io.jsonwebtoken.JwtException if the token is invalid
     */
    UUID extractUserId(String token);

    /**
     * Extracts the {@code role} claim from the token.
     *
     * @throws io.jsonwebtoken.JwtException if the token is invalid
     */
    String extractRole(String token);

    /**
     * Extracts the {@code username} claim from the token.
     *
     * @throws io.jsonwebtoken.JwtException if the token is invalid
     */
    String extractUsername(String token);
}
