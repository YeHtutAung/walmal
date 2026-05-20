package com.walmal.auth.api;

import com.walmal.common.exception.BusinessRuleException;
import com.walmal.common.exception.ResourceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * Maps domain exceptions to HTTP responses for the auth module's REST layer.
 *
 * <p>BusinessRuleException is context-sensitive:
 * <ul>
 *   <li>"Invalid credentials" or "deactivated" → 401 Unauthorized</li>
 *   <li>"already taken" or "already registered" → 409 Conflict</li>
 *   <li>All others → 400 Bad Request</li>
 * </ul>
 */
@RestControllerAdvice(basePackages = "com.walmal.auth.api")
public class AuthExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ProblemDetail handleBusinessRule(BusinessRuleException ex) {
        String msg = ex.getMessage();
        if (msg != null && (msg.contains("Invalid credentials") || msg.contains("deactivated")
                || msg.contains("expired") || msg.contains("Refresh token")
                || msg.contains("refresh token"))) {
            return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, msg);
        }
        if (msg != null && (msg.contains("already taken") || msg.contains("already registered"))) {
            return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, msg);
        }
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, msg);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, errors);
    }
}
