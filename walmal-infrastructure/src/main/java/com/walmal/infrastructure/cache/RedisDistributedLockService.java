package com.walmal.infrastructure.cache;

import com.walmal.common.cache.DistributedLockService;
import com.walmal.common.exception.WalmalException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class RedisDistributedLockService implements DistributedLockService {

    private static final String LOCK_PREFIX = "lock:";
    private final StringRedisTemplate redisTemplate;

    public RedisDistributedLockService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean tryLock(String key, Duration timeout) {
        Boolean acquired = redisTemplate.opsForValue()
            .setIfAbsent(LOCK_PREFIX + key, UUID.randomUUID().toString(), timeout);
        return Boolean.TRUE.equals(acquired);
    }

    @Override
    public void unlock(String key) {
        redisTemplate.delete(LOCK_PREFIX + key);
    }

    @Override
    public <T> T executeWithLock(String key, Duration timeout, Supplier<T> action) {
        if (!tryLock(key, timeout)) {
            throw new WalmalException("Failed to acquire lock for key: " + key);
        }
        try {
            return action.get();
        } finally {
            unlock(key);
        }
    }
}
