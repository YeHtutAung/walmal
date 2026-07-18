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

/**
 * Sliding window rate limiter using CacheService (Redis-backed).
 * Runs after Spring Security so SecurityContext is available.
 *
 * <p>Limits: 100 req/min per authenticated user, 20 req/min per IP for unauthenticated.</p>
 *
 * <p>X-Forwarded-For is trusted only when {@code trustProxy=true} (set via
 * {@code WALMAL_TRUST_PROXY=true}). walmal requires a reverse proxy (Nginx/Caddy) that
 * sets X-Forwarded-For before enabling this flag. Without a trusted proxy, accepting
 * X-Forwarded-For allows any client to spoof their IP and bypass rate limits.</p>
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final Duration CACHE_TTL = Duration.ofSeconds(90);
    private static final String RATE_LIMIT_MESSAGE = "Rate limit exceeded. Try again later.";

    private final CacheService cacheService;
    private final ObjectMapper objectMapper;
    private final boolean trustProxy;
    private final int authenticatedLimit;
    private final int unauthenticatedLimit;

    public RateLimitFilter(CacheService cacheService, ObjectMapper objectMapper, boolean trustProxy,
                           int authenticatedLimit, int unauthenticatedLimit) {
        this.cacheService = cacheService;
        this.objectMapper = objectMapper;
        this.trustProxy = trustProxy;
        this.authenticatedLimit = authenticatedLimit;
        this.unauthenticatedLimit = unauthenticatedLimit;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator/")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger-ui")
                // Stripe webhook: Stripe retries automatically on any non-2xx response
                // (including 429), and its high-volume test/live traffic all arrives
                // from Stripe's own IPs behind no per-customer identity this filter
                // could key on anyway. The Stripe-Signature check inside
                // PaymentWebhookService is the real gate against abuse here, so this
                // endpoint is exempted rather than rate-limited.
                || path.equals("/api/v1/payment/webhook");
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
            limit = authenticatedLimit;
        } else {
            identity = "ip:" + resolveClientIp(request);
            limit = unauthenticatedLimit;
        }

        long windowKey = System.currentTimeMillis() / 60000;
        String cacheKey = "ratelimit:" + identity + ":" + windowKey;

        long count = cacheService.increment(cacheKey, CACHE_TTL);

        if (count > limit) {
            log.warn("Rate limit exceeded for identity={} count={} limit={}", identity, count, limit);
            writeRateLimitResponse(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void writeRateLimitResponse(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", "60");
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(RATE_LIMIT_MESSAGE));
    }

    private String resolveClientIp(HttpServletRequest request) {
        if (trustProxy) {
            String xForwardedFor = request.getHeader("X-Forwarded-For");
            if (xForwardedFor != null && !xForwardedFor.isBlank()) {
                return xForwardedFor.split(",")[0].trim();
            }
            log.warn("WALMAL_TRUST_PROXY=true but X-Forwarded-For header is absent. "
                    + "Ensure a reverse proxy (Nginx/Caddy) is configured to set X-Forwarded-For. "
                    + "Falling back to getRemoteAddr().");
        }
        return request.getRemoteAddr();
    }
}
