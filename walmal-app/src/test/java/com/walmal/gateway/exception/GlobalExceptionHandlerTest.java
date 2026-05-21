package com.walmal.gateway.exception;

import com.walmal.common.exception.BusinessRuleException;
import com.walmal.common.exception.ConcurrencyConflictException;
import com.walmal.common.exception.ResourceNotFoundException;
import com.walmal.common.model.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.core.MethodParameter;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.NoHandlerFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    void should_return429_when_rateLimitExceeded() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleRateLimitExceeded(new RateLimitExceededException());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().message()).contains("Rate limit exceeded");
    }

    @Test
    void should_return403_when_accessDenied() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleAccessDenied(new AccessDeniedException("forbidden"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Access denied.");
    }

    @Test
    void should_return401_when_authenticationFails() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleAuthentication(new BadCredentialsException("bad creds"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Authentication required.");
    }

    @Test
    void should_return405_when_methodNotSupported() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleMethodNotSupported(new HttpRequestMethodNotSupportedException("PATCH"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).contains("PATCH");
    }

    @Test
    void should_return404_when_noHandlerFound() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleNoHandlerFound(new NoHandlerFoundException("GET", "/api/v1/nonexistent", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).contains("No endpoint found");
        assertThat(response.getBody().message()).contains("/api/v1/nonexistent");
    }

    @Test
    void should_return400_when_messageNotReadable() {
        HttpInputMessage mockInput = mock(HttpInputMessage.class);
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleMessageNotReadable(
                        new HttpMessageNotReadableException("bad json", mockInput));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Malformed request body.");
    }

    @Test
    void should_return400WithFieldErrors_when_validationFails() {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "target");
        bindingResult.addError(new FieldError("target", "name", "must not be blank"));
        bindingResult.addError(new FieldError("target", "price", "must be positive"));

        MethodParameter methodParameter = new MethodParameter(
                Object.class.getDeclaredMethods()[0], -1);
        MethodArgumentNotValidException ex =
                new MethodArgumentNotValidException(methodParameter, bindingResult);

        ResponseEntity<ApiResponse<Void>> response = handler.handleValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Validation failed");
        assertThat(response.getBody().errors()).hasSize(2);
        assertThat(response.getBody().errors()).anyMatch(e -> e.contains("name"));
        assertThat(response.getBody().errors()).anyMatch(e -> e.contains("price"));
    }

    @Test
    void should_return404_when_resourceNotFound() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleResourceNotFound(new ResourceNotFoundException("Product", 42));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).contains("Product not found");
    }

    @Test
    void should_return409_when_businessRuleViolated() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleBusinessRule(new BusinessRuleException("Insufficient stock"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Insufficient stock");
    }

    @Test
    void should_return409_when_concurrencyConflict() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleConcurrencyConflict(new ConcurrencyConflictException("Stale data"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Stale data");
    }

    @Test
    void should_return500WithGenericMessage_when_unexpectedException() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleAll(new RuntimeException("secret internal details"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("An unexpected error occurred.");
        // Must NOT expose internal exception message
        assertThat(response.getBody().message()).doesNotContain("secret internal details");
    }
}
