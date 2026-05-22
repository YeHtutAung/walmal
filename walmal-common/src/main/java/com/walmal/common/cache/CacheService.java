package com.walmal.common.cache;

import java.time.Duration;
import java.util.Optional;

public interface CacheService {
    <T> Optional<T> get(String key, Class<T> type);
    <T> void put(String key, T value);
    <T> void put(String key, T value, Duration ttl);
    void evict(String key);
    void evictByPrefix(String prefix);

    /**
     * Atomically increments the integer stored at {@code key} by 1.
     * If the key does not exist it is created with value 1 and {@code ttlOnCreate} is applied.
     * If the key already exists its TTL is not changed.
     *
     * @return the value of the key after the increment
     */
    long increment(String key, Duration ttlOnCreate);
}
