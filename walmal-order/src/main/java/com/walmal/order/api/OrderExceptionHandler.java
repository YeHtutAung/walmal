package com.walmal.order.api;

import com.walmal.common.exception.BusinessRuleException;
import com.walmal.common.exception.ConcurrencyConflictException;
import com.walmal.common.exception.ResourceNotFoundException;
import com.walmal.common.model.ApiResponse;
import com.walmal.common.payment.WebhookVerificationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.core.annotation.Order;

/**
 * Exception handler for the walmal-order module.
 * Handles domain exceptions and maps them to appropriate HTTP status codes.
 */
@RestControllerAdvice(basePackages = "com.walmal.order.api")
// Module advice must outrank GlobalExceptionHandler\'s catch-all. Unannotated
// advice defaults to LOWEST_PRECEDENCE — the same as Global\'s explicit @Order —
// so the tie was broken by bean registration order and a module 4xx could
// nondeterministically become a global 500 (found via the webhook 400 path;
// pinned by ExceptionHandlerPrecedenceTest).
@Order(0)
public class OrderExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleNotFound(ResourceNotFoundException ex) {
        return ApiResponse.error(ex.getMessage());
    }

    @ExceptionHandler(BusinessRuleException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleBusinessRule(BusinessRuleException ex) {
        return ApiResponse.error(ex.getMessage());
    }

    @ExceptionHandler(ConcurrencyConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleConcurrencyConflict(ConcurrencyConflictException ex) {
        return ApiResponse.error(ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException ex) {
        return ApiResponse.error(ex.getMessage());
    }

    /**
     * Missing or invalid Stripe webhook signature ({@link PaymentWebhookController}).
     * The signature is that endpoint's entire authentication — a failure here means
     * the request cannot be trusted as a genuine Stripe delivery, so nothing is persisted.
     */
    @ExceptionHandler(WebhookVerificationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleWebhookVerification(WebhookVerificationException ex) {
        return ApiResponse.error(ex.getMessage());
    }
}
