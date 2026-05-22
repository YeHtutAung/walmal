package com.walmal.auth.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CorsConfigurationTest {

    @Test
    void should_restrictToConfiguredOrigins_when_specificOriginsGiven() {
        AuthSecurityConfig config = new AuthSecurityConfig();
        List<String> origins = List.of("http://localhost:3000");

        CorsConfigurationSource source = config.corsConfigurationSource(origins);

        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/v1/products");
        CorsConfiguration corsConfig = source.getCorsConfiguration(request);

        assertThat(corsConfig).isNotNull();
        assertThat(corsConfig.getAllowedOriginPatterns()).containsExactly("http://localhost:3000");
    }

    @Test
    void should_notContainWildcard_when_specificOriginsGiven() {
        AuthSecurityConfig config = new AuthSecurityConfig();

        CorsConfigurationSource source = config.corsConfigurationSource(List.of("https://app.walmal.com"));

        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/v1/orders");
        CorsConfiguration corsConfig = source.getCorsConfiguration(request);

        assertThat(corsConfig.getAllowedOriginPatterns()).doesNotContain("*");
    }

    @Test
    void should_allowMultipleOrigins_when_commaDelimitedListProvided() {
        AuthSecurityConfig config = new AuthSecurityConfig();
        List<String> origins = List.of("http://localhost:3000", "https://app.walmal.com");

        CorsConfigurationSource source = config.corsConfigurationSource(origins);

        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/v1/products");
        CorsConfiguration corsConfig = source.getCorsConfiguration(request);

        assertThat(corsConfig.getAllowedOriginPatterns())
                .containsExactlyInAnyOrder("http://localhost:3000", "https://app.walmal.com");
    }
}
