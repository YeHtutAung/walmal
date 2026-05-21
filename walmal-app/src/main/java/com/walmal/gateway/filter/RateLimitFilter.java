package com.walmal.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walmal.common.cache.CacheService;
import com.walmal.common.model.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * Sliding window rate limiter using CacheService (Redis-backed).
 * Runs after Spring Security so SecurityContext is available.
 *
 * <p>Limits: 100 req/min per authenticated user, 20 req/min per IP for unauthenticated.</p>
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final int AUTHENTICATED_LIMIT = 100;
    private static final int UNAUTHENTICATED_LIMIT = 20;
    private static final Duration CACHE_TTL = Duration.ofSeconds(90);
    private static final String RATE_LIMIT_MESSAGE = "Rate limit exceeded. Try again later.";

    private final CacheService cacheService;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(CacheService cacheService, ObjectMapper objectMapper) {
        this.cacheService = cacheService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator/")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger-ui");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String identity;
        int limit;

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal())) {
            identity = authentication.getName();
            limit = AUTHENTICATED_LIMIT;
        } else {
            identity = "ip:" + resolveClientIp(request);
            limit = UNAUTHENTICATED_LIMIT;
        }

        long windowKey = System.currentTimeMillis() / 60000;
        String cacheKey = "ratelimit:" + identity + ":" + windowKey;

        Optional<Integer> currentCount = cacheService.get(cacheKey, Integer.class);
        int count = currentCount.orElse(0);

        if (count >= limit) {
            log.warn("Rate limit exceeded for identity={} count={} limit={}", identity, count, limit);
            writeRateLimitResponse(response);
            return;
        }

        cacheService.put(cacheKey, count + 1, CACHE_TTL);
        filterChain.doFilter(request, response);
    }

    private void writeRateLimitResponse(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", "60");
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(RATE_LIMIT_MESSAGE));
    }

    private String resolveClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
