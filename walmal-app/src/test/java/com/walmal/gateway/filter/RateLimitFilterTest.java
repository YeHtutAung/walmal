package com.walmal.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walmal.common.cache.CacheService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    private RateLimitFilter filter;
    private RateLimitFilter filterWithProxy;

    @Mock
    private CacheService cacheService;

    @Mock
    private FilterChain filterChain;

    private ObjectMapper objectMapper;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        filter = new RateLimitFilter(cacheService, objectMapper, false);
        filterWithProxy = new RateLimitFilter(cacheService, objectMapper, true);
        request = new MockHttpServletRequest("GET", "/api/v1/products");
        request.setRemoteAddr("192.168.1.1");
        response = new MockHttpServletResponse();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── Fix 3: atomic increment ───────────────────────────────────────────────

    @Test
    void should_useAtomicIncrement_not_getOrPut() throws ServletException, IOException {
        when(cacheService.increment(anyString(), eq(Duration.ofSeconds(90)))).thenReturn(1L);

        filter.doFilterInternal(request, response, filterChain);

        verify(cacheService).increment(anyString(), eq(Duration.ofSeconds(90)));
        verify(cacheService, never()).get(anyString(), any());
        verify(cacheService, never()).put(anyString(), any(), any());
    }

    @Test
    void should_allowRequest_when_underUnauthenticatedLimit() throws ServletException, IOException {
        when(cacheService.increment(anyString(), eq(Duration.ofSeconds(90)))).thenReturn(5L);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isNotEqualTo(429);
    }

    @Test
    void should_allowRequest_when_firstRequest() throws ServletException, IOException {
        when(cacheService.increment(anyString(), eq(Duration.ofSeconds(90)))).thenReturn(1L);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    void should_blockRequest_when_unauthenticatedLimitExceeded() throws ServletException, IOException {
        // Unauthenticated limit is 20 — count of 21 means exceeded
        when(cacheService.increment(anyString(), eq(Duration.ofSeconds(90)))).thenReturn(21L);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("60");
        assertThat(response.getContentAsString()).contains("Rate limit exceeded");
    }

    @Test
    void should_useHigherLimit_when_authenticated() throws ServletException, IOException {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("user-42", null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);

        // 50 is well under the authenticated limit of 100
        when(cacheService.increment(anyString(), eq(Duration.ofSeconds(90)))).thenReturn(50L);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(cacheService).increment(keyCaptor.capture(), eq(Duration.ofSeconds(90)));
        assertThat(keyCaptor.getValue()).contains("user-42");
        assertThat(keyCaptor.getValue()).doesNotContain("ip:");
    }

    @Test
    void should_blockAuthenticated_when_authenticatedLimitExceeded() throws ServletException, IOException {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("user-42", null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);

        // Authenticated limit is 100 — count of 101 means exceeded
        when(cacheService.increment(anyString(), eq(Duration.ofSeconds(90)))).thenReturn(101L);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(429);
    }

    // ── Fix 2: proxy trust ────────────────────────────────────────────────────

    @Test
    void should_useRemoteAddr_when_proxyNotTrusted_evenIfXFFPresent() throws ServletException, IOException {
        request.addHeader("X-Forwarded-For", "10.0.0.1, 192.168.1.1");
        when(cacheService.increment(anyString(), any())).thenReturn(1L);

        filter.doFilterInternal(request, response, filterChain);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(cacheService).increment(keyCaptor.capture(), any());
        assertThat(keyCaptor.getValue()).contains("ip:192.168.1.1");
        assertThat(keyCaptor.getValue()).doesNotContain("10.0.0.1");
    }

    @Test
    void should_useXFFIp_when_proxyTrustedAndHeaderPresent() throws ServletException, IOException {
        request.addHeader("X-Forwarded-For", "10.0.0.1, 192.168.1.1");
        when(cacheService.increment(anyString(), any())).thenReturn(1L);

        filterWithProxy.doFilterInternal(request, response, filterChain);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(cacheService).increment(keyCaptor.capture(), any());
        assertThat(keyCaptor.getValue()).contains("ip:10.0.0.1");
    }

    @Test
    void should_fallbackToRemoteAddr_when_proxyTrustedButXFFMissing() throws ServletException, IOException {
        // No X-Forwarded-For header set
        when(cacheService.increment(anyString(), any())).thenReturn(1L);

        filterWithProxy.doFilterInternal(request, response, filterChain);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(cacheService).increment(keyCaptor.capture(), any());
        assertThat(keyCaptor.getValue()).contains("ip:192.168.1.1");
    }

    // ── shouldNotFilter ───────────────────────────────────────────────────────

    @Test
    void should_excludeActuatorPaths_when_shouldNotFilterCalled() {
        MockHttpServletRequest actuatorRequest = new MockHttpServletRequest("GET", "/actuator/health");
        assertThat(filter.shouldNotFilter(actuatorRequest)).isTrue();
    }

    @Test
    void should_excludeSwaggerPaths_when_shouldNotFilterCalled() {
        MockHttpServletRequest swaggerRequest = new MockHttpServletRequest("GET", "/swagger-ui/index.html");
        assertThat(filter.shouldNotFilter(swaggerRequest)).isTrue();
    }

    @Test
    void should_excludeApiDocsPaths_when_shouldNotFilterCalled() {
        MockHttpServletRequest apiDocsRequest = new MockHttpServletRequest("GET", "/v3/api-docs/something");
        assertThat(filter.shouldNotFilter(apiDocsRequest)).isTrue();
    }

    @Test
    void should_notExcludeApiPaths_when_shouldNotFilterCalled() {
        MockHttpServletRequest apiRequest = new MockHttpServletRequest("GET", "/api/v1/products");
        assertThat(filter.shouldNotFilter(apiRequest)).isFalse();
    }
}
