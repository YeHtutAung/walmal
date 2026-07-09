package com.walmal.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walmal.common.cache.CacheService;
import com.walmal.gateway.filter.RateLimitFilter;
import com.walmal.gateway.filter.RequestLoggingFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers gateway filters with explicit ordering relative to Spring Security (-100).
 */
@Configuration
public class GatewayFilterConfig {

    @Bean
    public FilterRegistrationBean<RequestLoggingFilter> requestLoggingFilterRegistration() {
        FilterRegistrationBean<RequestLoggingFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new RequestLoggingFilter());
        registration.setOrder(-110);
        registration.addUrlPatterns("/*");
        registration.setName("requestLoggingFilter");
        return registration;
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
            CacheService cacheService,
            ObjectMapper objectMapper,
            @Value("${WALMAL_TRUST_PROXY:false}") boolean trustProxy,
            @Value("${walmal.rate-limit.authenticated-limit:100}") int authenticatedLimit,
            @Value("${walmal.rate-limit.unauthenticated-limit:20}") int unauthenticatedLimit) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new RateLimitFilter(cacheService, objectMapper, trustProxy,
                authenticatedLimit, unauthenticatedLimit));
        registration.setOrder(-90);
        registration.addUrlPatterns("/*");
        registration.setName("rateLimitFilter");
        return registration;
    }
}
