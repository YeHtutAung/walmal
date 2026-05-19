package com.walmal.infrastructure.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisDistributedLockServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    @InjectMocks
    private RedisDistributedLockService lockService;

    @Test
    void should_returnTrue_when_lockAcquired() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq("lock:order-123"), anyString(), eq(Duration.ofSeconds(30))))
            .thenReturn(true);

        boolean result = lockService.tryLock("order-123", Duration.ofSeconds(30));

        assertThat(result).isTrue();
    }

    @Test
    void should_returnFalse_when_lockAlreadyHeld() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq("lock:order-123"), anyString(), eq(Duration.ofSeconds(30))))
            .thenReturn(false);

        boolean result = lockService.tryLock("order-123", Duration.ofSeconds(30));

        assertThat(result).isFalse();
    }

    @Test
    void should_executeAction_when_lockAcquiredViaExecuteWithLock() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq("lock:test"), anyString(), eq(Duration.ofSeconds(10))))
            .thenReturn(true);
        when(redisTemplate.delete("lock:test")).thenReturn(true);

        String result = lockService.executeWithLock("test", Duration.ofSeconds(10), () -> "done");

        assertThat(result).isEqualTo("done");
    }
}
