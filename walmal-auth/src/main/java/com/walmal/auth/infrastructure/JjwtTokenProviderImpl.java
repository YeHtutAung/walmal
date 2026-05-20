package com.walmal.auth.infrastructure;

import com.walmal.auth.config.JwtProperties;
import com.walmal.auth.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

/**
 * JJWT 0.12.x implementation of {@link JwtTokenProvider}.
 * ALL JJWT library calls (Jwts.builder, Jwts.parser) are contained in this class only.
 * No other class in walmal-auth may import io.jsonwebtoken.* directly.
 */
@Component
public class JjwtTokenProviderImpl implements JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JjwtTokenProviderImpl.class);

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_USERNAME = "username";

    private final SecretKey secretKey;
    private final long accessTokenExpireMinutes;

    public JjwtTokenProviderImpl(JwtProperties properties) {
        this.secretKey = Keys.hmacShaKeyFor(
                properties.secret().getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpireMinutes = properties.accessTokenExpireMinutes();
    }

    @Override
    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plus(accessTokenExpireMinutes, ChronoUnit.MINUTES);

        return Jwts.builder()
                .id(UUID.randomUUID().toString())           // jti
                .subject(user.getId().toString())           // sub = userId UUID
                .claim(CLAIM_ROLE, user.getRole().name())
                .claim(CLAIM_USERNAME, user.getUsername())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(secretKey)
                .compact();
    }

    @Override
    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public UUID extractUserId(String token) {
        return UUID.fromString(parseToken(token).getSubject());
    }

    @Override
    public String extractRole(String token) {
        return parseToken(token).get(CLAIM_ROLE, String.class);
    }

    @Override
    public String extractUsername(String token) {
        return parseToken(token).get(CLAIM_USERNAME, String.class);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
