package com.walmal.auth.infrastructure;

import com.walmal.auth.domain.RefreshTokenRecord;
import com.walmal.common.cache.CacheService;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Wraps {@link CacheService} for refresh token CRUD in Redis.
 *
 * <p>Key pattern: {@code auth:refresh:{userId}:{tokenId}}</p>
 * <p>Value: JSON-serialised {@link RefreshTokenRecord} (handled by CacheService/ObjectMapper).</p>
 * <p>TTL: 7 days (rolling — old key deleted, new key written on every /refresh call).</p>
 *
 * <p>Uses CacheService — never RedisTemplate directly (DIP compliance).</p>
 */
@Component
public class RefreshTokenAdapter {

    static final Duration REFRESH_TTL = Duration.ofDays(7);

    private final CacheService cacheService;

    public RefreshTokenAdapter(CacheService cacheService) {
        this.cacheService = cacheService;
    }

    public void store(UUID userId, UUID tokenId, RefreshTokenRecord record) {
        cacheService.put(key(userId, tokenId), record, REFRESH_TTL);
    }

    public Optional<RefreshTokenRecord> find(UUID userId, UUID tokenId) {
        return cacheService.get(key(userId, tokenId), RefreshTokenRecord.class);
    }

    public void delete(UUID userId, UUID tokenId) {
        cacheService.evict(key(userId, tokenId));
    }

    /**
     * Revokes all sessions for the given user (e.g. password change, account lock).
     * Uses prefix scan: {@code auth:refresh:{userId}:}.
     */
    public void deleteAllForUser(UUID userId) {
        cacheService.evictByPrefix("auth:refresh:" + userId + ":");
    }

    private static String key(UUID userId, UUID tokenId) {
        return "auth:refresh:" + userId + ":" + tokenId;
    }
}
