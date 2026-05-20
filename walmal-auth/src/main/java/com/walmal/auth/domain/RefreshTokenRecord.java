package com.walmal.auth.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Value object serialised as JSON and stored in Redis under
 * {@code auth:refresh:{userId}:{tokenId}} with a 7-day TTL.
 *
 * <p>This is NOT a JPA entity — it lives in Redis only.</p>
 */
public record RefreshTokenRecord(
        UUID userId,
        UUID tokenId,
        Instant issuedAt,
        Instant expiresAt
) {}
