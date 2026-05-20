package com.walmal.auth.infrastructure;

import com.walmal.auth.domain.User;

import java.util.UUID;

/**
 * DIP abstraction over the JJWT library. All JJWT API calls live in
 * {@link JjwtTokenProviderImpl} only — never in business logic or service classes.
 *
 * <p>This interface lives in {@code infrastructure/} because it is a port into the
 * JWT library infrastructure. AuthServiceImpl depends on this interface, not the impl.</p>
 */
public interface JwtTokenProvider {

    /**
     * Generates a signed HS256 access token containing claims:
     * sub (userId), role, username, jti, iat, exp.
     */
    String generateAccessToken(User user);

    /**
     * Returns {@code true} if the token is valid (signature OK, not expired).
     * Never throws — returns false for any parse error.
     */
    boolean validateToken(String token);

    /** Extracts the sub claim as UUID. */
    UUID extractUserId(String token);

    /** Extracts the {@code role} claim. */
    String extractRole(String token);

    /** Extracts the {@code username} claim. */
    String extractUsername(String token);
}
