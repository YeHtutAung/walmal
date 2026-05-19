package com.walmal.infrastructure.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisCacheServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private RedisCacheService cacheService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        cacheService = new RedisCacheService(redisTemplate, objectMapper);
    }

    @Test
    void should_returnValue_when_keyExists() {
        when(valueOps.get("test-key")).thenReturn("{\"name\":\"walmal\"}");

        Optional<TestDto> result = cacheService.get("test-key", TestDto.class);

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("walmal");
    }

    @Test
    void should_returnEmpty_when_keyNotFound() {
        when(valueOps.get("missing")).thenReturn(null);

        Optional<TestDto> result = cacheService.get("missing", TestDto.class);

        assertThat(result).isEmpty();
    }

    @Test
    void should_putWithTtl_when_durationProvided() {
        cacheService.put("key", new TestDto("val"), Duration.ofMinutes(5));

        verify(valueOps).set(eq("key"), anyString(), eq(Duration.ofMinutes(5)));
    }

    record TestDto(String name) {}
}
