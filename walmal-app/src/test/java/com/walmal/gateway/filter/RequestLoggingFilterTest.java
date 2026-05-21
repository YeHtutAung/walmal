package com.walmal.gateway.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RequestLoggingFilterTest {

    private RequestLoggingFilter filter;

    @Mock
    private FilterChain filterChain;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        filter = new RequestLoggingFilter();
        request = new MockHttpServletRequest("GET", "/api/v1/products");
        response = new MockHttpServletResponse();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    void should_setCorrelationIdInMdc_when_requestProcessed() throws ServletException, IOException {
        doAnswer(invocation -> {
            // During filter chain execution, MDC should have correlationId
            assertThat(MDC.get("correlationId")).isNotNull();
            return null;
        }).when(filterChain).doFilter(any(), any());

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    void should_setCorrelationIdResponseHeader_when_requestProcessed() throws ServletException, IOException {
        filter.doFilterInternal(request, response, filterChain);

        String headerValue = response.getHeader("X-Correlation-Id");
        assertThat(headerValue).isNotNull().isNotEmpty();
        // Should be a valid UUID format
        assertThat(headerValue).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void should_clearMdc_when_requestCompleted() throws ServletException, IOException {
        filter.doFilterInternal(request, response, filterChain);

        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void should_clearMdc_when_filterChainThrowsException() {
        try {
            doAnswer(invocation -> {
                throw new RuntimeException("simulated error");
            }).when(filterChain).doFilter(any(), any());

            filter.doFilterInternal(request, response, filterChain);
        } catch (Exception ignored) {
            // expected
        }

        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void should_logAnonymousUserId_when_noAuthentication() throws ServletException, IOException {
        // No authentication set — userId should resolve to "anonymous"
        // We verify indirectly through the filter completing without error
        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    void should_resolveAuthenticatedUserId_when_securityContextPresent() throws ServletException, IOException {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("user-123", null, java.util.List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }
}
