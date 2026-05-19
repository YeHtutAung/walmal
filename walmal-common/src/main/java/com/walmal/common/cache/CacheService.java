package com.walmal.common.cache;

import java.time.Duration;
import java.util.Optional;

public interface CacheService {
    <T> Optional<T> get(String key, Class<T> type);
    <T> void put(String key, T value);
    <T> void put(String key, T value, Duration ttl);
    void evict(String key);
    void evictByPrefix(String prefix);
}
